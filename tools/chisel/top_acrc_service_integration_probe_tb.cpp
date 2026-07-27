#include "VLinxCoreFrontendFetchRfAluTraceTop.h"
#include "verilated.h"

#include <array>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <map>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr std::uint64_t kBasePc = 0x1000;
constexpr std::uint32_t kAcrcRaw = 0x0010302bU;
constexpr std::uint16_t kCompressedBstop = 0x0000U;
constexpr std::uint16_t kOpAcrc = 546;
constexpr std::uint16_t kOpCMovr = 573;
constexpr std::uint64_t kA0Seed = 0x0102030405060708ULL;
constexpr std::uint64_t kA7SetTidAddress = 96;
constexpr std::uint64_t kServiceReturn = 0x8877665544332211ULL;

std::uint64_t g_cycle = 0;

[[noreturn]] void fail(const std::string &message) {
  std::cerr << "top-acrc-service-integration-probe: FAIL: " << message << "\n";
  std::exit(1);
}

void require(bool condition, const std::string &message) {
  if (!condition) {
    fail(message);
  }
}

std::string hex64(std::uint64_t value) {
  std::ostringstream os;
  os << "0x" << std::hex << value << std::dec;
  return os.str();
}

std::uint64_t bits(std::int64_t value, unsigned width) {
  return static_cast<std::uint64_t>(value) & ((1ULL << width) - 1ULL);
}

std::uint16_t encode_c_movi(unsigned dst, std::int32_t simm5) {
  return static_cast<std::uint16_t>(0x16U | (bits(simm5, 5) << 6) |
                                    (bits(dst, 5) << 11));
}

std::uint16_t encode_c_movr(unsigned dst, unsigned src) {
  return static_cast<std::uint16_t>(0x06U | (bits(src, 5) << 6) |
                                    (bits(dst, 5) << 11));
}

struct RobId {
  bool valid = false;
  bool wrap = false;
  std::uint8_t value = 0;
};

struct ServiceIdentity {
  std::uint8_t stid = 0;
  RobId bid;
  RobId gid;
  RobId rid;
};

struct ServiceRequest {
  std::uint8_t request_type = 0;
  ServiceIdentity identity;
  std::uint64_t a0 = 0;
  std::uint64_t a1 = 0;
  std::uint64_t a2 = 0;
  std::uint64_t a3 = 0;
  std::uint64_t a4 = 0;
  std::uint64_t a5 = 0;
  std::uint64_t a7 = 0;
};

class FetchMemory {
public:
  void store_insn(std::uint64_t pc, std::uint64_t raw, unsigned len) {
    for (unsigned i = 0; i < len; ++i) {
      bytes_[pc + i] = static_cast<std::uint8_t>((raw >> (i * 8U)) & 0xffU);
    }
  }

  void fill_compressed_nops(std::uint64_t pc, unsigned count) {
    for (unsigned i = 0; i < count; ++i) {
      store_insn(pc + i * 2ULL, encode_c_movi(4 + (i % 4), 0), 2);
    }
  }

  std::uint64_t read_window(std::uint64_t pc) const {
    std::uint64_t window = 0;
    for (unsigned i = 0; i < 8; ++i) {
      std::uint8_t byte = 0xffU;
      const auto it = bytes_.find(pc + i);
      if (it == bytes_.end()) {
        if (i == 0) {
          fail("fetch memory missing first byte at " + hex64(pc));
        }
      } else {
        byte = it->second;
      }
      window |= static_cast<std::uint64_t>(byte) << (i * 8U);
    }
    return window;
  }

private:
  std::map<std::uint64_t, std::uint8_t> bytes_;
};

void clear_inputs(VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  dut.io_startValid = 0;
  dut.io_startPc = 0;
  dut.io_restartValid = 0;
  dut.io_restartPc = 0;
  dut.io_bfuBodyValid = 0;
  dut.io_bfuHeaderPc = 0;
  dut.io_bfuHSizeBytes = 0;
  dut.io_bfuBSizeBytes = 0;
  dut.io_frontendFlushValid = 0;
  dut.io_peId = 0;
  dut.io_threadId = 0;
  dut.io_fetchReqReady = 1;
  dut.io_fetchRespValid = 0;
  dut.io_fetchRespWindow = 0;
  dut.io_rfInitValid = 0;
  dut.io_rfInitArchTag = 0;
  dut.io_rfInitData = 0;
  dut.io_deallocReady = 1;
  dut.io_loadLookupData = 0;
  dut.io_loadPairFirstLookupData = 0;
  dut.io_reducedServiceRequest_ready = 1;
  dut.io_reducedServiceResponse_valid = 0;
  dut.io_reducedServiceResponse_bits_requestType = 0;
  dut.io_reducedServiceResponse_bits_identity_stid = 0;
  dut.io_reducedServiceResponse_bits_identity_bid_valid = 0;
  dut.io_reducedServiceResponse_bits_identity_bid_value = 0;
  dut.io_reducedServiceResponse_bits_identity_bid_wrap = 0;
  dut.io_reducedServiceResponse_bits_identity_gid_valid = 0;
  dut.io_reducedServiceResponse_bits_identity_gid_value = 0;
  dut.io_reducedServiceResponse_bits_identity_gid_wrap = 0;
  dut.io_reducedServiceResponse_bits_identity_rid_valid = 0;
  dut.io_reducedServiceResponse_bits_identity_rid_value = 0;
  dut.io_reducedServiceResponse_bits_identity_rid_wrap = 0;
  dut.io_reducedServiceResponse_bits_a0 = 0;
}

void eval_with_loads(VLinxCoreFrontendFetchRfAluTraceTop &dut, const FetchMemory &mem) {
  dut.eval();
  if (dut.io_loadLookupValid) {
    dut.io_loadLookupData = mem.read_window(dut.io_loadLookupAddr);
    dut.eval();
  }
  if (dut.io_loadPairFirstLookupValid) {
    dut.io_loadPairFirstLookupData = mem.read_window(dut.io_loadPairFirstLookupAddr);
    dut.eval();
  }
}

void tick(VLinxCoreFrontendFetchRfAluTraceTop &dut, const FetchMemory &mem) {
  dut.clock = 0;
  eval_with_loads(dut, mem);
  dut.clock = 1;
  eval_with_loads(dut, mem);
  dut.clock = 0;
  eval_with_loads(dut, mem);
  ++g_cycle;
}

void reset(VLinxCoreFrontendFetchRfAluTraceTop &dut, const FetchMemory &mem) {
  clear_inputs(dut);
  dut.reset = 1;
  tick(dut, mem);
  tick(dut, mem);
  dut.reset = 0;
  eval_with_loads(dut, mem);
}

void init_rf(VLinxCoreFrontendFetchRfAluTraceTop &dut, const FetchMemory &mem,
             std::uint8_t arch_tag, std::uint64_t data) {
  clear_inputs(dut);
  dut.io_rfInitValid = 1;
  dut.io_rfInitArchTag = arch_tag;
  dut.io_rfInitData = data;
  tick(dut, mem);
  clear_inputs(dut);
  eval_with_loads(dut, mem);
}

void start_fetch(VLinxCoreFrontendFetchRfAluTraceTop &dut, const FetchMemory &mem,
                 std::uint64_t pc) {
  clear_inputs(dut);
  dut.io_startValid = 1;
  dut.io_startPc = pc;
  tick(dut, mem);
  clear_inputs(dut);
  eval_with_loads(dut, mem);
}

ServiceRequest read_request(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  ServiceRequest request;
  request.request_type = dut.io_reducedServiceRequest_bits_requestType;
  request.identity.stid = dut.io_reducedServiceRequest_bits_identity_stid;
  request.identity.bid.valid = dut.io_reducedServiceRequest_bits_identity_bid_valid;
  request.identity.bid.value = dut.io_reducedServiceRequest_bits_identity_bid_value;
  request.identity.bid.wrap = dut.io_reducedServiceRequest_bits_identity_bid_wrap;
  request.identity.gid.valid = dut.io_reducedServiceRequest_bits_identity_gid_valid;
  request.identity.gid.value = dut.io_reducedServiceRequest_bits_identity_gid_value;
  request.identity.gid.wrap = dut.io_reducedServiceRequest_bits_identity_gid_wrap;
  request.identity.rid.valid = dut.io_reducedServiceRequest_bits_identity_rid_valid;
  request.identity.rid.value = dut.io_reducedServiceRequest_bits_identity_rid_value;
  request.identity.rid.wrap = dut.io_reducedServiceRequest_bits_identity_rid_wrap;
  request.a0 = dut.io_reducedServiceRequest_bits_a0;
  request.a1 = dut.io_reducedServiceRequest_bits_a1;
  request.a2 = dut.io_reducedServiceRequest_bits_a2;
  request.a3 = dut.io_reducedServiceRequest_bits_a3;
  request.a4 = dut.io_reducedServiceRequest_bits_a4;
  request.a5 = dut.io_reducedServiceRequest_bits_a5;
  request.a7 = dut.io_reducedServiceRequest_bits_a7;
  return request;
}

void drive_response(VLinxCoreFrontendFetchRfAluTraceTop &dut,
                    const std::optional<ServiceRequest> &pending) {
  if (!pending.has_value()) {
    return;
  }
  const ServiceRequest &request = *pending;
  dut.io_reducedServiceResponse_valid = 1;
  dut.io_reducedServiceResponse_bits_requestType = request.request_type;
  dut.io_reducedServiceResponse_bits_identity_stid = request.identity.stid;
  dut.io_reducedServiceResponse_bits_identity_bid_valid = request.identity.bid.valid ? 1 : 0;
  dut.io_reducedServiceResponse_bits_identity_bid_value = request.identity.bid.value;
  dut.io_reducedServiceResponse_bits_identity_bid_wrap = request.identity.bid.wrap ? 1 : 0;
  dut.io_reducedServiceResponse_bits_identity_gid_valid = request.identity.gid.valid ? 1 : 0;
  dut.io_reducedServiceResponse_bits_identity_gid_value = request.identity.gid.value;
  dut.io_reducedServiceResponse_bits_identity_gid_wrap = request.identity.gid.wrap ? 1 : 0;
  dut.io_reducedServiceResponse_bits_identity_rid_valid = request.identity.rid.valid ? 1 : 0;
  dut.io_reducedServiceResponse_bits_identity_rid_value = request.identity.rid.value;
  dut.io_reducedServiceResponse_bits_identity_rid_wrap = request.identity.rid.wrap ? 1 : 0;
  dut.io_reducedServiceResponse_bits_a0 = kServiceReturn;
}

struct Observation {
  bool saw_enqueue = false;
  bool saw_issue_candidate = false;
  bool saw_snapshot_match = false;
  bool saw_request = false;
  bool saw_trap = false;
  bool saw_service_writeback = false;
  bool saw_service_rob_complete = false;
  bool saw_consumer_issue = false;
  bool saw_consumer_write = false;
  std::optional<ServiceRequest> request;
};

void observe_common(const VLinxCoreFrontendFetchRfAluTraceTop &dut, Observation &obs,
                    std::uint64_t consumer_pc) {
  obs.saw_enqueue = obs.saw_enqueue ||
      (dut.io_issueQueueEnqueueFire && dut.io_issueQueueInputOpcode == kOpAcrc);
  obs.saw_issue_candidate = obs.saw_issue_candidate ||
      (dut.io_issueQueueOutputValid && dut.io_issueQueueOutputOpcode == kOpAcrc);
  obs.saw_snapshot_match = obs.saw_snapshot_match || dut.io_reducedServiceSnapshotLookupMatch;
  obs.saw_trap = obs.saw_trap || dut.io_reducedServiceTrappedIllegalSequence;
  obs.saw_service_writeback = obs.saw_service_writeback ||
      (dut.io_rfWriteValid && dut.io_robCompleteArbiterSelectedService &&
       dut.io_rfWriteData == kServiceReturn);
  obs.saw_service_rob_complete = obs.saw_service_rob_complete ||
      dut.io_robCompleteArbiterSelectedService;

  obs.saw_consumer_issue = obs.saw_consumer_issue ||
      (dut.io_executeAcceptedIdentityValid && dut.io_executeAcceptedPc == consumer_pc &&
       dut.io_executeAcceptedOpcode == kOpCMovr);
  obs.saw_consumer_write = obs.saw_consumer_write ||
      (dut.io_rfWriteValid && dut.io_executeCompletePc == consumer_pc &&
       dut.io_rfWriteData == kServiceReturn);
}

Observation run_program(const FetchMemory &mem, std::uint64_t start_pc,
                        std::uint64_t consumer_pc, bool respond_to_service) {
  VLinxCoreFrontendFetchRfAluTraceTop dut;
  Observation obs;
  std::optional<std::uint64_t> pending_fetch_pc;
  std::optional<ServiceRequest> pending_response;

  reset(dut, mem);
  init_rf(dut, mem, 2, kA0Seed);
  init_rf(dut, mem, 3, 0x3333);
  init_rf(dut, mem, 4, 0x4444);
  init_rf(dut, mem, 5, 0x5555);
  init_rf(dut, mem, 6, 0x6666);
  init_rf(dut, mem, 7, 0x7777);
  init_rf(dut, mem, 9, kA7SetTidAddress);
  start_fetch(dut, mem, start_pc);

  for (unsigned i = 0; i < 360; ++i) {
    clear_inputs(dut);
    dut.io_fetchReqReady = pending_fetch_pc.has_value() ? 0 : 1;
    if (pending_fetch_pc.has_value()) {
      dut.io_fetchRespValid = 1;
      dut.io_fetchRespWindow = mem.read_window(*pending_fetch_pc);
    }
    drive_response(dut, pending_response);
    eval_with_loads(dut, mem);

    observe_common(dut, obs, consumer_pc);
    const bool request_fire =
        dut.io_reducedServiceRequest_valid && dut.io_reducedServiceRequest_ready;
    const bool response_fire =
        dut.io_reducedServiceResponse_valid && dut.io_reducedServiceResponse_ready;
    if (request_fire) {
      ServiceRequest request = read_request(dut);
      obs.saw_request = true;
      obs.request = request;
      if (respond_to_service) {
        pending_response = request;
      }
    }
    if (response_fire) {
      pending_response.reset();
    }
    const bool fetch_resp_fire =
        pending_fetch_pc.has_value() && dut.io_fetchRespValid && dut.io_fetchRespReady;
    const bool fetch_req_fire = !pending_fetch_pc.has_value() &&
        dut.io_fetchReqValid && dut.io_fetchReqReady;
    const std::uint64_t next_fetch_pc = dut.io_fetchReqPc;

    tick(dut, mem);

    if (fetch_resp_fire) {
      pending_fetch_pc.reset();
    }
    if (fetch_req_fire) {
      pending_fetch_pc = next_fetch_pc;
    }
    if (dut.io_commitContractError || dut.io_rfStateError || dut.io_issueQueueProtocolError) {
      fail("DUT protocol error");
    }
    if (respond_to_service && obs.saw_consumer_write) {
      return obs;
    }
    if (!respond_to_service && obs.saw_trap) {
      return obs;
    }
  }
  return obs;
}

void prove_legal_acrc_path() {
  FetchMemory mem;
  mem.store_insn(kBasePc, kAcrcRaw, 4);
  mem.store_insn(kBasePc + 4, kCompressedBstop, 2);
  mem.store_insn(kBasePc + 6, encode_c_movr(3, 2), 2);
  mem.fill_compressed_nops(kBasePc + 8, 64);

  Observation obs = run_program(mem, kBasePc, kBasePc + 6, true);
  require(obs.saw_enqueue, "legal ACRC was not enqueued into service path");
  require(obs.saw_issue_candidate, "legal ACRC never became a service issue candidate");
  require(obs.saw_snapshot_match, "legal ACRC did not match service snapshot identity");
  require(obs.saw_request, "legal ACRC did not fire service request");
  require(obs.request.has_value(), "legal service request was not captured");
  require(obs.request->request_type == 1, "ACRC request type was not SCT_SYS");
  require(obs.request->a0 == kA0Seed, "service request a0 did not come from initial a0 RF mapping");
  require(obs.request->a7 == kA7SetTidAddress, "service request a7 did not come from initial a7 RF mapping");
  require(obs.saw_service_rob_complete, "service completion did not select ROB completion arbiter service source");
  require(obs.saw_service_writeback, "service writeback was not the selected RF write");
  require(obs.saw_consumer_issue, "dependent consumer after C.BSTOP did not issue");
  require(obs.saw_consumer_write, "dependent consumer did not observe service a0 writeback value");
}

void prove_wrong_pc_stop_rejected() {
  FetchMemory mem;
  mem.store_insn(kBasePc, kAcrcRaw, 4);
  mem.store_insn(kBasePc + 4, encode_c_movi(4, 1), 2);
  mem.store_insn(kBasePc + 6, kCompressedBstop, 2);
  mem.fill_compressed_nops(kBasePc + 8, 64);

  Observation obs = run_program(mem, kBasePc, kBasePc + 8, false);
  require(obs.saw_enqueue, "wrong-PC ACRC was not enqueued");
  require(obs.saw_issue_candidate, "wrong-PC ACRC never became a service issue candidate");
  require(!obs.saw_request, "illegal ACRC sequence fired an external service request");
  require(obs.saw_trap, "illegal ACRC sequence did not trap");
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  prove_legal_acrc_path();
  prove_wrong_pc_stop_rejected();
  std::cout << "top-acrc-service-integration-probe: PASS\n";
  return 0;
}
