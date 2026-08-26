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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var running = false
    private lateinit var accelerationChart: AccelerationChartView
    private lateinit var healthRecords: HealthRecordsManager
    private lateinit var healthStatus: TextView
    private lateinit var heartRateRecord: TextView
    private lateinit var oxygenRecord: TextView
    private var healthSyncJob: Job? = null
    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthRecords.permissions)) syncHealthRecords()
        else healthStatus.text = "Permiso de Health Connect no concedido"
    }
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
        healthRecords = HealthRecordsManager(this)
        healthStatus = findViewById(R.id.healthStatus)
        heartRateRecord = findViewById(R.id.heartRateRecord)
        oxygenRecord = findViewById(R.id.oxygenRecord)
        findViewById<Button>(R.id.syncHealth).setOnClickListener { requestAndSyncHealth() }
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

    private fun requestAndSyncHealth() {
        if (healthRecords.availability() != HealthConnectClient.SDK_AVAILABLE) {
            healthStatus.text = "Health Connect no esta disponible o necesita actualizarse"
            return
        }
        lifecycleScope.launch {
            if (healthRecords.hasPermissions()) syncHealthRecords()
            else healthPermissionLauncher.launch(healthRecords.permissions)
        }
    }

    private fun syncHealthRecords() {
        healthStatus.text = "Leyendo registros de Mi Fitness..."
        lifecycleScope.launch {
            try {
                val summary = healthRecords.readLastSevenDays()
                heartRateRecord.text = summary.heartRate
                oxygenRecord.text = summary.oxygen
                healthStatus.text = "${summary.detail}\nActualizacion automatica cada minuto"
            } catch (e: Exception) {
                healthStatus.text = "No fue posible leer Health Connect: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter("co.edu.ecci.MONITOR_STATUS"), ContextCompat.RECEIVER_NOT_EXPORTED)
        healthSyncJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    if (healthRecords.availability() == HealthConnectClient.SDK_AVAILABLE &&
                        healthRecords.hasPermissions()) {
                        syncHealthRecords()
                    }
                } catch (_: Exception) {
                    // El boton permite reintentar y mostrar el error al usuario.
                }
                delay(60_000L)
            }
        }
    }
    override fun onStop() {
        healthSyncJob?.cancel()
        healthSyncJob = null
        unregisterReceiver(receiver)
        super.onStop()
    }
}
