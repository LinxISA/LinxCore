#include <array>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>

#include "VScalarDecodeRenameTemplateSnapshotBehaviorProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint16_t kFentry = 429;
constexpr std::uint16_t kFexit = 430;
constexpr std::uint16_t kFretRa = 431;
constexpr std::uint16_t kFretStk = 432;
constexpr std::uint16_t kNonTemplate = 1;
constexpr unsigned kArchRegs = 24;
constexpr unsigned kStidCount = 2;
constexpr unsigned kFirstAllocatablePhys = kStidCount * kArchRegs;

using Map = std::array<std::uint8_t, kArchRegs>;

struct Row {
    std::uint16_t opcode;
    std::uint8_t stid;
    std::uint8_t active_stid;
    bool dst_valid;
    std::uint8_t dst_arch;
    std::uint8_t bid;
    std::uint8_t gid;
    std::uint8_t rid;
    std::uint64_t block_bid;
    std::uint64_t uid;
};

[[noreturn]] void fail(const std::string &message) {
    std::cerr << "backend-rename-template-snapshot-probe: FAIL: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

void tick(VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

void clear_controls(VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut) {
    dut.io_inputValid = 0;
    dut.io_opcode = 0;
    dut.io_threadId = 0;
    dut.io_activeStid = 0;
    dut.io_outReady = 1;
    dut.io_robAllocReady = 1;
    dut.io_dstValid = 0;
    dut.io_dstArch = 0;
    dut.io_bidValue = 0;
    dut.io_gidValue = 0;
    dut.io_ridValue = 0;
    dut.io_blockBid = 0;
    dut.io_uid = 0;
    dut.io_commitValid = 0;
    dut.io_commitBidValue = 0;
    dut.io_commitBlockBid = 0;
    dut.io_commitStid = 0;
    dut.io_cleanupReplayValid = 0;
    dut.io_cleanupStid = 0;
}

void reset(VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut) {
    clear_controls(dut);
    dut.reset = 1;
    tick(dut);
    tick(dut);
    dut.reset = 0;
    dut.clock = 0;
    dut.eval();
}

std::uint8_t identity_tag(unsigned stid, unsigned arch) {
    require(stid < kStidCount, "reference STID outside two-lane probe");
    require(arch < kArchRegs, "reference arch outside scalar GPR namespace");
    return static_cast<std::uint8_t>(stid * kArchRegs + arch);
}

Map identity_map(unsigned stid) {
    Map map{};
    for (unsigned arch = 0; arch < kArchRegs; ++arch) {
        map[arch] = identity_tag(stid, arch);
    }
    return map;
}

void prove_reference_identity_maps_are_disjoint() {
    const auto stid0 = identity_map(0);
    const auto stid1 = identity_map(1);
    for (unsigned arch = 0; arch < kArchRegs; ++arch) {
        require(stid0[arch] == arch, "STID0 identity map changed");
        require(stid1[arch] == kArchRegs + arch,
                "STID1 identity map is not offset by the architectural register count");
        require(stid0[arch] < kFirstAllocatablePhys &&
                    stid1[arch] < kFirstAllocatablePhys,
                "identity map escaped the reserved per-STID partition");
        require(stid0[arch] != stid1[arch], "reference maps alias across STIDs");
    }
}

std::uint8_t smap_at(
    const VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut, unsigned arch) {
    switch (arch) {
    case 0: return dut.io_templateSmapSnapshot_0;
    case 1: return dut.io_templateSmapSnapshot_1;
    case 2: return dut.io_templateSmapSnapshot_2;
    case 3: return dut.io_templateSmapSnapshot_3;
    case 4: return dut.io_templateSmapSnapshot_4;
    case 5: return dut.io_templateSmapSnapshot_5;
    case 6: return dut.io_templateSmapSnapshot_6;
    case 7: return dut.io_templateSmapSnapshot_7;
    case 8: return dut.io_templateSmapSnapshot_8;
    case 9: return dut.io_templateSmapSnapshot_9;
    case 10: return dut.io_templateSmapSnapshot_10;
    case 11: return dut.io_templateSmapSnapshot_11;
    case 12: return dut.io_templateSmapSnapshot_12;
    case 13: return dut.io_templateSmapSnapshot_13;
    case 14: return dut.io_templateSmapSnapshot_14;
    case 15: return dut.io_templateSmapSnapshot_15;
    case 16: return dut.io_templateSmapSnapshot_16;
    case 17: return dut.io_templateSmapSnapshot_17;
    case 18: return dut.io_templateSmapSnapshot_18;
    case 19: return dut.io_templateSmapSnapshot_19;
    case 20: return dut.io_templateSmapSnapshot_20;
    case 21: return dut.io_templateSmapSnapshot_21;
    case 22: return dut.io_templateSmapSnapshot_22;
    case 23: return dut.io_templateSmapSnapshot_23;
    default: fail("SMAP index outside exact 24-entry vector");
    }
}

std::uint8_t cmap_at(
    const VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut, unsigned arch) {
    switch (arch) {
    case 0: return dut.io_templateCmapSnapshot_0;
    case 1: return dut.io_templateCmapSnapshot_1;
    case 2: return dut.io_templateCmapSnapshot_2;
    case 3: return dut.io_templateCmapSnapshot_3;
    case 4: return dut.io_templateCmapSnapshot_4;
    case 5: return dut.io_templateCmapSnapshot_5;
    case 6: return dut.io_templateCmapSnapshot_6;
    case 7: return dut.io_templateCmapSnapshot_7;
    case 8: return dut.io_templateCmapSnapshot_8;
    case 9: return dut.io_templateCmapSnapshot_9;
    case 10: return dut.io_templateCmapSnapshot_10;
    case 11: return dut.io_templateCmapSnapshot_11;
    case 12: return dut.io_templateCmapSnapshot_12;
    case 13: return dut.io_templateCmapSnapshot_13;
    case 14: return dut.io_templateCmapSnapshot_14;
    case 15: return dut.io_templateCmapSnapshot_15;
    case 16: return dut.io_templateCmapSnapshot_16;
    case 17: return dut.io_templateCmapSnapshot_17;
    case 18: return dut.io_templateCmapSnapshot_18;
    case 19: return dut.io_templateCmapSnapshot_19;
    case 20: return dut.io_templateCmapSnapshot_20;
    case 21: return dut.io_templateCmapSnapshot_21;
    case 22: return dut.io_templateCmapSnapshot_22;
    case 23: return dut.io_templateCmapSnapshot_23;
    default: fail("CMAP index outside exact 24-entry vector");
    }
}

void drive_row(VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut,
               const Row &row,
               bool out_ready = true,
               bool rob_ready = true) {
    clear_controls(dut);
    dut.io_inputValid = 1;
    dut.io_opcode = row.opcode;
    dut.io_threadId = row.stid;
    dut.io_activeStid = row.active_stid;
    dut.io_outReady = out_ready;
    dut.io_robAllocReady = rob_ready;
    dut.io_dstValid = row.dst_valid;
    dut.io_dstArch = row.dst_arch;
    dut.io_bidValue = row.bid;
    dut.io_gidValue = row.gid;
    dut.io_ridValue = row.rid;
    dut.io_blockBid = row.block_bid;
    dut.io_uid = row.uid;
    dut.eval();
}

void check_maps(const VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut,
                const Map &expected_smap,
                const Map &expected_cmap,
                const std::string &label) {
    for (unsigned arch = 0; arch < kArchRegs; ++arch) {
        require(smap_at(dut, arch) == expected_smap[arch],
                label + ": SMAP mismatch at arch " + std::to_string(arch));
        require(cmap_at(dut, arch) == expected_cmap[arch],
                label + ": CMAP mismatch at arch " + std::to_string(arch));
        require(smap_at(dut, arch) < 64 && cmap_at(dut, arch) < 64,
                label + ": map tag exceeds exact six-bit physical namespace");
    }
}

void accept_template(VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut,
                     const Row &row,
                     Map &expected_smap,
                     const Map &expected_cmap,
                     std::uint16_t &expected_generation,
                     const std::string &label) {
    drive_row(dut, row);
    require(dut.io_accepted, label + ": template was not accepted");
    require(dut.io_templateSnapshotValid,
            label + ": accepted template did not publish a snapshot");
    require(dut.io_templateSnapshotGeneration == expected_generation,
            label + ": generation did not identify exactly this accepted template");

    if (row.dst_valid) {
        const auto physical = static_cast<std::uint8_t>(dut.io_dstPhysTag);
        require(physical >= kFirstAllocatablePhys,
                label + ": destination allocated from reserved per-STID identity partition");
        expected_smap[row.dst_arch] = physical;
    }
    check_maps(dut, expected_smap, expected_cmap, label);

    tick(dut);
    ++expected_generation;
    clear_controls(dut);
    dut.eval();
    require(!dut.io_templateSnapshotValid, label + ": snapshot valid leaked past outFire");
    require(dut.io_templateSnapshotGeneration == expected_generation,
            label + ": generation did not advance exactly once after outFire");
}

void prove_blocked_and_non_template_hold(
    VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut,
    const Row &template_row,
    std::uint16_t expected_generation) {
    drive_row(dut, template_row, false, true);
    require(!dut.io_accepted && !dut.io_templateSnapshotValid,
            "outReady-blocked template published a snapshot");
    require(dut.io_templateSnapshotGeneration == expected_generation,
            "outReady blocking advanced generation");
    tick(dut);

    drive_row(dut, template_row, true, false);
    require(!dut.io_accepted && !dut.io_templateSnapshotValid,
            "ROB-blocked template published a snapshot");
    require(dut.io_templateSnapshotGeneration == expected_generation,
            "ROB blocking advanced generation");
    tick(dut);

    drive_row(dut, template_row);
    dut.io_cleanupReplayValid = 1;
    dut.io_cleanupStid = template_row.stid;
    dut.eval();
    require(!dut.io_accepted && !dut.io_templateSnapshotValid,
            "maintenance-blocked template published a snapshot");
    require(dut.io_templateSnapshotGeneration == expected_generation,
            "maintenance blocking advanced generation");
    tick(dut);
    require(dut.io_cleanupReplayObserved,
            "ordinary replay cleanup did not reach the real rename owner");

    Row ordinary{kNonTemplate, 0, 1, false, 0, 11, 12, 13, 0x111, 0x111};
    drive_row(dut, ordinary);
    require(dut.io_accepted, "ordinary non-template row was not accepted");
    require(!dut.io_templateSnapshotValid,
            "ordinary non-template row published a template snapshot");
    require(dut.io_templateSnapshotGeneration == expected_generation,
            "ordinary non-template acceptance advanced generation");
    tick(dut);
    clear_controls(dut);
    dut.eval();
    require(dut.io_templateSnapshotGeneration == expected_generation,
            "generation changed after ordinary non-template acceptance");
}

std::uint8_t rename_then_commit(VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut,
                                Map &smap,
                                Map &cmap,
                                std::uint16_t expected_generation) {
    const Row rename{kNonTemplate, 0, 1, true, 7, 20, 21, 22, 0x220, 0x220};
    drive_row(dut, rename);
    require(dut.io_accepted, "CMAP preparation rename was not accepted");
    require(!dut.io_templateSnapshotValid, "CMAP preparation emitted a template snapshot");
    require(dut.io_templateSnapshotGeneration == expected_generation,
            "CMAP preparation advanced generation");
    const auto physical = static_cast<std::uint8_t>(dut.io_dstPhysTag);
    require(physical >= kFirstAllocatablePhys,
            "CMAP preparation allocated from reserved per-STID identity partition");
    smap[rename.dst_arch] = physical;
    tick(dut);

    clear_controls(dut);
    dut.io_commitValid = 1;
    dut.io_commitBidValue = rename.bid;
    dut.io_commitBlockBid = rename.block_bid;
    dut.io_commitStid = rename.stid;
    dut.eval();
    require(dut.io_commitAccepted, "CMAP preparation commit was not accepted");
    require(!dut.io_templateSnapshotValid, "commit emitted a template snapshot");
    require(dut.io_templateSnapshotGeneration == expected_generation,
            "commit advanced generation");
    tick(dut);
    cmap[rename.dst_arch] = physical;
    clear_controls(dut);
    dut.eval();
    return physical;
}

void prove_snapshot_and_generation_contract(
    VScalarDecodeRenameTemplateSnapshotBehaviorProbe &dut) {
    reset(dut);
    require(!dut.io_templateSnapshotValid, "reset exposed snapshot valid");
    require(dut.io_templateSnapshotGeneration == 0,
            "generation did not start deterministically at zero");

    std::array<Map, 2> smap{identity_map(0), identity_map(1)};
    std::array<Map, 2> cmap{identity_map(0), identity_map(1)};
    std::uint16_t generation = 0;

    Row fentry{kFentry, 1, 0, true, 5, 1, 2, 3, 0x101, 0x1001};
    accept_template(dut, fentry, smap[1], cmap[1], generation,
                    "FENTRY-post-rename-thread-selection");

    Row blocked{kFexit, 0, 1, false, 0, 4, 5, 6, 0x102, 0x1002};
    prove_blocked_and_non_template_hold(dut, blocked, generation);

    Row fexit{kFexit, 0, 1, false, 0, 7, 8, 9, 0x103, 0x1003};
    accept_template(dut, fexit, smap[0], cmap[0], generation,
                    "FEXIT-active-stid-mismatch");

    const auto committed = rename_then_commit(dut, smap[0], cmap[0], generation);
    require(committed != 7, "CMAP preparation did not distinguish committed map from identity");

    Row fret_ra{kFretRa, 0, 1, true, 9, 30, 31, 32, 0x330, 0x330};
    accept_template(dut, fret_ra, smap[0], cmap[0], generation,
                    "FRET-RA-post-SMAP-actual-CMAP");

    Row fret_stk{kFretStk, 1, 0, false, 0, 40, 41, 42, 0x440, 0x440};
    accept_template(dut, fret_stk, smap[1], cmap[1], generation,
                    "FRET-STK-first-same-key-parent");
    const auto first_same_key_generation = static_cast<std::uint16_t>(generation - 1);

    clear_controls(dut);
    dut.io_cleanupReplayValid = 1;
    dut.io_cleanupStid = 1;
    dut.eval();
    require(dut.io_templateSnapshotGeneration == generation,
            "ordinary cleanup reset generation");
    tick(dut);
    clear_controls(dut);
    dut.eval();
    require(dut.io_templateSnapshotGeneration == generation,
            "generation changed after ordinary cleanup");

    accept_template(dut, fret_stk, smap[1], cmap[1], generation,
                    "FRET-STK-later-same-key-parent");
    require(static_cast<std::uint16_t>(generation - 1) != first_same_key_generation,
            "later same-key parent reused its predecessor generation");

    reset(dut);
    require(dut.io_templateSnapshotGeneration == 0,
            "reset did not restore deterministic initial generation");
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    if (argc != 1) {
        fail("unknown argument");
    }

    VScalarDecodeRenameTemplateSnapshotBehaviorProbe dut;
    dut.clock = 0;
    dut.reset = 0;

    prove_reference_identity_maps_are_disjoint();
    prove_snapshot_and_generation_contract(dut);

    std::cout
        << "backend-rename-template-snapshot-probe: PASS "
        << "(four template opcodes, exact outFire, post-SMAP, committed CMAP, "
           "accepted STID, blockers, cleanup survival, monotonic same-key generation)\n";
    return 0;
}
