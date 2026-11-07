package me.magnum.melonds.ir

import androidx.annotation.Keep
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Receives IR packets transmitted by the emulated cartridge. Native code calls [onIrTransmit] on the
 * emulator thread while the IR link is attached (`MelonEmulator.irAttach()`), so it only enqueues;
 * [IrDongleManager]'s writer drains the queue onto USB.
 */
@Keep
object IrBridge {
    // The game sends at most a few packets per 100 ms; 16 covers any burst. If the writer stalls,
    // the oldest packets are dropped (they would be stale for the game anyway).
    internal val txQueue = Channel<ByteArray>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Keep
    @JvmStatic
    fun onIrTransmit(data: ByteArray) {
        txQueue.trySend(data)
    }

    /** Drops packets queued while no dongle was writing (e.g. before a reconnect). */
    internal fun clearPending() {
        while (txQueue.tryReceive().isSuccess) {
            // Discard
        }
    }
}
