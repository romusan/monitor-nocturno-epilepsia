package co.edu.ecci.monitornocturno

import android.os.Build
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Minimal Xiaomi protobuf authentication and realtime-heart-rate protocol. */
class XiaomiProtocol(authKeyHex: String) {
    private val secretKey = authKeyHex.hex()
    private val phoneNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private var encryptionKey = ByteArray(16)
    private var decryptionKey = ByteArray(16)
    private var encryptionNonce = ByteArray(4)
    private var decryptionNonce = ByteArray(4)
    private var encryptedIndex = 1
    var authenticated = false
        private set

    data class Result(
        val needsAck: Boolean = false,
        val outgoing: List<ByteArray> = emptyList(),
        val authenticatedNow: Boolean = false,
        val heartRate: Int? = null,
        val message: String? = null
    )

    fun noncePacket(): ByteArray {
        val phoneNonceMessage = fieldBytes(1, phoneNonce)
        val auth = fieldBytes(30, phoneNonceMessage)
        return frame(command(1, 26, 3 to auth), encrypted = false)
    }

    fun handleFrame(frame: ByteArray): Result {
        if (frame.size < 4 || frame[0] != 0.toByte() || frame[1] != 0.toByte()) return Result(message = "Paquete Xiaomi no reconocido")
        return when (frame[2].toInt() and 0xff) {
            2 -> {
                val encrypted = frame[3].toInt() == 1
                val payload = frame.copyOfRange(4, frame.size)
                val plain = if (encrypted) decrypt(payload) else payload
                handleCommand(plain).copy(needsAck = true)
            }
            3 -> Result(message = if (frame[3].toInt() == 0) "ACK Xiaomi" else "NACK Xiaomi ${frame[3].toInt() and 0xff}")
            else -> Result(message = "Trama Xiaomi tipo ${frame[2].toInt() and 0xff}")
        }
    }

    private fun handleCommand(bytes: ByteArray): Result {
        val cmd = ProtoReader(bytes)
        val type = cmd.varint(1)?.toInt()
        val subtype = cmd.varint(2)?.toInt()
        if (type == 1 && subtype == 26) {
            val auth = cmd.bytes(3) ?: return Result(message = "Respuesta Xiaomi sin auth")
            val watchNonceMessage = ProtoReader(auth).bytes(31) ?: return Result(message = "Respuesta Xiaomi sin nonce")
            val nonceReader = ProtoReader(watchNonceMessage)
            val watchNonce = nonceReader.bytes(1) ?: return Result(message = "Nonce del reloj ausente")
            val watchHmac = nonceReader.bytes(2) ?: return Result(message = "HMAC del reloj ausente")
            val material = derive(secretKey, phoneNonce, watchNonce)
            decryptionKey = material.copyOfRange(0, 16)
            encryptionKey = material.copyOfRange(16, 32)
            decryptionNonce = material.copyOfRange(32, 36)
            encryptionNonce = material.copyOfRange(36, 40)
            val confirmation = hmac(decryptionKey, watchNonce + phoneNonce)
            if (!confirmation.contentEquals(watchHmac)) return Result(message = "Clave Xiaomi rechazada: HMAC no coincide")

            val deviceInfo = fieldVarint(1, 0) + fieldFixed32(2, Build.VERSION.SDK_INT.toFloat()) +
                fieldString(3, Build.MODEL) + fieldVarint(4, 224) +
                fieldString(5, Locale.getDefault().country.ifBlank { "CO" }.take(2).uppercase())
            val step3 = fieldBytes(1, hmac(encryptionKey, phoneNonce + watchNonce)) +
                fieldBytes(2, encryptCcm(encryptionKey, packetNonce(encryptionNonce, 0), deviceInfo))
            val authStep3 = fieldBytes(32, step3)
            return Result(outgoing = listOf(frame(command(1, 27, 3 to authStep3), false)), message = "Reto Xiaomi validado")
        }
        if (type == 1 && subtype == 27) {
            authenticated = true
            encryptedIndex = 1
            return Result(
                outgoing = listOf(encryptedFrame(command(8, 45))),
                authenticatedNow = true,
                message = "Sesion Xiaomi autenticada; iniciando pulso"
            )
        }
        if (type == 8 && subtype == 47) {
            val health = cmd.bytes(10) ?: return Result(message = "Evento de salud sin datos")
            val stats = ProtoReader(health).bytes(39) ?: return Result(message = "Evento sin estadisticas")
            val bpm = ProtoReader(stats).varint(4)?.toInt()?.takeIf { it in 11..240 }
            return Result(heartRate = bpm, message = bpm?.let { "Pulso Xiaomi: $it lpm" } ?: "Esperando lectura cardiaca")
        }
        return Result(message = "Comando Xiaomi tipo=$type subtipo=$subtype")
    }

    private fun encryptedFrame(payload: ByteArray): ByteArray {
        val index = encryptedIndex++
        val encrypted = encryptCcm(encryptionKey, packetNonce(encryptionNonce, index), payload)
        return ByteBuffer.allocate(6 + encrypted.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).put(2).put(1).putShort(index.toShort()).put(encrypted).array()
    }

    private fun frame(payload: ByteArray, encrypted: Boolean): ByteArray =
        ByteBuffer.allocate(4 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).put(2).put(if (encrypted) 1 else 2).put(payload).array()

    private fun decrypt(payload: ByteArray): ByteArray = decryptCcm(decryptionKey, packetNonce(decryptionNonce, 0), payload)

    companion object {
        val ACK = byteArrayOf(0, 0, 3, 0)

        private fun command(type: Int, subtype: Int, nested: Pair<Int, ByteArray>? = null): ByteArray =
            fieldVarint(1, type.toLong()) + fieldVarint(2, subtype.toLong()) +
                (nested?.let { fieldBytes(it.first, it.second) } ?: byteArrayOf())

        private fun derive(secret: ByteArray, phone: ByteArray, watch: ByteArray): ByteArray {
            val first = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(phone + watch, "HmacSHA256")) }.doFinal(secret)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(first, "HmacSHA256")) }
            val info = "miwear-auth".toByteArray()
            var previous = byteArrayOf()
            val out = ByteArrayOutputStream()
            for (counter in 1..2) {
                previous = mac.doFinal(previous + info + counter.toByte())
                out.write(previous)
            }
            return out.toByteArray()
        }

        private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(input)

        private fun packetNonce(prefix: ByteArray, index: Int) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(prefix).putInt(0).putInt(index).array()

        private fun encryptCcm(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray = ccm(true, key, nonce, input)
        private fun decryptCcm(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray = ccm(false, key, nonce, input)
        private fun ccm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
            val cipher = CCMBlockCipher(AESEngine())
            cipher.init(encrypt, AEADParameters(KeyParameter(key), 32, nonce, null))
            val out = ByteArray(cipher.getOutputSize(input.size))
            val count = cipher.processBytes(input, 0, input.size, out, 0)
            cipher.doFinal(out, count)
            return out
        }

        private fun fieldVarint(field: Int, value: Long) = varint((field shl 3).toLong()) + varint(value)
        private fun fieldBytes(field: Int, value: ByteArray) = varint(((field shl 3) or 2).toLong()) + varint(value.size.toLong()) + value
        private fun fieldString(field: Int, value: String) = fieldBytes(field, value.toByteArray())
        private fun fieldFixed32(field: Int, value: Float) = varint(((field shl 3) or 5).toLong()) +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
        private fun varint(value: Long): ByteArray {
            var v = value
            val out = ByteArrayOutputStream()
            do { var b = (v and 0x7f).toInt(); v = v ushr 7; if (v != 0L) b = b or 0x80; out.write(b) } while (v != 0L)
            return out.toByteArray()
        }
        private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

private class ProtoReader(private val data: ByteArray) {
    private data class Item(val field: Int, val wire: Int, val number: Long?, val bytes: ByteArray?)
    private val items: List<Item> by lazy { parse() }
    fun varint(field: Int) = items.lastOrNull { it.field == field && it.wire == 0 }?.number
    fun bytes(field: Int) = items.lastOrNull { it.field == field && it.wire == 2 }?.bytes
    private fun parse(): List<Item> {
        val result = mutableListOf<Item>()
        var p = 0
        fun readVarint(): Long { var value = 0L; var shift = 0; while (p < data.size && shift < 64) { val b = data[p++].toInt() and 0xff; value = value or ((b and 0x7f).toLong() shl shift); if (b and 0x80 == 0) break; shift += 7 }; return value }
        while (p < data.size) {
            val tag = readVarint().toInt(); val field = tag ushr 3; val wire = tag and 7
            when (wire) {
                0 -> result += Item(field, wire, readVarint(), null)
                1 -> { if (p + 8 > data.size) break; p += 8 }
                2 -> { val size = readVarint().toInt(); if (size < 0 || p + size > data.size) break; result += Item(field, wire, null, data.copyOfRange(p, p + size)); p += size }
                5 -> { if (p + 4 > data.size) break; p += 4 }
                else -> break
            }
        }
        return result
    }
}
