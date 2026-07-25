#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

#include "VTemplateRecoveryQualificationBehaviorProbe.h"
#include "verilated.h"

namespace {

struct Identity {
    std::uint16_t generation = 0x1234;
    std::uint8_t stid = 1;
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
};

enum class Mismatch {
    Stid,
    BidValid,
    BidWrap,
    BidValue,
    GidValid,
    GidWrap,
    GidValue,
    RidValid,
    RidWrap,
    RidValue,
    Slot,
    Generation,
};

void require(bool condition, const std::string &message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void eval(VTemplateRecoveryQualificationBehaviorProbe &dut) {
    dut.eval();
}

void tick(VTemplateRecoveryQualificationBehaviorProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
    dut.clock = 0;
    eval(dut);
}

#define DEFINE_IDENTITY_DRIVER(NAME, PREFIX)                                             \
    void NAME(VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &id) {    \
        dut.PREFIX##_generation = id.generation;                                          \
        dut.PREFIX##_stid = id.stid;                                                      \
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
    }

DEFINE_IDENTITY_DRIVER(set_active_identity, io_activeIdentity)
DEFINE_IDENTITY_DRIVER(set_recovery_identity, io_recoveryIdentity)

#undef DEFINE_IDENTITY_DRIVER

Identity mismatched(Identity id, Mismatch mismatch) {
    switch (mismatch) {
    case Mismatch::Stid:
        id.stid ^= 1;
        break;
    case Mismatch::BidValid:
        id.bid_valid = !id.bid_valid;
        break;
    case Mismatch::BidWrap:
        id.bid_wrap = !id.bid_wrap;
        break;
    case Mismatch::BidValue:
        id.bid_value ^= 1;
        break;
    case Mismatch::GidValid:
        id.gid_valid = !id.gid_valid;
        break;
    case Mismatch::GidWrap:
        id.gid_wrap = !id.gid_wrap;
        break;
    case Mismatch::GidValue:
        id.gid_value ^= 1;
        break;
    case Mismatch::RidValid:
        id.rid_valid = !id.rid_valid;
        break;
    case Mismatch::RidWrap:
        id.rid_wrap = !id.rid_wrap;
        break;
    case Mismatch::RidValue:
        id.rid_value ^= 1;
        break;
    case Mismatch::Slot:
        id.rob_slot ^= 1;
        break;
    case Mismatch::Generation:
        id.generation ^= 1;
        break;
    }
    return id;
}

void clear_controls(VTemplateRecoveryQualificationBehaviorProbe &dut) {
    dut.io_activeValid = 1;
    dut.io_completed = 0;
    dut.io_committed = 0;
    dut.io_recoveryValid = 0;
    dut.io_recoveryKillsActive = 0;
    dut.io_sourceResolved = 0;
    dut.io_restartValid = 0;
    dut.io_globalClear = 0;
}

void initialize(VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &active) {
    clear_controls(dut);
    set_active_identity(dut, active);
    set_recovery_identity(dut, active);
}

void reset(VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &active) {
    initialize(dut, active);
    dut.reset = 1;
    tick(dut);
    tick(dut);
    dut.reset = 0;
    eval(dut);
    require(!dut.io_selfRestartPending, "reset retained a self-restart token");
}

void require_preserve(const VTemplateRecoveryQualificationBehaviorProbe &dut,
                      const std::string &context) {
    require(dut.io_preserveActive, context + ": active template was not preserved");
    require(!dut.io_clearActive, context + ": active template was cleared");
}

void set_retained_phase(VTemplateRecoveryQualificationBehaviorProbe &dut, bool committed) {
    dut.io_completed = 1;
    dut.io_committed = committed;
}

std::string phase_name(bool committed) {
    return committed ? "committed-drain" : "completed-before-commit";
}

void require_inactive_outputs(const VTemplateRecoveryQualificationBehaviorProbe &dut,
                              const std::string &context) {
    require(!dut.io_recoverySelf && !dut.io_externalPreCompletionKill &&
                !dut.io_ignoredRecovery && !dut.io_illegalDiscardAttempt &&
                !dut.io_selfRestartPending && !dut.io_selfRestartQualified &&
                !dut.io_preserveActive && !dut.io_clearActive,
            context + ": inactive owner produced recovery policy");
}

void prove_precompletion_self_is_impossible(
    VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &active) {
    reset(dut, active);
    dut.io_recoveryValid = 1;
    dut.io_recoveryKillsActive = 1;
    dut.io_sourceResolved = 1;
    dut.io_restartValid = 1;
    eval(dut);
    require(!dut.io_recoverySelf && !dut.io_selfRestartPending &&
                !dut.io_selfRestartQualified,
            "pre-completion matching self was positively classified");
    require(dut.io_ignoredRecovery,
            "pre-completion impossible matching self was not explicitly ignored");
    require(!dut.io_externalPreCompletionKill && !dut.io_illegalDiscardAttempt,
            "pre-completion matching self aliased an external recovery");
    require_preserve(dut, "pre-completion impossible matching self");
    tick(dut);

    clear_controls(dut);
    dut.io_restartValid = 1;
    eval(dut);
    require(!dut.io_selfRestartPending && !dut.io_selfRestartQualified,
            "pre-completion matching self retained a restart token");
    require_preserve(dut, "pre-completion restart without a token");
}

void prove_same_cycle_self_restart_in_retained_phases(
    VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &active) {
    for (const bool committed : {false, true}) {
        reset(dut, active);
        set_retained_phase(dut, committed);
        dut.io_recoveryValid = 1;
        dut.io_recoveryKillsActive = 1;
        dut.io_sourceResolved = 1;
        dut.io_restartValid = 1;
        eval(dut);
        const auto phase = phase_name(committed);
        require(dut.io_recoverySelf, phase + ": matching self recovery was not classified");
        require(dut.io_selfRestartQualified,
                phase + ": same-cycle self restart was not qualified");
        require(dut.io_selfRestartPending,
                phase + ": same-cycle restart did not expose its pending token");
        require_preserve(dut, phase + " same-cycle self restart");
        require(!dut.io_externalPreCompletionKill && !dut.io_illegalDiscardAttempt &&
                    !dut.io_ignoredRecovery,
                phase + ": same-cycle self restart overlapped another classification");
        tick(dut);

        clear_controls(dut);
        set_retained_phase(dut, committed);
        eval(dut);
        require(!dut.io_selfRestartPending && !dut.io_selfRestartQualified,
                phase + ": same-cycle self token did not drain exactly once");
        require_preserve(dut, phase + " after same-cycle self restart");
    }
}

void prove_retained_self_restart_in_retained_phases(
    VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &active) {
    for (const bool committed : {false, true}) {
        reset(dut, active);
        set_retained_phase(dut, committed);
        dut.io_recoveryValid = 1;
        eval(dut);
        const auto phase = phase_name(committed);
        require(dut.io_recoverySelf && dut.io_selfRestartPending,
                phase + ": self recovery did not create a retained restart token");
        require(!dut.io_selfRestartQualified,
                phase + ": self recovery qualified without restart");
        require_preserve(dut, phase + " retained self recovery");
        tick(dut);

        clear_controls(dut);
        set_retained_phase(dut, committed);
        dut.io_sourceResolved = 1;
        eval(dut);
        require(dut.io_selfRestartPending,
                phase + ": source resolution erased retained self ownership");
        require(!dut.io_selfRestartQualified,
                phase + ": source resolution synthesized restart qualification");
        require_preserve(dut, phase + " source-resolved retained self recovery");
        tick(dut);

        clear_controls(dut);
        set_retained_phase(dut, committed);
        dut.io_restartValid = 1;
        eval(dut);
        require(dut.io_selfRestartPending && dut.io_selfRestartQualified,
                phase + ": next-cycle restart did not consume retained self token");
        require_preserve(dut, phase + " next-cycle self restart");
        tick(dut);

        clear_controls(dut);
        set_retained_phase(dut, committed);
        eval(dut);
        require(!dut.io_selfRestartPending && !dut.io_selfRestartQualified,
                phase + ": retained self token qualified more than once");
        require_preserve(dut, phase + " after retained self restart");
    }
}

void prove_each_identity_mismatch(
    VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &active) {
    const std::vector<std::pair<Mismatch, const char *>> cases = {
        {Mismatch::Stid, "STID"},
        {Mismatch::BidValid, "BID valid"},
        {Mismatch::BidWrap, "BID wrap"},
        {Mismatch::BidValue, "BID value"},
        {Mismatch::GidValid, "GID valid"},
        {Mismatch::GidWrap, "GID wrap"},
        {Mismatch::GidValue, "GID value"},
        {Mismatch::RidValid, "RID valid"},
        {Mismatch::RidWrap, "RID wrap"},
        {Mismatch::RidValue, "RID value"},
        {Mismatch::Slot, "parent slot"},
        {Mismatch::Generation, "generation"},
    };
    for (const auto &[mismatch, name] : cases) {
        reset(dut, active);
        set_retained_phase(dut, false);
        const auto other = mismatched(active, mismatch);
        set_recovery_identity(dut, other);
        dut.io_recoveryValid = 1;
        eval(dut);
        require(!dut.io_recoverySelf, std::string(name) + " mismatch aliased self");
        require(dut.io_ignoredRecovery,
                std::string(name) + " unrelated recovery was not ignored");
        require_preserve(dut, std::string(name) + " mismatch");
        require(!dut.io_selfRestartPending && !dut.io_selfRestartQualified &&
                    !dut.io_externalPreCompletionKill && !dut.io_illegalDiscardAttempt,
                std::string(name) + " mismatch produced another classification");
    }
}

void prove_external_precompletion_policy(
    VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &active) {
    reset(dut, active);
    set_recovery_identity(dut, mismatched(active, Mismatch::Generation));
    dut.io_recoveryValid = 1;
    dut.io_recoveryKillsActive = 1;
    eval(dut);
    require(dut.io_externalPreCompletionKill,
            "eligible external pre-completion recovery did not kill");
    require(dut.io_clearActive && !dut.io_preserveActive,
            "eligible external pre-completion recovery did not request clear");
    require(!dut.io_ignoredRecovery && !dut.io_illegalDiscardAttempt &&
                !dut.io_selfRestartPending,
            "eligible external pre-completion kill overlapped another classification");

    reset(dut, active);
    set_recovery_identity(dut, mismatched(active, Mismatch::Generation));
    dut.io_recoveryValid = 1;
    eval(dut);
    require(dut.io_ignoredRecovery, "younger/unrelated recovery was not ignored");
    require_preserve(dut, "younger/unrelated recovery");
}

void prove_postcompletion_preservation(
    VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &active) {
    for (const bool committed : {false, true}) {
        reset(dut, active);
        set_recovery_identity(dut, mismatched(active, Mismatch::BidValue));
        set_retained_phase(dut, committed);
        dut.io_recoveryValid = 1;
        dut.io_recoveryKillsActive = 1;
        eval(dut);
        const std::string phase = committed ? "committed" : "completed";
        require(dut.io_illegalDiscardAttempt,
                phase + " external discard was not diagnosed");
        require_preserve(dut, phase + " external discard");
        require(!dut.io_externalPreCompletionKill && !dut.io_ignoredRecovery &&
                    !dut.io_selfRestartPending,
                phase + " external discard overlapped another classification");
    }
}

void prove_inactive_owner_matrix(
    VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &active) {
    reset(dut, active);
    dut.io_activeValid = 0;
    set_retained_phase(dut, false);
    dut.io_recoveryValid = 1;
    dut.io_recoveryKillsActive = 1;
    dut.io_sourceResolved = 1;
    eval(dut);
    require_inactive_outputs(dut, "inactive matching recovery");

    reset(dut, active);
    dut.io_activeValid = 0;
    set_recovery_identity(dut, mismatched(active, Mismatch::Generation));
    dut.io_recoveryValid = 1;
    dut.io_recoveryKillsActive = 1;
    eval(dut);
    require_inactive_outputs(dut, "inactive external recovery");

    reset(dut, active);
    dut.io_activeValid = 0;
    dut.io_sourceResolved = 1;
    dut.io_restartValid = 1;
    eval(dut);
    require_inactive_outputs(dut, "inactive restart");

    reset(dut, active);
    dut.io_activeValid = 0;
    dut.io_globalClear = 1;
    eval(dut);
    require(dut.io_clearActive && !dut.io_preserveActive,
            "inactive global clear did not remain dominant");
    require(!dut.io_recoverySelf && !dut.io_externalPreCompletionKill &&
                !dut.io_ignoredRecovery && !dut.io_illegalDiscardAttempt &&
                !dut.io_selfRestartPending && !dut.io_selfRestartQualified,
            "inactive global clear leaked recovery policy");
}

void prove_global_clear_dominates(
    VTemplateRecoveryQualificationBehaviorProbe &dut, const Identity &active) {
    reset(dut, active);
    set_retained_phase(dut, false);
    dut.io_recoveryValid = 1;
    eval(dut);
    tick(dut);
    require(dut.io_selfRestartPending, "global-clear setup did not retain self token");

    clear_controls(dut);
    dut.io_activeValid = 0;
    dut.io_sourceResolved = 1;
    dut.io_restartValid = 1;
    dut.io_globalClear = 1;
    eval(dut);
    require(dut.io_clearActive && !dut.io_preserveActive,
            "global clear did not dominate preservation");
    require(!dut.io_recoverySelf && !dut.io_externalPreCompletionKill &&
                !dut.io_ignoredRecovery && !dut.io_illegalDiscardAttempt &&
                !dut.io_selfRestartPending && !dut.io_selfRestartQualified,
            "global clear leaked recovery classification or pending state");
    tick(dut);
    clear_controls(dut);
    dut.io_activeValid = 0;
    eval(dut);
    require(!dut.io_selfRestartPending, "global clear did not clear retained self token");
    require_inactive_outputs(dut, "inactive state after global clear");
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VTemplateRecoveryQualificationBehaviorProbe dut;
    const Identity active;

    try {
        prove_precompletion_self_is_impossible(dut, active);
        prove_same_cycle_self_restart_in_retained_phases(dut, active);
        prove_retained_self_restart_in_retained_phases(dut, active);
        prove_each_identity_mismatch(dut, active);
        prove_external_precompletion_policy(dut, active);
        prove_postcompletion_preservation(dut, active);
        prove_inactive_owner_matrix(dut, active);
        prove_global_clear_dominates(dut, active);
    } catch (const std::exception &error) {
        std::cerr << "recovery-template-qualification-behavior-probe: FAIL: "
                  << error.what() << '\n';
        return EXIT_FAILURE;
    }

    std::cout << "recovery-template-qualification-behavior-probe: PASS\n";
    return EXIT_SUCCESS;
}
