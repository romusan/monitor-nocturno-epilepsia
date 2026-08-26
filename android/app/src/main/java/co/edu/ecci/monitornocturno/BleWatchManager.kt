package co.edu.ecci.monitornocturno

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Diagnostic BLE client for the Xiaomi Watch S1. It intentionally does not claim
 * to decode Xiaomi's authenticated protobuf protocol. The first goal is to prove
 * connectivity and capture the UUIDs/notifications exposed by the actual firmware.
 */
@SuppressLint("MissingPermission")
class BleWatchManager(
    private val context: Context,
    private val report: (String, String) -> Unit
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val logLines = ArrayDeque<String>()
    private var notifyCandidates = emptyList<BluetoothGattCharacteristic>()
    private var notifyIndex = 0

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
            append("Visto: ${name.ifBlank { "sin nombre" }} | RSSI ${result.rssi}")
            if (isWatchS1(name)) {
                stopScan()
                report("Conectando con $name...", logText())
                gatt?.close()
                gatt = if (Build.VERSION.SDK_INT >= 23)
                    result.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                else result.device.connectGatt(context, false, callback)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            report("Fallo de escaneo BLE ($errorCode)", logText())
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    append("GATT conectado; solicitando MTU 247")
                    report("Watch S1 conectado", logText())
                    if (!g.requestMtu(247)) {
                        append("El telefono no acepto cambiar MTU; descubriendo servicios")
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    append("GATT desconectado; estado=$status")
                    report("Watch S1 desconectado", logText())
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            append("MTU=$mtu estado=$status")
            report("Watch S1 conectado; buscando servicios", logText())
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            append("Servicios descubiertos: ${g.services.size}")
            g.services.forEach { service ->
                append("S ${short(service.uuid)}")
                service.characteristics.forEach { characteristic ->
                    append("  C ${short(characteristic.uuid)} ${properties(characteristic.properties)}")
                }
            }
            report("Conectado: ${g.services.size} servicios BLE", logText())
            notifyCandidates = g.services.flatMap { it.characteristics }
            notifyIndex = 0
            enableNextNotification()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            append("CCCD ${short(descriptor.characteristic.uuid)} estado=$status")
            enableNextNotification()
        }

        @Deprecated("Used on Android 12 and earlier")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            receive(characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(characteristic, value)
        }
    }

    fun scanAndConnect() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || adapter?.isEnabled != true || scanner == null) {
            report("Active Bluetooth para buscar el reloj", logText()); return
        }
        closeGattOnly()
        logLines.clear(); append("Buscando Xiaomi Watch S1 durante 15 s...")
        scanning = true
        scanner.startScan(scanCallback)
        handler.postDelayed({
            if (scanning) {
                stopScan()
                report("No se encontro Watch S1", logText() + "\nAbra Bluetooth/Mi Fitness y acerque el reloj.")
            }
        }, 15_000)
    }

    private fun enableNextNotification() {
        val g = gatt ?: return
        while (notifyIndex < notifyCandidates.size) {
            val c = notifyCandidates[notifyIndex++]
            val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!notify && !indicate) continue
            if (!g.setCharacteristicNotification(c, true)) continue
            val cccd = c.getDescriptor(CCCD) ?: continue
            val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val accepted = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value) == 0
            else {
                @Suppress("DEPRECATION") cccd.value = value
                @Suppress("DEPRECATION") g.writeDescriptor(cccd)
            }
            append("Notificacion solicitada ${short(c.uuid)} aceptada=$accepted")
            if (accepted) {
                report("Watch S1 conectado; habilitando datos", logText())
                return
            }
        }
        report("Watch S1 conectado; escuchando paquetes", logText())
    }

    private fun receive(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val hex = value.take(48).joinToString("") { "%02X".format(it) }
        append("RX ${short(characteristic.uuid)} ${value.size}B $hex")
        report("Recibiendo datos del Watch S1", logText())
    }

    private fun isWatchS1(name: String): Boolean {
        val n = name.lowercase()
        return (n.contains("watch s1") || n.contains("xiaomi watch s1")) && !n.contains("s1 pro")
    }

    private fun properties(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("R")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("W")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WNR")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("N")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("I")
    }.joinToString("|").ifBlank { "-" }

    private fun short(uuid: UUID) = uuid.toString()
    private fun append(line: String) {
        logLines.addLast(line)
        while (logLines.size > 45) logLines.removeFirst()
    }
    private fun logText() = logLines.joinToString("\n")
    private fun stopScan() {
        if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }
    private fun closeGattOnly() { gatt?.disconnect(); gatt?.close(); gatt = null }
    fun close() { stopScan(); closeGattOnly(); handler.removeCallbacksAndMessages(null) }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
