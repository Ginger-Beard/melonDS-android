#ifndef IRLINK_H
#define IRLINK_H

#include <atomic>
#include <chrono>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <vector>

namespace MelonDSAndroid
{

/**
 * Packet link between an IR cart (Platform::IR_SendPacket / IR_RecvPacket) and an external IR
 * transceiver owned by the frontend. Packets are whole and passed exactly as they travel through
 * the air.
 *
 * onGameSend() and onGamePoll() are called on the emulator thread and never block for longer than
 * a short critical section. Every other method may be called from any thread.
 */
class IrLink
{
public:
    using Clock = std::chrono::steady_clock;
    // Called on the emulator thread with a packet sent by the game. Must only enqueue and return.
    using TxSink = std::function<void(const uint8_t* data, int len)>;

    static constexpr size_t kMaxQueuedPackets = 8;
    static constexpr Clock::duration kMaxPacketAge = std::chrono::milliseconds(500);
    static constexpr Clock::duration kActivityWindow = std::chrono::seconds(2);

    // emulator thread
    void onGameSend(const uint8_t* data, int len);
    int onGamePoll(uint8_t* out, int maxLen);

    // any thread
    void pushReceived(const uint8_t* data, int len);
    void flush();
    // A null sink drops transmitted packets (no transceiver attached).
    void setTxSink(TxSink sink);
    // True if a packet was sent or received over a connected link within kActivityWindow.
    bool recentlyActive() const;

    // Test hook: replaces the clock used for packet age and activity.
    void setClockForTesting(std::function<Clock::time_point()> clock);

private:
    struct Packet
    {
        Clock::time_point received;
        std::vector<uint8_t> data;
    };

    Clock::time_point now() const;
    void markActive(Clock::time_point when);

    mutable std::mutex mutex;
    std::deque<Packet> rxQueue;
    TxSink txSink;
    std::function<Clock::time_point()> clock;
    std::atomic<int64_t> lastActivityNs { INT64_MIN };
};

}

#endif // IRLINK_H
