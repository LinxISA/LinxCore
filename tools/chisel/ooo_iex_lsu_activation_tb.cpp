#include "VOOOIEXLSUActivationProbe.h"
#include "verilated.h"

#include <cstdint>
#include <iostream>
#include <string>
#include <vector>

namespace {
struct Instruction {
  const char* name;
  uint64_t raw;
  uint8_t length;
  uint8_t stid;
  uint64_t pc;
};

void tick(VOOOIEXLSUActivationProbe& dut) {
  dut.clock = 0;
  dut.eval();
  dut.clock = 1;
  dut.eval();
}

void clear_program(VOOOIEXLSUActivationProbe& dut) {
  dut.io_program_valid = 0;
  dut.io_program_bits_count = 0;
}

bool send(VOOOIEXLSUActivationProbe& dut, const Instruction& instruction,
          uint64_t instruction_id, uint16_t epoch) {
  clear_program(dut);
  dut.io_program_bits_count = 3;
#define DRIVE_ENTRY(LANE, RAW, LENGTH, PC, ID)                              \
  dut.io_program_bits_entries_##LANE##_kind = 0;                           \
  dut.io_program_bits_entries_##LANE##_parent_identity_peId = 1;           \
  dut.io_program_bits_entries_##LANE##_parent_identity_stid =              \
      instruction.stid;                                                     \
  dut.io_program_bits_entries_##LANE##_parent_identity_instructionId = ID; \
  dut.io_program_bits_entries_##LANE##_parent_identity_epoch = epoch;      \
  dut.io_program_bits_entries_##LANE##_parent_pc = PC;                     \
  dut.io_program_bits_entries_##LANE##_parent_instruction = RAW;           \
  dut.io_program_bits_entries_##LANE##_parent_lengthBytes = LENGTH;        \
  dut.io_program_bits_entries_##LANE##_parent_prediction_valid = 1;        \
  dut.io_program_bits_entries_##LANE##_parent_prediction_predictionTag =   \
      ID;                                                                   \
  dut.io_program_bits_entries_##LANE##_parent_prediction_transactionId =   \
      ID;                                                                   \
  dut.io_program_bits_entries_##LANE##_parent_prediction_checkpointId =    \
      ID & 3;                                                               \
  dut.io_program_bits_entries_##LANE##_parent_prediction_requestPc = PC;   \
  dut.io_program_bits_entries_##LANE##_parent_prediction_taken = 0;        \
  dut.io_program_bits_entries_##LANE##_parent_prediction_target = PC + 4;  \
  dut.io_program_bits_entries_##LANE##_parent_prediction_fallthroughPc =   \
      PC + LENGTH;                                                          \
  dut.io_program_bits_entries_##LANE##_parent_prediction_kind = 0;         \
  dut.io_program_bits_entries_##LANE##_parent_prediction_provider = 0;     \
  dut.io_program_bits_entries_##LANE##_parent_prediction_confidence = 0;   \
  dut.io_program_bits_entries_##LANE##_parent_prediction_epoch = epoch;    \
  dut.io_program_bits_entries_##LANE##_parent_fetchFault = 0;              \
  dut.io_program_bits_entries_##LANE##_parent_fetchFaultCause = 0
  const uint64_t marker_id = instruction_id * 3;
  DRIVE_ENTRY(0, 0x1001ULL, 4, instruction.pc - 4, marker_id);
  DRIVE_ENTRY(1, instruction.raw, instruction.length, instruction.pc,
              marker_id + 1);
  DRIVE_ENTRY(2, 0x1ULL, 4, instruction.pc + instruction.length,
              marker_id + 2);
#undef DRIVE_ENTRY
  dut.io_program_valid = 1;
  for (int cycle = 0; cycle < 80; ++cycle) {
    dut.eval();
    if (dut.io_program_ready) {
      tick(dut);
      clear_program(dut);
      return true;
    }
    tick(dut);
  }
  std::cerr << "timeout admitting " << instruction.name
            << " ingress=" << dut.io_ingressCount
            << " bootstrap=" << dut.io_bootstrapInitCount
            << " alu=" << dut.io_aluCount << " bru=" << dut.io_bruCount
            << " agu=" << dut.io_aguCount << " std=" << dut.io_stdCount
            << " resolve=" << dut.io_resolveCount
            << " rf=" << dut.io_rfWriteCount
            << " branch=" << dut.io_branchCount
            << " load=" << dut.io_loadCount
            << " memory=" << dut.io_memoryCount
            << " commit=" << dut.io_commitCount
            << " stid0=" << dut.io_stid0Progress
            << " stid1=" << dut.io_stid1Progress
            << " dispatchStall=" << dut.io_dispatchStallCount
            << " trace=" << dut.io_traceCount << '\n';
  clear_program(dut);
  return false;
}

bool send_system_cmd_block(VOOOIEXLSUActivationProbe& dut,
                           uint64_t instruction_id, uint16_t epoch) {
  clear_program(dut);
  dut.io_program_bits_count = 4;
#define DRIVE_SYSTEM_CMD_ENTRY(LANE, RAW, PC)                              \
  dut.io_program_bits_entries_##LANE##_kind = 0;                           \
  dut.io_program_bits_entries_##LANE##_parent_identity_peId = 1;           \
  dut.io_program_bits_entries_##LANE##_parent_identity_stid = 0;           \
  dut.io_program_bits_entries_##LANE##_parent_identity_instructionId =     \
      instruction_id + LANE;                                               \
  dut.io_program_bits_entries_##LANE##_parent_identity_epoch = epoch;      \
  dut.io_program_bits_entries_##LANE##_parent_pc = PC;                     \
  dut.io_program_bits_entries_##LANE##_parent_instruction = RAW;           \
  dut.io_program_bits_entries_##LANE##_parent_lengthBytes = 4;             \
  dut.io_program_bits_entries_##LANE##_parent_prediction_valid = 1;        \
  dut.io_program_bits_entries_##LANE##_parent_prediction_predictionTag =   \
      instruction_id + LANE;                                               \
  dut.io_program_bits_entries_##LANE##_parent_prediction_transactionId =   \
      instruction_id + LANE;                                               \
  dut.io_program_bits_entries_##LANE##_parent_prediction_checkpointId =    \
      (instruction_id + LANE) & 3;                                         \
  dut.io_program_bits_entries_##LANE##_parent_prediction_requestPc = PC;   \
  dut.io_program_bits_entries_##LANE##_parent_prediction_taken = 0;        \
  dut.io_program_bits_entries_##LANE##_parent_prediction_target = PC + 4;  \
  dut.io_program_bits_entries_##LANE##_parent_prediction_fallthroughPc =   \
      PC + 4;                                                              \
  dut.io_program_bits_entries_##LANE##_parent_prediction_kind = 0;         \
  dut.io_program_bits_entries_##LANE##_parent_prediction_provider = 0;     \
  dut.io_program_bits_entries_##LANE##_parent_prediction_confidence = 0;   \
  dut.io_program_bits_entries_##LANE##_parent_prediction_epoch = epoch;    \
  dut.io_program_bits_entries_##LANE##_parent_fetchFault = 0;              \
  dut.io_program_bits_entries_##LANE##_parent_fetchFaultCause = 0
  DRIVE_SYSTEM_CMD_ENTRY(0, 0x1001ULL, 0x6000ULL);
  DRIVE_SYSTEM_CMD_ENTRY(1, 0x30702bULL, 0x6004ULL);
  DRIVE_SYSTEM_CMD_ENTRY(2, 0x33ULL, 0x6008ULL);
  DRIVE_SYSTEM_CMD_ENTRY(3, 0x1ULL, 0x600cULL);
#undef DRIVE_SYSTEM_CMD_ENTRY
  dut.io_program_valid = 1;
  for (int cycle = 0; cycle < 80; ++cycle) {
    dut.eval();
    if (dut.io_program_ready) {
      tick(dut);
      clear_program(dut);
      return true;
    }
    tick(dut);
  }
  std::cerr << "timeout admitting System/CMD block\n";
  clear_program(dut);
  return false;
}

bool require_nonzero(const char* name, uint32_t value) {
  if (value != 0) return true;
  std::cerr << "missing activation: " << name << '\n';
  return false;
}

struct LoadIdentity {
  uint8_t pe_id;
  uint8_t stid;
  uint8_t rid_slot;
  uint16_t rid_generation;
  uint8_t member_index;
  uint16_t resident_generation;
  uint8_t bid;
  uint16_t brob_generation;
  uint64_t transaction;
  uint16_t transaction_generation;
  uint32_t lsid;
  uint16_t attempt;
  uint8_t pipe;
};

LoadIdentity current_load(const VOOOIEXLSUActivationProbe& dut) {
  return {dut.io_lastLoadIssueIdentity_rob_peId,
          dut.io_lastLoadIssueIdentity_rob_stid,
          dut.io_lastLoadIssueIdentity_rob_ridSlot,
          dut.io_lastLoadIssueIdentity_rob_ridGeneration,
          dut.io_lastLoadIssueIdentity_rob_memberIndex,
          dut.io_lastLoadIssueIdentity_rob_residentGeneration,
          dut.io_lastLoadIssueIdentity_rob_bid,
          dut.io_lastLoadIssueIdentity_rob_brobGeneration,
          dut.io_lastLoadIssueIdentity_transaction_value,
          dut.io_lastLoadIssueIdentity_transaction_generation,
          dut.io_lastLoadIssueIdentity_lsid,
          dut.io_lastLoadIssueIdentity_attemptGeneration,
          dut.io_lastLoadIssueIdentity_pipeId};
}

LoadIdentity rebound_load(const VOOOIEXLSUActivationProbe& dut) {
  return {dut.io_lastLoadRebindNext_rob_peId,
          dut.io_lastLoadRebindNext_rob_stid,
          dut.io_lastLoadRebindNext_rob_ridSlot,
          dut.io_lastLoadRebindNext_rob_ridGeneration,
          dut.io_lastLoadRebindNext_rob_memberIndex,
          dut.io_lastLoadRebindNext_rob_residentGeneration,
          dut.io_lastLoadRebindNext_rob_bid,
          dut.io_lastLoadRebindNext_rob_brobGeneration,
          dut.io_lastLoadRebindNext_transaction_value,
          dut.io_lastLoadRebindNext_transaction_generation,
          dut.io_lastLoadRebindNext_lsid,
          dut.io_lastLoadRebindNext_attemptGeneration,
          dut.io_lastLoadRebindNext_pipeId};
}

#define DRIVE_ID(DUT, PREFIX, ID)                                           \
  DUT.PREFIX##_rob_peId = ID.pe_id;                                        \
  DUT.PREFIX##_rob_stid = ID.stid;                                         \
  DUT.PREFIX##_rob_ridSlot = ID.rid_slot;                                  \
  DUT.PREFIX##_rob_ridGeneration = ID.rid_generation;                      \
  DUT.PREFIX##_rob_memberIndex = ID.member_index;                          \
  DUT.PREFIX##_rob_residentGeneration = ID.resident_generation;            \
  DUT.PREFIX##_rob_bid = ID.bid;                                           \
  DUT.PREFIX##_rob_brobGeneration = ID.brob_generation;                    \
  DUT.PREFIX##_transaction_value = ID.transaction;                         \
  DUT.PREFIX##_transaction_generation = ID.transaction_generation;         \
  DUT.PREFIX##_lsid = ID.lsid;                                             \
  DUT.PREFIX##_attemptGeneration = ID.attempt;                             \
  DUT.PREFIX##_pipeId = ID.pipe

template <typename Predicate>
bool wait_until(VOOOIEXLSUActivationProbe& dut, int bound,
                Predicate predicate) {
  for (int cycle = 0; cycle < bound; ++cycle) {
    dut.eval();
    if (predicate(dut)) return true;
    tick(dut);
  }
  return false;
}
}  // namespace

int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);
  VOOOIEXLSUActivationProbe dut;
  clear_program(dut);
  dut.io_commitReady = 1;
  dut.io_trapReady = 1;
  dut.io_cmdReady = 1;
  dut.io_systemReady = 1;
  dut.io_oooTraceReady = 1;
  dut.io_iexTraceReady = 1;
  dut.io_recoveryReady = 1;
  dut.io_lsuTraceReady = 1;
  dut.io_memoryReady_0 = 1;
  dut.io_memoryReady_1 = 1;
  dut.io_memoryReady_2 = 1;
  dut.io_memoryReady_3 = 1;
  dut.io_memoryResponseValid = 0;
  dut.io_memoryResponseId = 0;
  dut.io_memoryResponseGeneration = 0;
  dut.io_memoryResponseAddress = 0;
  dut.io_memoryResponseData = 0;
  dut.io_loadReissueRequest_valid = 0;
  dut.io_loadResultInject_valid = 0;
  dut.reset = 1;
  for (int cycle = 0; cycle < 4; ++cycle) tick(dut);
  dut.reset = 0;

  const uint64_t addi = 0x15ULL | (5ULL << 7) | (7ULL << 20);
  const uint64_t jump = 0x37ULL | (2ULL << 15);
  const uint64_t load = 0x3019ULL | (6ULL << 7) | (0x80ULL << 20);
  const uint64_t store = 0x3059ULL | (0x88ULL << 20);
  uint64_t instruction_id = 1;
  int mismatch_count = 0;
  if (!send(dut, {"ADDI.stid0", addi, 4, 0, 0x1000}, instruction_id++, 1))
    return 2;
  if (!wait_until(dut, 160, [](const auto& d) { return d.io_rfWriteCount == 1; }))
    return 3;
  if (dut.io_lastRfWriteValue != 7 || dut.io_lastRfWritePtag != 48 ||
      dut.io_lastRfWriteGeneration != 0) {
    std::cerr << "ADDI RF mismatch value=" << dut.io_lastRfWriteValue
              << " ptag=" << unsigned(dut.io_lastRfWritePtag)
              << " generation=" << dut.io_lastRfWriteGeneration << '\n';
    ++mismatch_count;
  }
  if (!send(dut, {"ADDI.stid1", addi, 4, 1, 0x2000}, instruction_id++, 1))
    return 4;
  for (int cycle = 0; cycle < 16; ++cycle) tick(dut);

  // Hold only the selected lower-memory sink, then release it and retain the
  // first miss identity for the old-attempt tombstone response.
  dut.io_memoryReady_0 = 0;
  if (!send(dut, {"LDI", load, 4, 0, 0x1008}, instruction_id++, 1)) return 5;
  if (!wait_until(dut, 192, [](const auto& d) {
        return d.io_memoryStallCount > 0;
      })) return 18;
  if (dut.io_memoryCount != 0 || dut.io_memoryStallCount == 0) {
    std::cerr << "memory request did not remain valid under selected-lane "
                 "backpressure\n";
    ++mismatch_count;
  }
  dut.io_memoryReady_0 = 1;
  if (!wait_until(dut, 192, [](const auto& d) {
        return d.io_loadCount == 1 && d.io_loadLaunchCount == 1 &&
               d.io_loadAttemptLaunchCount == 1 && d.io_memoryCount == 1;
      })) return 6;

  const LoadIdentity old_attempt = current_load(dut);
  const uint64_t allocation_value = dut.io_lastLoadAllocationId_value;
  const uint16_t allocation_generation = dut.io_lastLoadAllocationId_generation;
  const uint64_t load_address = dut.io_lastLoadAddress;
  const uint64_t old_memory_id = dut.io_lastMemoryId;
  const uint16_t old_memory_generation = dut.io_lastMemoryGeneration;
  const uint64_t old_memory_address = dut.io_lastMemoryAddress;
  dut.io_loadReissueRequest_bits_allocationId_value = allocation_value;
  dut.io_loadReissueRequest_bits_allocationId_generation = allocation_generation;
  DRIVE_ID(dut, io_loadReissueRequest_bits_currentIdentity, old_attempt);
  dut.io_loadReissueRequest_bits_address = load_address;
  dut.io_loadReissueRequest_valid = 1;
  if (!wait_until(dut, 64, [](const auto& d) {
        return d.io_loadReissueRequest_ready;
      })) return 7;
  tick(dut);
  dut.io_loadReissueRequest_valid = 0;
  const LoadIdentity new_attempt = rebound_load(dut);
  if (new_attempt.transaction != old_attempt.transaction ||
      new_attempt.transaction_generation != old_attempt.transaction_generation ||
      new_attempt.attempt != old_attempt.attempt + 1) {
    std::cerr << "LSU-authored replay identity mismatch oldAttempt="
              << old_attempt.attempt << " nextAttempt=" << new_attempt.attempt
              << " oldTransaction=" << old_attempt.transaction
              << " nextTransaction=" << new_attempt.transaction << '\n';
    ++mismatch_count;
  }
  if (!wait_until(dut, 128, [](const auto& d) {
        return d.io_loadLaunchCount == 2 &&
               d.io_loadAttemptLaunchCount == 2 && d.io_memoryCount == 2;
      })) return 8;
  const uint64_t new_memory_id = dut.io_lastMemoryId;
  const uint16_t new_memory_generation = dut.io_lastMemoryGeneration;
  const uint64_t new_memory_address = dut.io_lastMemoryAddress;
  if (new_memory_id == old_memory_id &&
      new_memory_generation == old_memory_generation) {
    std::cerr << "replay reused old lower-memory identity\n";
    ++mismatch_count;
  }

  const uint32_t resolve_before = dut.io_resolveCount;
  const uint32_t rf_before = dut.io_rfWriteCount;
  const uint32_t commit_before = dut.io_commitCount;
  dut.io_memoryResponseId = old_memory_id;
  dut.io_memoryResponseGeneration = old_memory_generation;
  dut.io_memoryResponseAddress = old_memory_address;
  dut.io_memoryResponseData = 0xdeadbeefULL;
  dut.io_memoryResponseValid = 1;
  if (!wait_until(dut, 64, [](const auto& d) { return d.io_memoryResponseReady; }))
    return 9;
  tick(dut);
  dut.io_memoryResponseValid = 0;
  for (int cycle = 0; cycle < 8; ++cycle) tick(dut);

  DRIVE_ID(dut, io_loadResultInject_bits_identity, old_attempt);
  dut.io_loadResultInject_bits_allocationId_value = allocation_value;
  dut.io_loadResultInject_bits_allocationId_generation = allocation_generation;
  dut.io_loadResultInject_bits_data = 0xdeadbeefULL;
  dut.io_loadResultInject_bits_destination_valid = dut.io_lastLoadDestination_valid;
  dut.io_loadResultInject_bits_destination_kind = dut.io_lastLoadDestination_kind;
  dut.io_loadResultInject_bits_destination_atag = dut.io_lastLoadDestination_atag;
  dut.io_loadResultInject_bits_destination_ptag = dut.io_lastLoadDestination_ptag;
  dut.io_loadResultInject_bits_destination_previousPtag =
      dut.io_lastLoadDestination_previousPtag;
  dut.io_loadResultInject_bits_destination_pGeneration =
      dut.io_lastLoadDestination_pGeneration;
  dut.io_loadResultInject_bits_destination_previousPGeneration =
      dut.io_lastLoadDestination_previousPGeneration;
  dut.io_loadResultInject_bits_destination_previousPtagValid =
      dut.io_lastLoadDestination_previousPtagValid;
  dut.io_loadResultInject_bits_destination_ptagValid =
      dut.io_lastLoadDestination_ptagValid;
  dut.io_loadResultInject_bits_destinationRelativeIndex =
      dut.io_lastLoadDestinationRelativeIndex;
  dut.io_loadResultInject_bits_trap_valid = 0;
  dut.io_loadResultInject_valid = 1;
  if (!wait_until(dut, 64, [](const auto& d) { return d.io_loadResultInject_ready; }))
    return 10;
  tick(dut);
  dut.io_loadResultInject_valid = 0;
  for (int cycle = 0; cycle < 8; ++cycle) tick(dut);
  if (dut.io_resolveCount != resolve_before || dut.io_rfWriteCount != rf_before ||
      dut.io_commitCount != commit_before || dut.io_loadAttemptCancelCount != 1 ||
      dut.io_memoryCount != 2 || dut.io_loadLaunchCount != 2) {
    std::cerr << "old attempt produced an architectural or duplicate effect\n";
    ++mismatch_count;
  }

  dut.io_memoryResponseId = new_memory_id;
  dut.io_memoryResponseGeneration = new_memory_generation;
  dut.io_memoryResponseAddress = new_memory_address;
  dut.io_memoryResponseData = 0x1122334455667788ULL;
  dut.io_memoryResponseValid = 1;
  if (!wait_until(dut, 64, [](const auto& d) { return d.io_memoryResponseReady; }))
    return 11;
  tick(dut);
  dut.io_memoryResponseValid = 0;
  if (!wait_until(dut, 192, [&](const auto& d) {
        return d.io_resolveCount == resolve_before + 1 &&
               d.io_rfWriteCount == rf_before + 1 &&
               d.io_commitCount == commit_before + 1;
      })) return 12;
  if (dut.io_loadAttemptLaunchCount != 2 || dut.io_loadLaunchCount != 3 ||
      dut.io_memoryCount != 2 || dut.io_loadAttemptCancelCount != 1 ||
      dut.io_lastLoadLaunchIdentity_attemptGeneration != new_attempt.attempt ||
      dut.io_lastResolveValue != 0x1122334455667788ULL || load_address != 0x400) {
    std::cerr << "Option-A replay terminal mismatch unique="
              << dut.io_loadAttemptLaunchCount
              << " raw=" << dut.io_loadLaunchCount
              << " memory=" << dut.io_memoryCount
              << " cancel=" << dut.io_loadAttemptCancelCount
              << " expectedAttempt=" << new_attempt.attempt
              << " actualAttempt="
              << dut.io_lastLoadLaunchIdentity_attemptGeneration
              << " resolveValue=" << dut.io_lastResolveValue
              << " loadAddress=" << load_address << '\n';
    ++mismatch_count;
  }

  const uint32_t system_count_before = dut.io_systemCount;
  const uint32_t cmd_count_before = dut.io_cmdCount;
  const uint32_t system_issue_before = dut.io_systemIssueCount;
  const uint32_t cmd_issue_before = dut.io_cmdIssueCount;
  const uint32_t dispatch_stall_before = dut.io_dispatchStallCount;
  dut.io_systemReady = 0;
  dut.io_cmdReady = 0;
  if (!send_system_cmd_block(dut, instruction_id, 1)) return 14;
  instruction_id += 4;
  if (!wait_until(dut, 128, [&](const auto& d) {
        return d.io_systemCount == system_count_before + 1 &&
               d.io_cmdCount == cmd_count_before + 1;
      })) return 15;
  if (!wait_until(dut, 128, [&](const auto& d) {
        return d.io_dispatchStallCount > dispatch_stall_before;
      })) return 24;
  if (dut.io_systemIssueCount != system_issue_before ||
      dut.io_cmdIssueCount != cmd_issue_before ||
      dut.io_dispatchStallCount <= dispatch_stall_before) {
    std::cerr << "System/CMD escaped closed sink backpressure\n";
    ++mismatch_count;
  }

  dut.io_systemReady = 1;
  if (!wait_until(dut, 128, [&](const auto& d) {
        return d.io_systemIssueCount == system_issue_before + 1;
      })) {
    std::cerr << "timeout publishing System terminal system="
              << dut.io_systemCount << " cmd=" << dut.io_cmdCount
              << " systemIssue=" << dut.io_systemIssueCount
              << " cmdIssue=" << dut.io_cmdIssueCount
              << " resolve=" << dut.io_resolveCount
              << " commit=" << dut.io_commitCount << '\n';
    return 16;
  }
  for (int cycle = 0; cycle < 8; ++cycle) tick(dut);
  if (dut.io_cmdIssueCount != cmd_issue_before) {
    std::cerr << "CMD escaped independent backpressure\n";
    ++mismatch_count;
  }
  dut.io_cmdReady = 1;
  if (!wait_until(dut, 256, [&](const auto& d) {
        return d.io_cmdIssueCount == cmd_issue_before + 1;
      })) return 17;

  const uint32_t bru_before = dut.io_bruCount;
  const uint32_t recovery_event_before = dut.io_recoveryEventCount;
  const uint32_t recovery_apply_before = dut.io_recoveryApplyCount;
  dut.io_recoveryReady = 0;
  if (!send(dut, {"J", jump, 4, 0, 0x1004}, instruction_id++, 1)) return 13;
  if (!wait_until(dut, 128, [&](const auto& d) {
        return d.io_bruCount == bru_before + 1;
      })) return 19;
  for (int cycle = 0; cycle < 8; ++cycle) tick(dut);
  if (dut.io_recoveryEventCount != recovery_event_before ||
      dut.io_recoveryApplyCount != recovery_apply_before) {
    std::cerr << "recovery escaped public backpressure\n";
    ++mismatch_count;
  }

  dut.io_recoveryReady = 1;
  if (!wait_until(dut, 256, [&](const auto& d) {
        return d.io_recoveryEventCount == recovery_event_before + 1 &&
               d.io_recoveryApplyCount == recovery_apply_before + 1;
      })) return 20;
  const uint32_t stid1_after_apply = dut.io_stid1Progress;
  if (!send(dut, {"ADDI.peer-after-apply", addi, 4, 1, 0x2004},
            instruction_id++, 1)) return 21;
  if (!wait_until(dut, 192, [&](const auto& d) {
        return d.io_stid1Progress > stid1_after_apply;
      })) return 22;

  if (!send(dut, {"SDI", store, 4, 0, 0x100c}, instruction_id++, 1))
    return 23;
  for (int cycle = 0; cycle < 32; ++cycle) tick(dut);

  bool ok = true;
  ok &= require_nonzero("ALU", dut.io_aluCount);
  ok &= require_nonzero("BRU", dut.io_bruCount);
  ok &= require_nonzero("AGU", dut.io_aguCount);
  ok &= require_nonzero("STD", dut.io_stdCount);
  ok &= require_nonzero("load", dut.io_loadCount);
  ok &= require_nonzero("store-address", dut.io_storeAddressCount);
  ok &= require_nonzero("store-data", dut.io_storeDataCount);
  ok &= require_nonzero("system", dut.io_systemCount);
  ok &= require_nonzero("CMD", dut.io_cmdCount);
  ok &= require_nonzero("system terminal", dut.io_systemIssueCount);
  ok &= require_nonzero("CMD terminal", dut.io_cmdIssueCount);
  ok &= require_nonzero("lower-memory request", dut.io_memoryCount);
  ok &= require_nonzero("branch recovery", dut.io_branchCount);
  ok &= require_nonzero("recovery event", dut.io_recoveryEventCount);
  ok &= require_nonzero("common recovery apply", dut.io_recoveryApplyCount);
  ok &= require_nonzero("STID0 progress", dut.io_stid0Progress);
  ok &= require_nonzero("STID1 progress", dut.io_stid1Progress);
  ok &= require_nonzero("blocked public dispatch/terminal cycles",
                        dut.io_dispatchStallCount);
  ok &= require_nonzero("blocked lower-memory cycles",
                        dut.io_memoryStallCount);

  if (dut.io_recoveryEventCount != 1 || dut.io_recoveryApplyCount != 1) {
    std::cerr << "recovery cardinality mismatch event="
              << dut.io_recoveryEventCount
              << " apply=" << dut.io_recoveryApplyCount << '\n';
    ++mismatch_count;
  }

  if (dut.io_lastBranchTarget != 0x1008) {
    std::cerr << "branch target mismatch expected=4104 actual="
              << dut.io_lastBranchTarget << '\n';
    ++mismatch_count;
  }
  if (dut.io_lastStoreAddress != 0x20 || dut.io_lastStoreData != 0) {
    std::cerr << "store mismatch address=" << dut.io_lastStoreAddress
              << " data=" << dut.io_lastStoreData << '\n';
    ++mismatch_count;
  }
  std::cout << "activation ingress=" << dut.io_ingressCount
            << " bootstrap=" << dut.io_bootstrapInitCount
            << " alu=" << dut.io_aluCount << " bru=" << dut.io_bruCount
            << " agu=" << dut.io_aguCount << " std=" << dut.io_stdCount
            << " load=" << dut.io_loadCount
            << " sta=" << dut.io_storeAddressCount
            << " storeData=" << dut.io_storeDataCount
            << " system=" << dut.io_systemCount << " cmd=" << dut.io_cmdCount
            << " systemIssue=" << dut.io_systemIssueCount
            << " cmdIssue=" << dut.io_cmdIssueCount
            << " resolve=" << dut.io_resolveCount
            << " rf=" << dut.io_rfWriteCount
            << " branch=" << dut.io_branchCount
            << " recoveryEvent=" << dut.io_recoveryEventCount
            << " recoveryApply=" << dut.io_recoveryApplyCount
            << " memory=" << dut.io_memoryCount
            << " commit=" << dut.io_commitCount
            << " trace=" << dut.io_traceCount
            << " stid0=" << dut.io_stid0Progress
            << " stid1=" << dut.io_stid1Progress
            << " dispatchStall=" << dut.io_dispatchStallCount
            << " memoryStall=" << dut.io_memoryStallCount
            << " uniqueLoadAttempts=" << dut.io_loadAttemptLaunchCount
            << " rawLoadPasses=" << dut.io_loadLaunchCount
            << " mismatch_count=" << mismatch_count << '\n';
  dut.final();
  return ok && mismatch_count == 0 ? 0 : 1;
}
