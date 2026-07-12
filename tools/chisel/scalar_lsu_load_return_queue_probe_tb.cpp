#include <cstdlib>
#include <iostream>

#include "VScalarLSULoadReturnQueueProbe.h"
#include "verilated.h"

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "scalar-lsu-load-return-queue-probe: " << message << '\n';
        std::exit(1);
    }
}

static void tick(VScalarLSULoadReturnQueueProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void idle(VScalarLSULoadReturnQueueProbe &dut) {
    dut.io_enqueueValid = 0;
    dut.io_drainReady = 0;
    dut.io_hardFlush = 0;
    dut.io_preciseFlushValid = 0;
}

static void enqueue(VScalarLSULoadReturnQueueProbe &dut, unsigned stid,
                    unsigned pipe, unsigned bid, bool wrap, unsigned lsid,
                    unsigned long long data) {
    idle(dut);
    dut.io_enqueueValid = 1;
    dut.io_enqueueStid = stid;
    dut.io_enqueuePipe = pipe;
    dut.io_enqueueBid = bid;
    dut.io_enqueueWrap = wrap;
    dut.io_enqueueLsId = lsid;
    dut.io_enqueueData = data;
    dut.eval();
    require(dut.io_enqueueReady && dut.io_enqueueAccepted,
            "expected enqueue was not accepted");
    tick(dut);
    dut.io_enqueueValid = 0;
}

static void drain(VScalarLSULoadReturnQueueProbe &dut, unsigned stid,
                  unsigned pipe, unsigned bid, bool wrap,
                  unsigned long long data) {
    idle(dut);
    dut.io_drainReady = 1;
    dut.eval();
    require(dut.io_drainValid && dut.io_drainFire,
            "expected drain was not accepted");
    if (dut.io_drainStid != stid || dut.io_drainPipe != pipe ||
        dut.io_drainBid != bid || dut.io_drainWrap != wrap ||
        dut.io_drainData != data) {
        std::cerr << "expected stid=" << stid << " pipe=" << pipe
                  << " bid=" << bid << " wrap=" << wrap << " data=" << data
                  << " observed stid=" << static_cast<unsigned>(dut.io_drainStid)
                  << " pipe=" << static_cast<unsigned>(dut.io_drainPipe) << " bid="
                  << static_cast<unsigned>(dut.io_drainBid) << " data="
                  << dut.io_drainData << '\n';
        require(false, "drain payload or scope did not match FIFO head");
    }
    tick(dut);
    dut.io_drainReady = 0;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VScalarLSULoadReturnQueueProbe dut;

    dut.reset = 1;
    dut.io_enqueueValid = 0;
    dut.io_enqueueStid = 0;
    dut.io_enqueuePipe = 0;
    dut.io_enqueueBid = 0;
    dut.io_enqueueWrap = 0;
    dut.io_enqueueLsId = 0;
    dut.io_enqueueData = 0;
    dut.io_drainReady = 0;
    dut.io_hardFlush = 0;
    dut.io_preciseFlushValid = 0;
    dut.io_preciseFlushStid = 0;
    dut.io_preciseFlushBid = 0;
    dut.io_preciseFlushWrap = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    enqueue(dut, 0, 0, 1, false, 1, 0x11);
    enqueue(dut, 0, 0, 2, false, 2, 0x22);
    enqueue(dut, 1, 0, 3, false, 3, 0x33);
    require(dut.io_lane0Count == 2 && dut.io_lane2Count == 1 &&
                dut.io_totalCount == 3,
            "independent lane counts are incorrect");

    drain(dut, 1, 0, 3, false, 0x33);
    drain(dut, 0, 0, 1, false, 0x11);
    drain(dut, 0, 0, 2, false, 0x22);
    require(dut.io_totalCount == 0, "round-robin drain did not empty all lanes");

    enqueue(dut, 0, 0, 1, false, 1, 0x101);
    enqueue(dut, 0, 0, 2, false, 2, 0x202);
    idle(dut);
    dut.io_enqueueValid = 1;
    dut.io_enqueueStid = 0;
    dut.io_enqueuePipe = 0;
    dut.io_enqueueBid = 3;
    dut.io_enqueueWrap = 0;
    dut.io_enqueueLsId = 3;
    dut.io_enqueueData = 0x303;
    dut.eval();
    require(dut.io_preEnqueueReady && !dut.io_enqueueReady &&
                !dut.io_enqueueAccepted && dut.io_blockedByFull,
            "selected full pipe did not preserve same-STID pre-admission credit");
    require(!dut.io_full,
            "bank full asserted when only one selected lane was full");
    enqueue(dut, 0, 1, 3, false, 3, 0x303);
    enqueue(dut, 1, 0, 4, false, 4, 0x404);

    idle(dut);
    dut.io_preciseFlushValid = 1;
    dut.io_preciseFlushStid = 0;
    dut.io_preciseFlushBid = 2;
    dut.io_preciseFlushWrap = 0;
    dut.eval();
    require(dut.io_precisePruneCount == 2,
            "precise flush did not identify only the selected killed suffix");
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_lane0Count == 1 && dut.io_lane1Count == 0 &&
                dut.io_lane2Count == 1 &&
                dut.io_totalCount == 2,
            "precise flush did not preserve older and independent entries");

    drain(dut, 1, 0, 4, false, 0x404);
    drain(dut, 0, 0, 1, false, 0x101);

    enqueue(dut, 0, 0, 1, false, 1, 0x501);
    enqueue(dut, 0, 0, 2, false, 2, 0x502);
    idle(dut);
    dut.io_enqueueValid = 1;
    dut.io_enqueueStid = 0;
    dut.io_enqueuePipe = 0;
    dut.io_enqueueBid = 3;
    dut.io_enqueueWrap = 0;
    dut.io_enqueueLsId = 3;
    dut.io_enqueueData = 0x503;
    dut.io_drainReady = 1;
    dut.eval();
    require(dut.io_enqueueReady && dut.io_enqueueAccepted && dut.io_drainFire,
            "full-lane same-cycle dequeue did not open enqueue credit");
    tick(dut);
    idle(dut);
    require(dut.io_lane0Count == 2,
            "same-cycle dequeue/enqueue did not preserve full-lane count");
    drain(dut, 0, 0, 2, false, 0x502);
    drain(dut, 0, 0, 3, false, 0x503);

    enqueue(dut, 0, 0, 6, false, 6, 0x606);
    enqueue(dut, 0, 0, 0, true, 0, 0x700);
    enqueue(dut, 1, 1, 1, true, 1, 0x711);
    idle(dut);
    dut.io_preciseFlushValid = 1;
    dut.io_preciseFlushStid = 0;
    dut.io_preciseFlushBid = 7;
    dut.io_preciseFlushWrap = 0;
    dut.io_enqueueValid = 1;
    dut.io_enqueueStid = 0;
    dut.io_enqueuePipe = 1;
    dut.io_enqueueBid = 2;
    dut.io_enqueueWrap = 1;
    dut.io_enqueueLsId = 2;
    dut.io_enqueueData = 0x702;
    dut.io_drainReady = 1;
    dut.eval();
    require(dut.io_precisePruneCount == 1 && !dut.io_enqueueAccepted &&
                !dut.io_drainFire,
            "precise wrap pruning did not suppress concurrent queue mutation");
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_lane0Count == 1 && dut.io_lane3Count == 1 &&
                dut.io_totalCount == 2,
            "wrapped precise flush did not preserve older and independent entries");
    drain(dut, 1, 1, 1, true, 0x711);
    drain(dut, 0, 0, 6, false, 0x606);

    for (unsigned stid = 0; stid < 2; ++stid) {
        for (unsigned pipe = 0; pipe < 2; ++pipe) {
            enqueue(dut, stid, pipe, 1, false, 1, 0x800 + stid * 0x10 + pipe);
            enqueue(dut, stid, pipe, 2, false, 2, 0x900 + stid * 0x10 + pipe);
        }
    }
    idle(dut);
    dut.eval();
    require(dut.io_full && dut.io_totalCount == 8,
            "bank full did not report resident all-lane fullness without a request");

    dut.io_hardFlush = 1;
    tick(dut);
    idle(dut);
    require(dut.io_totalCount == 0, "hard flush did not clear all queue lanes");

    std::cout << "scalar-lsu-load-return-queue-probe: PASS\n";
    return 0;
}
