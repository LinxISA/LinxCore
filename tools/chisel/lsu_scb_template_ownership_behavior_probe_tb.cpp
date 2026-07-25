#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>

#include "VSCBTemplateOwnershipBehaviorProbe.h"
#include "verilated.h"

namespace {

struct Request {
    bool valid = false;
    bool owns_stq_row = false;
    bool last = false;
    std::uint8_t stq_index = 0;
    std::uint64_t addr = 0;
    std::uint64_t data = 0;
    std::uint8_t size = 0;
};

[[noreturn]] void fail(const std::string &message) {
    std::cerr << "lsu-scb-template-ownership-behavior-probe: FAIL: "
              << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

Request request(bool owns_stq_row,
                bool last,
                std::uint8_t stq_index,
                std::uint64_t addr,
                std::uint64_t data = 0x1122334455667788ULL) {
    return {true, owns_stq_row, last, stq_index, addr, data, 8};
}

void drive(VSCBTemplateOwnershipBehaviorProbe &dut,
           const Request &lane0 = {},
           const Request &lane1 = {}) {
    dut.io_reqValid_0 = lane0.valid;
    dut.io_reqValid_1 = lane1.valid;
    dut.io_reqOwnsStqRow_0 = lane0.owns_stq_row;
    dut.io_reqOwnsStqRow_1 = lane1.owns_stq_row;
    dut.io_reqLast_0 = lane0.last;
    dut.io_reqLast_1 = lane1.last;
    dut.io_reqStqIndex_0 = lane0.stq_index;
    dut.io_reqStqIndex_1 = lane1.stq_index;
    dut.io_reqAddr_0 = lane0.addr;
    dut.io_reqAddr_1 = lane1.addr;
    dut.io_reqData_0 = lane0.data;
    dut.io_reqData_1 = lane1.data;
    dut.io_reqSize_0 = lane0.size;
    dut.io_reqSize_1 = lane1.size;
    dut.eval();
}

void tick(VSCBTemplateOwnershipBehaviorProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
    dut.clock = 0;
    dut.eval();
}

void reset(VSCBTemplateOwnershipBehaviorProbe &dut) {
    drive(dut);
    dut.reset = 1;
    tick(dut);
    dut.reset = 0;
    drive(dut);
    require(dut.io_entryCount == 0, "reset did not empty the SCB");
    require(dut.io_validMask == 0, "reset left a valid SCB row");
}

void require_admission(VSCBTemplateOwnershipBehaviorProbe &dut,
                       std::uint8_t accepted_mask,
                       std::uint8_t free_mask,
                       std::uint8_t free_count,
                       const std::string &label) {
    require(dut.io_acceptedMask == accepted_mask,
            label + ": acceptedMask mismatch");
    require(dut.io_commitFreeMask == free_mask,
            label + ": commitFreeMask mismatch");
    require(dut.io_commitFreeCount == free_count,
            label + ": commitFreeCount mismatch");
    require(dut.io_commitFreeMaskValid == (free_mask != 0),
            label + ": commitFreeMaskValid mismatch");
    require((dut.io_acceptedMask & accepted_mask) == accepted_mask,
            label + ": expected request was not accepted");
}

void prove_single_request_ownership(VSCBTemplateOwnershipBehaviorProbe &dut) {
    reset(dut);
    drive(dut, request(true, true, 3, 0x1000));
    require_admission(dut, 0x1, 0x08, 1, "owned-final");
    require(dut.io_wakeupValid_0, "owned-final did not publish SCB wakeup");
    tick(dut);
    drive(dut);
    require(dut.io_entryCount == 1, "owned-final did not update SCB state");

    reset(dut);
    drive(dut, request(true, false, 3, 0x1100));
    require_admission(dut, 0x1, 0x00, 0, "owned-non-final");
    tick(dut);
    drive(dut);
    require(dut.io_entryCount == 1, "owned-non-final did not update SCB state");

    reset(dut);
    drive(dut, request(false, true, 5, 0x1200));
    require_admission(dut, 0x1, 0x00, 0, "unowned-final-template");
    require(dut.io_wakeupValid_0,
            "unowned-final template did not publish SCB wakeup");
    tick(dut);
    drive(dut);
    require(dut.io_entryCount == 1,
            "unowned-final template did not update SCB state");

    reset(dut);
    drive(dut, request(false, false, 5, 0x1300));
    require_admission(dut, 0x1, 0x00, 0, "unowned-non-final-template");

    reset(dut);
    Request inactive = request(true, true, 6, 0x1400);
    inactive.valid = false;
    drive(dut, inactive);
    require_admission(dut, 0x0, 0x00, 0, "inactive-owned-final");
    require(dut.io_stalledMask == 0, "inactive request was reported stalled");
}

void prove_mixed_and_same_index_lanes(
    VSCBTemplateOwnershipBehaviorProbe &dut) {
    reset(dut);
    drive(
        dut,
        request(true, true, 2, 0x2000),
        request(false, true, 5, 0x2100));
    require_admission(dut, 0x3, 0x04, 1, "mixed-owned-unowned-final");
    require(dut.io_wakeupValid_0 && dut.io_wakeupValid_1,
            "mixed requests did not both publish SCB wakeups");
    tick(dut);
    drive(dut);
    require(dut.io_entryCount == 2,
            "mixed requests did not update two SCB rows");

    reset(dut);
    drive(
        dut,
        request(true, true, 4, 0x2200),
        request(true, true, 4, 0x2300));
    require_admission(dut, 0x3, 0x10, 1, "same-stq-index-owned-finals");
    tick(dut);
    drive(dut);
    require(dut.io_entryCount == 2,
            "same-index requests did not update distinct SCB rows");
}

void prove_blocked_request_never_frees(
    VSCBTemplateOwnershipBehaviorProbe &dut) {
    reset(dut);
    drive(
        dut,
        request(false, false, 0, 0x3000),
        request(false, false, 0, 0x3100));
    require_admission(dut, 0x3, 0x00, 0, "fill-first-two");
    tick(dut);

    drive(dut, request(false, false, 0, 0x3200));
    require_admission(dut, 0x1, 0x00, 0, "fill-third");
    tick(dut);
    drive(dut);
    require(dut.io_entryCount == 3, "setup did not fill three SCB rows");
    require(!dut.io_modelBatchReady,
            "SCB batch gate stayed ready with one row free");

    drive(dut, request(true, true, 7, 0x3300));
    require_admission(dut, 0x0, 0x00, 0, "blocked-owned-final");
    require(dut.io_stalledMask == 0x1,
            "blocked request was not reported stalled");
    require(dut.io_structuralBlockedMask == 0,
            "batch-gated request was mislabeled structurally blocked");
    tick(dut);
    drive(dut);
    require(dut.io_entryCount == 3,
            "blocked request changed SCB state");
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    if (argc != 1) {
        fail("unknown argument");
    }

    VSCBTemplateOwnershipBehaviorProbe dut;
    dut.clock = 0;
    dut.reset = 0;

    prove_single_request_ownership(dut);
    prove_mixed_and_same_index_lanes(dut);
    prove_blocked_request_never_frees(dut);

    std::cout
        << "lsu-scb-template-ownership-behavior-probe: PASS "
        << "(owned/unowned final and non-final, inactive, blocked, mixed lanes, "
           "same-index deduplication, exact acceptance/state/free masks)\n";
    return 0;
}
