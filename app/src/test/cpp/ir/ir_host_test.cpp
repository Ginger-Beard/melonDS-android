// Host test for the IR cart link: CartRetailIR's IR SPI commands (0x01 receive, 0x02 send) driven
// the way the game's libirc drives them, wired through the Platform hooks to the frontend IrLink.
//
// The SPI command cases are extracted verbatim from melonDS-android-lib/src/NDSCart.cpp at build
// time, so the test exercises the real core code; the AUXSPI position/hold logic is a copy of
// NDSCartSlot::WriteSPIData. Build and run from the repository root (host g++, no Android):
//
//   mkdir -p builds/ir-host-test && sed -n '/case 0x01: \/\/ receive/,/case 0x08: \/\/ ID/p' melonDS-android-lib/src/NDSCart.cpp | sed '$d' > builds/ir-host-test/ir_spi_cases.inc && g++ -std=c++17 -O1 -g -Wall -Wextra -pthread -Ibuilds/ir-host-test -Iapp/src/main/cpp app/src/test/cpp/ir/ir_host_test.cpp app/src/main/cpp/IrLink.cpp -o builds/ir-host-test/ir_host_test && builds/ir-host-test/ir_host_test

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>

#include "IrLink.h"

using u8 = uint8_t;
using u32 = uint32_t;

using MelonDSAndroid::IrLink;

// ---------------------------------------------------------------------------------------------
// Platform hooks, implemented like PlatformAndroid.cpp (userdata -> IrLink)

namespace Platform
{
int recvCalls = 0;

void IR_SendPacket(const u8* data, int len, void* userdata)
{
    if (userdata)
        static_cast<IrLink*>(userdata)->onGameSend(data, len);
}

int IR_RecvPacket(u8* data, int maxlen, void* userdata)
{
    recvCalls++;
    if (!userdata)
        return 0;
    return static_cast<IrLink*>(userdata)->onGamePoll(data, maxlen);
}
}

// ---------------------------------------------------------------------------------------------
// Minimal CartRetailIR: the same members the core uses, with the real IR command cases

struct TestCartIR
{
    static constexpr u32 IRBufferSize = 0xB8;
    void* UserData = nullptr;
    u8 IRCmd = 0;
    u8 IRRxBuf[IRBufferSize] {};
    u32 IRRxLen = 0;
    u8 IRTxBuf[IRBufferSize] {};

    void Reset()
    {
        IRCmd = 0;
        IRRxLen = 0;
    }

    u8 SPIWrite(u8 val, u32 pos, bool last)
    {
        if (pos == 0)
        {
            IRCmd = val;
            return 0;
        }

        switch (IRCmd)
        {
        case 0x00: // pass-through (not under test)
            return 0xFF;

#include "ir_spi_cases.inc"

        case 0x08: // ID
            return 0xAA;
        }

        return 0;
    }
};

// AUXSPICNT/AUXSPIDATA position logic, copied from NDSCartSlot::WriteSPIData
struct TestSlot
{
    TestCartIR cart;
    bool SPIHold = false;
    u32 SPIDataPos = 0;

    u8 transfer(u8 val, bool hold)
    {
        bool islast = false;
        if (!hold)
        {
            if (SPIHold) SPIDataPos++;
            else         SPIDataPos = 0;
            islast = true;
            SPIHold = false;
        }
        else if (hold && (!SPIHold))
        {
            SPIHold = true;
            SPIDataPos = 0;
        }
        else
        {
            SPIDataPos++;
        }
        return cart.SPIWrite(val, SPIDataPos, islast);
    }

    // CARD_SpiWaitReadRange (libirc): cmd 0x01 + length byte with CS held; then `len` bytes with CS
    // released on the last one, or one dummy byte with CS released if len == 0
    std::vector<u8> gameRead()
    {
        transfer(0x01, true);
        u32 len = transfer(0x00, true);
        if (len > 0xB9)
            len = 0;

        std::vector<u8> data;
        if (len == 0)
        {
            transfer(0x00, false);
        }
        else
        {
            for (u32 i = 0; i < len; i++)
                data.push_back(transfer(0x00, i != len - 1));
        }
        return data;
    }

    // CARD_SpiWaitWriteRange (libirc): cmd 0x02 with CS held, then the data with CS released on the
    // last byte
    void gameWrite(const std::vector<u8>& data)
    {
        transfer(0x02, true);
        for (size_t i = 0; i < data.size(); i++)
            transfer(data[i], i != data.size() - 1);
    }
};

// ---------------------------------------------------------------------------------------------

static int failures = 0;
static int checks = 0;

#define CHECK(cond) do { checks++; if (!(cond)) { failures++; std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } } while (0)

static std::vector<u8> pattern(size_t len, u8 seed)
{
    std::vector<u8> v(len);
    for (size_t i = 0; i < len; i++)
        v[i] = (u8) (seed + i * 7);
    return v;
}

struct FakeClock
{
    IrLink::Clock::time_point t = IrLink::Clock::time_point(std::chrono::seconds(1000));
    void advance(std::chrono::milliseconds ms) { t += ms; }
};

struct Fixture
{
    IrLink link;
    TestSlot slot;
    FakeClock clock;
    std::vector<std::vector<u8>> sent;

    Fixture()
    {
        link.setClockForTesting([this] { return clock.t; });
        link.setTxSink([this](const u8* d, int n) { sent.emplace_back(d, d + n); });
        slot.cart.UserData = &link;
        slot.cart.Reset();
    }
};

static void testPollEmpty()
{
    Fixture f;
    int before = Platform::recvCalls;
    CHECK(f.slot.gameRead().empty());
    CHECK(Platform::recvCalls - before == 1); // exactly one hook call per poll transfer
    CHECK(!f.slot.SPIHold);                    // dummy read released CS
    // the next transfer still parses its command byte correctly
    f.link.pushReceived(pattern(8, 1).data(), 8);
    CHECK(f.slot.gameRead() == pattern(8, 1));
}

static void testReceiveOnePacketPerPoll()
{
    Fixture f;
    auto a = pattern(136, 0x10), b = pattern(8, 0x80);
    f.link.pushReceived(a.data(), (int) a.size());
    f.link.pushReceived(b.data(), (int) b.size());
    CHECK(f.slot.gameRead() == a); // not merged with b
    CHECK(f.slot.gameRead() == b);
    CHECK(f.slot.gameRead().empty());
}

static void testAdvert()
{
    Fixture f;
    u8 advert = 0x56; // 0xFC ^ 0xAA
    f.link.pushReceived(&advert, 1);
    auto got = f.slot.gameRead();
    CHECK(got.size() == 1 && got[0] == 0x56);

    f.slot.gameWrite({ 0x56 });
    CHECK(f.sent.size() == 1 && f.sent[0] == std::vector<u8>{ 0x56 });
}

static void testMaxSizes()
{
    Fixture f;
    auto max = pattern(0xB8, 3);
    f.link.pushReceived(max.data(), (int) max.size());
    CHECK(f.slot.gameRead() == max);

    // oversized packets are truncated to the core buffer (the checksum will then fail in the game)
    auto big = pattern(0xC0, 5);
    f.link.pushReceived(big.data(), (int) big.size());
    auto got = f.slot.gameRead();
    CHECK(got.size() == 0xB8 && std::equal(got.begin(), got.end(), big.begin()));

    f.slot.gameWrite(max);
    CHECK(f.sent.size() == 1 && f.sent[0] == max);
    f.slot.gameWrite(big);
    CHECK(f.sent.size() == 2 && f.sent[1].size() == 0xB8 && std::equal(f.sent[1].begin(), f.sent[1].end(), big.begin()));
}

static void testSendOnCsRelease()
{
    Fixture f;
    auto pkt = pattern(8, 0x20);
    f.slot.transfer(0x02, true);
    for (size_t i = 0; i + 1 < pkt.size(); i++)
        f.slot.transfer(pkt[i], true);
    CHECK(f.sent.empty()); // nothing sent while CS is held
    f.slot.transfer(pkt.back(), false);
    CHECK(f.sent.size() == 1 && f.sent[0] == pkt);

    // command byte alone with CS released sends nothing
    f.slot.transfer(0x02, false);
    CHECK(f.sent.size() == 1);
}

static void testIdAndInterleaving()
{
    Fixture f;
    f.slot.transfer(0x08, true);
    CHECK(f.slot.transfer(0x00, false) == 0xAA);

    // a typical exchange: game sends, peer replies, game polls
    f.slot.gameWrite(pattern(8, 0x40));
    CHECK(f.slot.gameRead().empty());
    auto reply = pattern(16, 0x50);
    f.link.pushReceived(reply.data(), (int) reply.size());
    CHECK(f.slot.gameRead() == reply);
    CHECK(f.sent.size() == 1);
}

static void testResetClearsRx()
{
    Fixture f;
    auto pkt = pattern(8, 0x60);
    f.link.pushReceived(pkt.data(), 8);
    f.slot.transfer(0x01, true);
    CHECK(f.slot.transfer(0x00, true) == 8); // length read, data not yet read
    f.slot.cart.Reset();
    f.slot.SPIHold = false;
    f.slot.transfer(0x01, true);
    f.slot.transfer(0x00, true);              // nothing pending
    CHECK(f.slot.transfer(0x00, false) == 0); // stale data not returned
}

static void testNullUserdata()
{
    TestSlot slot;
    slot.cart.UserData = nullptr;
    CHECK(slot.gameRead().empty());
    slot.gameWrite({ 1, 2, 3 }); // must not crash
}

static void testQueueCapDropsOldest()
{
    Fixture f;
    for (u8 i = 0; i < 12; i++)
        f.link.pushReceived(&i, 1);
    for (u8 i = 4; i < 12; i++)
    {
        auto got = f.slot.gameRead();
        CHECK(got.size() == 1 && got[0] == i);
    }
    CHECK(f.slot.gameRead().empty());
}

static void testFlush()
{
    Fixture f;
    auto pkt = pattern(8, 0x70);
    f.link.pushReceived(pkt.data(), 8);
    f.link.pushReceived(pkt.data(), 8);
    f.link.flush();
    CHECK(f.slot.gameRead().empty());
}

static void testStaleness()
{
    Fixture f;
    auto old = pattern(8, 1), fresh = pattern(8, 2);
    f.link.pushReceived(old.data(), 8);
    f.clock.advance(std::chrono::milliseconds(400));
    f.link.pushReceived(fresh.data(), 8);
    f.clock.advance(std::chrono::milliseconds(200)); // old is 600 ms, fresh is 200 ms
    CHECK(f.slot.gameRead() == fresh);
    CHECK(f.slot.gameRead().empty());

    f.link.pushReceived(fresh.data(), 8);
    f.clock.advance(std::chrono::milliseconds(499));
    CHECK(f.slot.gameRead() == fresh); // still within the window
}

static void testRecentlyActive()
{
    Fixture f;
    CHECK(!f.link.recentlyActive());
    f.slot.gameWrite({ 1 });
    CHECK(f.link.recentlyActive());
    f.clock.advance(std::chrono::milliseconds(1900));
    CHECK(f.link.recentlyActive());
    f.clock.advance(std::chrono::milliseconds(200));
    CHECK(!f.link.recentlyActive());

    u8 b = 1;
    f.link.pushReceived(&b, 1);
    CHECK(f.link.recentlyActive());

    // detaching clears activity, and sends without a sink are dropped and don't count
    f.link.setTxSink(nullptr);
    CHECK(!f.link.recentlyActive());
    f.slot.gameWrite({ 2 });
    CHECK(!f.link.recentlyActive());
    CHECK(f.sent.empty() || f.sent.back() != std::vector<u8>{ 2 });
}

static void testSinkMayPushBack()
{
    // a loopback sink calling back into the link from the emulator thread must not deadlock
    IrLink link;
    link.setTxSink([&link](const u8* d, int n) { link.pushReceived(d, n); });
    TestSlot slot;
    slot.cart.UserData = &link;
    slot.gameWrite({ 9, 8, 7 });
    CHECK((slot.gameRead() == std::vector<u8>{ 9, 8, 7 }));
}

static void testConcurrentPushAndPoll()
{
    IrLink link;
    TestSlot slot;
    slot.cart.UserData = &link;
    std::atomic<bool> done { false };
    const int kPackets = 20000;

    std::thread producer([&] {
        for (int i = 0; i < kPackets; i++)
        {
            auto p = pattern(1 + (i % 136), (u8) i);
            link.pushReceived(p.data(), (int) p.size());
            if (i % 64 == 0)
                link.flush();
        }
        done = true;
    });

    int received = 0;
    bool wellFormed = true;
    while (!done)
    {
        auto got = slot.gameRead();
        if (!got.empty())
        {
            received++;
            // every packet is whole: it matches the pattern for its length and first byte
            auto expected = pattern(got.size(), got[0]);
            wellFormed &= got == expected;
        }
    }
    producer.join();
    CHECK(received > 0);
    CHECK(wellFormed);
}

int main()
{
    testPollEmpty();
    testReceiveOnePacketPerPoll();
    testAdvert();
    testMaxSizes();
    testSendOnCsRelease();
    testIdAndInterleaving();
    testResetClearsRx();
    testNullUserdata();
    testQueueCapDropsOldest();
    testFlush();
    testStaleness();
    testRecentlyActive();
    testSinkMayPushBack();
    testConcurrentPushAndPoll();

    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
