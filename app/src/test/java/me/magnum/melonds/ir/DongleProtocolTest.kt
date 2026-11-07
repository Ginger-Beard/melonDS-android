package me.magnum.melonds.ir

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire vectors were generated with `frame_encode` / `cobs_encode` from melonds-walker's
 * tools/walkerctl.py, so the app and the reference client agree byte for byte.
 */
class DongleProtocolTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Strips the 0x00 delimiter and COBS-decodes one wire frame. */
    private fun decodeWire(wire: ByteArray): ByteArray {
        assertEquals(0.toByte(), wire.last())
        return Cobs.decode(wire, 0, wire.size - 1)!!
    }

    @Test
    fun identifyFrame() {
        assertArrayEquals(hex("020200"), DongleProtocol.identifyFrame())
    }

    @Test
    fun irTxFrame() {
        // Arbitrary on-air (already XOR'd) bytes containing zeros, as the dongle sees them
        val packet = hex("50ab00aa00000000")
        assertArrayEquals(hex("040150ab02aa0101010100"), DongleProtocol.irTxFrame(packet))
        assertArrayEquals(hex("030150021200"), DongleProtocol.irTxFrame(hex("500012")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun irTxRejectsEmptyPacket() {
        DongleProtocol.irTxFrame(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun irTxRejectsOversizedPacket() {
        DongleProtocol.irTxFrame(ByteArray(DongleProtocol.MAX_IR_PACKET + 1) { 1 })
    }

    @Test
    fun irTxMaxPacketRoundTrips() {
        val packet = ByteArray(DongleProtocol.MAX_IR_PACKET) { (it and 0xFF).toByte() }
        val decoded = decodeWire(DongleProtocol.irTxFrame(packet))
        assertEquals(DongleProtocol.TYPE_IR_TX, decoded[0].toInt())
        assertArrayEquals(packet, decoded.copyOfRange(1, decoded.size))
    }

    @Test
    fun pingFrame() {
        assertArrayEquals(hex("030501020200"), DongleProtocol.pingFrame(hex("010002")))
    }

    @Test
    fun setConfigIsLittleEndian() {
        val decoded = decodeWire(DongleProtocol.setConfigU16Frame(DongleProtocol.CONFIG_GAP_US, 1000))
        assertArrayEquals(hex("0301e803"), decoded)
    }

    @Test
    fun parseIdentifyResponse() {
        val message = DongleProtocol.parse(decodeWire(hex("0d824d4453570103302e312e3000")))
        assertTrue(message is DongleMessage.IdentifyResponse)
        message as DongleMessage.IdentifyResponse
        assertArrayEquals("MDSW".toByteArray(), message.magic)
        assertEquals(1, message.protocolVersion)
        assertEquals(DongleProtocol.CAP_FRAMED or DongleProtocol.CAP_RAW, message.capabilities)
        assertEquals("0.1.0", message.firmwareVersion)
        assertTrue(message.isSupported)
    }

    @Test
    fun identifyResponseValidation() {
        fun response(magic: String, proto: Int, caps: Int) =
            DongleProtocol.parse(byteArrayOf(0x82.toByte()) + magic.toByteArray() + byteArrayOf(proto.toByte(), caps.toByte())) as DongleMessage.IdentifyResponse

        assertTrue(response("MDSW", 1, 0x01).isSupported)
        assertEquals("", response("MDSW", 1, 0x01).firmwareVersion)
        assertFalse(response("ESP3", 1, 0x03).isSupported)
        assertFalse(response("MDSW", 2, 0x03).isSupported)
        assertFalse(response("MDSW", 1, 0x02).isSupported)
        assertNull(DongleProtocol.parse(hex("824d445357")))
    }

    @Test
    fun parseIrRxStripsTimestamp() {
        val message = DongleProtocol.parse(decodeWire(hex("0781785634125600")))
        assertTrue(message is DongleMessage.IrRx)
        message as DongleMessage.IrRx
        assertEquals(0x12345678L, message.timestampUs)
        assertArrayEquals(hex("56"), message.packet)

        val withZeros = DongleProtocol.parse(decodeWire(hex("028102010102aa020100"))) as DongleMessage.IrRx
        assertEquals(0x100L, withZeros.timestampUs)
        assertArrayEquals(hex("aa0001"), withZeros.packet)
    }

    @Test
    fun parseIrRxTimestampIsUnsigned() {
        val message = DongleProtocol.parse(hex("81ffffffff01")) as DongleMessage.IrRx
        assertEquals(0xFFFFFFFFL, message.timestampUs)
        assertArrayEquals(hex("01"), message.packet)
    }

    @Test
    fun parseTruncatedFramesReturnNull() {
        assertNull(DongleProtocol.parse(ByteArray(0)))
        assertNull(DongleProtocol.parse(hex("81010203")))
        assertNull(DongleProtocol.parse(hex("8301")))
        assertNull(DongleProtocol.parse(hex("84010203")))
        assertNull(DongleProtocol.parse(hex("8f")))
    }

    @Test
    fun parseOtherMessages() {
        assertEquals(DongleMessage.ConfigAck(0x01, 0), DongleProtocol.parse(hex("830100")))
        assertEquals(DongleMessage.TxDone(0x04030201L), DongleProtocol.parse(hex("8401020304")))
        assertEquals(DongleMessage.Log("hi"), DongleProtocol.parse(hex("8e6869")))
        assertEquals(DongleMessage.Error(0x06, "full"), DongleProtocol.parse(hex("8f0666756c6c")))
        assertArrayEquals(hex("0102"), (DongleProtocol.parse(hex("850102")) as DongleMessage.Pong).payload)
        val unknown = DongleProtocol.parse(hex("9001")) as DongleMessage.Unknown
        assertEquals(0x90, unknown.type)
    }

    @Test
    fun streamOfMixedFrames() {
        val messages = mutableListOf<DongleMessage?>()
        val decoder = CobsStreamDecoder(DongleProtocol.MAX_FRAME, { messages += DongleProtocol.parse(it) })
        decoder.feed(hex("0d824d4453570103302e312e3000") + hex("0781785634125600"))
        assertEquals(2, messages.size)
        assertTrue(messages[0] is DongleMessage.IdentifyResponse)
        assertTrue(messages[1] is DongleMessage.IrRx)
    }
}
