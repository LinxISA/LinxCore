#include <cstdlib>
#include <iostream>

#include "VScalarL1DProbe.h"
#include "verilated.h"

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "scalar-l1d-probe: " << message << '\n';
        std::exit(1);
    }
}

static void tick(VScalarL1DProbe &dut) {
    dut.clock = 0;
    dut.eval();
    require(!dut.io_protocolError, "protocol error asserted");
    dut.clock = 1;
    dut.eval();
}

static void idle(VScalarL1DProbe &dut) {
    dut.io_hardFlush = 0;
    dut.io_preciseFlush = 0;
    dut.io_loadValid = 0;
    dut.io_loadLineAddr = 0;
    dut.io_refillValid = 0;
    dut.io_refillLineAddr = 0;
    dut.io_refillData = 0;
    dut.io_refillWritable = 0;
    dut.io_evictionReady = 0;
    dut.io_storeLookupValid = 0;
    dut.io_storeLineAddr = 0;
    dut.io_storeUpdateValid = 0;
    dut.io_grantWriteValid = 0;
    dut.io_storeByteMask = 0;
    for (unsigned word = 0; word < 16; ++word) dut.io_storeData[word] = 0;
}

static void refill(VScalarL1DProbe &dut, uint64_t addr, uint64_t data,
                   bool writable) {
    idle(dut);
    dut.io_refillValid = 1;
    dut.io_refillLineAddr = addr;
    dut.io_refillData = data;
    dut.io_refillWritable = writable;
    dut.eval();
    require(dut.io_refillReady && dut.io_refillAccepted,
            "refill was not accepted");
    tick(dut);
}

static void require_hit(VScalarL1DProbe &dut, uint64_t addr, uint64_t data) {
    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadLineAddr = addr;
    dut.eval();
    require(dut.io_loadHit && dut.io_loadData == data, "load hit/data mismatch");
    tick(dut);
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VScalarL1DProbe dut;
    idle(dut);
    dut.reset = 1;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadLineAddr = 0;
    dut.eval();
    require(!dut.io_loadHit, "empty cache reported a hit");

    refill(dut, 0, 0x1111111111111111ULL, false);
    require_hit(dut, 0, 0x1111111111111111ULL);

    idle(dut);
    dut.io_refillValid = 1;
    dut.io_refillLineAddr = 0;
    dut.io_refillData = 0x2222222222222222ULL;
    dut.io_refillWritable = 0;
    dut.eval();
    require(dut.io_refillAccepted && dut.io_refillDuplicate &&
                dut.io_refillReturnData == 0x1111111111111111ULL,
            "duplicate refill overwrote resident data");
    tick(dut);

    idle(dut);
    dut.io_storeLookupValid = 1;
    dut.io_storeLineAddr = 0;
    dut.eval();
    require(dut.io_storeTagHit && !dut.io_storeWriteHit,
            "read-only line did not request an ownership upgrade");
    dut.io_grantWriteValid = 1;
    tick(dut);
    idle(dut);
    dut.io_storeLookupValid = 1;
    dut.io_storeLineAddr = 0;
    dut.eval();
    require(dut.io_storeTagHit && dut.io_storeWriteHit,
            "accepted ownership upgrade did not grant write permission");
    dut.io_storeUpdateValid = 1;
    dut.io_storeByteMask = 1;
    dut.io_storeData[0] = 0xaa;
    tick(dut);
    require_hit(dut, 0, 0x11111111111111aaULL);
    require(dut.io_dirtyCount == 1, "store update did not mark the line dirty");

    refill(dut, 128, 0x3333333333333333ULL, false);
    idle(dut);
    dut.io_refillValid = 1;
    dut.io_refillLineAddr = 256;
    dut.io_refillData = 0x4444444444444444ULL;
    dut.io_evictionReady = 0;
    dut.eval();
    require(dut.io_evictionValid && dut.io_evictionDirty &&
                dut.io_evictionLineAddr == 0 &&
                dut.io_evictionData == 0x11111111111111aaULL &&
                !dut.io_refillReady && !dut.io_refillAccepted,
            "dirty victim was not retained under eviction backpressure");
    dut.io_evictionReady = 1;
    dut.eval();
    require(dut.io_refillReady && dut.io_refillAccepted,
            "refill did not proceed with accepted eviction");
    tick(dut);
    require_hit(dut, 256, 0x4444444444444444ULL);

    idle(dut);
    dut.io_hardFlush = 1;
    tick(dut);
    require_hit(dut, 256, 0x4444444444444444ULL);
    idle(dut);
    dut.io_preciseFlush = 1;
    tick(dut);
    require_hit(dut, 256, 0x4444444444444444ULL);

    std::cout << "scalar-l1d-probe: PASS\n";
    return 0;
}
