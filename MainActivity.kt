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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("MissingPermission", "SetJavaScriptEnabled")
class MainActivity : Activity() {

    private lateinit var sender: BleMidiSender
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val prefs by lazy { getSharedPreferences("fp10", MODE_PRIVATE) }
    @Volatile private var lastStatus = "Déconnecté"
    @Volatile private var fluidStatusText = "Aucune cible choisie"
    private var fluidInfos: List<MidiDeviceInfo> = emptyList()
    private var fluidDevice: MidiDevice? = null
    private var fluidPort: MidiInputPort? = null

    /** État de connexion : mémorisé et envoyé à la page. */
    private fun status(s: String) {
        if (s.startsWith("TX") || s.startsWith("RX") || s.startsWith("  paquet")) return
        lastStatus = s
        runOnUiThread { pushStatus() }
    }

    private fun pushStatus() {
        if (!::web.isInitialized) return
        web.evaluateJavascript(
            "window.onNativeStatus && window.onNativeStatus(" + JSONObject.quote(lastStatus) + ")", null)
        web.evaluateJavascript(
            "window.onFluidStatus && window.onFluidStatus(" + JSONObject.quote(fluidStatusText) + ")", null)
    }

    /** Ce que la page peut demander à l'app. */
    private val host = object : MidiHost {
        override fun connect() { runOnUiThread { scan() } }
        override fun setFast(on: Boolean) { applyFast(on) }
        override fun isFast(): Boolean = prefs.getBoolean("fast", true)
        override fun journal() { runOnUiThread { showJournal() } }
        override fun status(): String = lastStatus
        override fun setPause(ms: Int) {
            prefs.edit().putInt("pause", if (ms < 0) -1 else ms).apply()
            sender.pauseMs = effectivePause()
        }
        override fun getPause(): Int = effectivePause().toInt()
        override fun setMediaVolume(pct: Int) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val v = Math.round(pct.coerceIn(0, 100) * max / 100f)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        }
        override fun fluidList(): String = listFluidTargets()
        override fun fluidSelect(index: Int) { runOnUiThread { openFluid(index) } }
        override fun fluidSend(hex: String) = sendToFluid(hex)
        override fun fluidStatus(): String = fluidStatusText
        override fun getMediaVolume(): Int {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sender = BleMidiSender(this, onState = { status(it) })
        applyFast(prefs.getBoolean("fast", true))

        web = WebView(this)
        setContentView(web)   // la page occupe tout l'écran et défile en entier

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true   // localStorage : la banque de boutons y est stockée
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(MidiBridge(sender, host), "AndroidMidi")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) { pushStatus() }
        }
        web.webChromeClient = object : WebChromeClient() {
            // sélecteur de fichier pour « Charger boutons »
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(Intent.createChooser(i, "Choisir un fichier"), 2)
                return true
            }
        }
        web.loadUrl("file:///android_asset/index.html")

        if (hasPerms()) autoConnect() else requestPerms()
        autoFluid()
    }

    // ---------- Cible MIDI Android (FluidSynth) ----------

    private fun midiName(d: MidiDeviceInfo): String =
        d.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Appareil ${d.id}"

    @Suppress("DEPRECATION")
    private fun listFluidTargets(): String {
        val mm = getSystemService(MIDI_SERVICE) as MidiManager
        val infos = mm.devices.filter { it.inputPortCount > 0 }
        fluidInfos = infos
        val arr = JSONArray()
        infos.forEachIndexed { i, d ->
            val kind = when (d.type) {
                MidiDeviceInfo.TYPE_USB -> "USB"
                MidiDeviceInfo.TYPE_VIRTUAL -> "virtuel"
                MidiDeviceInfo.TYPE_BLUETOOTH -> "Bluetooth"
                else -> "?"
            }
            arr.put(JSONObject().put("index", i).put("name", "${midiName(d)} ($kind)"))
        }
        return arr.toString()
    }

    private fun setFluidStatus(s: String) {
        fluidStatusText = s
        runOnUiThread { pushStatus() }
    }

    private fun openFluid(index: Int) {
        val info = fluidInfos.getOrNull(index) ?: return
        try { fluidPort?.close() } catch (e: Exception) {}
        try { fluidDevice?.close() } catch (e: Exception) {}
        fluidPort = null; fluidDevice = null
        setFluidStatus("Ouverture de ${midiName(info)}…")
        val mm = getSystemService(MIDI_SERVICE) as MidiManager
        mm.openDevice(info, { dev ->
            if (dev == null) {
                setFluidStatus("Impossible d'ouvrir l'appareil")
            } else {
                fluidDevice = dev
                fluidPort = dev.openInputPort(0)
                if (fluidPort == null) {
                    setFluidStatus("Port d'entrée absent ou déjà utilisé")
                } else {
                    prefs.edit().putString("fluid_name", midiName(info)).apply()
                    setFluidStatus("Connecté : ${midiName(info)}")
                }
            }
        }, ui)
    }

    /** Rouvre automatiquement la dernière cible, si elle est encore présente. */
    private fun autoFluid() {
        val saved = prefs.getString("fluid_name", null) ?: return
        try {
            listFluidTargets()
            val i = fluidInfos.indexOfFirst { midiName(it) == saved }
            if (i >= 0) openFluid(i)
        } catch (e: Exception) { }
    }

    /** Envoie des octets MIDI bruts, un message à la fois (F0…F7 ou statut + données). */
    private fun sendToFluid(hex: String) {
        val port = fluidPort ?: return
        val b = hex.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            .map { it.toInt(16) and 0xFF }.toIntArray()
        var i = 0
        try {
            while (i < b.size) {
                var j: Int
                if (b[i] == 0xF0) {
                    j = i
                    while (j < b.size && b[j] != 0xF7) j++
                    if (j >= b.size) break
                    j++
                } else if (b[i] >= 0x80) {
                    j = i + 1
                    while (j < b.size && b[j] < 0x80) j++
                } else { i++; continue }
                val msg = ByteArray(j - i) { b[i + it].toByte() }
                port.send(msg, 0, msg.size)
                i = j
            }
        } catch (e: Exception) {
            setFluidStatus("Erreur d'envoi : ${e.message}")
        }
    }

    /** Mode rapide : écriture sans attendre de réponse du piano + pause plus courte entre paquets. */
    private fun applyFast(on: Boolean) {
        sender.useWriteWithResponse = !on
        prefs.edit().putBoolean("fast", on).apply()
        sender.pauseMs = effectivePause()
    }

    /** Pause entre paquets : valeur réglée dans la page, sinon 6 ms (rapide) ou 15 ms. */
    private fun effectivePause(): Long {
        val p = prefs.getInt("pause", -1)
        return if (p >= 0) p.toLong() else if (prefs.getBoolean("fast", true)) 6L else 15L
    }

    private fun showJournal() {
        val txt = sender.journalText().ifEmpty { "(vide : appuie d'abord sur un bouton)" }
        val tv = TextView(this).apply {
            text = txt
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Journal d'envoi")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Copier") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("journal", txt))
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun requestPerms() = requestPermissions(perms(), 1)

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (hasPerms()) autoConnect()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 2) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data))
            fileCb = null
        }
    }

    /** Reconnexion automatique au dernier FP-10 choisi. */
    private fun autoConnect() {
        val addr = prefs.getString("last", null) ?: return
        status("Connexion à $addr…")
        try { sender.connectByAddress(addr) } catch (e: Exception) { status("Erreur : ${e.message}") }
    }

    private fun scan() {
        if (!hasPerms()) { requestPerms(); return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { status("Bluetooth désactivé"); return }
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
        status("Recherche (6 s)…")
        ui.postDelayed({
            scanner.stopScan(cb)
            val list = found.values.toList()
            if (list.isEmpty()) { status("Aucun appareil trouvé"); return@postDelayed }
            val names = list.map { "${it.name ?: "?"}  (${it.address})" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Choisir l'appareil")
                .setItems(names) { _, i ->
                    prefs.edit().putString("last", list[i].address).apply()
                    status("Connexion à ${names[i]}…")
                    sender.connect(list[i])
                }.show()
        }, 6000)
    }

    override fun onDestroy() {
        try { fluidPort?.close() } catch (e: Exception) {}
        try { fluidDevice?.close() } catch (e: Exception) {}
        sender.disconnect()
        super.onDestroy()
    }
}
