#include <cstdlib>
#include <iostream>

#include "VScalarL1DScbProbe.h"
#include "verilated.h"

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "scalar-l1d-scb-probe: " << message << '\n';
        std::exit(1);
    }
}

static void tick(VScalarL1DScbProbe &dut) {
    dut.clock = 0;
    dut.eval();
    require(!dut.io_protocolError, "protocol error asserted");
    dut.clock = 1;
    dut.eval();
}

static void idle(VScalarL1DScbProbe &dut) {
    dut.io_requestValid = 0;
    dut.io_requestAddr = 0;
    dut.io_requestData = 0;
    dut.io_requestSize = 0;
    dut.io_evictEnable = 0;
    dut.io_l2Ready = 1;
    dut.io_rawRespValid = 0;
    dut.io_rawRespTxnId = 0;
    dut.io_rawRespUpgrade = 0;
    dut.io_refillValid = 0;
    dut.io_refillLineAddr = 0;
    dut.io_refillData = 0;
    dut.io_refillWritable = 0;
    dut.io_evictionReady = 1;
    dut.io_loadValid = 0;
    dut.io_loadLineAddr = 0;
}

static void refill(VScalarL1DScbProbe &dut, uint64_t addr, uint64_t data) {
    idle(dut);
    dut.io_refillValid = 1;
    dut.io_refillLineAddr = addr;
    dut.io_refillData = data;
    dut.eval();
    require(dut.io_refillReady, "refill was not accepted");
    tick(dut);
}

static void require_hit(VScalarL1DScbProbe &dut, uint64_t addr, uint64_t data) {
    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadLineAddr = addr;
    dut.eval();
    require(dut.io_loadHit && dut.io_loadData == data, "load hit/data mismatch");
    tick(dut);
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VScalarL1DScbProbe dut;
    idle(dut);
    dut.reset = 1;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    refill(dut, 0, 0x1111111111111111ULL);

    idle(dut);
    dut.io_requestValid = 1;
    dut.io_requestAddr = 0;
    dut.io_requestData = 0xaa;
    dut.io_requestSize = 1;
    dut.eval();
    require(dut.io_requestAccepted, "SCB did not accept store fragment");
    tick(dut);

    idle(dut);
    dut.io_evictEnable = 1;
    dut.eval();
    require(dut.io_l2Valid && dut.io_l2Upgrade,
            "read-only cache line did not emit upgrade request");
    const unsigned txn = dut.io_l2TxnId;
    tick(dut);

    idle(dut);
    dut.io_rawRespValid = 1;
    dut.io_rawRespTxnId = txn;
    dut.io_rawRespUpgrade = 1;
    dut.eval();
    require(dut.io_rawRespReady, "upgrade response was not accepted");
    tick(dut);

    idle(dut);
    dut.io_evictEnable = 1;
    dut.eval();
    require(dut.io_respDecodedUpgrade, "upgrade response was not decoded");
    tick(dut);

    idle(dut);
    dut.io_evictEnable = 1;
    dut.eval();
    require(dut.io_dcacheUpdateValid, "SCB retry did not update writable L1D");
    tick(dut);

    require_hit(dut, 0, 0x11111111111111aaULL);
    require(dut.io_dirtyCount == 1, "integrated store update did not mark dirty");
    require(dut.io_scbEntryCount == 0, "SCB row did not clear after cache update");

    idle(dut);
    dut.io_refillValid = 1;
    dut.io_refillLineAddr = 0;
    dut.io_refillData = 0x2222222222222222ULL;
    dut.eval();
    require(dut.io_refillReady && dut.io_refillDuplicate,
            "duplicate refill was not recognized after store update");
    tick(dut);
    require_hit(dut, 0, 0x11111111111111aaULL);

    refill(dut, 128, 0x3333333333333333ULL);
    idle(dut);
    dut.io_refillValid = 1;
    dut.io_refillLineAddr = 256;
    dut.io_refillData = 0x4444444444444444ULL;
    dut.io_evictionReady = 0;
    dut.eval();
    require(dut.io_evictionValid && dut.io_evictionDirty &&
                dut.io_evictionLineAddr == 0 &&
                dut.io_evictionData == 0x11111111111111aaULL,
            "dirty SCB-updated victim was not retained");

    std::cout << "scalar-l1d-scb-probe: PASS\n";
    return 0;
}
