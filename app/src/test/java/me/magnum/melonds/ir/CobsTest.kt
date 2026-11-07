package me.magnum.melonds.ir

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected encodings were generated with `cobs_encode` from melonds-walker's tools/walkerctl.py. */
class CobsTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** 1, 2, ..., 255, 1, 2, ... (never zero), like the Python vectors. */
    private fun nonZeroRun(n: Int): ByteArray = ByteArray(n) { ((it % 255) + 1).toByte() }

    private fun assertRoundTrip(data: ByteArray, expectedEncoded: ByteArray? = null) {
        val encoded = Cobs.encode(data)
        expectedEncoded?.let { assertArrayEquals(it, encoded) }
        assertEquals("no zero bytes in encoded data", -1, encoded.indexOf(0))
        assertArrayEquals(data, Cobs.decode(encoded))
    }

    @Test
    fun emptyInput() {
        assertRoundTrip(ByteArray(0), hex("01"))
    }

    @Test
    fun singleZero() {
        assertRoundTrip(hex("00"), hex("0101"))
    }

    @Test
    fun runOfZeros() {
        assertRoundTrip(hex("000000"), hex("01010101"))
    }

    @Test
    fun mixed() {
        assertRoundTrip(hex("11220033"), hex("0311220233"))
    }

    @Test
    fun trailingZero() {
        assertRoundTrip(hex("1100"), hex("021101"))
    }

    @Test
    fun block253() {
        val data = nonZeroRun(253)
        val expected = byteArrayOf(0xFE.toByte()) + data
        assertRoundTrip(data, expected)
    }

    @Test
    fun block254ClosesWithExtraCode() {
        // walkerctl / firmware always close a full block: FF <254 bytes> 01
        val data = nonZeroRun(254)
        val expected = byteArrayOf(0xFF.toByte()) + data + byteArrayOf(0x01)
        assertRoundTrip(data, expected)
        assertEquals(256, Cobs.encode(data).size)
    }

    @Test
    fun block255() {
        val data = nonZeroRun(255)
        val expected = byteArrayOf(0xFF.toByte()) + data.copyOfRange(0, 254) + byteArrayOf(0x02, 0xFF.toByte())
        assertRoundTrip(data, expected)
    }

    @Test
    fun block254ThenZero() {
        val data = nonZeroRun(254) + byteArrayOf(0)
        val expected = byteArrayOf(0xFF.toByte()) + nonZeroRun(254) + byteArrayOf(0x01, 0x01)
        assertRoundTrip(data, expected)
    }

    @Test
    fun maxEncodedLengthIsAnUpperBound() {
        for (n in listOf(0, 1, 253, 254, 255, 300, 508, 509)) {
            val encoded = Cobs.encode(nonZeroRun(n))
            assertTrue("n=$n", encoded.size <= Cobs.maxEncodedLength(n))
        }
    }

    @Test
    fun decodeRejectsMalformed() {
        assertNull(Cobs.decode(hex("0011")))       // zero code byte
        assertNull(Cobs.decode(hex("0511")))       // block overruns input
        assertNull(Cobs.decode(hex("031100")))     // zero inside block
        assertNull(Cobs.decode(hex("0311220233"), maxLength = 3))
    }

    @Test
    fun streamDecoderSplitsFramesAcrossChunks() {
        val frames = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        val decoder = CobsStreamDecoder(300, { frames += it }) { errors += it }

        // Leading resync zero, two frames, the second split across feeds, then an empty frame
        val wire = hex("00") + hex("0311220233") + hex("00") + hex("021101") + hex("00") + hex("00")
        decoder.feed(wire, 0, 7)
        decoder.feed(wire, 7, wire.size - 7)

        assertEquals(0, errors.size)
        assertEquals(2, frames.size)
        assertArrayEquals(hex("11220033"), frames[0])
        assertArrayEquals(hex("1100"), frames[1])
    }

    @Test
    fun streamDecoderDropsOversizedAndBadFramesThenRecovers() {
        val frames = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        val decoder = CobsStreamDecoder(8, { frames += it }) { errors += it }

        decoder.feed(ByteArray(40) { 0x01 } + hex("00"))   // too long
        decoder.feed(hex("0511") + hex("00"))              // bad COBS
        decoder.feed(hex("020500"))                        // good: [05]

        assertEquals(2, errors.size)
        assertEquals(1, frames.size)
        assertArrayEquals(hex("05"), frames[0])
    }
}
