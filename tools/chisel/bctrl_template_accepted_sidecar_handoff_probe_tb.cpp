#include <array>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

#include "VTemplateAcceptedSidecarHandoffProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint16_t kFentry = 429;
constexpr std::uint16_t kFexit = 430;
constexpr std::uint16_t kFretRa = 431;
constexpr std::uint16_t kFretStk = 432;
constexpr std::uint16_t kNonTemplate = 17;
constexpr std::uint64_t kReadyMask = ~0ULL;
constexpr int kArchRegs = 24;
constexpr int kKeyFaultCount = 12;

struct Identity {
    std::uint16_t generation = 0;
    std::uint8_t stid = 0;
    std::uint8_t pe_id = 3;
    std::uint64_t pc = 0x10000;
    std::uint64_t raw = 0x29350041ULL;
    std::uint16_t opcode = kFentry;
    bool bid_valid = true;
    bool bid_wrap = true;
    std::uint8_t bid_value = 11;
    bool gid_valid = true;
    bool gid_wrap = false;
    std::uint8_t gid_value = 12;
    bool rid_valid = true;
    bool rid_wrap = true;
    std::uint8_t rid_value = 13;
    std::uint8_t rob_slot = 13;
    bool block_bid_valid = true;
    std::uint64_t block_bid = 0xabcddcbaULL;
};

struct Scenario {
    const char *name;
    Identity identity;
    std::uint8_t range_m = 2;
    std::uint8_t range_n = 4;
    std::uint64_t imm = 0x80;
    std::uint64_t old_sp = 0x8000;
    std::array<std::uint64_t, 3> src_data{0x111, 0x222, 0x333};
};

void require(bool condition, const std::string &message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void eval(VTemplateAcceptedSidecarHandoffProbe &dut) {
    dut.eval();
}

void tick(VTemplateAcceptedSidecarHandoffProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
    dut.clock = 0;
    eval(dut);
}

#define DEFINE_MAP_READER(NAME, PREFIX)                                           \
    std::uint8_t NAME(const VTemplateAcceptedSidecarHandoffProbe &dut, int index) { \
        switch (index) {                                                          \
            case 0: return dut.PREFIX##_0;  case 1: return dut.PREFIX##_1;         \
            case 2: return dut.PREFIX##_2;  case 3: return dut.PREFIX##_3;         \
            case 4: return dut.PREFIX##_4;  case 5: return dut.PREFIX##_5;         \
            case 6: return dut.PREFIX##_6;  case 7: return dut.PREFIX##_7;         \
            case 8: return dut.PREFIX##_8;  case 9: return dut.PREFIX##_9;         \
            case 10: return dut.PREFIX##_10; case 11: return dut.PREFIX##_11;      \
            case 12: return dut.PREFIX##_12; case 13: return dut.PREFIX##_13;      \
            case 14: return dut.PREFIX##_14; case 15: return dut.PREFIX##_15;      \
            case 16: return dut.PREFIX##_16; case 17: return dut.PREFIX##_17;      \
            case 18: return dut.PREFIX##_18; case 19: return dut.PREFIX##_19;      \
            case 20: return dut.PREFIX##_20; case 21: return dut.PREFIX##_21;      \
            case 22: return dut.PREFIX##_22; case 23: return dut.PREFIX##_23;      \
            default: throw std::runtime_error("map index outside 24-entry snapshot"); \
        }                                                                         \
    }

DEFINE_MAP_READER(bridge_smap, io_bridgeTemplateSmap)
DEFINE_MAP_READER(bridge_cmap, io_bridgeTemplateCmap)
DEFINE_MAP_READER(sidecar_smap, io_sidecarSmap)
DEFINE_MAP_READER(sidecar_cmap, io_sidecarCmap)
DEFINE_MAP_READER(active_smap, io_activeSmap)
DEFINE_MAP_READER(active_cmap, io_activeCmap)

#undef DEFINE_MAP_READER

#define DEFINE_KEY_DRIVER(NAME, PREFIX)                                           \
    void NAME(VTemplateAcceptedSidecarHandoffProbe &dut, const Identity &id) {    \
        dut.PREFIX##_generation = id.generation;                                  \
        dut.PREFIX##_stid = id.stid;                                              \
        dut.PREFIX##_bidValid = id.bid_valid;                                     \
        dut.PREFIX##_bidWrap = id.bid_wrap;                                       \
        dut.PREFIX##_bidValue = id.bid_value;                                     \
        dut.PREFIX##_gidValid = id.gid_valid;                                     \
        dut.PREFIX##_gidWrap = id.gid_wrap;                                       \
        dut.PREFIX##_gidValue = id.gid_value;                                     \
        dut.PREFIX##_ridValid = id.rid_valid;                                     \
        dut.PREFIX##_ridWrap = id.rid_wrap;                                       \
        dut.PREFIX##_ridValue = id.rid_value;                                     \
        dut.PREFIX##_robSlot = id.rob_slot;                                       \
    }

DEFINE_KEY_DRIVER(set_cancel_key, io_cancelKey)
DEFINE_KEY_DRIVER(set_decode_release_key, io_releaseDecodeKey)
DEFINE_KEY_DRIVER(set_issue_release_key, io_releaseIssueKey)
DEFINE_KEY_DRIVER(set_memory_release_key, io_releaseMemoryKey)
DEFINE_KEY_DRIVER(set_commit_key, io_parentCommitKey)
DEFINE_KEY_DRIVER(set_recovery_key, io_recoveryKey)

#undef DEFINE_KEY_DRIVER

Identity make_identity(std::uint16_t opcode, std::uint8_t stid,
                       std::uint8_t slot, std::uint8_t serial) {
    Identity id;
    id.opcode = opcode;
    id.stid = stid;
    id.rob_slot = slot;
    id.rid_value = slot;
    id.bid_value = static_cast<std::uint8_t>((slot + 61) & 63);
    id.gid_value = static_cast<std::uint8_t>((slot + 62) & 63);
    id.pc = 0x10000 + static_cast<std::uint64_t>(serial) * 0x20;
    id.raw = 0x29350041ULL + opcode + serial;
    id.bid_wrap = (serial & 1U) != 0;
    id.gid_wrap = (serial & 2U) != 0;
    id.rid_wrap = (serial & 4U) != 0;
    id.block_bid = 0xabcddcba00000000ULL | serial;
    return id;
}

Scenario make_scenario(const char *name, std::uint16_t opcode, std::uint8_t stid,
                       std::uint8_t slot, std::uint8_t serial) {
    Scenario scenario;
    scenario.name = name;
    scenario.identity = make_identity(opcode, stid, slot, serial);
    scenario.old_sp = stid == 0 ? 0x8000 : 0x9000;
    scenario.src_data = {
        0x1000000000000000ULL | serial,
        0x2000000000000000ULL | serial,
        0x3000000000000000ULL | serial,
    };
    return scenario;
}

void drive_accept(VTemplateAcceptedSidecarHandoffProbe &dut, const Scenario &scenario) {
    const Identity &id = scenario.identity;
    dut.io_acceptValid = 1;
    dut.io_acceptOpcode = id.opcode;
    dut.io_acceptStid = id.stid;
    dut.io_acceptPeId = id.pe_id;
    dut.io_acceptPc = id.pc;
    dut.io_acceptRaw = id.raw;
    dut.io_acceptImm = scenario.imm;
    dut.io_acceptRangeM = scenario.range_m;
    dut.io_acceptRangeN = scenario.range_n;
    dut.io_acceptBidValid = id.bid_valid;
    dut.io_acceptBidWrap = id.bid_wrap;
    dut.io_acceptBidValue = id.bid_value;
    dut.io_acceptGidValid = id.gid_valid;
    dut.io_acceptGidWrap = id.gid_wrap;
    dut.io_acceptGidValue = id.gid_value;
    dut.io_acceptRidValid = id.rid_valid;
    dut.io_acceptRidWrap = id.rid_wrap;
    dut.io_acceptRidValue = id.rid_value;
    dut.io_acceptUid = 0x9000 + id.rob_slot;
    dut.io_acceptBlockBidValid = id.block_bid_valid;
    dut.io_acceptBlockBid = id.block_bid;
    dut.io_scalarSpHeadBidValue_0 = id.bid_value;
    dut.io_scalarSpHeadBidValue_1 = id.bid_value;
    dut.io_scalarSpHeadRidValue_0 = id.rid_value;
    dut.io_scalarSpHeadRidValue_1 = id.rid_value;
    dut.io_scalarSpSnapshot_0 = scenario.old_sp;
    dut.io_scalarSpSnapshot_1 = scenario.old_sp;
    dut.io_readData_0 = scenario.src_data[0];
    dut.io_readData_1 = scenario.src_data[1];
    dut.io_readData_2 = scenario.src_data[2];
}

void clear_pulses(VTemplateAcceptedSidecarHandoffProbe &dut) {
    dut.io_acceptValid = 0;
    dut.io_pWakeupValid = 0;
    dut.io_cancelValid = 0;
    dut.io_releaseDecodeValid = 0;
    dut.io_releaseIssueValid = 0;
    dut.io_releaseMemoryValid = 0;
    dut.io_selectedTemplate = 0;
    dut.io_parentCommitValid = 0;
    dut.io_storeGrant = 0;
    dut.io_recoveryValid = 0;
    dut.io_globalClear = 0;
    dut.io_rfReadResponseValid = 0;
    dut.io_loadResponseValid = 0;
}

void initialize_inputs(VTemplateAcceptedSidecarHandoffProbe &dut) {
    clear_pulses(dut);
    Scenario initial = make_scenario("initial", kFentry, 0, 13, 0);
    drive_accept(dut, initial);
    dut.io_acceptValid = 0;
    dut.io_robAllocReady = 1;
    dut.io_readyMask = kReadyMask;
    dut.io_pWakeupTag = 0;
    dut.io_localTReadyMask = 0xf;
    dut.io_localUReadyMask = 0xf;
    dut.io_scalarSpHeadValid_0 = 1;
    dut.io_scalarSpHeadValid_1 = 1;
    dut.io_issueKeyFaultMask = 0;
    dut.io_parentRequestReadyOverride = 1;
    set_cancel_key(dut, initial.identity);
    set_decode_release_key(dut, initial.identity);
    set_issue_release_key(dut, initial.identity);
    set_memory_release_key(dut, initial.identity);
    set_commit_key(dut, initial.identity);
    set_recovery_key(dut, initial.identity);
    dut.io_recoveryKillsActive = 0;
    dut.io_rfReadReady = 0;
    dut.io_rfReadResponseChildIndex = 0;
    dut.io_rfReadResponsePhysTag = 0;
    dut.io_rfReadResponseData = 0;
    dut.io_loadReady = 0;
    dut.io_loadResponseChildIndex = 0;
    dut.io_loadResponseAddr = 0;
    dut.io_loadResponseData = 0;
    dut.io_storeReady = 0;
    dut.io_rfWriteReady = 0;
}

void reset(VTemplateAcceptedSidecarHandoffProbe &dut) {
    initialize_inputs(dut);
    dut.reset = 1;
    tick(dut);
    tick(dut);
    dut.reset = 0;
    tick(dut);
    require(!dut.io_sequencerActive, "reset left the sequencer active");
    require(dut.io_sidecarOccupiedMask == 0, "reset left a sidecar owner");
    require(dut.io_sidecarDecodeFenceMask == 0 &&
                dut.io_sidecarIssueFenceMask == 0 &&
                dut.io_sidecarMemoryFenceMask == 0,
            "reset left sidecar fences asserted");
}

std::uint8_t expected_initial_map(std::uint8_t stid, int arch) {
    return static_cast<std::uint8_t>(stid * kArchRegs + arch);
}

void check_accepted_maps(VTemplateAcceptedSidecarHandoffProbe &dut,
                         const Scenario &scenario) {
    for (int arch = 0; arch < kArchRegs; ++arch) {
        const auto expected = expected_initial_map(scenario.identity.stid, arch);
        require(bridge_smap(dut, arch) == expected &&
                    bridge_cmap(dut, arch) == expected,
                std::string(scenario.name) + ": bridge actual map snapshot mismatch");
        require(sidecar_smap(dut, arch) == bridge_smap(dut, arch) &&
                    sidecar_cmap(dut, arch) == bridge_cmap(dut, arch),
                std::string(scenario.name) + ": sidecar did not carry accepted maps");
    }
}

Identity accept_template(VTemplateAcceptedSidecarHandoffProbe &dut,
                         const Scenario &scenario) {
    eval(dut);
    const auto prior_occupied_mask = dut.io_sidecarOccupiedMask;
    const auto prior_decode_fence_mask = dut.io_sidecarDecodeFenceMask;
    const auto prior_issue_fence_mask = dut.io_sidecarIssueFenceMask;
    const auto prior_memory_fence_mask = dut.io_sidecarMemoryFenceMask;
    const auto accepted_mask = (1U << scenario.identity.stid);
    drive_accept(dut, scenario);
    eval(dut);
    require(dut.io_bridgeInReady && dut.io_bridgeAccepted,
            std::string(scenario.name) + ": template was not accepted");
    require(dut.io_bridgeTemplateSnapshotValid && dut.io_sidecarValid &&
                dut.io_sidecarReady && dut.io_sidecarFire,
            std::string(scenario.name) + ": accepted template sidecar was not atomic");
    require(dut.io_sidecarStid == scenario.identity.stid &&
                dut.io_sidecarRaw == scenario.identity.raw &&
                dut.io_sidecarRangeM == scenario.range_m &&
                dut.io_sidecarRangeN == scenario.range_n &&
                dut.io_sidecarSrc0Imm == scenario.imm,
            std::string(scenario.name) + ": accepted sidecar payload mismatch");
    require(dut.io_sidecarGeneration == dut.io_bridgeTemplateSnapshotGeneration,
            std::string(scenario.name) + ": sidecar generation was reconstructed");
    check_accepted_maps(dut, scenario);

    Identity accepted = scenario.identity;
    accepted.generation = dut.io_sidecarGeneration;
    tick(dut);
    dut.io_acceptValid = 0;
    eval(dut);
    require(dut.io_sidecarOccupiedMask == (prior_occupied_mask | accepted_mask),
            std::string(scenario.name) + ": sidecar owner was not retained");
    require(dut.io_sidecarDecodeFenceMask == (prior_decode_fence_mask | accepted_mask) &&
                dut.io_sidecarIssueFenceMask == (prior_issue_fence_mask | accepted_mask) &&
                dut.io_sidecarMemoryFenceMask == (prior_memory_fence_mask | accepted_mask),
            std::string(scenario.name) + ": accepted sidecar fences missing");
    return accepted;
}

void global_clear(VTemplateAcceptedSidecarHandoffProbe &dut) {
    clear_pulses(dut);
    dut.io_globalClear = 1;
    tick(dut);
    clear_pulses(dut);
    eval(dut);
}

void wait_for_parent_valid(VTemplateAcceptedSidecarHandoffProbe &dut,
                           const std::string &name) {
    for (int cycle = 0; cycle < 24; ++cycle) {
        eval(dut);
        if (dut.io_parentRequestValid) {
            return;
        }
        tick(dut);
    }
    throw std::runtime_error(name + ": real issue path did not produce a parent request");
}

void check_active_payload(VTemplateAcceptedSidecarHandoffProbe &dut,
                          const Scenario &scenario, const Identity &accepted) {
    const Identity &source = scenario.identity;
    require(dut.io_sequencerActive, std::string(scenario.name) + ": no active owner");
    require(dut.io_activeGeneration == accepted.generation &&
                dut.io_activeStid == source.stid &&
                dut.io_activePeId == source.pe_id &&
                dut.io_activePc == source.pc &&
                dut.io_activeOpcode == source.opcode &&
                dut.io_activeRaw == source.raw,
            std::string(scenario.name) + ": retained scalar identity mismatch");
    require(dut.io_activeBidValid == source.bid_valid &&
                dut.io_activeBidWrap == source.bid_wrap &&
                dut.io_activeBidValue == source.bid_value &&
                dut.io_activeGidValid == source.gid_valid &&
                dut.io_activeGidWrap == source.gid_wrap &&
                dut.io_activeGidValue == source.gid_value &&
                dut.io_activeRidValid == source.rid_valid &&
                dut.io_activeRidWrap == source.rid_wrap &&
                dut.io_activeRidValue == source.rid_value &&
                dut.io_activeRobSlot == source.rob_slot,
            std::string(scenario.name) + ": retained full ROB identity mismatch");
    require(dut.io_activeBlockBidValid == source.block_bid_valid &&
                dut.io_activeBlockBid == source.block_bid &&
                dut.io_activeCommitBid == source.bid_value &&
                dut.io_activeCommitGid == source.gid_value &&
                dut.io_activeCommitRid == source.rid_value,
            std::string(scenario.name) + ": retained block/commit identity mismatch");
    require(dut.io_activeOldSp == scenario.old_sp &&
                dut.io_activeSrcData_0 == scenario.src_data[0] &&
                dut.io_activeSrcData_1 == scenario.src_data[1] &&
                dut.io_activeSrcData_2 == scenario.src_data[2],
            std::string(scenario.name) + ": retained SP/source payload mismatch");
    for (int arch = 0; arch < kArchRegs; ++arch) {
        require(active_smap(dut, arch) == expected_initial_map(source.stid, arch) &&
                    active_cmap(dut, arch) == expected_initial_map(source.stid, arch),
                std::string(scenario.name) + ": sequencer retained map mismatch");
    }
}

void transfer_parent(VTemplateAcceptedSidecarHandoffProbe &dut,
                     const Scenario &scenario, const Identity &accepted) {
    wait_for_parent_valid(dut, scenario.name);
    require(dut.io_issuedTemplateGenerationValid &&
                dut.io_issuedTemplateGeneration == accepted.generation,
            std::string(scenario.name) + ": generation did not survive real issue");
    require(dut.io_issuedStid == accepted.stid &&
                dut.io_issuedRaw == scenario.identity.raw &&
                dut.io_issuedBidValue == scenario.identity.bid_value &&
                dut.io_issuedGidValue == scenario.identity.gid_value &&
                dut.io_issuedRidValue == scenario.identity.rid_value &&
                dut.io_issuedSrcData0 == scenario.src_data[0],
            std::string(scenario.name) + ": issued payload mismatch");
    require(dut.io_parentRequestReady && dut.io_parentTransfer &&
                dut.io_issueValid && dut.io_issueReady && dut.io_issueFire,
            std::string(scenario.name) + ": exact parent transfer did not fire");
    require(dut.io_sidecarOccupiedMask == (1U << accepted.stid) &&
                !dut.io_sequencerActive,
            std::string(scenario.name) + ": pre-edge ownership was not sidecar-only");
    tick(dut);
    eval(dut);
    require(dut.io_sidecarOccupiedMask == 0 && dut.io_sequencerActive,
            std::string(scenario.name) + ": transfer did not atomically change owner");
    require(dut.io_transferCount == 1 && dut.io_enqueueCount == 1,
            std::string(scenario.name) + ": transfer/enqueue was not one-shot");
    require(dut.io_sidecarDecodeFenceMask == (1U << accepted.stid) &&
                dut.io_sidecarIssueFenceMask == (1U << accepted.stid) &&
                dut.io_sidecarMemoryFenceMask == (1U << accepted.stid) &&
                dut.io_decodeFence && dut.io_issueFence && dut.io_memoryFence,
            std::string(scenario.name) + ": ownership transfer released a fence");
    check_active_payload(dut, scenario, accepted);
}

std::vector<std::uint8_t> legal_ring(std::uint8_t m, std::uint8_t n) {
    std::vector<std::uint8_t> result;
    std::uint8_t value = m;
    for (int guard = 0; guard < 22; ++guard) {
        result.push_back(value);
        if (value == n) {
            return result;
        }
        value = value == 23 ? 2 : static_cast<std::uint8_t>(value + 1);
    }
    throw std::runtime_error("test range did not terminate");
}

bool is_entry(const Scenario &scenario) {
    return scenario.identity.opcode == kFentry;
}

std::uint64_t expected_new_sp(const Scenario &scenario) {
    const auto frame = scenario.imm & 0x7fffULL;
    return is_entry(scenario) ? scenario.old_sp - frame : scenario.old_sp + frame;
}

std::uint64_t expected_child_addr(const Scenario &scenario, std::size_t index) {
    const auto offset = 8ULL * (index + 1);
    if (is_entry(scenario)) {
        return expected_new_sp(scenario) + (scenario.imm & 0x7fffULL) - offset;
    }
    return expected_new_sp(scenario) - offset;
}

void gather_public_range(VTemplateAcceptedSidecarHandoffProbe &dut,
                         const Scenario &scenario) {
    tick(dut);  // CaptureParent -> GatherChildren
    const auto ring = legal_ring(scenario.range_m, scenario.range_n);
    for (std::size_t index = 0; index < ring.size(); ++index) {
        clear_pulses(dut);
        eval(dut);
        const auto data = 0xa000000000000000ULL | index;
        if (is_entry(scenario)) {
            require(dut.io_rfReadRequestValid &&
                        dut.io_rfReadRequestChildIndex == index &&
                        dut.io_rfReadRequestArchReg == ring[index] &&
                        dut.io_rfReadRequestPhysTag ==
                            expected_initial_map(scenario.identity.stid, ring[index]),
                    std::string(scenario.name) + ": retained range/map RF request mismatch");
            dut.io_rfReadReady = 1;
            tick(dut);
            dut.io_rfReadReady = 0;
            dut.io_rfReadResponseValid = 1;
            dut.io_rfReadResponseChildIndex = index;
            dut.io_rfReadResponsePhysTag =
                expected_initial_map(scenario.identity.stid, ring[index]);
            dut.io_rfReadResponseData = data;
            tick(dut);
        } else {
            const auto address = expected_child_addr(scenario, index);
            require(dut.io_loadRequestValid &&
                        dut.io_loadRequestChildIndex == index &&
                        dut.io_loadRequestArchReg == ring[index] &&
                        dut.io_loadRequestAddr == address,
                    std::string(scenario.name) + ": retained range/imm load request mismatch");
            dut.io_loadReady = 1;
            tick(dut);
            dut.io_loadReady = 0;
            dut.io_loadResponseValid = 1;
            dut.io_loadResponseChildIndex = index;
            dut.io_loadResponseAddr = address;
            dut.io_loadResponseData = data;
            tick(dut);
        }
    }
    clear_pulses(dut);
    eval(dut);
    require(dut.io_completionValid &&
                dut.io_completionResult == expected_new_sp(scenario) &&
                dut.io_completionNewSp == expected_new_sp(scenario),
            std::string(scenario.name) + ": retained immediate did not reach completion");
}

Identity mutate_key(Identity id, int field) {
    switch (field) {
        case 0: id.stid ^= 1; break;
        case 1: id.bid_valid = !id.bid_valid; break;
        case 2: id.bid_wrap = !id.bid_wrap; break;
        case 3: id.bid_value ^= 1; break;
        case 4: id.gid_valid = !id.gid_valid; break;
        case 5: id.gid_wrap = !id.gid_wrap; break;
        case 6: id.gid_value ^= 1; break;
        case 7: id.rid_valid = !id.rid_valid; break;
        case 8: id.rid_wrap = !id.rid_wrap; break;
        case 9: id.rid_value ^= 1; break;
        case 10: id.rob_slot ^= 1; break;
        case 11: id.generation ^= 1; break;
        default: throw std::runtime_error("invalid full-key mutation");
    }
    return id;
}

void run_opcode_classification(VTemplateAcceptedSidecarHandoffProbe &dut) {
    const std::array<std::uint16_t, 4> opcodes{kFentry, kFexit, kFretRa, kFretStk};
    for (std::size_t index = 0; index < opcodes.size(); ++index) {
        reset(dut);
        dut.io_parentRequestReadyOverride = 0;
        const auto scenario = make_scenario(
            "template-opcode-classification", opcodes[index], index == 3 ? 1 : 0,
            static_cast<std::uint8_t>(8 + index), static_cast<std::uint8_t>(index));
        (void)accept_template(dut, scenario);
        require(dut.io_enqueueCount == 1,
                "accepted template opcode did not enqueue exactly once");
    }
}

void run_non_template_classification(VTemplateAcceptedSidecarHandoffProbe &dut) {
    reset(dut);
    auto scenario = make_scenario("non-template-classification", kNonTemplate, 0, 18, 8);
    drive_accept(dut, scenario);
    eval(dut);
    require(dut.io_bridgeAccepted && dut.io_bridgeOutValid,
            "non-template did not follow ordinary bridge acceptance");
    require(!dut.io_bridgeTemplateSnapshotValid && !dut.io_sidecarValid &&
                !dut.io_sidecarFire,
            "non-template was classified as a template sidecar");
    tick(dut);
    dut.io_acceptValid = 0;
    for (int cycle = 0; cycle < 8; ++cycle) {
        tick(dut);
    }
    require(dut.io_enqueueCount == 0 && dut.io_sidecarOccupiedMask == 0,
            "non-template created sidecar ownership");
}

void run_sidecar_accept_backpressure(VTemplateAcceptedSidecarHandoffProbe &dut) {
    reset(dut);
    dut.io_parentRequestReadyOverride = 0;
    const auto first = make_scenario("sidecar-accept-backpressure", kFentry, 0, 19, 9);
    (void)accept_template(dut, first);
    auto blocked = first;
    blocked.identity.pc += 4;
    blocked.identity.raw ^= 0x100;
    drive_accept(dut, blocked);
    eval(dut);
    require(!dut.io_sidecarReady && !dut.io_bridgeInReady &&
                !dut.io_bridgeAccepted && !dut.io_sidecarFire,
            "occupied same-STID sidecar did not block rename acceptance");
    dut.io_acceptValid = 0;
}

void run_issue_key_mismatch_matrix(VTemplateAcceptedSidecarHandoffProbe &dut) {
    static const std::array<const char *, kKeyFaultCount> names{
        "stid", "bid-valid", "bid-wrap", "bid-value",
        "gid-valid", "gid-wrap", "gid-value",
        "rid-valid", "rid-wrap", "rid-value", "rob-slot", "generation",
    };
    for (int field = 0; field < kKeyFaultCount; ++field) {
        reset(dut);
        dut.io_issueKeyFaultMask = 1U << field;
        const auto scenario = make_scenario(names[field], kFentry, 0, 20, 10);
        (void)accept_template(dut, scenario);
        bool rejected = false;
        for (int cycle = 0; cycle < 24; ++cycle) {
            eval(dut);
            require(!dut.io_parentRequestValid && !dut.io_parentTransfer,
                    std::string(names[field]) + ": mismatched issue formed parent request");
            if (dut.io_issueValid && dut.io_issueReady) {
                tick(dut);
                rejected = true;
                break;
            }
            tick(dut);
        }
        require(rejected, std::string(names[field]) + ": mismatched issue was not consumed");
        require(dut.io_mismatchDropCount == 1 &&
                    dut.io_staleGenerationDropCount == (field == 11 ? 1 : 0),
                std::string(names[field]) + ": mismatch counters were not isolated");
        require(dut.io_sidecarOccupiedMask == 1 && dut.io_transferCount == 0 &&
                    !dut.io_sequencerActive,
                std::string(names[field]) + ": mismatch destroyed or transferred owner");
    }
}

void run_downstream_backpressure_and_retention(
    VTemplateAcceptedSidecarHandoffProbe &dut) {
    reset(dut);
    dut.io_parentRequestReadyOverride = 0;
    auto scenario = make_scenario("downstream-parent-backpressure", kFentry, 1, 21, 11);
    scenario.range_m = 22;
    scenario.range_n = 3;
    scenario.imm = 0x98;
    const Identity accepted = accept_template(dut, scenario);
    wait_for_parent_valid(dut, scenario.name);
    require(!dut.io_parentRequestReady && !dut.io_parentTransfer &&
                dut.io_issueValid && !dut.io_issueFire,
            "downstream backpressure did not hold the real issue request");
    const auto held_generation = dut.io_issuedTemplateGeneration;
    const auto held_raw = dut.io_issuedRaw;
    const auto held_src0 = dut.io_issuedSrcData0;
    tick(dut);
    tick(dut);
    require(dut.io_issueValid && !dut.io_issueFire &&
                dut.io_issuedTemplateGeneration == held_generation &&
                dut.io_issuedRaw == held_raw &&
                dut.io_issuedSrcData0 == held_src0 &&
                dut.io_sidecarOccupiedMask == 2 && !dut.io_sequencerActive,
            "held issue valid/bits changed under parent backpressure");

    dut.io_parentRequestReadyOverride = 1;
    transfer_parent(dut, scenario, accepted);
    set_recovery_key(dut, accepted);
    dut.io_recoveryValid = 1;
    dut.io_recoveryKillsActive = 1;
    eval(dut);
    require(dut.io_selfRestartObserved && !dut.io_cancelObserved,
            "matching self restart was not observed after transfer");
    tick(dut);
    clear_pulses(dut);
    require(dut.io_sequencerActive && dut.io_sidecarOccupiedMask == 0 &&
                dut.io_enqueueCount == 1,
            "self restart recreated or discarded sidecar ownership");
    check_active_payload(dut, scenario, accepted);
    gather_public_range(dut, scenario);
}

void run_pre_issue_cancel(VTemplateAcceptedSidecarHandoffProbe &dut) {
    reset(dut);
    dut.io_parentRequestReadyOverride = 0;
    const auto scenario = make_scenario("pre-issue-cancel", kFexit, 0, 22, 12);
    const Identity accepted = accept_template(dut, scenario);
    set_cancel_key(dut, accepted);
    dut.io_cancelValid = 1;
    tick(dut);
    clear_pulses(dut);
    require(dut.io_sidecarOccupiedMask == 0 &&
                dut.io_sidecarDecodeFenceMask == 0 &&
                dut.io_sidecarIssueFenceMask == 0 &&
                dut.io_sidecarMemoryFenceMask == 0 &&
                dut.io_transferCount == 0 && !dut.io_sequencerActive,
            "pre-issue cancellation did not clear only the sidecar owner");
}

void run_same_cycle_cancel_dominance(VTemplateAcceptedSidecarHandoffProbe &dut) {
    reset(dut);
    dut.io_parentRequestReadyOverride = 0;
    const auto scenario = make_scenario("same-cycle-cancel-dominance", kFretRa, 0, 23, 13);
    const Identity accepted = accept_template(dut, scenario);
    wait_for_parent_valid(dut, scenario.name);
    set_cancel_key(dut, accepted);
    dut.io_cancelValid = 1;
    dut.io_parentRequestReadyOverride = 1;
    eval(dut);
    require(!dut.io_parentRequestValid && !dut.io_parentTransfer &&
                !dut.io_issueFire,
            "matching cancel lost same-cycle dominance to transfer");
    tick(dut);
    clear_pulses(dut);
    require(dut.io_sidecarOccupiedMask == 0 && !dut.io_sequencerActive &&
                dut.io_transferCount == 0,
            "same-cycle cancel left or transferred an owner");
}

void pulse_release(VTemplateAcceptedSidecarHandoffProbe &dut, int fence,
                   const Identity &key) {
    clear_pulses(dut);
    if (fence == 0) {
        set_decode_release_key(dut, key);
        dut.io_releaseDecodeValid = 1;
    } else if (fence == 1) {
        set_issue_release_key(dut, key);
        dut.io_releaseIssueValid = 1;
    } else {
        set_memory_release_key(dut, key);
        dut.io_releaseMemoryValid = 1;
    }
    tick(dut);
    clear_pulses(dut);
}

void run_independent_fence_release_matrix(VTemplateAcceptedSidecarHandoffProbe &dut) {
    reset(dut);
    const auto scenario = make_scenario("independent-fence-release", kFretStk, 0, 24, 14);
    const Identity accepted = accept_template(dut, scenario);
    transfer_parent(dut, scenario, accepted);

    for (int fence = 0; fence < 3; ++fence) {
        for (int field = 0; field < kKeyFaultCount; ++field) {
            pulse_release(dut, fence, mutate_key(accepted, field));
            require(dut.io_sidecarDecodeFenceMask == 1 &&
                        dut.io_sidecarIssueFenceMask == 1 &&
                        dut.io_sidecarMemoryFenceMask == 1,
                    "wrong full-key fence release changed retained masks");
        }
    }

    pulse_release(dut, 0, accepted);
    require(dut.io_sidecarDecodeFenceMask == 0 &&
                dut.io_sidecarIssueFenceMask == 1 &&
                dut.io_sidecarMemoryFenceMask == 1,
            "matching decode release was not independent");
    pulse_release(dut, 1, accepted);
    require(dut.io_sidecarDecodeFenceMask == 0 &&
                dut.io_sidecarIssueFenceMask == 0 &&
                dut.io_sidecarMemoryFenceMask == 1,
            "matching issue release was not independent");
    pulse_release(dut, 2, accepted);
    require(dut.io_sidecarDecodeFenceMask == 0 &&
                dut.io_sidecarIssueFenceMask == 0 &&
                dut.io_sidecarMemoryFenceMask == 0,
            "matching memory release was not independent");
    require(dut.io_decodeFence && dut.io_issueFence && dut.io_memoryFence &&
                dut.io_sequencerActive,
            "sidecar releases altered the distinct sequencer fence owner");
}

void run_different_stid_and_global_clear(VTemplateAcceptedSidecarHandoffProbe &dut) {
    reset(dut);
    dut.io_parentRequestReadyOverride = 0;
    const auto stid0 = make_scenario("stid0-owner", kFentry, 0, 25, 15);
    const Identity accepted0 = accept_template(dut, stid0);
    const auto stid1 = make_scenario("stid1-independent-owner", kFexit, 1, 26, 16);
    const Identity accepted1 = accept_template(dut, stid1);
    require(accepted1.generation == static_cast<std::uint16_t>(accepted0.generation + 1) &&
                dut.io_sidecarOccupiedMask == 3 &&
                dut.io_sidecarDecodeFenceMask == 3 &&
                dut.io_sidecarIssueFenceMask == 3 &&
                dut.io_sidecarMemoryFenceMask == 3,
            "two STIDs did not retain independent maps/generations/owners");

    set_cancel_key(dut, accepted0);
    dut.io_cancelValid = 1;
    tick(dut);
    clear_pulses(dut);
    require(dut.io_sidecarOccupiedMask == 2 &&
                dut.io_sidecarDecodeFenceMask == 2 &&
                dut.io_sidecarIssueFenceMask == 2 &&
                dut.io_sidecarMemoryFenceMask == 2,
            "STID0 cancel disturbed independent STID1 owner");

    global_clear(dut);
    require(dut.io_sidecarOccupiedMask == 0 &&
                dut.io_sidecarDecodeFenceMask == 0 &&
                dut.io_sidecarIssueFenceMask == 0 &&
                dut.io_sidecarMemoryFenceMask == 0 &&
                !dut.io_sequencerActive,
            "global clear did not clear all sidecar/sequencer state");
}

}  // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    try {
        VTemplateAcceptedSidecarHandoffProbe dut;
        run_opcode_classification(dut);
        run_non_template_classification(dut);
        run_sidecar_accept_backpressure(dut);
        run_issue_key_mismatch_matrix(dut);
        run_downstream_backpressure_and_retention(dut);
        run_pre_issue_cancel(dut);
        run_same_cycle_cancel_dominance(dut);
        run_independent_fence_release_matrix(dut);
        run_different_stid_and_global_clear(dut);
        std::cout << "bctrl-template-accepted-sidecar-handoff-probe: PASS\n";
        return 0;
    } catch (const std::exception &ex) {
        std::cerr << "bctrl-template-accepted-sidecar-handoff-probe: FAIL: "
                  << ex.what() << "\n";
        return 1;
    }
}
