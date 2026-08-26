package co.edu.ecci.monitornocturno

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var running = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra("status") ?: "Sin datos"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
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

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter("co.edu.ecci.MONITOR_STATUS"), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStop() { unregisterReceiver(receiver); super.onStop() }
}

