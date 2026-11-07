package me.magnum.melonds.ir

import java.io.ByteArrayOutputStream

/**
 * Consistent Overhead Byte Stuffing, as used by the IR dongle's host link. Matches the dongle
 * firmware and `walkerctl.py`: a full 254-byte block is always closed, so exactly 254 non-zero
 * bytes encode to `FF <254 bytes> 01`.
 */
object Cobs {

    fun maxEncodedLength(length: Int): Int = length + length / 254 + 1

    /** Encodes [data] without the trailing 0x00 delimiter. */
    fun encode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        val out = ByteArray(maxEncodedLength(length))
        var write = 1
        var codePos = 0
        var code = 1
        for (i in offset until offset + length) {
            val b = data[i]
            if (b.toInt() == 0) {
                out[codePos] = code.toByte()
                codePos = write++
                code = 1
            } else {
                out[write++] = b
                code++
                if (code == 0xFF) {
                    out[codePos] = code.toByte()
                    codePos = write++
                    code = 1
                }
            }
        }
        out[codePos] = code.toByte()
        return out.copyOf(write)
    }

    /** Decodes [data] (no delimiter). Returns null if it is not valid COBS or exceeds [maxLength]. */
    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, maxLength: Int = Int.MAX_VALUE): ByteArray? {
        val out = ByteArrayOutputStream(length)
        var i = offset
        val end = offset + length
        while (i < end) {
            val code = data[i++].toInt() and 0xFF
            if (code == 0) return null
            val n = code - 1
            if (n > end - i) return null
            for (k in 0 until n) {
                val b = data[i++]
                if (b.toInt() == 0) return null
                out.write(b.toInt())
            }
            if (code != 0xFF && i < end) {
                out.write(0)
            }
            if (out.size() > maxLength) return null
        }
        return out.toByteArray()
    }
}

/**
 * Splits a byte stream into 0x00-delimited COBS frames and decodes them. Not thread-safe: feed it
 * from a single reader thread. Empty frames (0x00 0x00) are skipped silently, as the protocol allows
 * them for resynchronisation.
 */
class CobsStreamDecoder(
    private val maxDecodedLength: Int,
    private val onFrame: (ByteArray) -> Unit,
    private val onMalformed: (reason: String) -> Unit = {},
) {
    private val buffer = ByteArray(Cobs.maxEncodedLength(maxDecodedLength))
    private var length = 0
    private var overflow = false

    fun feed(data: ByteArray, offset: Int = 0, count: Int = data.size - offset) {
        for (i in offset until offset + count) {
            val b = data[i]
            if (b.toInt() != 0) {
                if (length < buffer.size) {
                    buffer[length++] = b
                } else {
                    overflow = true
                }
                continue
            }

            if (overflow) {
                onMalformed("frame longer than $maxDecodedLength bytes")
            } else if (length > 0) {
                val decoded = Cobs.decode(buffer, 0, length, maxDecodedLength)
                if (decoded == null) {
                    onMalformed("bad COBS")
                } else {
                    onFrame(decoded)
                }
            }
            reset()
        }
    }

    fun reset() {
        length = 0
        overflow = false
    }
}
