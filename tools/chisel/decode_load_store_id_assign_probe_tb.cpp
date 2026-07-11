#include <cstdlib>
#include <iostream>

#include "VDecodeLoadStoreIdAssignProbe.h"
#include "verilated.h"

static void tick(VDecodeLoadStoreIdAssignProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "decode-load-store-id-assign-probe: " << message << '\n';
        std::exit(1);
    }
}

static void idle(VDecodeLoadStoreIdAssignProbe &dut) {
    dut.io_valid = 0;
    dut.io_isLoad = 0;
    dut.io_isStore = 0;
    dut.io_accept = 0;
    dut.io_flushValid = 0;
    dut.io_flushAll = 0;
    dut.io_restoreValid = 0;
}

static void accept(VDecodeLoadStoreIdAssignProbe &dut, unsigned stid,
                   bool load, bool store) {
    idle(dut);
    dut.io_valid = 1;
    dut.io_stid = stid;
    dut.io_isLoad = load;
    dut.io_isStore = store;
    dut.io_accept = 1;
    dut.eval();
    require(dut.io_selectedStidInRange && dut.io_assignFire,
            "valid in-range memory row was not accepted");
    tick(dut);
    idle(dut);
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VDecodeLoadStoreIdAssignProbe dut;
    dut.reset = 1;
    dut.io_stid = 0;
    dut.io_flushStid = 0;
    dut.io_restoreStid = 0;
    dut.io_restoreLsId = 0;
    dut.io_restoreLoadId = 0;
    dut.io_restoreStoreId = 0;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;

    accept(dut, 0, true, false);
    accept(dut, 1, false, true);
    accept(dut, 0, false, true);
    dut.eval();
    require(dut.io_nextLsIdByStid_0 == 2 && dut.io_nextLoadIdByStid_0 == 1 &&
                dut.io_nextStoreIdByStid_0 == 1,
            "STID0 counters did not advance independently");
    require(dut.io_nextLsIdByStid_1 == 1 && dut.io_nextLoadIdByStid_1 == 0 &&
                dut.io_nextStoreIdByStid_1 == 1,
            "STID1 counters did not advance independently");

    // A scoped restore changes STID1 only.
    dut.io_flushValid = 1;
    dut.io_flushAll = 0;
    dut.io_flushStid = 1;
    dut.io_restoreValid = 1;
    dut.io_restoreStid = 1;
    dut.io_restoreLsId = 9;
    dut.io_restoreLoadId = 4;
    dut.io_restoreStoreId = 5;
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_nextLsIdByStid_0 == 2 && dut.io_nextStoreIdByStid_0 == 1,
            "scoped restore corrupted another STID");
    require(dut.io_nextLsIdByStid_1 == 9 && dut.io_nextLoadIdByStid_1 == 4 &&
                dut.io_nextStoreIdByStid_1 == 5,
            "scoped restore did not load the selected STID");

    // Out-of-range STID2 is rejected without mutating either implemented lane.
    dut.io_valid = 1;
    dut.io_stid = 2;
    dut.io_isLoad = 1;
    dut.io_accept = 1;
    dut.eval();
    require(!dut.io_selectedStidInRange && !dut.io_assignFire,
            "out-of-range STID advanced memory identities");
    tick(dut);
    idle(dut);

    dut.io_flushValid = 1;
    dut.io_flushAll = 1;
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_nextLsIdByStid_0 == 0 && dut.io_nextLsIdByStid_1 == 0 &&
                dut.io_nextStoreIdByStid_0 == 0 && dut.io_nextStoreIdByStid_1 == 0,
            "all-lane restart did not clear memory identities");

    std::cout << "decode-load-store-id-assign-probe: PASS\n";
    return 0;
}
