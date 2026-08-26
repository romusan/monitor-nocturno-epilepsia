package co.edu.ecci.monitornocturno

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var running = false
    private lateinit var watchManager: BleWatchManager
    private lateinit var accelerationChart: AccelerationChartView
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getBooleanExtra("sample", false) == true) {
                accelerationChart.addSample(
                    intent.getFloatExtra("ax", 0f),
                    intent.getFloatExtra("ay", 0f),
                    intent.getFloatExtra("az", 0f))
            } else status.text = intent?.getStringExtra("status") ?: "Sin datos"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        accelerationChart = findViewById(R.id.accelerationChart)
        val watchStatus = findViewById<TextView>(R.id.watchStatus)
        val heartRate = findViewById<TextView>(R.id.heartRate)
        val watchLog = findViewById<TextView>(R.id.watchLog)
        val watchDetails = findViewById<Button>(R.id.watchDetails)
        val authKeyInput = findViewById<EditText>(R.id.xiaomiAuthKey)
        val authKeyStatus = findViewById<TextView>(R.id.xiaomiKeyStatus)
        val prefs = getSharedPreferences("xiaomi_watch", Context.MODE_PRIVATE)
        fun updateKeyStatus() {
            authKeyStatus.text = if (prefs.getString("auth_key", "").isNullOrBlank())
                "Clave Xiaomi no configurada"
            else "Clave Xiaomi configurada (oculta)"
        }
        updateKeyStatus()
        findViewById<Button>(R.id.saveXiaomiKey).setOnClickListener {
            val key = authKeyInput.text.toString().trim().removePrefix("0x")
            if (!key.matches(Regex("[0-9a-fA-F]{32}"))) {
                authKeyInput.error = "Debe contener exactamente 32 caracteres hexadecimales"
            } else {
                prefs.edit().putString("auth_key", key.lowercase()).apply()
                authKeyInput.text.clear()
                updateKeyStatus()
                Toast.makeText(this, "Clave guardada; nunca se mostrara en el registro", Toast.LENGTH_LONG).show()
            }
        }
        watchDetails.setOnClickListener {
            val show = watchLog.visibility != View.VISIBLE
            watchLog.visibility = if (show) View.VISIBLE else View.GONE
            watchDetails.text = if (show) "Ocultar detalles tecnicos" else "Ver detalles tecnicos"
        }
        watchManager = BleWatchManager(
            this,
            { headline, detail ->
                runOnUiThread {
                    watchStatus.text = headline
                    val connected = headline.contains("conectado", true) || headline.contains("recibiendo", true)
                    watchStatus.setTextColor(if (connected) Color.rgb(32, 125, 66) else Color.rgb(175, 55, 55))
                    if (detail.isNotBlank()) watchLog.text = detail
                }
            },
            { bpm -> runOnUiThread { heartRate.text = "$bpm lpm" } }
        )
        findViewById<Button>(R.id.connectWatch).setOnClickListener {
            if (hasBluetoothPermissions()) watchManager.scanAndConnect(prefs.getString("auth_key", null))
            else ActivityCompat.requestPermissions(this, bluetoothPermissions(), 20)
        }
        val toggle = findViewById<Button>(R.id.toggle)
        toggle.setOnClickListener {
            if (!running) {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                }
                ContextCompat.startForegroundService(this, Intent(this, MonitoringService::class.java).setAction("START"))
                running = true; toggle.text = "Detener y cerrar archivo"
            } else {
                startService(Intent(this, MonitoringService::class.java).setAction("STOP"))
                running = false; toggle.text = "Iniciar sesion nocturna"
            }
        }
        findViewById<Button>(R.id.confirm).setOnClickListener { sendLabel("evento_confirmado") }
        findViewById<Button>(R.id.falseAlarm).setOnClickListener { sendLabel("falsa_alarma") }
    }

    private fun sendLabel(label: String) {
        startService(Intent(this, MonitoringService::class.java).setAction("LABEL").putExtra("label", label))
    }

    private fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBluetoothPermissions() = bluetoothPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 20 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            watchManager.scanAndConnect(getSharedPreferences("xiaomi_watch", Context.MODE_PRIVATE).getString("auth_key", null))
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter("co.edu.ecci.MONITOR_STATUS"), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStop() { unregisterReceiver(receiver); super.onStop() }
    override fun onDestroy() { watchManager.close(); super.onDestroy() }
}
