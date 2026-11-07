package me.magnum.melonds.ir

/**
 * Host side of the IR dongle's USB serial protocol, version 1 (framed mode). Every frame is
 * `COBS(type + payload) + 0x00`; multi-byte integers are little-endian.
 *
 * `RAW_MODE` (0x04) is deliberately not implemented: the app must never leave framed mode.
 */
object DongleProtocol {
    const val PROTOCOL_VERSION = 1
    val MAGIC = byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 'W'.code.toByte())

    const val CAP_FRAMED = 0x01
    const val CAP_RAW = 0x02
    const val CAP_WIFI = 0x04

    const val MAX_FRAME = 300
    const val MAX_IR_PACKET = 256
    const val MAX_PING = 32

    // Host -> dongle
    const val TYPE_IR_TX = 0x01
    const val TYPE_IDENTIFY = 0x02
    const val TYPE_SET_CONFIG = 0x03
    const val TYPE_PING = 0x05

    // Dongle -> host
    const val TYPE_IR_RX = 0x81
    const val TYPE_IDENTIFY_RESP = 0x82
    const val TYPE_CONFIG_ACK = 0x83
    const val TYPE_TX_DONE = 0x84
    const val TYPE_PONG = 0x85
    const val TYPE_LOG = 0x8E
    const val TYPE_ERROR = 0x8F

    // SET_CONFIG keys
    const val CONFIG_GAP_US = 0x01
    const val CONFIG_ECHO_GUARD_US = 0x02
    const val CONFIG_TRANSCEIVER_ENABLE = 0x03

    fun errorName(code: Int): String = when (code) {
        0x01 -> "malformed frame"
        0x02 -> "unknown type"
        0x03 -> "IR RX overflow or truncation"
        0x04 -> "IR TX too long"
        0x05 -> "bad config key"
        0x06 -> "IR TX queue full (packet dropped)"
        else -> "unknown error"
    }

    /** Builds a complete wire frame: COBS-encoded type + payload, then the 0x00 delimiter. */
    fun encodeFrame(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(1 + payload.size <= MAX_FRAME) { "frame too long" }
        val decoded = ByteArray(1 + payload.size)
        decoded[0] = type.toByte()
        payload.copyInto(decoded, 1)
        val encoded = Cobs.encode(decoded)
        return encoded.copyOf(encoded.size + 1)
    }

    fun irTxFrame(packet: ByteArray): ByteArray {
        require(packet.isNotEmpty() && packet.size <= MAX_IR_PACKET) { "IR packet must be 1..$MAX_IR_PACKET bytes" }
        return encodeFrame(TYPE_IR_TX, packet)
    }

    fun identifyFrame(): ByteArray = encodeFrame(TYPE_IDENTIFY)

    fun pingFrame(payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PING) { "PING payload longer than $MAX_PING bytes" }
        return encodeFrame(TYPE_PING, payload)
    }

    fun setConfigU16Frame(key: Int, value: Int): ByteArray {
        return encodeFrame(TYPE_SET_CONFIG, byteArrayOf(key.toByte(), (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))
    }

    /** Parses one decoded frame (type byte + payload). Returns null for an empty or truncated frame. */
    fun parse(frame: ByteArray): DongleMessage? {
        if (frame.isEmpty()) return null
        val type = frame[0].toInt() and 0xFF
        val payload = frame.copyOfRange(1, frame.size)
        return when (type) {
            TYPE_IR_RX -> {
                if (payload.size < 4) return null
                DongleMessage.IrRx(readU32(payload, 0), payload.copyOfRange(4, payload.size))
            }
            TYPE_IDENTIFY_RESP -> {
                if (payload.size < 6) return null
                DongleMessage.IdentifyResponse(
                    magic = payload.copyOfRange(0, 4),
                    protocolVersion = payload[4].toInt() and 0xFF,
                    capabilities = payload[5].toInt() and 0xFF,
                    firmwareVersion = String(payload, 6, payload.size - 6, Charsets.US_ASCII),
                )
            }
            TYPE_CONFIG_ACK -> {
                if (payload.size < 2) return null
                DongleMessage.ConfigAck(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF)
            }
            TYPE_TX_DONE -> {
                if (payload.size < 4) return null
                DongleMessage.TxDone(readU32(payload, 0))
            }
            TYPE_PONG -> DongleMessage.Pong(payload)
            TYPE_LOG -> DongleMessage.Log(String(payload, Charsets.US_ASCII))
            TYPE_ERROR -> {
                if (payload.isEmpty()) return null
                DongleMessage.Error(payload[0].toInt() and 0xFF, String(payload, 1, payload.size - 1, Charsets.US_ASCII))
            }
            else -> DongleMessage.Unknown(type, payload)
        }
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
}

sealed class DongleMessage {
    /** One received IR packet, still XOR'd, with the dongle's timestamp of its first byte. */
    class IrRx(val timestampUs: Long, val packet: ByteArray) : DongleMessage()

    class IdentifyResponse(val magic: ByteArray, val protocolVersion: Int, val capabilities: Int, val firmwareVersion: String) : DongleMessage() {
        val isSupported: Boolean
            get() = magic.contentEquals(DongleProtocol.MAGIC) &&
                    protocolVersion == DongleProtocol.PROTOCOL_VERSION &&
                    (capabilities and DongleProtocol.CAP_FRAMED) != 0
    }

    data class ConfigAck(val key: Int, val status: Int) : DongleMessage()
    data class TxDone(val timestampUs: Long) : DongleMessage()
    class Pong(val payload: ByteArray) : DongleMessage()
    data class Log(val text: String) : DongleMessage()
    data class Error(val code: Int, val text: String) : DongleMessage()
    class Unknown(val type: Int, val payload: ByteArray) : DongleMessage()
}
