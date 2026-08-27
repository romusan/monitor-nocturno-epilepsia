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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var miFitnessMinutes: EditText
    private lateinit var miFitnessScheduleStatus: TextView
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
        miFitnessMinutes = findViewById(R.id.miFitnessMinutes)
        miFitnessScheduleStatus = findViewById(R.id.miFitnessScheduleStatus)
        val savedMinutes = getSharedPreferences("monitor_settings", MODE_PRIVATE)
            .getInt("mi_fitness_interval_minutes", 30)
        miFitnessMinutes.setText(savedMinutes.toString())
        refreshMiFitnessScheduleStatus()
        findViewById<Button>(R.id.syncHealth).setOnClickListener { requestAndSyncHealth() }
        findViewById<Button>(R.id.programMiFitness).setOnClickListener { programMiFitnessReminders() }
        findViewById<Button>(R.id.openMiFitness).setOnClickListener { openMiFitness() }
        findViewById<Button>(R.id.stopMiFitnessSchedule).setOnClickListener { stopMiFitnessReminders() }
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

    private fun programMiFitnessReminders() {
        val minutes = miFitnessMinutes.text.toString().toIntOrNull()
        if (minutes == null || minutes < 5 || minutes > 720) {
            miFitnessMinutes.error = "Ingrese un valor entre 5 y 720 minutos"
            return
        }
        requestNotificationPermissionIfNeeded()
        getSharedPreferences("monitor_settings", MODE_PRIVATE).edit()
            .putInt("mi_fitness_interval_minutes", minutes)
            .putBoolean("mi_fitness_reminders_enabled", true)
            .apply()
        ContextCompat.startForegroundService(
            this,
            Intent(this, MonitoringService::class.java)
                .setAction("CONFIGURE_MI_FITNESS")
                .putExtra("interval_minutes", minutes)
        )
        refreshMiFitnessScheduleStatus()
    }

    private fun stopMiFitnessReminders() {
        getSharedPreferences("monitor_settings", MODE_PRIVATE).edit()
            .putBoolean("mi_fitness_reminders_enabled", false).apply()
        startService(Intent(this, MonitoringService::class.java).setAction("DISABLE_MI_FITNESS"))
        refreshMiFitnessScheduleStatus()
    }

    private fun openMiFitness() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.xiaomi.wearable")
        if (launchIntent == null) {
            Toast.makeText(this, "Mi Fitness no está instalada o no se encontró", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
    }

    private fun refreshMiFitnessScheduleStatus() {
        val preferences = getSharedPreferences("monitor_settings", MODE_PRIVATE)
        val enabled = preferences.getBoolean("mi_fitness_reminders_enabled", false)
        val minutes = preferences.getInt("mi_fitness_interval_minutes", 30)
        miFitnessScheduleStatus.text = if (enabled) {
            "Aviso activo cada $minutes minutos. Toque la notificación para abrir Mi Fitness."
        } else "Avisos de Mi Fitness desactivados"
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
