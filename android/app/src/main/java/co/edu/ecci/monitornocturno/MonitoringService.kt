package co.edu.ecci.monitornocturno

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MonitoringService : Service(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private val detector = MovementDetector()
    private var writer: BufferedWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var gravity = 9.81
    private var lastStatusNs = 0L
    private var lastSampleBroadcastNs = 0L
    private var alerted = false
    private var currentLabel = "normal"
    private val schedulerHandler = Handler(Looper.getMainLooper())
    private var miFitnessIntervalMs = 0L
    private var remindersEnabled = false
    private val miFitnessReminder = object : Runnable {
        override fun run() {
            if (!remindersEnabled) return
            showMiFitnessReminder()
            schedulerHandler.postDelayed(this, miFitnessIntervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> stopMonitoring()
            "CONFIGURE_MI_FITNESS" -> configureMiFitnessReminders(
                intent.getIntExtra("interval_minutes", 30)
            )
            "DISABLE_MI_FITNESS" -> disableMiFitnessReminders()
            "LABEL" -> {
                currentLabel = intent.getStringExtra("label") ?: "normal"
                writer?.apply { write("#label,${System.currentTimeMillis()},$currentLabel\n"); flush() }
                publish("Etiqueta guardada: $currentLabel")
            }
            "START" -> {
                startMonitoring()
                restoreMiFitnessReminders()
            }
            else -> {
                val preferences = getSharedPreferences("monitor_settings", MODE_PRIVATE)
                if (preferences.getBoolean("mi_fitness_reminders_enabled", false)) {
                    configureMiFitnessReminders(
                        preferences.getInt("mi_fitness_interval_minutes", 30)
                    )
                } else stopSelf()
            }
        }
        return if (remindersEnabled || writer != null) START_STICKY else START_NOT_STICKY
    }

    private fun startMonitoring() {
        if (writer != null) return
        val dir = File(filesDir, "captures").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        writer = BufferedWriter(FileWriter(File(dir, "noche_$stamp.csv"))).also {
            it.write("timestamp_ns,ax,ay,az,dynamic_magnitude,sta_lta,rhythmicity,label\n")
        }
        startForeground(1, notification("Monitoreo activo", false))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MonitorNocturno::sensor").apply { acquire(10 * 60 * 60 * 1000L) }
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, 20_000) }
        publish("Monitoreando - archivo $stamp")
    }

    private fun configureMiFitnessReminders(minutes: Int) {
        val safeMinutes = minutes.coerceIn(5, 720)
        miFitnessIntervalMs = safeMinutes * 60_000L
        remindersEnabled = true
        getSharedPreferences("monitor_settings", MODE_PRIVATE).edit()
            .putInt("mi_fitness_interval_minutes", safeMinutes)
            .putBoolean("mi_fitness_reminders_enabled", true)
            .apply()
        startForeground(1, notification("Aviso de Mi Fitness cada $safeMinutes minutos", false))
        schedulerHandler.removeCallbacks(miFitnessReminder)
        schedulerHandler.postDelayed(miFitnessReminder, miFitnessIntervalMs)
        publish("Avisos de Mi Fitness activos cada $safeMinutes minutos")
    }

    private fun restoreMiFitnessReminders() {
        val preferences = getSharedPreferences("monitor_settings", MODE_PRIVATE)
        if (!preferences.getBoolean("mi_fitness_reminders_enabled", false)) return
        val minutes = preferences.getInt("mi_fitness_interval_minutes", 30).coerceIn(5, 720)
        miFitnessIntervalMs = minutes * 60_000L
        remindersEnabled = true
        schedulerHandler.removeCallbacks(miFitnessReminder)
        schedulerHandler.postDelayed(miFitnessReminder, miFitnessIntervalMs)
    }

    private fun disableMiFitnessReminders() {
        remindersEnabled = false
        schedulerHandler.removeCallbacks(miFitnessReminder)
        getSharedPreferences("monitor_settings", MODE_PRIVATE).edit()
            .putBoolean("mi_fitness_reminders_enabled", false).apply()
        if (writer == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, notification("Monitoreo activo", false))
        }
        publish("Avisos de Mi Fitness desactivados")
    }

    private fun showMiFitnessReminder() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(3, miFitnessNotification())
    }

    private fun miFitnessLaunchIntent(): Intent =
        packageManager.getLaunchIntentForPackage("com.xiaomi.wearable")
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: Intent(this, MainActivity::class.java)

    private fun miFitnessNotification() = NotificationCompat.Builder(this, "mi_fitness_sync")
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("Sincronizar el Watch S1")
        .setContentText("Toque para abrir Mi Fitness; luego regrese a Monitor ECCI.")
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 30, miFitnessLaunchIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            android.R.drawable.ic_popup_sync,
            "Abrir Mi Fitness",
            PendingIntent.getActivity(
                this, 31, miFitnessLaunchIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val magnitude = sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]).toDouble())
        gravity = 0.995 * gravity + 0.005 * magnitude
        val dynamic = magnitude - gravity
        val state = detector.add(dynamic)
        writer?.write(String.format(Locale.US, "%d,%.6f,%.6f,%.6f,%.6f,%.4f,%.4f,%s\n", event.timestamp, event.values[0], event.values[1], event.values[2], dynamic, state.ratio, state.rhythmicity, currentLabel))
        if (event.timestamp - lastSampleBroadcastNs >= 100_000_000L) {
            lastSampleBroadcastNs = event.timestamp
            sendBroadcast(Intent("co.edu.ecci.MONITOR_STATUS").setPackage(packageName)
                .putExtra("sample", true)
                .putExtra("ax", event.values[0])
                .putExtra("ay", event.values[1])
                .putExtra("az", event.values[2]))
        }
        if (state.candidate && !alerted) {
            alerted = true
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2, notification("Movimiento ritmico sostenido: verificar", true))
        } else if (!state.candidate) alerted = false
        if (event.timestamp - lastStatusNs > 1_000_000_000L) {
            lastStatusNs = event.timestamp
            publish(String.format(Locale.US, "Activo | STA/LTA %.2f | ritmo %.2f | etiqueta %s", state.ratio, state.rhythmicity, currentLabel))
            writer?.flush()
        }
    }

    private fun stopMonitoring() {
        sensors.unregisterListener(this)
        writer?.close(); writer = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        publish("Sesion detenida y CSV guardado")
        if (remindersEnabled) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, notification("Avisos periódicos de Mi Fitness activos", false))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(text: String, alarm: Boolean) = NotificationCompat.Builder(this, "monitor")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(if (alarm) "Verificar a la persona" else "Monitor nocturno experimental")
        .setContentText(text).setOngoing(!alarm).setPriority(if (alarm) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("monitor", "Monitor nocturno", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("mi_fitness_sync", "Sincronización de Mi Fitness", NotificationManager.IMPORTANCE_HIGH))
    }
    private fun publish(text: String) { sendBroadcast(Intent("co.edu.ecci.MONITOR_STATUS").setPackage(packageName).putExtra("status", text)) }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        schedulerHandler.removeCallbacks(miFitnessReminder)
        if (writer != null) stopMonitoring()
        super.onDestroy()
    }
}
