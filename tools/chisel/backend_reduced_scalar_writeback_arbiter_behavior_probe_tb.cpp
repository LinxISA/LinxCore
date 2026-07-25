#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>

#include "VReducedScalarWritebackArbiterBehaviorProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint8_t kExecuteTag = 5;
constexpr std::uint8_t kReplayTag = 19;
constexpr std::uint8_t kTemplateTag = 42;
constexpr std::uint64_t kExecuteData = 0x1111222233334444ULL;
constexpr std::uint64_t kReplayData = 0x5555666677778888ULL;
constexpr std::uint64_t kTemplateData = 0x9999aaaabbbbccccULL;

struct Source {
    bool enable;
    bool valid;
    std::uint8_t tag;
    std::uint64_t data;
};

struct Expected {
    bool valid;
    std::uint8_t tag;
    std::uint64_t data;
    bool execute;
    bool replay;
    bool templ;
};

[[noreturn]] void fail(const std::string &message) {
    std::cerr << "backend-reduced-scalar-writeback-arbiter-behavior-probe: FAIL: "
              << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

void drive(VReducedScalarWritebackArbiterBehaviorProbe &dut,
           const Source &execute,
           const Source &replay,
           const Source &templ,
           bool rf_port_ready) {
    dut.io_executeValid = execute.valid;
    dut.io_executeTag = execute.tag;
    dut.io_executeData = execute.data;
    dut.io_replayEnable = replay.enable;
    dut.io_replayValid = replay.valid;
    dut.io_replayTag = replay.tag;
    dut.io_replayData = replay.data;
    dut.io_templateEnable = templ.enable;
    dut.io_templateValid = templ.valid;
    dut.io_templateTag = templ.tag;
    dut.io_templateData = templ.data;
    dut.io_rfPortReady = rf_port_ready;
}

Expected expected_for(const Source &execute,
                      const Source &replay,
                      const Source &templ) {
    const bool replay_candidate = replay.enable && replay.valid;
    const bool template_candidate = templ.enable && templ.valid;
    if (execute.valid) {
        return {true, execute.tag, execute.data, true, false, false};
    }
    if (replay_candidate) {
        return {true, replay.tag, replay.data, false, true, false};
    }
    if (template_candidate) {
        return {true, templ.tag, templ.data, false, false, true};
    }
    return {false, 0, 0, false, false, false};
}

void check_outputs(VReducedScalarWritebackArbiterBehaviorProbe &dut,
                   const Source &execute,
                   const Source &replay,
                   const Source &templ,
                   bool rf_port_ready,
                   const std::string &label) {
    dut.eval();
    const Expected expected = expected_for(execute, replay, templ);
    const bool replay_candidate = replay.enable && replay.valid;
    const bool template_candidate = templ.enable && templ.valid;
    const unsigned selection_count =
        static_cast<unsigned>(dut.io_selectedExecute) +
        static_cast<unsigned>(dut.io_selectedReplay) +
        static_cast<unsigned>(dut.io_selectedTemplate);

    require(dut.io_writeValid == expected.valid, label + ": write-valid mismatch");
    require(dut.io_writeTag == expected.tag, label + ": write-tag mismatch");
    require(dut.io_writeData == expected.data, label + ": write-data mismatch");
    require(dut.io_selectedExecute == expected.execute, label + ": execute selection mismatch");
    require(dut.io_selectedReplay == expected.replay, label + ": replay selection mismatch");
    require(dut.io_selectedTemplate == expected.templ, label + ": template selection mismatch");
    require(selection_count == static_cast<unsigned>(expected.valid),
            label + ": selection is not idle-or-one-hot");

    require(dut.io_replayBlockedByDisabled == (!replay.enable && replay.valid),
            label + ": replay disabled-blocker mismatch");
    require(dut.io_replayBlockedByExecute == (replay_candidate && execute.valid),
            label + ": replay execute-blocker mismatch");
    require(dut.io_templateBlockedByDisabled == (!templ.enable && templ.valid),
            label + ": template disabled-blocker mismatch");
    require(dut.io_templateBlockedByExecute == (template_candidate && execute.valid),
            label + ": template execute-blocker mismatch");
    require(dut.io_templateBlockedByReplay ==
                (template_candidate && !execute.valid && replay_candidate),
            label + ": template replay-blocker mismatch");

    require(dut.io_writeFire == (expected.valid && rf_port_ready),
            label + ": RF-port fire mismatch");
    require(dut.io_templateAdvance == (expected.templ && rf_port_ready),
            label + ": template advancement mismatch");
    require(!dut.io_templateAdvance ||
                (dut.io_selectedTemplate && dut.io_writeValid && rf_port_ready),
            label + ": template advanced without selected RF-port fire");
}

void prove_all_enable_and_valid_combinations(
    VReducedScalarWritebackArbiterBehaviorProbe &dut) {
    for (unsigned bits = 0; bits < 32; ++bits) {
        const Source execute{true, (bits & 16U) != 0, kExecuteTag, kExecuteData};
        const Source replay{
            (bits & 8U) != 0, (bits & 4U) != 0, kReplayTag, kReplayData};
        const Source templ{
            (bits & 2U) != 0, (bits & 1U) != 0, kTemplateTag, kTemplateData};
        for (unsigned ready = 0; ready < 2; ++ready) {
            drive(dut, execute, replay, templ, ready != 0);
            check_outputs(
                dut,
                execute,
                replay,
                templ,
                ready != 0,
                "enable-valid-combination-" + std::to_string(bits) +
                    "-ready-" + std::to_string(ready));
        }
    }
}

void prove_unselected_payload_independence(
    VReducedScalarWritebackArbiterBehaviorProbe &dut) {
    Source execute{true, true, kExecuteTag, kExecuteData};
    Source replay{true, true, kReplayTag, kReplayData};
    Source templ{true, true, kTemplateTag, kTemplateData};
    drive(dut, execute, replay, templ, true);
    check_outputs(dut, execute, replay, templ, true, "execute-before-payload-mutation");
    replay = {true, true, 63, 0x0102030405060708ULL};
    templ = {true, true, 1, 0xf0e0d0c0b0a09080ULL};
    drive(dut, execute, replay, templ, true);
    check_outputs(dut, execute, replay, templ, true, "execute-after-payload-mutation");

    execute = {true, false, 62, 0xdeadbeefdeadbeefULL};
    replay = {true, true, kReplayTag, kReplayData};
    templ = {true, true, kTemplateTag, kTemplateData};
    drive(dut, execute, replay, templ, true);
    check_outputs(dut, execute, replay, templ, true, "replay-before-payload-mutation");
    execute = {true, false, 2, 0x1212121212121212ULL};
    templ = {true, true, 61, 0x3434343434343434ULL};
    drive(dut, execute, replay, templ, true);
    check_outputs(dut, execute, replay, templ, true, "replay-after-payload-mutation");

    execute = {true, false, 60, 0x5656565656565656ULL};
    replay = {true, false, 59, 0x7878787878787878ULL};
    templ = {true, true, kTemplateTag, kTemplateData};
    drive(dut, execute, replay, templ, true);
    check_outputs(dut, execute, replay, templ, true, "template-before-payload-mutation");
    execute = {true, false, 3, 0x9191919191919191ULL};
    replay = {false, true, 4, 0xa2a2a2a2a2a2a2a2ULL};
    drive(dut, execute, replay, templ, true);
    check_outputs(dut, execute, replay, templ, true, "template-after-payload-mutation");
}

void prove_held_template_waits_for_selection_and_fire(
    VReducedScalarWritebackArbiterBehaviorProbe &dut) {
    const Source templ{true, true, kTemplateTag, kTemplateData};
    Source execute{true, true, kExecuteTag, kExecuteData};
    Source replay{true, true, kReplayTag, kReplayData};

    drive(dut, execute, replay, templ, true);
    check_outputs(dut, execute, replay, templ, true, "template-held-under-execute");
    require(!dut.io_templateAdvance, "template advanced while execute blocked it");

    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
    check_outputs(dut, execute, replay, templ, true, "template-stable-after-execute-cycle");

    execute.valid = false;
    drive(dut, execute, replay, templ, true);
    check_outputs(dut, execute, replay, templ, true, "template-held-under-replay");
    require(!dut.io_templateAdvance, "template advanced while replay blocked it");

    replay.valid = false;
    drive(dut, execute, replay, templ, false);
    check_outputs(dut, execute, replay, templ, false, "template-selected-rf-not-ready");
    require(!dut.io_templateAdvance, "template advanced without RF-port readiness");

    drive(dut, execute, replay, templ, true);
    check_outputs(dut, execute, replay, templ, true, "template-selected-rf-fire");
    require(dut.io_templateAdvance, "template did not advance on selected RF-port fire");
}

void prove_idle_zero(VReducedScalarWritebackArbiterBehaviorProbe &dut) {
    const Source execute{true, false, 63, 0x1111111111111111ULL};
    const Source replay{false, true, 62, 0x2222222222222222ULL};
    const Source templ{false, true, 61, 0x3333333333333333ULL};
    drive(dut, execute, replay, templ, true);
    check_outputs(dut, execute, replay, templ, true, "idle-disabled-stale-payloads");
    require(!dut.io_writeValid && dut.io_writeTag == 0 && dut.io_writeData == 0,
            "idle outputs were not zero");
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    if (argc != 1) {
        fail("unknown argument");
    }

    VReducedScalarWritebackArbiterBehaviorProbe dut;
    dut.clock = 0;
    dut.reset = 0;

    prove_all_enable_and_valid_combinations(dut);
    prove_unselected_payload_independence(dut);
    prove_held_template_waits_for_selection_and_fire(dut);
    prove_idle_zero(dut);

    std::cout
        << "backend-reduced-scalar-writeback-arbiter-behavior-probe: PASS "
        << "(32 enable/valid combinations x 2 RF-ready states, priority, blockers, "
           "one-hot, payload independence, held template, advancement, idle zero)\n";
    return 0;
}
