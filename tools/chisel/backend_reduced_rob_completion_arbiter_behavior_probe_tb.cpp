#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>

#include "VReducedRobCompletionArbiterBehaviorProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint64_t kExecuteToken = 0x1111222233334444ULL;
constexpr std::uint64_t kReplayToken = 0x5555666677778888ULL;
constexpr std::uint64_t kTemplateToken = 0x9999aaaabbbbccccULL;
constexpr std::uint64_t kSignatureXor = 0xa5a5a5a5a5a5a5a5ULL;

struct Source {
    bool valid;
    std::uint8_t slot;
    bool row_valid;
    std::uint64_t token;
};

struct Expected {
    bool valid;
    std::uint8_t slot;
    bool row_valid;
    std::uint64_t token;
    bool execute;
    bool replay;
    bool service;
    bool templ;
    std::uint8_t source_mask;
    bool row_payload;
};

[[noreturn]] void fail(const std::string &message) {
    std::cerr << "backend-reduced-rob-completion-arbiter-behavior-probe: FAIL: "
              << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

void drive(VReducedRobCompletionArbiterBehaviorProbe &dut,
           const Source &execute,
           const Source &replay,
           const Source &service,
           const Source &templ,
           std::uint8_t template_parent_slot) {
    dut.io_executeValid = execute.valid;
    dut.io_executeSlot = execute.slot;
    dut.io_executeRowValid = execute.row_valid;
    dut.io_executeRowToken = execute.token;
    dut.io_replayValid = replay.valid;
    dut.io_replaySlot = replay.slot;
    dut.io_replayRowValid = replay.row_valid;
    dut.io_replayRowToken = replay.token;
    dut.io_serviceValid = service.valid;
    dut.io_serviceSlot = service.slot;
    dut.io_serviceRowValid = service.row_valid;
    dut.io_serviceRowToken = service.token;
    dut.io_templateValid = templ.valid;
    dut.io_templateSlot = templ.slot;
    dut.io_templateRowValid = templ.row_valid;
    dut.io_templateRowToken = templ.token;
    dut.io_templateParentSlot = template_parent_slot;
}

Expected expected_for(
    const Source &execute,
    const Source &replay,
    const Source &service,
    const Source &templ) {
    if (execute.valid) {
        return {true, execute.slot, execute.row_valid, execute.token, true, false, false, false, 1, true};
    }
    if (replay.valid) {
        return {true, replay.slot, replay.row_valid, replay.token, false, true, false, false, 2, true};
    }
    if (service.valid) {
        return {true, service.slot, service.row_valid, service.token, false, false, true, false, 4, true};
    }
    if (templ.valid) {
        return {true, templ.slot, templ.row_valid, templ.token, false, false, false, true, 8, true};
    }
    return {false, 0, false, 0, false, false, false, false, 0, false};
}

void check_outputs(VReducedRobCompletionArbiterBehaviorProbe &dut,
                   const Source &execute,
                   const Source &replay,
                   const Source &service,
                   const Source &templ,
                   const std::string &label) {
    dut.eval();
    const Expected expected = expected_for(execute, replay, service, templ);
    const unsigned selection_count =
        static_cast<unsigned>(dut.io_selectedExecute) +
        static_cast<unsigned>(dut.io_selectedReplay) +
        static_cast<unsigned>(dut.io_selectedService) +
        static_cast<unsigned>(dut.io_selectedTemplate);

    require(dut.io_completeValid == expected.valid, label + ": complete-valid mismatch");
    require(dut.io_completeSlot == expected.slot, label + ": selected slot mismatch");
    require(dut.io_completeRowValid == expected.row_valid, label + ": row-valid mismatch");
    require(dut.io_completeRowToken == expected.token, label + ": row token mismatch");
    require(dut.io_completeRowSignature ==
                (expected.row_payload ? expected.token + 8 : 0),
            label + ": row signature mismatch");
    require(dut.io_completeRowMemToken ==
                (expected.row_payload ? expected.token ^ kSignatureXor : 0),
            label + ": row memory token mismatch");
    require(dut.io_selectedExecute == expected.execute, label + ": execute selection mismatch");
    require(dut.io_selectedReplay == expected.replay, label + ": replay selection mismatch");
    require(dut.io_selectedService == expected.service, label + ": service selection mismatch");
    require(dut.io_selectedTemplate == expected.templ, label + ": template selection mismatch");
    require(dut.io_selectedSourceMask == expected.source_mask, label + ": source-mask mismatch");
    require(selection_count == static_cast<unsigned>(expected.valid),
            label + ": selection is not idle-or-one-hot");
    require(dut.io_replayBlockedByExecute == (replay.valid && execute.valid),
            label + ": replay blocker mismatch");
    require(dut.io_serviceBlockedByExecute == (service.valid && execute.valid),
            label + ": service execute-blocker mismatch");
    require(dut.io_serviceBlockedByReplay ==
                (service.valid && !execute.valid && replay.valid),
            label + ": service replay-blocker mismatch");
    require(dut.io_templateBlockedByExecute == (templ.valid && execute.valid),
            label + ": template execute-blocker mismatch");
    require(dut.io_templateBlockedByReplay ==
                (templ.valid && !execute.valid && replay.valid),
            label + ": template replay-blocker mismatch");
}

void prove_all_valid_combinations(VReducedRobCompletionArbiterBehaviorProbe &dut) {
    for (unsigned bits = 0; bits < 16; ++bits) {
        const Source execute{(bits & 8U) != 0, 1, true, kExecuteToken};
        const Source replay{(bits & 4U) != 0, 3, false, kReplayToken};
        const Source service{(bits & 2U) != 0, 5, true, 0x0102030405060708ULL};
        const Source templ{(bits & 1U) != 0, 6, true, kTemplateToken};
        drive(dut, execute, replay, service, templ, templ.slot);
        check_outputs(dut, execute, replay, service, templ, "valid-combination-" + std::to_string(bits));
    }
}

void prove_row_valid_forwarding(VReducedRobCompletionArbiterBehaviorProbe &dut) {
    for (unsigned selected = 0; selected < 4; ++selected) {
        for (unsigned row_valid = 0; row_valid < 2; ++row_valid) {
            Source execute{selected == 0, 1, row_valid != 0, kExecuteToken};
            Source replay{selected == 1, 3, row_valid != 0, kReplayToken};
            Source service{selected == 2, 5, row_valid != 0, 0x0102030405060708ULL};
            Source templ{selected == 3, 6, row_valid != 0, kTemplateToken};
            drive(dut, execute, replay, service, templ, templ.slot);
            check_outputs(
                dut,
                execute,
                replay,
                service,
                templ,
                "row-valid-source-" + std::to_string(selected) + "-" +
                    std::to_string(row_valid));
        }
    }
}

void prove_unselected_payload_independence(VReducedRobCompletionArbiterBehaviorProbe &dut) {
    Source execute{true, 1, true, kExecuteToken};
    Source replay{true, 3, false, kReplayToken};
    Source service{true, 5, true, 0x0102030405060708ULL};
    Source templ{true, 6, true, kTemplateToken};
    drive(dut, execute, replay, service, templ, templ.slot);
    check_outputs(dut, execute, replay, service, templ, "execute-before-unselected-mutation");
    replay = {true, 7, true, 0x0102030405060708ULL};
    service = {true, 4, true, 0x2222333344445555ULL};
    templ = {true, 2, false, 0xf0e0d0c0b0a09080ULL};
    drive(dut, execute, replay, service, templ, templ.slot);
    check_outputs(dut, execute, replay, service, templ, "execute-after-unselected-mutation");

    execute = {false, 7, false, 0xdeadbeefdeadbeefULL};
    replay = {true, 3, true, kReplayToken};
    service = {true, 5, true, 0x0102030405060708ULL};
    templ = {true, 6, false, kTemplateToken};
    drive(dut, execute, replay, service, templ, templ.slot);
    check_outputs(dut, execute, replay, service, templ, "replay-before-unselected-mutation");
    execute = {false, 2, true, 0x1212121212121212ULL};
    service = {true, 4, true, 0x2222333344445555ULL};
    templ = {true, 5, true, 0x3434343434343434ULL};
    drive(dut, execute, replay, service, templ, templ.slot);
    check_outputs(dut, execute, replay, service, templ, "replay-after-unselected-mutation");

    execute = {false, 1, true, kExecuteToken};
    replay = {false, 3, false, kReplayToken};
    service = {true, 5, true, 0x0102030405060708ULL};
    templ = {true, 6, true, kTemplateToken};
    drive(dut, execute, replay, service, templ, templ.slot);
    check_outputs(dut, execute, replay, service, templ, "service-before-template-mutation");
    templ = {true, 2, true, 0x7878787878787878ULL};
    drive(dut, execute, replay, service, templ, templ.slot);
    check_outputs(dut, execute, replay, service, templ, "service-after-template-mutation");

    service = {false, 5, false, 0};
    templ = {true, 6, true, kTemplateToken};
    drive(dut, execute, replay, service, templ, templ.slot);
    check_outputs(dut, execute, replay, service, templ, "template-before-unselected-mutation");
    execute = {false, 7, false, 0x5656565656565656ULL};
    replay = {false, 2, true, 0x7878787878787878ULL};
    service = {false, 4, false, 0};
    drive(dut, execute, replay, service, templ, templ.slot);
    check_outputs(dut, execute, replay, service, templ, "template-after-unselected-mutation");
}

void prove_idle_sanitization(VReducedRobCompletionArbiterBehaviorProbe &dut) {
    const Source execute{false, 7, true, 0x1111111111111111ULL};
    const Source replay{false, 5, true, 0x2222222222222222ULL};
    const Source service{false, 3, true, 0x4444444444444444ULL};
    const Source templ{false, 4, true, 0x3333333333333333ULL};
    drive(dut, execute, replay, service, templ, templ.slot);
    check_outputs(dut, execute, replay, service, templ, "idle-with-stale-payloads");
}

void prove_contention_diagnostics(VReducedRobCompletionArbiterBehaviorProbe &dut) {
    Source execute{true, 2, true, kExecuteToken};
    Source replay{true, 2, true, kReplayToken};
    Source service{true, 2, true, 0x0102030405060708ULL};
    Source templ{true, 2, true, kTemplateToken};
    drive(dut, execute, replay, service, templ, templ.slot);
    dut.eval();
    require(dut.io_completionContended, "same-RID contention should be visible");
    require(dut.io_sameRobCompletionContention, "same-RID contention diagnostic mismatch");
    require(!dut.io_differentRobCompletionContention, "same-RID contention was marked different");
    require(!dut.io_protocolError, "same-RID contention should not be protocolError");

    execute = {false, 2, true, kExecuteToken};
    replay = {true, 1, true, kReplayToken};
    service = {true, 3, true, 0x0102030405060708ULL};
    templ = {true, 3, true, kTemplateToken};
    drive(dut, execute, replay, service, templ, templ.slot);
    dut.eval();
    require(dut.io_completionContended, "different-RID contention should be visible");
    require(!dut.io_sameRobCompletionContention, "different-RID contention was marked same");
    require(dut.io_differentRobCompletionContention, "different-RID diagnostic mismatch");
    require(dut.io_protocolError, "different-RID contention should be protocolError");
}

[[noreturn]] void trigger_parent_slot_assertion(
    VReducedRobCompletionArbiterBehaviorProbe &dut) {
    const Source execute{false, 1, true, kExecuteToken};
    const Source replay{false, 3, true, kReplayToken};
    const Source service{false, 5, true, 0x0102030405060708ULL};
    const Source templ{true, 6, true, kTemplateToken};
    dut.reset = 0;
    dut.clock = 0;
    drive(dut, execute, replay, service, templ, 5);
    dut.eval();
    dut.clock = 1;
    dut.eval();
    fail("parent-slot mismatch was silently accepted by the emitted hardware");
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VReducedRobCompletionArbiterBehaviorProbe dut;
    dut.clock = 0;
    dut.reset = 0;

    if (argc == 2 && std::string(argv[1]) == "--trigger-parent-slot-mismatch") {
        trigger_parent_slot_assertion(dut);
    }
    if (argc != 1) {
        fail("unknown argument");
    }

    prove_all_valid_combinations(dut);
    prove_row_valid_forwarding(dut);
    prove_unselected_payload_independence(dut);
    prove_idle_sanitization(dut);
    prove_contention_diagnostics(dut);

    std::cout
        << "backend-reduced-rob-completion-arbiter-behavior-probe: PASS "
        << "(16 combinations, service rows, template rows, blockers, diagnostics, one-hot, idle)\n";
    return 0;
}
