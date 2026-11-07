#include "IrLink.h"

#include <algorithm>
#include <cstring>

namespace MelonDSAndroid
{

void IrLink::onGameSend(const uint8_t* data, int len)
{
    if (!data || len <= 0)
        return;

    TxSink sink;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (!txSink)
            return;
        sink = txSink;
    }

    // Called outside the lock so that the sink may push packets back without deadlocking
    markActive(now());
    sink(data, len);
}

int IrLink::onGamePoll(uint8_t* out, int maxLen)
{
    if (!out || maxLen <= 0)
        return 0;

    std::lock_guard<std::mutex> lock(mutex);
    if (rxQueue.empty())
        return 0;

    // Drop packets that waited too long (pause, hitch, game not polling): a stale reply would only
    // confuse the game
    Clock::time_point cutoff = now() - kMaxPacketAge;
    while (!rxQueue.empty() && rxQueue.front().received < cutoff)
        rxQueue.pop_front();

    if (rxQueue.empty())
        return 0;

    const std::vector<uint8_t>& packet = rxQueue.front().data;
    int len = std::min((int) packet.size(), maxLen);
    memcpy(out, packet.data(), len);
    rxQueue.pop_front();
    return len;
}

void IrLink::pushReceived(const uint8_t* data, int len)
{
    if (!data || len <= 0)
        return;

    Clock::time_point timestamp = now();
    {
        std::lock_guard<std::mutex> lock(mutex);
        while (rxQueue.size() >= kMaxQueuedPackets)
            rxQueue.pop_front();

        rxQueue.push_back(Packet { timestamp, std::vector<uint8_t>(data, data + len) });
    }
    markActive(timestamp);
}

void IrLink::flush()
{
    std::lock_guard<std::mutex> lock(mutex);
    rxQueue.clear();
}

void IrLink::setTxSink(TxSink sink)
{
    bool detached = !sink;
    {
        std::lock_guard<std::mutex> lock(mutex);
        txSink = std::move(sink);
    }

    if (detached)
        lastActivityNs.store(INT64_MIN, std::memory_order_relaxed);
}

bool IrLink::recentlyActive() const
{
    int64_t last = lastActivityNs.load(std::memory_order_relaxed);
    if (last == INT64_MIN)
        return false;

    int64_t current = std::chrono::duration_cast<std::chrono::nanoseconds>(now().time_since_epoch()).count();
    return current - last < std::chrono::duration_cast<std::chrono::nanoseconds>(kActivityWindow).count();
}

void IrLink::setClockForTesting(std::function<Clock::time_point()> testClock)
{
    clock = std::move(testClock);
}

IrLink::Clock::time_point IrLink::now() const
{
    return clock ? clock() : Clock::now();
}

void IrLink::markActive(Clock::time_point when)
{
    lastActivityNs.store(std::chrono::duration_cast<std::chrono::nanoseconds>(when.time_since_epoch()).count(), std::memory_order_relaxed);
}

}
