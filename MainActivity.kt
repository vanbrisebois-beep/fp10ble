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
import org.json.JSONObject

@SuppressLint("MissingPermission", "SetJavaScriptEnabled")
class MainActivity : Activity() {

    private lateinit var sender: BleMidiSender
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val prefs by lazy { getSharedPreferences("fp10", MODE_PRIVATE) }
    @Volatile private var lastStatus = "Déconnecté"

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
    }

    /** Ce que la page peut demander à l'app. */
    private val host = object : MidiHost {
        override fun connect() { runOnUiThread { scan() } }
        override fun setFast(on: Boolean) { runOnUiThread { applyFast(on) } }
        override fun isFast(): Boolean = prefs.getBoolean("fast", true)
        override fun journal() { runOnUiThread { showJournal() } }
        override fun status(): String = lastStatus
        override fun setPause(ms: Int) {
            prefs.edit().putInt("pause", if (ms < 0) -1 else ms).apply()
            sender.pauseMs = effectivePause()
        }
        override fun getPause(): Int = effectivePause().toInt()
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
        sender.disconnect()
        super.onDestroy()
    }
}
