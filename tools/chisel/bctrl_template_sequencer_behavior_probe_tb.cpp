#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "VBlockControlTemplateSequencerBehaviorProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint16_t kFentry = 429;
constexpr std::uint16_t kFexit = 430;
constexpr std::uint16_t kFretRa = 431;
constexpr std::uint16_t kFretStk = 432;

struct Identity {
    std::uint16_t generation = 7;
    std::uint8_t stid = 0;
    std::uint8_t pe_id = 3;
    std::uint64_t pc = 0x11370;
    std::uint64_t raw = 0x29350041;
    std::uint16_t opcode = kFentry;
    bool bid_valid = true;
    bool bid_wrap = false;
    std::uint8_t bid_value = 11;
    bool gid_valid = true;
    bool gid_wrap = true;
    std::uint8_t gid_value = 12;
    bool rid_valid = true;
    bool rid_wrap = false;
    std::uint8_t rid_value = 13;
    std::uint8_t rob_slot = 13;
    bool block_bid_valid = true;
    std::uint64_t block_bid = 0xabcddcbaULL;
    std::uint32_t commit_bid = 0x101;
    std::uint32_t commit_gid = 0x202;
    std::uint32_t commit_rid = 0x303;
};

#define DEFINE_IDENTITY_DRIVER(NAME, PREFIX)                                             \
    void NAME(VBlockControlTemplateSequencerBehaviorProbe &dut, const Identity &id) {    \
        dut.PREFIX##_generation = id.generation;                                          \
        dut.PREFIX##_stid = id.stid;                                                      \
        dut.PREFIX##_peId = id.pe_id;                                                     \
        dut.PREFIX##_pc = id.pc;                                                          \
        dut.PREFIX##_raw = id.raw;                                                        \
        dut.PREFIX##_opcode = id.opcode;                                                  \
        dut.PREFIX##_bidValid = id.bid_valid;                                             \
        dut.PREFIX##_bidWrap = id.bid_wrap;                                               \
        dut.PREFIX##_bidValue = id.bid_value;                                             \
        dut.PREFIX##_gidValid = id.gid_valid;                                             \
        dut.PREFIX##_gidWrap = id.gid_wrap;                                               \
        dut.PREFIX##_gidValue = id.gid_value;                                             \
        dut.PREFIX##_ridValid = id.rid_valid;                                             \
        dut.PREFIX##_ridWrap = id.rid_wrap;                                               \
        dut.PREFIX##_ridValue = id.rid_value;                                             \
        dut.PREFIX##_robSlot = id.rob_slot;                                               \
        dut.PREFIX##_blockBidValid = id.block_bid_valid;                                  \
        dut.PREFIX##_blockBid = id.block_bid;                                             \
        dut.PREFIX##_commitIdentityBid = id.commit_bid;                                   \
        dut.PREFIX##_commitIdentityGid = id.commit_gid;                                   \
        dut.PREFIX##_commitIdentityRid = id.commit_rid;                                   \
    }

DEFINE_IDENTITY_DRIVER(set_enqueue_identity, io_enqueueIdentity)
DEFINE_IDENTITY_DRIVER(set_issue_identity, io_issueIdentity)
DEFINE_IDENTITY_DRIVER(set_cancel_identity, io_cancelIdentity)
DEFINE_IDENTITY_DRIVER(set_rf_response_identity, io_rfReadResponseIdentity)
DEFINE_IDENTITY_DRIVER(set_load_response_identity, io_loadResponseIdentity)
DEFINE_IDENTITY_DRIVER(set_commit_identity, io_parentCommitIdentity)
DEFINE_IDENTITY_DRIVER(set_recovery_identity, io_recoveryIdentity)

#undef DEFINE_IDENTITY_DRIVER

struct Scenario {
    const char *name;
    std::uint16_t opcode;
    std::uint8_t range_m;
    std::uint8_t range_n;
    std::uint64_t old_sp;
    std::uint64_t imm;
    std::uint64_t src0;
};

struct Trace {
    std::vector<std::uint8_t> arch;
    std::vector<std::uint64_t> addresses;
    std::vector<std::uint64_t> data;
    std::uint64_t target = 0;
    std::uint16_t generation = 0;
    std::uint8_t slot = 0;
};

void require(bool condition, const std::string &message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void eval(VBlockControlTemplateSequencerBehaviorProbe &dut) {
    dut.eval();
}

void tick(VBlockControlTemplateSequencerBehaviorProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
    dut.clock = 0;
    eval(dut);
}

Identity make_identity(std::uint16_t opcode, std::uint16_t generation = 7,
                       std::uint8_t slot = 13, std::uint8_t stid = 0) {
    Identity id;
    id.opcode = opcode;
    id.generation = generation;
    id.rob_slot = slot;
    id.rid_value = slot;
    id.bid_value = static_cast<std::uint8_t>((slot + 61) & 63);
    id.gid_value = static_cast<std::uint8_t>((slot + 62) & 63);
    id.stid = stid;
    id.pc = 0x10000 + static_cast<std::uint64_t>(slot) * 16;
    id.raw = 0x29350041ULL + opcode;
    id.commit_rid = 0x300 + slot;
    return id;
}

void clear_controls(VBlockControlTemplateSequencerBehaviorProbe &dut) {
    dut.io_enqueueValid = 0;
    dut.io_issueValid = 0;
    dut.io_cancelValid = 0;
    dut.io_globalClear = 0;
    dut.io_rfReadReady = 0;
    dut.io_rfReadResponseValid = 0;
    dut.io_loadReady = 0;
    dut.io_loadResponseValid = 0;
    dut.io_selectedTemplate = 0;
    dut.io_parentCommitValid = 0;
    dut.io_storeGrant = 0;
    dut.io_recoveryValid = 0;
    dut.io_recoveryKillsActive = 0;
    dut.io_storeReady = 0;
    dut.io_rfWriteReady = 0;
}

void initialize_inputs(VBlockControlTemplateSequencerBehaviorProbe &dut) {
    clear_controls(dut);
    const Identity zero = make_identity(kFentry, 0, 0, 0);
    set_enqueue_identity(dut, zero);
    set_issue_identity(dut, zero);
    set_cancel_identity(dut, zero);
    set_rf_response_identity(dut, zero);
    set_load_response_identity(dut, zero);
    set_commit_identity(dut, zero);
    set_recovery_identity(dut, zero);
    dut.io_src0Imm = 0;
    dut.io_rangeM = 2;
    dut.io_rangeN = 2;
    dut.io_mapSeed = 0;
    dut.io_oldSp = 0;
    dut.io_srcData0 = 0;
    dut.io_srcData1 = 0;
    dut.io_srcData2 = 0;
    dut.io_rfReadResponseChildIndex = 0;
    dut.io_rfReadResponsePhysTag = 0;
    dut.io_rfReadResponseData = 0;
    dut.io_loadResponseChildIndex = 0;
    dut.io_loadResponseAddr = 0;
    dut.io_loadResponseData = 0;
}

void reset(VBlockControlTemplateSequencerBehaviorProbe &dut) {
    initialize_inputs(dut);
    dut.reset = 1;
    tick(dut);
    tick(dut);
    dut.reset = 0;
    tick(dut);
    require(dut.io_state == 0 && !dut.io_activeValid, "reset did not reach idle");
}

std::vector<std::uint8_t> legal_ring(std::uint8_t m, std::uint8_t n) {
    std::vector<std::uint8_t> result;
    std::uint8_t value = m;
    for (int guard = 0; guard < 22; ++guard) {
        require(value >= 2 && value <= 23, "test requested an illegal CTGen endpoint");
        result.push_back(value);
        if (value == n) {
            return result;
        }
        value = value == 23 ? 2 : static_cast<std::uint8_t>(value + 1);
    }
    throw std::runtime_error("CTGen test ring did not terminate");
}

bool is_entry(const Scenario &scenario) {
    return scenario.opcode == kFentry;
}

bool is_fret(const Scenario &scenario) {
    return scenario.opcode == kFretRa || scenario.opcode == kFretStk;
}

std::uint64_t new_sp(const Scenario &scenario) {
    const std::uint64_t frame = scenario.imm & 0x7fffULL;
    return is_entry(scenario) ? scenario.old_sp - frame : scenario.old_sp + frame;
}

std::uint64_t child_address(const Scenario &scenario, std::size_t index) {
    const std::uint64_t offset = 8ULL * (index + 1);
    if (is_entry(scenario)) {
        return new_sp(scenario) + (scenario.imm & 0x7fffULL) - offset;
    }
    return new_sp(scenario) - offset;
}

std::uint8_t mapped_tag(std::uint8_t seed, std::uint8_t arch) {
    return static_cast<std::uint8_t>((seed + arch) & 63);
}

void enqueue(VBlockControlTemplateSequencerBehaviorProbe &dut, const Identity &id,
             const Scenario &scenario, std::uint8_t map_seed) {
    clear_controls(dut);
    set_enqueue_identity(dut, id);
    dut.io_src0Imm = scenario.imm;
    dut.io_rangeM = scenario.range_m;
    dut.io_rangeN = scenario.range_n;
    dut.io_mapSeed = map_seed;
    dut.io_enqueueValid = 1;
    eval(dut);
    require(dut.io_enqueueReady, std::string(scenario.name) + ": enqueue not ready");
    tick(dut);
    dut.io_enqueueValid = 0;
    eval(dut);
    require(dut.io_sidecarOccupiedMask == (1U << id.stid),
            std::string(scenario.name) + ": sidecar occupancy missing after enqueue");
    require(dut.io_sidecarDecodeFenceMask == (1U << id.stid) &&
                dut.io_sidecarIssueFenceMask == (1U << id.stid) &&
                dut.io_sidecarMemoryFenceMask == (1U << id.stid),
            std::string(scenario.name) + ": sidecar fences missing after enqueue");
}

void transfer(VBlockControlTemplateSequencerBehaviorProbe &dut, const Identity &id,
              const Scenario &scenario) {
    clear_controls(dut);
    set_issue_identity(dut, id);
    dut.io_oldSp = scenario.old_sp;
    dut.io_srcData0 = scenario.src0;
    dut.io_srcData1 = 0x2222222222222222ULL;
    dut.io_srcData2 = 0x3333333333333333ULL;
    dut.io_issueValid = 1;
    eval(dut);
    require(dut.io_issueReady && dut.io_parentRequestValid && dut.io_parentRequestReady &&
                dut.io_parentTransfer,
            std::string(scenario.name) + ": exact sidecar transfer did not fire");
    tick(dut);
    dut.io_issueValid = 0;
    eval(dut);
    require(dut.io_activeValid, std::string(scenario.name) + ": sequencer did not own parent");
    require(dut.io_activeGeneration == id.generation && dut.io_activeStid == id.stid &&
                dut.io_activeRobSlot == id.rob_slot && dut.io_activeOldSp == scenario.old_sp &&
                dut.io_activeSrcData0 == scenario.src0,
            std::string(scenario.name) + ": transferred parent payload changed");
    require(dut.io_sidecarOccupiedMask == 0,
            std::string(scenario.name) + ": sidecar slot remained occupied after transfer");
    require(dut.io_sidecarDecodeFenceMask == (1U << id.stid) &&
                dut.io_sidecarIssueFenceMask == (1U << id.stid) &&
                dut.io_sidecarMemoryFenceMask == (1U << id.stid),
            std::string(scenario.name) + ": sidecar fence lifetime ended at transfer");
}

void advance_to_gather(VBlockControlTemplateSequencerBehaviorProbe &dut,
                       const std::string &name) {
    require(dut.io_state == 1, name + ": expected CaptureParent after transfer");
    tick(dut);
    require(dut.io_state == 2, name + ": expected GatherChildren after capture");
}

Trace gather_children(VBlockControlTemplateSequencerBehaviorProbe &dut, const Identity &id,
                      const Scenario &scenario, std::uint8_t map_seed,
                      bool check_stalls = true) {
    Trace trace;
    trace.generation = id.generation;
    trace.slot = id.rob_slot;
    const auto registers = legal_ring(scenario.range_m, scenario.range_n);
    for (std::size_t index = 0; index < registers.size(); ++index) {
        clear_controls(dut);
        eval(dut);
        const std::uint64_t expected_addr = child_address(scenario, index);
        const std::uint64_t expected_data =
            0xa000000000000000ULL | (static_cast<std::uint64_t>(scenario.opcode) << 16) | index;
        if (is_entry(scenario)) {
            require(dut.io_rfReadRequestValid,
                    std::string(scenario.name) + ": missing RF-read request");
            require(dut.io_rfReadRequestChildIndex == index &&
                        dut.io_rfReadRequestArchReg == registers[index] &&
                        dut.io_rfReadRequestPhysTag == mapped_tag(map_seed, registers[index]),
                    std::string(scenario.name) + ": RF-read order/tag mismatch");
            if (check_stalls && index == 0) {
                const auto held_index = dut.io_rfReadRequestChildIndex;
                const auto held_arch = dut.io_rfReadRequestArchReg;
                const auto held_tag = dut.io_rfReadRequestPhysTag;
                tick(dut);
                require(dut.io_rfReadRequestValid &&
                            dut.io_rfReadRequestChildIndex == held_index &&
                            dut.io_rfReadRequestArchReg == held_arch &&
                            dut.io_rfReadRequestPhysTag == held_tag,
                        std::string(scenario.name) + ": RF-read payload changed under stall");
            }
            dut.io_rfReadReady = 1;
            eval(dut);
            require(dut.io_rfReadRequestValid && dut.io_rfReadRequestReady,
                    std::string(scenario.name) + ": RF-read handshake missing");
            tick(dut);
            clear_controls(dut);
            set_rf_response_identity(dut, id);
            dut.io_rfReadResponseValid = 1;
            dut.io_rfReadResponseChildIndex = index;
            dut.io_rfReadResponsePhysTag = mapped_tag(map_seed, registers[index]);
            dut.io_rfReadResponseData = expected_data;
            eval(dut);
            require(!dut.io_lookupObservationValid,
                    std::string(scenario.name) + ": entry emitted lookup observation");
            tick(dut);
        } else {
            require(dut.io_loadRequestValid,
                    std::string(scenario.name) + ": missing load request");
            require(dut.io_loadRequestChildIndex == index &&
                        dut.io_loadRequestArchReg == registers[index] &&
                        dut.io_loadRequestAddr == expected_addr,
                    std::string(scenario.name) + ": load CTGen/address mismatch");
            if (check_stalls && index == 0) {
                const auto held_index = dut.io_loadRequestChildIndex;
                const auto held_arch = dut.io_loadRequestArchReg;
                const auto held_addr = dut.io_loadRequestAddr;
                tick(dut);
                require(dut.io_loadRequestValid && dut.io_loadRequestChildIndex == held_index &&
                            dut.io_loadRequestArchReg == held_arch &&
                            dut.io_loadRequestAddr == held_addr,
                        std::string(scenario.name) + ": load payload changed under stall");
            }
            dut.io_loadReady = 1;
            eval(dut);
            require(dut.io_loadRequestValid && dut.io_loadRequestReady,
                    std::string(scenario.name) + ": load handshake missing");
            tick(dut);
            clear_controls(dut);
            set_load_response_identity(dut, id);
            dut.io_loadResponseValid = 1;
            dut.io_loadResponseChildIndex = index;
            dut.io_loadResponseAddr = expected_addr;
            dut.io_loadResponseData = expected_data;
            eval(dut);
            require(dut.io_lookupObservationValid == is_fret(scenario),
                    std::string(scenario.name) + ": lookup observation classification mismatch");
            if (is_fret(scenario)) {
                require(dut.io_lookupObservationChildIndex == index &&
                            dut.io_lookupObservationAddr == expected_addr &&
                            dut.io_lookupObservationData == expected_data,
                        std::string(scenario.name) + ": lookup observation payload mismatch");
            }
            tick(dut);
        }
        trace.arch.push_back(registers[index]);
        trace.addresses.push_back(expected_addr);
        trace.data.push_back(expected_data);
    }
    clear_controls(dut);
    eval(dut);
    require(dut.io_state == 3 && dut.io_completionValid,
            std::string(scenario.name) + ": gather did not reach completion hold");
    trace.target = scenario.opcode == kFretRa
                       ? scenario.src0
                       : scenario.opcode == kFretStk ? trace.data.front() : id.pc + 4;
    return trace;
}

void select_completion(VBlockControlTemplateSequencerBehaviorProbe &dut, const Identity &id,
                       const Scenario &scenario, const Trace &trace) {
    clear_controls(dut);
    eval(dut);
    const auto result = dut.io_completionResult;
    const auto next_pc = dut.io_completionNextPc;
    const auto stores = dut.io_completionRetainedStoreCount;
    const auto rf = dut.io_completionRetainedRfCount;
    tick(dut);
    require(dut.io_completionValid && dut.io_completionResult == result &&
                dut.io_completionNextPc == next_pc &&
                dut.io_completionRetainedStoreCount == stores &&
                dut.io_completionRetainedRfCount == rf,
            std::string(scenario.name) + ": completion payload changed under stall");
    require(result == new_sp(scenario) && dut.io_completionNewSp == new_sp(scenario) &&
                dut.io_completionParentSlot == id.rob_slot && next_pc == trace.target &&
                dut.io_completionRedirectValid == is_fret(scenario),
            std::string(scenario.name) + ": completion result/target mismatch");
    require(stores == (is_entry(scenario) ? trace.arch.size() : 0) &&
                rf == (is_entry(scenario) ? 0 : trace.arch.size()),
            std::string(scenario.name) + ": completion retained counts mismatch");

    dut.io_selectedTemplate = 1;
    eval(dut);
    require(dut.io_terminal && dut.io_parentIssueRelease && !dut.io_tailZero,
            std::string(scenario.name) + ": terminal/release boundary mismatch");
    tick(dut);
    clear_controls(dut);
    require(dut.io_state == 4 && dut.io_activeValid,
            std::string(scenario.name) + ": completion did not retain active parent");

    set_recovery_identity(dut, id);
    dut.io_recoveryValid = 1;
    dut.io_recoveryKillsActive = 1;
    eval(dut);
    require(dut.io_selfRestartObserved && !dut.io_cancelObserved &&
                !dut.io_illegalDiscardAttempt,
            std::string(scenario.name) + ": matching self restart was not retained");
    tick(dut);
    clear_controls(dut);
    require(dut.io_state == 4 && dut.io_activeValid,
            std::string(scenario.name) + ": self restart discarded completed parent");
}

void commit_parent(VBlockControlTemplateSequencerBehaviorProbe &dut, const Identity &id,
                   const Scenario &scenario) {
    Identity wrong = id;
    wrong.generation++;
    clear_controls(dut);
    set_commit_identity(dut, wrong);
    dut.io_parentCommitValid = 1;
    eval(dut);
    require(!dut.io_spPublishValid,
            std::string(scenario.name) + ": mismatched commit published SP");
    tick(dut);
    require(dut.io_state == 4,
            std::string(scenario.name) + ": mismatched commit changed phase");

    clear_controls(dut);
    set_commit_identity(dut, id);
    dut.io_parentCommitValid = 1;
    eval(dut);
    require(dut.io_spPublishValid && dut.io_spPublishValue == new_sp(scenario),
            std::string(scenario.name) + ": matching commit did not publish SP");
    tick(dut);
    clear_controls(dut);
    require(dut.io_committed &&
                dut.io_state == (is_entry(scenario) ? 5 : 7),
            std::string(scenario.name) + ": matching commit did not enter drain");

    Identity external = id;
    external.generation++;
    set_recovery_identity(dut, external);
    dut.io_recoveryValid = 1;
    dut.io_recoveryKillsActive = 1;
    eval(dut);
    require(dut.io_illegalDiscardAttempt && !dut.io_cancelObserved,
            std::string(scenario.name) + ": committed drain did not reject blind discard");
    tick(dut);
    clear_controls(dut);
    require(dut.io_activeValid && dut.io_committed,
            std::string(scenario.name) + ": external recovery discarded committed drain");
}

void drain_children(VBlockControlTemplateSequencerBehaviorProbe &dut,
                    const Identity &id, const Scenario &scenario, const Trace &trace,
                    std::uint8_t map_seed) {
    if (is_entry(scenario)) {
        dut.io_storeGrant = 1;
        tick(dut);
        clear_controls(dut);
        require(dut.io_state == 6,
                std::string(scenario.name) + ": store grant did not enter burst");
    }

    for (std::size_t index = 0; index < trace.arch.size(); ++index) {
        eval(dut);
        if (is_entry(scenario)) {
            require(dut.io_storeRequestValid && !dut.io_storeRequestOwnsStqRow &&
                        dut.io_storeRequestChildIndex == index &&
                        dut.io_storeRequestChildLast == (index + 1 == trace.arch.size()) &&
                        dut.io_storeRequestAddr == trace.addresses[index] &&
                        dut.io_storeRequestData == trace.data[index],
                    std::string(scenario.name) + ": held SCB payload mismatch");
            if (index == 0) {
                const auto held_addr = dut.io_storeRequestAddr;
                const auto held_data = dut.io_storeRequestData;
                tick(dut);
                require(dut.io_storeRequestValid && dut.io_storeRequestAddr == held_addr &&
                            dut.io_storeRequestData == held_data &&
                            !dut.io_storeObservationValid,
                        std::string(scenario.name) + ": SCB payload/observation violated stall");
            }
            dut.io_storeReady = 1;
            eval(dut);
            require(dut.io_storeObservationValid &&
                        dut.io_storeObservationChildIndex == index &&
                        dut.io_storeObservationAddr == trace.addresses[index] &&
                        dut.io_storeObservationData == trace.data[index],
                    std::string(scenario.name) + ": accepted store observation mismatch");
            tick(dut);
            clear_controls(dut);
        } else {
            require(dut.io_rfWriteRequestValid &&
                        dut.io_rfWriteRequestChildIndex == index &&
                        dut.io_rfWriteRequestChildLast == (index + 1 == trace.arch.size()) &&
                        dut.io_rfWriteRequestArchReg == trace.arch[index] &&
                        dut.io_rfWriteRequestPhysTag == mapped_tag(map_seed, trace.arch[index]) &&
                        dut.io_rfWriteRequestData == trace.data[index],
                    std::string(scenario.name) + ": held RF-write payload mismatch");
            if (index == 0) {
                const auto held_arch = dut.io_rfWriteRequestArchReg;
                const auto held_tag = dut.io_rfWriteRequestPhysTag;
                const auto held_data = dut.io_rfWriteRequestData;
                tick(dut);
                require(dut.io_rfWriteRequestValid &&
                            dut.io_rfWriteRequestArchReg == held_arch &&
                            dut.io_rfWriteRequestPhysTag == held_tag &&
                            dut.io_rfWriteRequestData == held_data,
                        std::string(scenario.name) + ": RF-write payload changed under stall");
            }
            dut.io_rfWriteReady = 1;
            tick(dut);
            clear_controls(dut);
        }
    }
    eval(dut);
    require(dut.io_state == 8 && dut.io_tailZero && dut.io_retainedStoreCount == 0 &&
                dut.io_retainedRfCount == 0 && !dut.io_decodeFence &&
                !dut.io_issueFence && !dut.io_memoryFence,
            std::string(scenario.name) + ": final drain did not reach tail-zero");
    tick(dut);
    require(dut.io_state == 0 && !dut.io_activeValid && !dut.io_tailZero,
            std::string(scenario.name) + ": tail-zero did not retire to idle");
    require(dut.io_activeMapReg22 == mapped_tag(map_seed, 22),
            std::string(scenario.name) + ": captured map diagnostics changed");
    (void)id;
}

Trace run_scenario(VBlockControlTemplateSequencerBehaviorProbe &dut,
                   const Scenario &scenario, std::uint16_t generation,
                   std::uint8_t slot, std::uint8_t map_seed) {
    reset(dut);
    const Identity id = make_identity(scenario.opcode, generation, slot);
    enqueue(dut, id, scenario, map_seed);
    transfer(dut, id, scenario);
    advance_to_gather(dut, scenario.name);
    Trace trace = gather_children(dut, id, scenario, map_seed);
    select_completion(dut, id, scenario, trace);
    commit_parent(dut, id, scenario);
    drain_children(dut, id, scenario, trace, map_seed);
    return trace;
}

void run_sidecar_match_suite(VBlockControlTemplateSequencerBehaviorProbe &dut) {
    const Scenario scenario{"sidecar-key-suite", kFentry, 10, 10, 0x40000, 0x80, 0x1234};
    const Identity id = make_identity(kFentry, 41, 19);
    reset(dut);
    enqueue(dut, id, scenario, 5);

    clear_controls(dut);
    set_enqueue_identity(dut, id);
    dut.io_enqueueValid = 1;
    eval(dut);
    require(!dut.io_enqueueReady, "duplicate same-STID sidecar enqueue was accepted");
    tick(dut);
    require(dut.io_enqueueCount == 1, "duplicate enqueue changed accepted count");

    std::vector<std::pair<const char *, Identity>> mismatches;
    Identity bad = id;
    bad.bid_value ^= 1;
    mismatches.emplace_back("bid", bad);
    bad = id;
    bad.gid_wrap = !bad.gid_wrap;
    mismatches.emplace_back("gid", bad);
    bad = id;
    bad.rid_valid = false;
    mismatches.emplace_back("rid", bad);
    bad = id;
    bad.rob_slot ^= 1;
    mismatches.emplace_back("slot", bad);
    bad = id;
    bad.stid = 1;
    mismatches.emplace_back("stid", bad);
    bad = id;
    bad.generation++;
    mismatches.emplace_back("generation", bad);

    std::uint32_t expected_drops = 0;
    for (const auto &[name, candidate] : mismatches) {
        clear_controls(dut);
        set_issue_identity(dut, candidate);
        dut.io_issueValid = 1;
        eval(dut);
        require(dut.io_issueReady && !dut.io_parentRequestValid && !dut.io_parentTransfer,
                std::string("sidecar mismatch was not locally rejected: ") + name);
        tick(dut);
        ++expected_drops;
        require(dut.io_mismatchDropCount == expected_drops,
                std::string("sidecar mismatch count wrong for ") + name);
        require(dut.io_sidecarOccupiedMask == 1,
                std::string("sidecar mismatch destroyed owner for ") + name);
    }
    require(dut.io_staleGenerationDropCount == 1,
            "stale generation did not increment dedicated counter exactly once");
    transfer(dut, id, scenario);
    require(dut.io_transferCount == 1, "exact transfer count was not one");
}

bool trace_matches(const Trace &trace, const std::vector<std::uint8_t> &arch,
                   const std::vector<std::uint64_t> &addresses,
                   const std::vector<std::uint64_t> &data,
                   std::uint64_t target, std::uint16_t generation,
                   std::uint8_t slot) {
    return trace.arch == arch && trace.addresses == addresses && trace.data == data &&
           trace.target == target && trace.generation == generation && trace.slot == slot;
}

void run_classifier_adversarial_suite(const Trace &captured, const Scenario &scenario,
                                      std::uint16_t generation, std::uint8_t slot) {
    const auto expected_arch = legal_ring(scenario.range_m, scenario.range_n);
    std::vector<std::uint64_t> expected_addresses;
    std::vector<std::uint64_t> expected_data;
    for (std::size_t index = 0; index < expected_arch.size(); ++index) {
        expected_addresses.push_back(child_address(scenario, index));
        expected_data.push_back(
            0xa000000000000000ULL |
            (static_cast<std::uint64_t>(scenario.opcode) << 16) | index);
    }
    const auto expected_target = expected_data.front();
    require(trace_matches(captured, expected_arch, expected_addresses, expected_data,
                          expected_target, generation, slot),
            "DUT-observation classifier rejected the wrapped positive");

    Trace mutation = captured;
    mutation.generation++;
    require(!trace_matches(mutation, expected_arch, expected_addresses, expected_data,
                           expected_target, generation, slot),
            "classifier accepted mutated identity generation");
    mutation = captured;
    std::swap(mutation.arch[0], mutation.arch[1]);
    require(!trace_matches(mutation, expected_arch, expected_addresses, expected_data,
                           expected_target, generation, slot),
            "classifier accepted mutated CTGen order");
    mutation = captured;
    mutation.data[2] ^= 0x40;
    require(!trace_matches(mutation, expected_arch, expected_addresses, expected_data,
                           expected_target, generation, slot),
            "classifier accepted mutated child data");
    mutation = captured;
    mutation.target ^= 8;
    require(!trace_matches(mutation, expected_arch, expected_addresses, expected_data,
                           expected_target, generation, slot),
            "classifier accepted mutated terminal target");
}

void prepare_to_completion(VBlockControlTemplateSequencerBehaviorProbe &dut,
                           const Scenario &scenario, const Identity &id) {
    reset(dut);
    enqueue(dut, id, scenario, 3);
    transfer(dut, id, scenario);
    advance_to_gather(dut, scenario.name);
    (void)gather_children(dut, id, scenario, 3, false);
}

void prepare_to_commit(VBlockControlTemplateSequencerBehaviorProbe &dut,
                       const Scenario &scenario, const Identity &id) {
    prepare_to_completion(dut, scenario, id);
    clear_controls(dut);
    dut.io_selectedTemplate = 1;
    tick(dut);
    clear_controls(dut);
    require(dut.io_state == 4, std::string(scenario.name) + ": race prep missed commit state");
}

void prepare_to_burst(VBlockControlTemplateSequencerBehaviorProbe &dut,
                      const Scenario &scenario, const Identity &id) {
    prepare_to_commit(dut, scenario, id);
    set_commit_identity(dut, id);
    dut.io_parentCommitValid = 1;
    tick(dut);
    clear_controls(dut);
    if (is_entry(scenario)) {
        dut.io_storeGrant = 1;
        tick(dut);
        clear_controls(dut);
        require(dut.io_state == 6, "race prep missed store burst");
    } else {
        require(dut.io_state == 7, "race prep missed RF burst");
    }
}

std::uint32_t run_race_suite(VBlockControlTemplateSequencerBehaviorProbe &dut) {
    std::uint32_t red = 0;
    constexpr std::uint32_t kSidecarCancel = 1U << 0;
    constexpr std::uint32_t kExternalCompletion = 1U << 1;
    constexpr std::uint32_t kGlobalCompletion = 1U << 2;
    constexpr std::uint32_t kGlobalStore = 1U << 3;
    constexpr std::uint32_t kGlobalRf = 1U << 4;
    constexpr std::uint32_t kGlobalSp = 1U << 5;
    constexpr std::uint32_t kGlobalLookup = 1U << 6;
    constexpr std::uint32_t kExternalLookup = 1U << 7;

    const Scenario entry{"race-entry", kFentry, 10, 10, 0x50000, 0x80, 0x73440};
    const Scenario exit{"race-exit", kFexit, 10, 10, 0x50000, 0x80, 0x73440};
    const Scenario fret{"race-fret", kFretStk, 10, 10, 0x50000, 0x80, 0x73440};
    const Identity entry_id = make_identity(kFentry, 51, 21);
    const Identity exit_id = make_identity(kFexit, 52, 22);
    const Identity fret_id = make_identity(kFretStk, 53, 23);

    reset(dut);
    enqueue(dut, entry_id, entry, 3);
    clear_controls(dut);
    set_issue_identity(dut, entry_id);
    set_cancel_identity(dut, entry_id);
    dut.io_oldSp = entry.old_sp;
    dut.io_srcData0 = entry.src0;
    dut.io_issueValid = 1;
    dut.io_cancelValid = 1;
    eval(dut);
    const bool sidecar_fired = dut.io_parentTransfer || dut.io_parentRequestValid;
    tick(dut);
    if (sidecar_fired || dut.io_activeValid || dut.io_transferCount != 0 ||
        dut.io_sidecarOccupiedMask != 0 || dut.io_sidecarDecodeFenceMask != 0 ||
        dut.io_sidecarIssueFenceMask != 0 || dut.io_sidecarMemoryFenceMask != 0 ||
        dut.io_decodeFence || dut.io_issueFence || dut.io_memoryFence) {
        red |= kSidecarCancel;
    }

    prepare_to_completion(dut, entry, entry_id);
    clear_controls(dut);
    Identity external = entry_id;
    external.generation++;
    set_recovery_identity(dut, external);
    dut.io_recoveryValid = 1;
    dut.io_recoveryKillsActive = 1;
    dut.io_selectedTemplate = 1;
    eval(dut);
    if (dut.io_completionValid || dut.io_parentIssueRelease || dut.io_terminal ||
        dut.io_spPublishValid || dut.io_storeObservationValid ||
        dut.io_lookupObservationValid) {
        red |= kExternalCompletion;
    }
    tick(dut);
    require(!dut.io_activeValid && !dut.io_decodeFence && !dut.io_issueFence &&
                !dut.io_memoryFence,
            "eligible external cancellation did not clear pre-completion owner");

    prepare_to_completion(dut, entry, entry_id);
    clear_controls(dut);
    dut.io_selectedTemplate = 1;
    dut.io_globalClear = 1;
    eval(dut);
    if (dut.io_completionValid || dut.io_parentIssueRelease || dut.io_terminal ||
        dut.io_spPublishValid || dut.io_storeObservationValid ||
        dut.io_lookupObservationValid) {
        red |= kGlobalCompletion;
    }
    tick(dut);
    require(!dut.io_activeValid, "global clear did not clear completion owner");

    prepare_to_burst(dut, entry, entry_id);
    clear_controls(dut);
    dut.io_storeReady = 1;
    dut.io_globalClear = 1;
    eval(dut);
    if (dut.io_storeRequestValid || dut.io_storeObservationValid ||
        dut.io_acceptedStoreCount != 0) {
        red |= kGlobalStore;
    }
    tick(dut);
    require(!dut.io_activeValid, "global clear did not clear store owner");

    prepare_to_burst(dut, exit, exit_id);
    clear_controls(dut);
    dut.io_rfWriteReady = 1;
    dut.io_globalClear = 1;
    eval(dut);
    if (dut.io_rfWriteRequestValid || dut.io_acceptedRfWriteCount != 0) {
        red |= kGlobalRf;
    }
    tick(dut);
    require(!dut.io_activeValid, "global clear did not clear RF owner");

    prepare_to_commit(dut, entry, entry_id);
    clear_controls(dut);
    set_commit_identity(dut, entry_id);
    dut.io_parentCommitValid = 1;
    dut.io_globalClear = 1;
    eval(dut);
    if (dut.io_spPublishValid || dut.io_parentIssueRelease || dut.io_terminal) {
        red |= kGlobalSp;
    }
    tick(dut);
    require(!dut.io_activeValid, "global clear did not clear SP-publication owner");

    reset(dut);
    enqueue(dut, fret_id, fret, 3);
    transfer(dut, fret_id, fret);
    advance_to_gather(dut, fret.name);
    clear_controls(dut);
    dut.io_loadReady = 1;
    tick(dut);
    clear_controls(dut);
    set_load_response_identity(dut, fret_id);
    dut.io_loadResponseValid = 1;
    dut.io_loadResponseChildIndex = 0;
    dut.io_loadResponseAddr = child_address(fret, 0);
    dut.io_loadResponseData = 0x73440;
    dut.io_globalClear = 1;
    eval(dut);
    if (dut.io_lookupObservationValid) {
        red |= kGlobalLookup;
    }
    tick(dut);
    require(!dut.io_activeValid, "global clear did not clear lookup owner");

    reset(dut);
    enqueue(dut, fret_id, fret, 3);
    transfer(dut, fret_id, fret);
    advance_to_gather(dut, fret.name);
    clear_controls(dut);
    dut.io_loadReady = 1;
    tick(dut);
    clear_controls(dut);
    set_load_response_identity(dut, fret_id);
    set_recovery_identity(dut, external);
    dut.io_loadResponseValid = 1;
    dut.io_loadResponseChildIndex = 0;
    dut.io_loadResponseAddr = child_address(fret, 0);
    dut.io_loadResponseData = 0x73440;
    dut.io_recoveryValid = 1;
    dut.io_recoveryKillsActive = 1;
    eval(dut);
    if (dut.io_lookupObservationValid) {
        red |= kExternalLookup;
    }
    tick(dut);
    require(!dut.io_activeValid, "external cancellation did not clear lookup owner");

    const std::uint32_t expected = kSidecarCancel | kExternalCompletion |
                                   kGlobalCompletion | kGlobalStore | kGlobalRf |
                                   kGlobalSp | kGlobalLookup | kExternalLookup;
    if (red != 0 && red != expected) {
        throw std::runtime_error("race classifier observed only a partial known-red mask: " +
                                 std::to_string(red));
    }
    return red;
}

bool env_is_one(const char *name) {
    const char *value = std::getenv(name);
    return value != nullptr && std::string(value) == "1";
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VBlockControlTemplateSequencerBehaviorProbe dut;
    try {
        run_sidecar_match_suite(dut);

        const Scenario entry{"fentry-normal", kFentry, 10, 12, 0x60000, 0xa0, 0x73440};
        const Scenario exit{"fexit-wrapped", kFexit, 22, 3, 0x60000, 0xa0, 0x73440};
        const Scenario fret_ra{"fret-ra-normal", kFretRa, 10, 11, 0x60000, 0xa0, 0x73440};
        const Scenario fret_stk{"fret-stk-wrapped", kFretStk, 22, 3, 0x60000, 0xa0, 0x73440};

        (void)run_scenario(dut, entry, 61, 25, 7);
        (void)run_scenario(dut, exit, 62, 26, 8);
        (void)run_scenario(dut, fret_ra, 63, 27, 9);
        const Trace wrapped = run_scenario(dut, fret_stk, 64, 28, 10);
        run_classifier_adversarial_suite(wrapped, fret_stk, 64, 28);

        if (env_is_one("SELF_TEST_ONLY")) {
            std::cout << "bctrl-template-sequencer-behavior-probe: self-test PASS\n";
            return 0;
        }

        const std::uint32_t red_mask = run_race_suite(dut);
        if (env_is_one("EXPECT_CURRENT_RED")) {
            if (red_mask == 0) {
                std::cerr << "bctrl-template-sequencer-behavior-probe: expected current red "
                             "but DUT is future-green\n";
                return 2;
            }
            std::cout << "bctrl-template-sequencer-behavior-probe: expected-current-red PASS "
                      << "mask=" << red_mask << '\n';
            return 0;
        }
        if (red_mask != 0) {
            std::cerr << "bctrl-template-sequencer-behavior-probe: current-red mask="
                      << red_mask << '\n';
            return 1;
        }
        std::cout << "bctrl-template-sequencer-behavior-probe: future-green PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "bctrl-template-sequencer-behavior-probe: setup/unexpected failure: "
                  << error.what() << '\n';
        return 2;
    }
}
