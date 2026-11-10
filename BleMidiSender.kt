package com.example.fp10ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID

/**
 * Envoi MIDI/SysEx en BLE MIDI via GATT brut (contourne android.media.midi,
 * cassé pour les SysEx via BLE depuis Android 13).
 *
 * Permissions (Manifest) :
 *   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
 *   <uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
 * (à demander à l'exécution sur Android 12+). Le FP-10 doit être appairé
 * (Paramètres Bluetooth ou via une première connexion).
 */
@SuppressLint("MissingPermission")
class BleMidiSender(
    private val context: Context,
    var pauseMs: Long = 15L,
    var useWriteWithResponse: Boolean = true,
    private val onState: (String) -> Unit = { Log.d(TAG, it) }
) {
    companion object {
        const val TAG = "BleMidi"
        val SERVICE: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
        val CHAR: UUID = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var midiChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile var ready = false
        private set

    private val queue = ArrayDeque<ByteArray>()
    private var writing = false

    // ---------- Journal horodaté (diagnostic de la vitesse d'envoi) ----------
    private val journal = ArrayList<String>()
    private var t0 = 0L
    private var lastEvent = 0L
    private var burstPackets = 0

    private fun jlog(line: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(journal) {
            if (now - lastEvent > 1500) {
                t0 = now; burstPackets = 0
                journal.add("--- nouvel envoi ---")
            }
            lastEvent = now
            journal.add("+${now - t0} ms  $line")
            while (journal.size > 400) journal.removeAt(0)
        }
    }

    fun journalText(): String = synchronized(journal) { journal.joinToString("\n") }

    // ---------- Connexion ----------

    fun connect(device: BluetoothDevice) {
        ready = false
        gatt?.close()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun connectByAddress(address: String) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        connect(adapter.getRemoteDevice(address))
    }

    fun disconnect() {
        ready = false
        queue.clear(); writing = false
        gatt?.disconnect(); gatt?.close(); gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onState("Connecté, négociation MTU…")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(185)   // 23 par défaut = goulot d'étranglement
            } else {
                ready = false
                onState("Déconnecté (status=$status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            onState("MTU = $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            midiChar = g.getService(SERVICE)?.getCharacteristic(CHAR)
            val c = midiChar ?: run { onState("Service BLE MIDI introuvable"); return }
            g.setCharacteristicNotification(c, true)
            // Beaucoup d'appareils (dont Roland) exigent l'activation des notifications
            // avant d'accepter des écritures.
            val cccd = c.getDescriptor(CCCD)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            } else {
                ready = true; onState("Prêt")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready = true
            onState("Prêt (notifications activées)")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onState("RX: " + value.joinToString(" ") { "%02X".format(it) })
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            onState("RX: " + (c.value ?: ByteArray(0)).joinToString(" ") { "%02X".format(it) })
        }

        // Contrôle de flux : on n'envoie le paquet suivant qu'après cet accusé
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) onState("ÉCHEC écriture status=$status") else onState("  paquet écrit OK")
            jlog(if (status == BluetoothGatt.GATT_SUCCESS) "  ok" else "  ÉCHEC status=$status")
            main.postDelayed({
                writing = false
                if (queue.isEmpty()) jlog("FIN : $burstPackets paquets, mode " +
                    (if (useWriteWithResponse) "avec réponse" else "rapide") + ", pause ${pauseMs} ms")
                pump()
            }, pauseMs)
        }
    }

    // ---------- API d'envoi ----------

    /** Message court (CC, PC, note…) : ex. send(0xB0, 7, 100) */
    fun send(vararg bytes: Int) {
        val b = bytes.map { it.toByte() }.toByteArray()
        if (b.isNotEmpty() && (b[0].toInt() and 0xFF) == 0xF0) sendSysex(b) else enqueueShort(b)
    }

    /** SysEx complet, F0 ... F7 inclus. Fragmenté selon le MTU. */
    fun sendSysex(sysex: ByteArray) {
        require(sysex.first() == 0xF0.toByte() && sysex.last() == 0xF7.toByte()) { "SysEx doit être F0…F7" }
        val payloadMax = mtu - 3  // octets utiles par écriture GATT
        for (p in buildSysexPackets(sysex, payloadMax)) enqueue(p)
    }

    /** "F0 41 10 ... F7" → envoi */
    fun sendSysexHex(hex: String) {
        val bytes = hex.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            .map { it.removePrefix("0x").toInt(16).toByte() }.toByteArray()
        sendSysex(bytes)
    }

    /** Octets MIDI bruts (un ou plusieurs messages) : chaque F0…F7 part seul, avec ses propres timestamps. */
    fun sendRaw(b: IntArray) {
        if (!ready) { onState("Pas connecté : message ignoré"); return }
        var i = 0
        while (i < b.size) {
            if (b[i] == 0xF0) {
                var j = i
                while (j < b.size && b[j] != 0xF7) j++
                if (j >= b.size) break
                send(*b.copyOfRange(i, j + 1))
                i = j + 1
            } else if (b[i] >= 0x80) {
                var j = i + 1
                while (j < b.size && b[j] < 0x80) j++
                send(*b.copyOfRange(i, j))
                i = j
            } else {
                i++
            }
        }
    }

    fun sendRawHex(hex: String) {
        val b = hex.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            .map { it.removePrefix("0x").toInt(16) }.toIntArray()
        sendRaw(b)
    }

    // ---------- Encodage BLE MIDI ----------

    private fun tsNow(): Int = (System.currentTimeMillis() and 0x1FFF).toInt()
    private fun hdr(ts: Int) = (0x80 or ((ts shr 7) and 0x3F)).toByte()
    private fun lo(ts: Int) = (0x80 or (ts and 0x7F)).toByte()

    private fun enqueueShort(msg: ByteArray) {
        val ts = tsNow()
        val out = ByteArrayOutputStream()
        out.write(hdr(ts).toInt()); out.write(lo(ts).toInt()); out.write(msg)
        enqueue(out.toByteArray())
    }

    /**
     * Paquet 1 : [header][ts][F0][données…]
     * Suivants : [header][données…]
     * Dernier  : […données][ts][F7]   (le F7 doit être précédé d'un octet timestamp)
     */
    private fun buildSysexPackets(sysex: ByteArray, payloadMax: Int): List<ByteArray> {
        val body = sysex.copyOfRange(1, sysex.size - 1)
        val packets = mutableListOf<ByteArray>()
        var pos = 0
        var first = true
        while (true) {
            val ts = tsNow()
            val cap = if (first) payloadMax - 3 else payloadMax - 1
            val remaining = body.size - pos
            val out = ByteArrayOutputStream()
            out.write(hdr(ts).toInt())
            if (first) { out.write(lo(ts).toInt()); out.write(0xF0) }
            if (remaining + 2 <= cap) {
                out.write(body, pos, remaining)
                out.write(lo(ts).toInt()); out.write(0xF7)
                packets.add(out.toByteArray())
                break
            } else {
                val take = minOf(remaining, cap)
                out.write(body, pos, take)
                pos += take
                packets.add(out.toByteArray())
            }
            first = false
        }
        return packets
    }

    // ---------- File d'attente sérialisée ----------

    private fun enqueue(p: ByteArray) {
        main.post { queue.add(p); pump() }
    }

    private fun pump() {
        if (!ready || writing) return
        val c = midiChar ?: return
        val g = gatt ?: return
        val data = queue.poll() ?: return
        onState("TX: " + data.joinToString(" ") { "%02X".format(it) })
        burstPackets++
        jlog("TX " + data.joinToString(" ") { "%02X".format(it) })
        writing = true
        val type = if (useWriteWithResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                   else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = type; c.value = data; g.writeCharacteristic(c) }
        }
        if (!ok) {
            Log.w(TAG, "writeCharacteristic refusé, nouvelle tentative")
            queue.addFirst(data)
            main.postDelayed({ writing = false; pump() }, 30)
        }
    }
}

/** Pont appelé par index.html : AndroidMidi.raw("F0 41 … F7") */
class MidiBridge(private val s: BleMidiSender) {
    @JavascriptInterface fun raw(hex: String) = s.sendRawHex(hex)
    @JavascriptInterface fun ready(): Boolean = s.ready
}
