package com.example.fp10ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var hexInput: EditText
    private lateinit var pauseInput: EditText
    private lateinit var respBox: CheckBox
    private lateinit var sender: BleMidiSender
    private val ui = Handler(Looper.getMainLooper())

    private fun log(s: String) = runOnUiThread { logView.append(s + "\n") }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sender = BleMidiSender(this, onState = { log(it) })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        fun btn(label: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }

        btn("1. Scanner / choisir le FP-10") { scan() }
        btn("2. Test court : CC7 = 30 (volume bas)") { send { sender.send(0xB0, 7, 30) } }
        btn("3. Test court : CC7 = 100 (volume haut)") { send { sender.send(0xB0, 7, 100) } }
        btn("3b. Note DO4 (Note On puis Off)") {
            send { sender.send(0x90, 60, 100) }
            ui.postDelayed({ sender.send(0x80, 60, 64) }, 600)
        }

        root.addView(TextView(this).apply { text = "Message à envoyer (hex ; SysEx = F0 … F7) :" })
        hexInput = EditText(this).apply {
            setText("F0 41 10 42 12 40 00 7F 00 41 F7") // GS reset (confirmé sur ton FP-10)
            textSize = 12f
        }
        root.addView(hexInput)
        btn("4. Envoyer (SysEx ou message court)") {
            send {
                val b = hexInput.text.toString().trim().split(Regex("[\\s,]+"))
                    .filter { it.isNotEmpty() }.map { it.toInt(16) }.toIntArray()
                // Découpe en messages séparés : chaque F0…F7 part seul, avec ses propres timestamps
                var i = 0
                var n = 0
                while (i < b.size) {
                    if (b[i] == 0xF0) {
                        var j = i
                        while (j < b.size && b[j] != 0xF7) j++
                        if (j >= b.size) { log("SysEx sans F7 final : ignoré"); break }
                        sender.send(*b.copyOfRange(i, j + 1)); n++
                        i = j + 1
                    } else if (b[i] >= 0x80) {
                        var j = i + 1
                        while (j < b.size && b[j] < 0x80) j++
                        sender.send(*b.copyOfRange(i, j)); n++
                        i = j
                    } else {
                        i++
                    }
                }
                log("$n message(s) envoyé(s) séparément")
            }
        }

        respBox = CheckBox(this).apply {
            text = "Écriture avec réponse (WRITE_TYPE_DEFAULT)"
            isChecked = true
        }
        root.addView(respBox)
        root.addView(TextView(this).apply { text = "Pause entre paquets (ms) :" })
        pauseInput = EditText(this).apply { setText("15") }
        root.addView(pauseInput)

        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN), 1)
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    private fun send(block: () -> Unit) {
        if (!sender.ready) { log("Pas connecté / pas prêt"); return }
        sender.useWriteWithResponse = respBox.isChecked
        sender.pauseMs = pauseInput.text.toString().toLongOrNull() ?: 15L
        try { block() } catch (e: Exception) { log("Erreur : ${e.message}") }
    }

    private fun scan() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { log("Bluetooth désactivé"); return }
        val found = LinkedHashMap<String, BluetoothDevice>()
        adapter.bondedDevices.forEach { found[it.address] = it }
        val scanner = adapter.bluetoothLeScanner
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { found[r.device.address] = r.device }
        }
        val filters = listOf(ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleMidiSender.SERVICE)).build())
        scanner.startScan(filters,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        log("Scan 6 s… (allume le FP-10, Bluetooth actif)")
        ui.postDelayed({
            scanner.stopScan(cb)
            val list = found.values.toList()
            if (list.isEmpty()) { log("Aucun appareil trouvé"); return@postDelayed }
            val names = list.map { "${it.name ?: "?"}  (${it.address})" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Choisir l'appareil")
                .setItems(names) { _, i ->
                    log("Connexion à ${names[i]}…")
                    sender.connect(list[i])
                }.show()
        }, 6000)
    }

    override fun onDestroy() {
        sender.disconnect()
        super.onDestroy()
    }
}
