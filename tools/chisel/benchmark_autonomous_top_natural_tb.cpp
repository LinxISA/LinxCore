#include "commit_trace_jsonl.h"

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>

#ifndef LINXCORE_BENCHMARK_AUTONOMOUS_NATURAL_SELF_TEST
#include "VLinxCoreBenchmarkAutonomousTop.h"
#include "verilated.h"
#endif

namespace {

constexpr std::uint64_t kUartDataAddr = 0x10000000ULL;
constexpr std::uint64_t kTestFinisherAddr = 0x10009000ULL;
constexpr std::uint16_t kFinisherPass = 0x5555U;
constexpr std::uint64_t kReducedServiceAcrcRequest = 1;
constexpr std::uint64_t kSysExit = 93;
constexpr std::uint64_t kSysSetTidAddress = 96;

std::uint64_t clamp_hist_index(std::uint64_t value, std::uint64_t max_index) {
  return value > max_index ? max_index : value;
}

std::uint64_t parse_u64_arg(const std::string &value, const std::string &name) {
  errno = 0;
  char *end = nullptr;
  const unsigned long long parsed = std::strtoull(value.c_str(), &end, 0);
  if (errno != 0 || end == value.c_str() || *end != '\0') {
    throw std::runtime_error("invalid " + name + ": " + value);
  }
  return static_cast<std::uint64_t>(parsed);
}

std::string json_escape(const std::string &value) {
  std::ostringstream out;
  for (const char ch : value) {
    switch (ch) {
    case '\\': out << "\\\\"; break;
    case '"': out << "\\\""; break;
    case '\n': out << "\\n"; break;
    case '\r': out << "\\r"; break;
    case '\t': out << "\\t"; break;
    default:
      if (static_cast<unsigned char>(ch) < 0x20) {
        out << "\\u" << std::hex << std::setw(4) << std::setfill('0')
            << static_cast<unsigned>(static_cast<unsigned char>(ch));
      } else {
        out << ch;
      }
    }
  }
  return out.str();
}

class SparseMemory {
public:
  void store_byte(std::uint64_t addr, std::uint8_t value) {
    bytes_[addr] = value;
  }

  void load_sparse_hex(const std::string &path) {
    std::ifstream in(path);
    if (!in) {
      throw std::runtime_error("failed to open sparse memory image: " + path);
    }
    std::string line;
    std::uint64_t line_no = 0;
    std::uint64_t count = 0;
    while (std::getline(in, line)) {
      ++line_no;
      const auto comment = line.find('#');
      if (comment != std::string::npos) {
        line.resize(comment);
      }
      std::istringstream iss(line);
      std::string addr_token;
      std::string byte_token;
      std::string extra;
      if (!(iss >> addr_token)) {
        continue;
      }
      if (!(iss >> byte_token) || (iss >> extra)) {
        throw std::runtime_error("invalid sparse memory line at " + path + ":" + std::to_string(line_no));
      }
      const std::uint64_t addr = parse_u64_arg(addr_token, "sparse memory address");
      const std::uint64_t byte = parse_u64_arg(byte_token, "sparse memory byte");
      if (byte > 0xffU) {
        throw std::runtime_error("sparse memory byte out of range at " + path + ":" + std::to_string(line_no));
      }
      store_byte(addr, static_cast<std::uint8_t>(byte));
      ++count;
    }
    if (count == 0) {
      throw std::runtime_error("sparse memory image is empty: " + path);
    }
  }

  std::uint64_t read_window(std::uint64_t pc) const {
    std::uint64_t value = 0;
    for (std::uint8_t i = 0; i < 8; ++i) {
      std::uint8_t byte = 0xffU;
      const bool found = read_byte(pc + i, byte);
      if (!found && i == 0) {
        throw std::runtime_error("fetch memory missing first byte at pc=0x" + hex(pc));
      }
      value |= static_cast<std::uint64_t>(byte) << (static_cast<unsigned>(i) * 8U);
    }
    return value;
  }

  std::uint64_t read_u64_or_zero(std::uint64_t addr) const {
    std::uint64_t value = 0;
    for (std::uint8_t i = 0; i < 8; ++i) {
      std::uint8_t byte = 0;
      (void)read_byte(addr + i, byte);
      value |= static_cast<std::uint64_t>(byte) << (static_cast<unsigned>(i) * 8U);
    }
    return value;
  }

  void apply_store(std::uint64_t addr, std::uint64_t data, std::uint8_t size, std::uint8_t mask) {
    if (size == 0 || size > 8) {
      throw std::runtime_error("unsupported committed store size: " + std::to_string(size));
    }
    std::uint8_t effective_mask = mask;
    if (effective_mask == 0) {
      const std::uint8_t low = static_cast<std::uint8_t>(addr & 0x7U);
      if (low + size > 8) {
        throw std::runtime_error("committed store crosses 8-byte observation lane");
      }
      effective_mask = static_cast<std::uint8_t>(((1U << size) - 1U) << low);
    }
    const std::uint64_t lane_base = addr & ~0x7ULL;
    for (std::uint8_t i = 0; i < 8; ++i) {
      if ((effective_mask & (1U << i)) == 0) {
        continue;
      }
      store_byte(
          lane_base + i,
          static_cast<std::uint8_t>((data >> (static_cast<unsigned>(i) * 8U)) & 0xffU));
    }
  }

  static std::string hex(std::uint64_t value) {
    std::ostringstream out;
    out << std::hex << value;
    return out.str();
  }

private:
  bool read_byte(std::uint64_t addr, std::uint8_t &value) const {
    const auto it = bytes_.find(addr);
    if (it == bytes_.end()) {
      return false;
    }
    value = it->second;
    return true;
  }

  std::map<std::uint64_t, std::uint8_t> bytes_;
};

struct ReducedServiceRobId {
  bool valid = false;
  bool wrap = false;
  std::uint64_t value = 0;
};

struct ReducedServiceIdentity {
  std::uint64_t stid = 0;
  ReducedServiceRobId bid;
  ReducedServiceRobId gid;
  ReducedServiceRobId rid;
};

struct ReducedServiceRequest {
  std::uint64_t request_type = 0;
  ReducedServiceIdentity identity;
  std::uint64_t a0 = 0;
  std::uint64_t a1 = 0;
  std::uint64_t a2 = 0;
  std::uint64_t a3 = 0;
  std::uint64_t a4 = 0;
  std::uint64_t a5 = 0;
  std::uint64_t a7 = 0;
};

struct ReducedServiceResponse {
  std::uint64_t request_type = 0;
  ReducedServiceIdentity identity;
  std::uint64_t a0 = 0;
};

class ReducedServiceResponder {
public:
  bool request_ready() const {
    return !response_valid_ && !unsupported_ && !exit_observed_;
  }

  bool response_valid() const {
    return response_valid_;
  }

  const ReducedServiceResponse &response() const {
    return response_;
  }

  std::uint64_t service_requests() const {
    return service_requests_;
  }

  std::uint64_t service_responses() const {
    return service_responses_;
  }

  bool have_last_service_nr() const {
    return have_last_service_nr_;
  }

  std::uint64_t last_service_nr() const {
    return last_service_nr_;
  }

  bool unsupported() const {
    return unsupported_;
  }

  std::uint64_t unsupported_service_nr() const {
    return unsupported_service_nr_;
  }

  bool exit_observed() const {
    return exit_observed_;
  }

  std::uint64_t exit_code() const {
    return exit_code_;
  }

  const ReducedServiceRequest &exit_request() const {
    return exit_request_;
  }

  void observe_cycle(bool request_fire, const ReducedServiceRequest &request, bool response_ready) {
    const bool response_fire = response_valid_ && response_ready;
    if (response_fire) {
      response_valid_ = false;
      ++service_responses_;
    }

    if (!request_fire) {
      return;
    }

    ++service_requests_;
    have_last_service_nr_ = true;
    last_service_nr_ = request.a7;

    if (request.request_type == kReducedServiceAcrcRequest &&
        request.a7 == kSysSetTidAddress) {
      response_.request_type = request.request_type;
      response_.identity = request.identity;
      response_.a0 = 1;
      response_valid_ = true;
      return;
    }

    if (request.request_type == kReducedServiceAcrcRequest &&
        request.a7 == kSysExit) {
      exit_observed_ = true;
      exit_code_ = request.a0;
      exit_request_ = request;
      return;
    }

    unsupported_ = true;
    unsupported_service_nr_ = request.a7;
  }

private:
  bool response_valid_ = false;
  bool unsupported_ = false;
  bool exit_observed_ = false;
  bool have_last_service_nr_ = false;
  std::uint64_t service_requests_ = 0;
  std::uint64_t service_responses_ = 0;
  std::uint64_t last_service_nr_ = 0;
  std::uint64_t unsupported_service_nr_ = 0;
  std::uint64_t exit_code_ = 0;
  ReducedServiceResponse response_;
  ReducedServiceRequest exit_request_;
};

struct Args {
  std::string memory_hex;
  std::string commit_trace;
  std::string event_trace;
  std::string uart_output;
  std::string manifest;
  std::uint64_t reset_pc = 0;
  std::uint64_t reset_sp = 0;
  std::uint64_t max_cycles = 1000000;
  std::uint64_t event_sample_period = 1;
  std::uint64_t commit_sample_period = 1;
};

std::string terminal_status(
    bool finisher_write,
    bool finisher_pass,
    bool trap,
    bool unsupported,
    bool halted) {
  if (finisher_write) {
    return finisher_pass ? "finisher_pass" : "finisher_fail";
  }
  if (trap) {
    return "trap";
  }
  if (unsupported) {
    return "unsupported";
  }
  if (halted) {
    return "halted";
  }
  return "timeout";
}

bool finisher_code_is_pass(std::uint16_t code) {
  return code == kFinisherPass;
}

[[noreturn]] void usage(const char *argv0) {
  std::cerr << "usage: " << argv0
            << " --memory-hex <sparse.mem> --reset-pc <addr> --reset-sp <addr>"
            << " --commit-trace <dut.jsonl> --event-trace <events.jsonl> --uart-output <uart.txt>"
            << " --manifest <run.json> [--max-cycles N] [--event-sample-period N]"
            << " [--commit-sample-period N]\n";
  std::exit(2);
}

Args parse_args(int argc, char **argv) {
  Args args;
  for (int i = 1; i < argc; ++i) {
    const std::string arg(argv[i]);
    if (arg == "--memory-hex" && i + 1 < argc) {
      args.memory_hex = argv[++i];
    } else if (arg == "--reset-pc" && i + 1 < argc) {
      args.reset_pc = parse_u64_arg(argv[++i], "--reset-pc");
    } else if (arg == "--reset-sp" && i + 1 < argc) {
      args.reset_sp = parse_u64_arg(argv[++i], "--reset-sp");
    } else if (arg == "--max-cycles" && i + 1 < argc) {
      args.max_cycles = parse_u64_arg(argv[++i], "--max-cycles");
    } else if (arg == "--event-sample-period" && i + 1 < argc) {
      args.event_sample_period = parse_u64_arg(argv[++i], "--event-sample-period");
    } else if (arg == "--commit-sample-period" && i + 1 < argc) {
      args.commit_sample_period = parse_u64_arg(argv[++i], "--commit-sample-period");
    } else if (arg == "--commit-trace" && i + 1 < argc) {
      args.commit_trace = argv[++i];
    } else if (arg == "--event-trace" && i + 1 < argc) {
      args.event_trace = argv[++i];
    } else if (arg == "--uart-output" && i + 1 < argc) {
      args.uart_output = argv[++i];
    } else if (arg == "--manifest" && i + 1 < argc) {
      args.manifest = argv[++i];
    } else if (arg == "--qemu-trace" || arg == "--expected-rows" || arg == "--qemu" ||
               arg == "--replay-rows" || arg == "--result-hint") {
      throw std::runtime_error("oracle/replay option is forbidden in natural mode: " + arg);
    } else {
      usage(argv[0]);
    }
  }
  if (args.memory_hex.empty() || args.commit_trace.empty() || args.event_trace.empty() ||
      args.uart_output.empty() || args.manifest.empty()) {
    usage(argv[0]);
  }
  return args;
}

void write_manifest_atomic(
    const Args &args,
    const std::string &status,
    std::uint64_t cycles,
    std::uint64_t commits,
    std::uint64_t fetch_requests,
    std::uint64_t fetch_responses,
    std::uint64_t service_requests,
    std::uint64_t service_responses,
    bool have_last_service_nr,
    std::uint64_t last_service_nr,
    bool have_unsupported_service_nr,
    std::uint64_t unsupported_service_nr,
    bool have_sys_exit_code,
    std::uint64_t sys_exit_code,
    std::uint16_t finisher_code,
    bool finisher_pass,
    const std::string &uart,
    const std::string &performance_json = "{}") {
  const std::string tmp = args.manifest + ".tmp";
  std::ofstream out(tmp);
  if (!out) {
    throw std::runtime_error("failed to open manifest tmp: " + tmp);
  }
  out << "{\n"
      << "  \"schema\":\"linxcore.benchmark_autonomous_natural.v1\",\n"
      << "  \"terminal_status\":\"" << status << "\",\n"
      << "  \"cycles\":" << cycles << ",\n"
      << "  \"commits\":" << commits << ",\n"
      << "  \"ipc\":" << std::setprecision(12)
      << (cycles == 0 ? 0.0 : static_cast<double>(commits) / static_cast<double>(cycles))
      << ",\n"
      << "  \"fetch_requests\":" << fetch_requests << ",\n"
      << "  \"fetch_responses\":" << fetch_responses << ",\n"
      << "  \"service_requests\":" << service_requests << ",\n"
      << "  \"service_responses\":" << service_responses << ",\n"
      << "  \"last_service_nr\":";
  if (have_last_service_nr) {
    out << last_service_nr;
  } else {
    out << "null";
  }
  out << ",\n"
      << "  \"unsupported_service_nr\":";
  if (have_unsupported_service_nr) {
    out << unsupported_service_nr;
  } else {
    out << "null";
  }
  out << ",\n"
      << "  \"sys_exit_code\":";
  if (have_sys_exit_code) {
    out << sys_exit_code;
  } else {
    out << "null";
  }
  out << ",\n"
      << "  \"reset_pc\":" << args.reset_pc << ",\n"
      << "  \"reset_sp\":" << args.reset_sp << ",\n"
      << "  \"max_cycles\":" << args.max_cycles << ",\n"
      << "  \"event_sample_period\":" << args.event_sample_period << ",\n"
      << "  \"commit_sample_period\":" << args.commit_sample_period << ",\n"
      << "  \"finisher_code\":" << finisher_code << ",\n"
      << "  \"finisher_pass\":" << (finisher_pass ? "true" : "false") << ",\n"
      << "  \"uart\":\"" << json_escape(uart) << "\",\n"
      << "  \"performance\":" << performance_json << ",\n"
      << "  \"artifacts\":{\n"
      << "    \"memory_hex\":\"" << json_escape(args.memory_hex) << "\",\n"
      << "    \"commit_trace\":\"" << json_escape(args.commit_trace) << "\",\n"
      << "    \"event_trace\":\"" << json_escape(args.event_trace) << "\",\n"
      << "    \"uart_output\":\"" << json_escape(args.uart_output) << "\"\n"
      << "  }\n"
      << "}\n";
  out.close();
  if (!out) {
    throw std::runtime_error("failed to write manifest tmp: " + tmp);
  }
  if (std::rename(tmp.c_str(), args.manifest.c_str()) != 0) {
    throw std::runtime_error("failed to publish manifest: " + std::string(std::strerror(errno)));
  }
}

} // namespace

#ifdef LINXCORE_BENCHMARK_AUTONOMOUS_NATURAL_SELF_TEST
int run_terminal_self_test(int argc, char **argv) {
  std::string self_test_case;
  Args args;
  std::string uart;
  std::uint64_t cycles = 0;
  std::uint64_t commits = 0;
  std::uint64_t fetch_requests = 0;
  std::uint64_t fetch_responses = 0;
  std::uint64_t service_requests = 0;
  std::uint64_t service_responses = 0;
  bool have_last_service_nr = false;
  std::uint64_t last_service_nr = 0;
  bool have_unsupported_service_nr = false;
  std::uint64_t unsupported_service_nr = 0;
  bool have_sys_exit_code = false;
  std::uint64_t sys_exit_code = 0;
  std::uint16_t finisher_code = 0;
  bool finisher_pass = false;
  std::string status = "timeout";

  for (int i = 1; i < argc; ++i) {
    const std::string arg(argv[i]);
    if (arg == "--self-test-case" && i + 1 < argc) {
      self_test_case = argv[++i];
    } else if (arg == "--manifest" && i + 1 < argc) {
      args.manifest = argv[++i];
    } else if (arg == "--uart-output" && i + 1 < argc) {
      args.uart_output = argv[++i];
    } else {
      usage(argv[0]);
    }
  }
  if (self_test_case.empty() || args.manifest.empty() || args.uart_output.empty()) {
    usage(argv[0]);
  }

  args.memory_hex = "self-test.mem";
  args.commit_trace = "self-test.commit.jsonl";
  args.event_trace = "self-test.events.jsonl";
  args.reset_pc = 0x1000;
  args.reset_sp = 0x2000;
  args.max_cycles = 17;

  if (self_test_case == "uart") {
    uart = "OK\n";
    status = terminal_status(false, false, false, false, true);
    cycles = 7;
    commits = 2;
    fetch_requests = 3;
    fetch_responses = 3;
  } else if (self_test_case == "finisher-pass") {
    finisher_code = kFinisherPass;
    finisher_pass = finisher_code_is_pass(finisher_code);
    status = terminal_status(true, finisher_pass, false, false, false);
    cycles = 1;
    commits = 1;
  } else if (self_test_case == "finisher-fail") {
    finisher_code = 0x3333U;
    finisher_pass = finisher_code_is_pass(finisher_code);
    status = terminal_status(true, finisher_pass, false, false, false);
    cycles = 1;
    commits = 1;
  } else if (self_test_case == "exit-pass") {
    have_sys_exit_code = true;
    sys_exit_code = 0;
    finisher_code = kFinisherPass;
    finisher_pass = true;
    status = terminal_status(true, finisher_pass, false, false, false);
    service_requests = 1;
    have_last_service_nr = true;
    last_service_nr = kSysExit;
    cycles = 2;
    commits = 2;
  } else if (self_test_case == "exit-fail") {
    have_sys_exit_code = true;
    sys_exit_code = 7;
    finisher_pass = false;
    status = terminal_status(true, finisher_pass, false, false, false);
    service_requests = 1;
    have_last_service_nr = true;
    last_service_nr = kSysExit;
    cycles = 2;
    commits = 2;
  } else if (self_test_case == "trap") {
    status = terminal_status(false, false, true, false, false);
    cycles = 4;
  } else if (self_test_case == "timeout") {
    status = terminal_status(false, false, false, false, false);
    cycles = args.max_cycles;
    fetch_requests = 5;
    fetch_responses = 4;
  } else {
    throw std::runtime_error("unknown self-test case: " + self_test_case);
  }

  std::ofstream uart_out(args.uart_output);
  if (!uart_out) {
    throw std::runtime_error("failed to open UART output: " + args.uart_output);
  }
  uart_out << uart;
  uart_out.close();
  if (!uart_out) {
    throw std::runtime_error("failed to write UART output: " + args.uart_output);
  }

  write_manifest_atomic(
      args,
      status,
      cycles,
      commits,
      fetch_requests,
      fetch_responses,
      service_requests,
      service_responses,
      have_last_service_nr,
      last_service_nr,
      have_unsupported_service_nr,
      unsupported_service_nr,
      have_sys_exit_code,
      sys_exit_code,
      finisher_code,
      finisher_pass,
      uart);
  std::cout << "benchmark-autonomous-natural-self-test-case: " << self_test_case << "\n";
  return status == "finisher_pass" ? 0 : 1;
}

int run_self_test() {
  SparseMemory mem;
  mem.store_byte(0x1000, 0x11);
  mem.store_byte(0x1001, 0x22);
  mem.store_byte(0x1002, 0x33);
  if (mem.read_window(0x1000) != 0xffffffffff332211ULL) {
    throw std::runtime_error("fetch window helper failed");
  }
  mem.apply_store(0x1001, 0x0000000000bbaa00ULL, 2, 0x06);
  if (mem.read_u64_or_zero(0x1000) != 0x0000000000bbaa11ULL) {
    throw std::runtime_error("masked store helper failed");
  }
  mem.apply_store(0x1004, 0x8877665544332211ULL, 4, 0);
  if (mem.read_u64_or_zero(0x1000) != 0x8877665500bbaa11ULL) {
    throw std::runtime_error("derived-mask store helper failed");
  }
  if (terminal_status(true, true, true, true, true) != "finisher_pass") {
    throw std::runtime_error("finisher pass priority helper failed");
  }
  if (terminal_status(true, false, false, false, false) != "finisher_fail") {
    throw std::runtime_error("finisher fail helper failed");
  }
  if (terminal_status(false, false, true, true, true) != "trap") {
    throw std::runtime_error("trap priority helper failed");
  }
  if (terminal_status(false, false, false, true, true) != "unsupported") {
    throw std::runtime_error("unsupported priority helper failed");
  }
  if (terminal_status(false, false, false, false, false) != "timeout") {
    throw std::runtime_error("timeout helper failed");
  }
  if (!finisher_code_is_pass(kFinisherPass)) {
    throw std::runtime_error("finisher pass code helper failed");
  }
  if (finisher_code_is_pass(0x0001U)) {
    throw std::runtime_error("finisher fail code helper failed");
  }
  ReducedServiceResponder responder;
  if (!responder.request_ready() || responder.response_valid()) {
    throw std::runtime_error("service responder idle handshake failed");
  }
  ReducedServiceRequest request;
  request.request_type = kReducedServiceAcrcRequest;
  request.identity.stid = 3;
  request.identity.bid = {true, true, 17};
  request.identity.gid = {true, false, 9};
  request.identity.rid = {true, true, 4};
  request.a0 = 0x12345678ULL;
  request.a7 = kSysSetTidAddress;
  responder.observe_cycle(true, request, false);
  if (!responder.response_valid() || responder.request_ready()) {
    throw std::runtime_error("service responder next-cycle response/backpressure failed");
  }
  const auto held = responder.response();
  responder.observe_cycle(false, request, false);
  if (!responder.response_valid() || responder.response().a0 != held.a0 ||
      responder.response().identity.bid.value != 17 ||
      responder.service_requests() != 1 || responder.service_responses() != 0 ||
      !responder.have_last_service_nr() || responder.last_service_nr() != kSysSetTidAddress) {
    throw std::runtime_error("service responder hold/identity failed");
  }
  responder.observe_cycle(false, request, true);
  if (responder.response_valid() || !responder.request_ready() ||
      responder.service_requests() != 1 || responder.service_responses() != 1) {
    throw std::runtime_error("service responder response fire accounting failed");
  }
  request.a7 = kSysExit;
  request.a0 = 0;
  request.identity.bid = {true, false, 23};
  responder.observe_cycle(true, request, false);
  if (!responder.exit_observed() || responder.exit_code() != 0 ||
      responder.exit_request().a7 != kSysExit ||
      responder.exit_request().identity.bid.value != 23 ||
      responder.response_valid() || responder.request_ready() ||
      responder.service_requests() != 2 || responder.service_responses() != 1 ||
      !responder.have_last_service_nr() || responder.last_service_nr() != kSysExit) {
    throw std::runtime_error("service responder exit syscall terminal latch failed");
  }
  responder.observe_cycle(false, request, true);
  if (responder.service_requests() != 2 || responder.service_responses() != 1 ||
      !responder.exit_observed() || responder.exit_request().identity.bid.value != 23 ||
      responder.response_valid() || responder.request_ready()) {
    throw std::runtime_error("service responder exit identity/no-response failed");
  }
  ReducedServiceResponder failing_exit_responder;
  request.a7 = kSysExit;
  request.a0 = 42;
  failing_exit_responder.observe_cycle(true, request, true);
  if (!failing_exit_responder.exit_observed() || failing_exit_responder.exit_code() != 42 ||
      failing_exit_responder.response_valid() ||
      failing_exit_responder.service_responses() != 0) {
    throw std::runtime_error("service responder nonzero exit accounting failed");
  }
  ReducedServiceResponder unknown_responder;
  request.a7 = 9999;
  unknown_responder.observe_cycle(true, request, false);
  if (!unknown_responder.unsupported() || unknown_responder.unsupported_service_nr() != 9999 ||
      unknown_responder.response_valid() || unknown_responder.request_ready()) {
    throw std::runtime_error("service responder unknown syscall fail-closed failed");
  }
  Args args;
  args.memory_hex = "memory.mem";
  args.commit_trace = "commit.jsonl";
  args.event_trace = "events.jsonl";
  args.uart_output = "uart.txt";
  args.manifest = "/tmp/linxcore-natural-self-test-manifest.json";
  args.reset_pc = 0x1000;
  args.reset_sp = 0x2000;
  args.max_cycles = 12;
  write_manifest_atomic(
      args,
      "finisher_pass",
      9,
      3,
      4,
      4,
      1,
      1,
      true,
      kSysSetTidAddress,
      false,
      0,
      false,
      0,
      kFinisherPass,
      true,
      "A\n");
  std::cout << "benchmark-autonomous-natural-self-test: ok\n";
  return 0;
}

int main(int argc, char **argv) {
  try {
    if (argc == 2 && std::string(argv[1]) == "--self-test") {
      return run_self_test();
    }
    if (argc > 1 && std::string(argv[1]) == "--self-test-case") {
      return run_terminal_self_test(argc, argv);
    }
    (void)parse_args(argc, argv);
    std::cerr << "self-test build only supports --self-test and argument validation\n";
    return 2;
  } catch (const std::exception &exc) {
    std::cerr << "error: " << exc.what() << "\n";
    return 2;
  }
}
#else
struct EventSample {
  bool fetch_req_fire = false;
  bool fetch_resp_fire = false;
  bool commit_valid = false;
  bool source_out_fire = false;
  bool source_restart_valid = false;
  bool source_blocked = false;
  bool source_active = false;
  bool source_waiting_response = false;
  bool source_packet_valid = false;
  bool block_marker_stop_redirect_valid = false;
  bool marker_redirect_fire = false;
  bool marker_redirect_pending = false;
  bool source_out_ready = false;
  bool dense_slot_in_ready = false;
  bool dense_slot_out_ready = false;
  bool path_decode_ready = false;
  bool path_renamed_out_ready = false;
  bool issue_in_ready = false;
  bool local_incoming_blocked = false;
  bool marker_retire_lifecycle_fire = false;
  bool commit_head_valid = false;
  bool dec_ren_head_rid_valid = false;
  bool renamed_out_valid = false;
  bool renamed_accepted = false;
  bool tu_rename_blocked_by_t_alloc = false;
  bool tu_rename_blocked_by_u_alloc = false;
  bool tu_retire_command_valid = false;
  bool tu_retire_command_fire = false;
  bool tu_retire_local_block_commit_pending = false;
  bool tu_retire_local_block_commit_valid = false;
  bool tu_retire_local_block_commit_ready = false;
  bool tu_retire_local_block_commit_fire = false;
  bool tu_retire_accepted = false;
  bool tu_retire_miss = false;
  bool tu_retire_release_mismatch = false;
  bool tu_retire_unsupported = false;
  bool gpr_commit_accepted = false;
  bool rob_rename_update_attempt_valid = false;
  bool rob_rename_update_ready = false;
  bool rob_rename_update_fire = false;
  bool rob_rename_update_ignored = false;
  bool issue_enqueue_fire = false;
  bool issue_input_valid = false;
  bool issue_input_bid_valid = false;
  bool issue_input_bid_wrap = false;
  bool issue_input_rid_valid = false;
  bool issue_input_rid_wrap = false;
  bool issue_pick_fire = false;
  bool issue_fire = false;
  bool issue_output_valid = false;
  bool issue_output_bid_valid = false;
  bool issue_output_bid_wrap = false;
  bool issue_output_rid_valid = false;
  bool issue_output_rid_wrap = false;
  bool issue_head_valid = false;
  bool issue_head_issued = false;
  bool issue_head_bid_valid = false;
  bool issue_head_bid_wrap = false;
  bool issue_head_rid_valid = false;
  bool issue_head_rid_wrap = false;
  bool issue_all_sources_ready = false;
  bool issue_selected_valid = false;
  bool issue_selected_read_ready = false;
  bool issue_scalar_sp_order_blocked = false;
  bool scalar_sp_stid0_issue_head_valid = false;
  bool scalar_sp_stid0_issue_head_bid_valid = false;
  bool scalar_sp_stid0_issue_head_bid_wrap = false;
  bool scalar_sp_stid0_issue_head_rid_valid = false;
  bool scalar_sp_stid0_issue_head_rid_wrap = false;
  bool p_wakeup_valid = false;
  bool p_wakeup_head_match = false;
  bool execute_accepted = false;
  bool execute_accepted_identity_valid = false;
  bool execute_accepted_bid_valid = false;
  bool execute_accepted_bid_wrap = false;
  bool execute_accepted_rid_valid = false;
  bool execute_accepted_rid_wrap = false;
  bool execute_busy = false;
  bool execute_unsupported = false;
  bool execute_complete_valid = false;
  bool rob_dealloc_block_last_valid = false;
  bool block_scalar_done_fire = false;
  bool block_retire_fire = false;
  bool scalar_lr_reservation_valid_stid0 = false;
  bool scalar_lr_reservation_protocol_error = false;
  bool scalar_lr_reservation_blocked_by_flush = false;
  bool scalar_lr_reservation_committed_store_invalidate = false;
  bool store_sta_queue_valid = false;
  bool store_std_queue_valid = false;
  bool store_sta_dequeue_fire = false;
  bool store_std_dequeue_fire = false;
  bool store_sta_insert_ready = false;
  bool store_std_insert_ready = false;
  bool store_selected_sta = false;
  bool store_selected_std = false;
  bool store_blocked_by_sta_exec = false;
  bool store_blocked_by_std_exec = false;
  bool store_stq_insert_valid = false;
  bool store_stq_insert_accepted = false;
  bool store_stq_insert_conflict = false;
  bool store_stq_empty = false;
  bool store_stq_full = false;
  bool store_stq_stall = false;
  std::uint64_t fetch_req_pc = 0;
  std::uint64_t fetch_resp_pc = 0;
  std::uint64_t commit_pc = 0;
  std::uint64_t source_current_pc = 0;
  std::uint64_t source_restart_pc = 0;
  std::uint64_t block_marker_stop_redirect_pc = 0;
  std::uint64_t marker_redirect_pc = 0;
  std::uint64_t body_cut_advance_bytes = 0;
  std::uint64_t f4_total_len_bytes = 0;
  std::uint64_t fret_condition_bits = 0;
  std::uint64_t continuation_bits = 0;
  std::uint64_t local_pending_counts = 0;
  std::uint64_t local_ready_masks = 0;
  std::uint64_t decode_block_bits = 0;
  std::uint64_t decode_ready_bits = 0;
  std::uint64_t tu_rename_source_underflow_mask = 0;
  std::uint64_t tu_rename_t_used_entries = 0;
  std::uint64_t tu_rename_u_used_entries = 0;
  std::uint64_t gpr_reservation_count = 0;
  std::uint64_t gpr_reservation_need = 0;
  std::uint64_t gpr_free_count = 0;
  std::uint64_t gpr_mapq_valid_count = 0;
  std::uint64_t gpr_mapq_free_count = 0;
  std::uint64_t gpr_free_list_mismatch_count = 0;
  std::uint64_t gpr_commit_block_bid = 0;
  std::uint64_t gpr_committed_mapq_count = 0;
  std::uint64_t gpr_released_phys_count = 0;
  std::uint64_t commit_head_status = 0;
  std::uint64_t commit_head_rob_value = 0;
  std::uint64_t rob_occupied_mask = 0;
  std::uint64_t rob_completed_mask = 0;
  std::uint64_t rob_dealloc_valid_mask = 0;
  std::uint64_t rob_dealloc_count = 0;
  std::uint64_t rob_dealloc_block_last_block_bid = 0;
  std::uint64_t block_scalar_done_bid = 0;
  std::uint64_t block_retire_bid = 0;
  std::uint64_t dec_ren_head_pc = 0;
  std::uint64_t dec_ren_head_rid_value = 0;
  std::uint64_t issue_head_pc = 0;
  std::uint64_t issue_head_stid = 0;
  std::uint64_t issue_head_bid_value = 0;
  std::uint64_t issue_head_rid_value = 0;
  std::uint64_t issue_input_pc = 0;
  std::uint64_t issue_input_opcode = 0;
  std::uint64_t issue_input_bid_value = 0;
  std::uint64_t issue_input_rid_value = 0;
  std::uint64_t issue_input_stid = 0;
  std::uint64_t issue_output_pc = 0;
  std::uint64_t issue_output_opcode = 0;
  std::uint64_t issue_output_bid_value = 0;
  std::uint64_t issue_output_rid_value = 0;
  std::uint64_t issue_output_stid = 0;
  std::uint64_t issue_head_src_valid_mask = 0;
  std::uint64_t issue_head_src_phys_tag[3] = {};
  std::uint64_t issue_source_ready_mask = 0;
  std::uint64_t issue_selected_index = 0;
  std::uint64_t issue_stage_bits = 0;
  std::uint64_t issue_blocked_bits = 0;
  std::uint64_t issue_bank_scalar_sp_order_blocked_mask = 0;
  std::uint64_t scalar_sp_stid0_issue_head_bid_value = 0;
  std::uint64_t scalar_sp_stid0_issue_head_rid_value = 0;
  std::uint64_t rf_ready_mask = 0;
  std::uint64_t p_wakeup_tag = 0;
  std::uint64_t execute_unsupported_opcode = 0;
  std::uint64_t execute_accepted_pc = 0;
  std::uint64_t execute_accepted_opcode = 0;
  std::uint64_t execute_accepted_bid_value = 0;
  std::uint64_t execute_accepted_rid_value = 0;
  std::uint64_t execute_accepted_stid = 0;
  std::uint64_t execute_complete_rob_value = 0;
  std::uint64_t execute_complete_pc = 0;
  std::uint64_t execute_complete_src_phys_valid_mask = 0;
  std::uint64_t execute_complete_src_phys_tag[3] = {};
  std::uint64_t rob_complete_arbiter_bits = 0;
  std::uint64_t rob_complete_result_bits = 0;
  std::uint64_t scalar_lr_reservation_line_stid0 = 0;
  std::uint64_t scalar_lr_reservation_count = 0;
  std::uint64_t store_sta_queue_count = 0;
  std::uint64_t store_std_queue_count = 0;
  std::uint64_t store_stq_insert_index = 0;
  std::uint64_t store_stq_occupied_mask = 0;
  std::uint64_t store_stq_wait_mask = 0;
  std::uint64_t store_stq_commit_mask = 0;
  std::uint64_t store_stq_addr_ready_mask = 0;
  std::uint64_t store_stq_data_ready_mask = 0;
  std::uint64_t store_stq_resident_count = 0;
  std::uint64_t store_stq_outstanding_wait_count = 0;
};

struct PerfCounters {
  std::uint64_t fetch_req_fire = 0;
  std::uint64_t fetch_resp_fire = 0;
  std::uint64_t fetch_source_active_cycles = 0;
  std::uint64_t fetch_source_blocked_cycles = 0;
  std::uint64_t fetch_waiting_response_cycles = 0;
  std::uint64_t fetch_packet_valid_cycles = 0;
  std::uint64_t fetch_source_out_fire = 0;
  std::uint64_t fetch_f4_valid_halfword_slots = 0;
  std::uint64_t fetch_f4_total_len_bytes = 0;

  std::uint64_t decode_ready_cycles = 0;
  std::uint64_t decode_blocked_cycles = 0;
  std::array<std::uint64_t, 16> decode_block_bits_hist = {};
  std::uint64_t rename_valid_cycles = 0;
  std::uint64_t rename_accepted = 0;
  std::uint64_t rename_blocked_cycles = 0;
  std::uint64_t rob_rename_update_attempts = 0;
  std::uint64_t rob_rename_update_fire = 0;
  std::uint64_t rob_rename_update_blocked = 0;
  std::uint64_t rob_rename_update_ignored = 0;

  std::uint64_t issue_input_valid_cycles = 0;
  std::uint64_t issue_enqueue_fire = 0;
  std::uint64_t issue_selected_valid_cycles = 0;
  std::uint64_t issue_pick_fire = 0;
  std::uint64_t issue_fire = 0;
  std::uint64_t issue_head_valid_cycles = 0;
  std::uint64_t issue_head_issued_cycles = 0;
  std::uint64_t issue_head_unissued_cycles = 0;
  std::uint64_t issue_head_residency_runs = 0;
  std::uint64_t issue_head_residency_max = 0;
  std::uint64_t issue_head_current_run = 0;
  std::uint64_t issue_head_last_key = 0;
  bool issue_head_last_valid = false;
  std::array<std::uint64_t, 16> issue_blocked_bits_hist = {};
  std::array<std::uint64_t, 4> issue_blocked_bit_cycles = {};

  std::uint64_t execute_accepted = 0;
  std::uint64_t execute_busy_cycles = 0;
  std::uint64_t execute_complete_valid = 0;
  std::uint64_t execute_unsupported = 0;

  std::uint64_t completion_accepted = 0;
  std::uint64_t completion_ignored = 0;
  std::uint64_t completion_selected_execute = 0;
  std::uint64_t completion_selected_replay = 0;
  std::uint64_t completion_replay_blocked_by_execute = 0;
  std::uint64_t completion_selected_without_accept = 0;

  std::array<std::uint64_t, 5> commit_rows_per_cycle_hist = {};
  std::array<std::uint64_t, 5> rob_dealloc_count_hist = {};
  std::uint64_t commit_head_valid_cycles = 0;
  std::uint64_t commit_head_invalid_cycles = 0;
  std::array<std::uint64_t, 8> commit_head_status_hist = {};

  std::uint64_t load_lookup_valid = 0;
  std::uint64_t load_execute_granted = 0;
  std::uint64_t load_replay_granted = 0;
  std::uint64_t load_wait_cycles = 0;

  std::uint64_t store_observe = 0;
  std::uint64_t store_observe_pair = 0;
  std::uint64_t store_sta_dequeue = 0;
  std::uint64_t store_std_dequeue = 0;
  std::uint64_t store_stq_insert_valid = 0;
  std::uint64_t store_stq_insert_accepted = 0;
  std::uint64_t store_stq_insert_conflict = 0;
  std::uint64_t store_stq_full_cycles = 0;
  std::uint64_t store_stq_stall_cycles = 0;
  std::uint64_t store_queue_wait_cycles = 0;

  std::uint64_t service_request_valid_cycles = 0;
  std::uint64_t service_request_fire = 0;
  std::uint64_t service_response_valid_cycles = 0;
  std::uint64_t service_response_fire = 0;
  std::uint64_t service_wait_cycles = 0;

  void observe(
      const EventSample &sample,
      std::uint64_t commit_rows,
      std::uint64_t rob_dealloc_count,
      bool load_lookup_valid,
      bool load_execute_granted,
      bool load_replay_granted,
      bool store_observe_valid,
      bool store_observe_pair_valid,
      bool service_request_valid,
      bool service_request_fire_now,
      bool service_response_valid,
      bool service_response_fire_now) {
    fetch_req_fire += sample.fetch_req_fire ? 1 : 0;
    fetch_resp_fire += sample.fetch_resp_fire ? 1 : 0;
    fetch_source_active_cycles += sample.source_active ? 1 : 0;
    fetch_source_blocked_cycles += sample.source_blocked ? 1 : 0;
    fetch_waiting_response_cycles += sample.source_waiting_response ? 1 : 0;
    fetch_packet_valid_cycles += sample.source_packet_valid ? 1 : 0;
    fetch_source_out_fire += sample.source_out_fire ? 1 : 0;
    fetch_f4_total_len_bytes += sample.f4_total_len_bytes;
    fetch_f4_valid_halfword_slots += sample.f4_total_len_bytes / 2U;

    decode_ready_cycles += sample.path_decode_ready ? 1 : 0;
    decode_blocked_cycles += sample.decode_block_bits != 0 ? 1 : 0;
    decode_block_bits_hist[clamp_hist_index(sample.decode_block_bits, decode_block_bits_hist.size() - 1)]++;
    rename_valid_cycles += sample.renamed_out_valid ? 1 : 0;
    rename_accepted += sample.renamed_accepted ? 1 : 0;
    rename_blocked_cycles += (sample.renamed_out_valid && !sample.renamed_accepted) ? 1 : 0;
    rob_rename_update_attempts += sample.rob_rename_update_attempt_valid ? 1 : 0;
    rob_rename_update_fire += sample.rob_rename_update_fire ? 1 : 0;
    rob_rename_update_blocked +=
        (sample.rob_rename_update_attempt_valid && !sample.rob_rename_update_ready) ? 1 : 0;
    rob_rename_update_ignored += sample.rob_rename_update_ignored ? 1 : 0;

    issue_input_valid_cycles += sample.issue_input_valid ? 1 : 0;
    issue_enqueue_fire += sample.issue_enqueue_fire ? 1 : 0;
    issue_selected_valid_cycles += sample.issue_selected_valid ? 1 : 0;
    issue_pick_fire += sample.issue_pick_fire ? 1 : 0;
    issue_fire += sample.issue_fire ? 1 : 0;
    issue_blocked_bits_hist[clamp_hist_index(sample.issue_blocked_bits, issue_blocked_bits_hist.size() - 1)]++;
    for (std::size_t bit = 0; bit < issue_blocked_bit_cycles.size(); ++bit) {
      issue_blocked_bit_cycles[bit] += ((sample.issue_blocked_bits >> bit) & 1ULL) != 0 ? 1 : 0;
    }
    if (sample.issue_head_valid) {
      ++issue_head_valid_cycles;
      issue_head_issued_cycles += sample.issue_head_issued ? 1 : 0;
      issue_head_unissued_cycles += sample.issue_head_issued ? 0 : 1;
      const std::uint64_t key =
          (sample.issue_head_pc << 16U) ^
          (sample.issue_head_stid << 12U) ^
          (sample.issue_head_bid_value << 6U) ^
          sample.issue_head_rid_value ^
          (sample.issue_head_issued ? (1ULL << 63U) : 0);
      if (!issue_head_last_valid || key != issue_head_last_key) {
        if (issue_head_last_valid) {
          ++issue_head_residency_runs;
          if (issue_head_current_run > issue_head_residency_max) {
            issue_head_residency_max = issue_head_current_run;
          }
        }
        issue_head_last_valid = true;
        issue_head_last_key = key;
        issue_head_current_run = 1;
      } else {
        ++issue_head_current_run;
      }
    } else if (issue_head_last_valid) {
      ++issue_head_residency_runs;
      if (issue_head_current_run > issue_head_residency_max) {
        issue_head_residency_max = issue_head_current_run;
      }
      issue_head_last_valid = false;
      issue_head_current_run = 0;
    }

    execute_accepted += sample.execute_accepted ? 1 : 0;
    execute_busy_cycles += sample.execute_busy ? 1 : 0;
    execute_complete_valid += sample.execute_complete_valid ? 1 : 0;
    execute_unsupported += sample.execute_unsupported ? 1 : 0;

    const bool complete_accepted = (sample.rob_complete_result_bits & 0x1U) != 0;
    const bool complete_ignored = (sample.rob_complete_result_bits & 0x2U) != 0;
    const bool selected_execute = (sample.rob_complete_arbiter_bits & 0x1U) != 0;
    const bool selected_replay = (sample.rob_complete_arbiter_bits & 0x2U) != 0;
    completion_accepted += complete_accepted ? 1 : 0;
    completion_ignored += complete_ignored ? 1 : 0;
    completion_selected_execute += selected_execute ? 1 : 0;
    completion_selected_replay += selected_replay ? 1 : 0;
    completion_replay_blocked_by_execute += (sample.rob_complete_arbiter_bits & 0x4U) != 0 ? 1 : 0;
    completion_selected_without_accept +=
        ((selected_execute || selected_replay) && !complete_accepted) ? 1 : 0;

    commit_rows_per_cycle_hist[clamp_hist_index(commit_rows, commit_rows_per_cycle_hist.size() - 1)]++;
    rob_dealloc_count_hist[clamp_hist_index(rob_dealloc_count, rob_dealloc_count_hist.size() - 1)]++;
    if (sample.commit_head_valid) {
      ++commit_head_valid_cycles;
      commit_head_status_hist[clamp_hist_index(sample.commit_head_status, commit_head_status_hist.size() - 1)]++;
    } else {
      ++commit_head_invalid_cycles;
    }

    this->load_lookup_valid += load_lookup_valid ? 1 : 0;
    this->load_execute_granted += load_execute_granted ? 1 : 0;
    this->load_replay_granted += load_replay_granted ? 1 : 0;
    load_wait_cycles += (load_lookup_valid && !load_execute_granted && !load_replay_granted) ? 1 : 0;

    store_observe += store_observe_valid ? 1 : 0;
    store_observe_pair += store_observe_pair_valid ? 1 : 0;
    store_sta_dequeue += sample.store_sta_dequeue_fire ? 1 : 0;
    store_std_dequeue += sample.store_std_dequeue_fire ? 1 : 0;
    store_stq_insert_valid += sample.store_stq_insert_valid ? 1 : 0;
    store_stq_insert_accepted += sample.store_stq_insert_accepted ? 1 : 0;
    store_stq_insert_conflict += sample.store_stq_insert_conflict ? 1 : 0;
    store_stq_full_cycles += sample.store_stq_full ? 1 : 0;
    store_stq_stall_cycles += sample.store_stq_stall ? 1 : 0;
    store_queue_wait_cycles +=
        ((sample.store_sta_queue_valid && !sample.store_sta_dequeue_fire) ||
         (sample.store_std_queue_valid && !sample.store_std_dequeue_fire)) ? 1 : 0;

    service_request_valid_cycles += service_request_valid ? 1 : 0;
    service_request_fire += service_request_fire_now ? 1 : 0;
    service_response_valid_cycles += service_response_valid ? 1 : 0;
    service_response_fire += service_response_fire_now ? 1 : 0;
    service_wait_cycles +=
        ((service_request_valid && !service_request_fire_now) ||
         (service_response_valid && !service_response_fire_now)) ? 1 : 0;
  }

  void finish() {
    if (issue_head_last_valid) {
      ++issue_head_residency_runs;
      if (issue_head_current_run > issue_head_residency_max) {
        issue_head_residency_max = issue_head_current_run;
      }
      issue_head_last_valid = false;
      issue_head_current_run = 0;
    }
  }
};

template <std::size_t N>
void write_json_array(std::ostringstream &out, const std::array<std::uint64_t, N> &values) {
  out << "[";
  for (std::size_t i = 0; i < values.size(); ++i) {
    if (i != 0) {
      out << ",";
    }
    out << values[i];
  }
  out << "]";
}

std::string perf_manifest_json(const PerfCounters &perf, std::uint64_t cycles, std::uint64_t commits) {
  std::ostringstream out;
  out << "{";
  out << "\"summary\":{"
      << "\"cycles\":" << cycles
      << ",\"commits\":" << commits
      << ",\"ipc\":" << std::setprecision(12)
      << (cycles == 0 ? 0.0 : static_cast<double>(commits) / static_cast<double>(cycles))
      << "}";
  out << ",\"frontend\":{"
      << "\"fetch_req_fire\":" << perf.fetch_req_fire
      << ",\"fetch_resp_fire\":" << perf.fetch_resp_fire
      << ",\"source_active_cycles\":" << perf.fetch_source_active_cycles
      << ",\"source_blocked_cycles\":" << perf.fetch_source_blocked_cycles
      << ",\"waiting_response_cycles\":" << perf.fetch_waiting_response_cycles
      << ",\"packet_valid_cycles\":" << perf.fetch_packet_valid_cycles
      << ",\"source_out_fire\":" << perf.fetch_source_out_fire
      << ",\"f4_valid_halfword_slots\":" << perf.fetch_f4_valid_halfword_slots
      << ",\"f4_total_len_bytes\":" << perf.fetch_f4_total_len_bytes
      << "}";
  out << ",\"decode_rename\":{"
      << "\"decode_ready_cycles\":" << perf.decode_ready_cycles
      << ",\"decode_blocked_cycles\":" << perf.decode_blocked_cycles
      << ",\"decode_block_bits_hist\":";
  write_json_array(out, perf.decode_block_bits_hist);
  out << ",\"rename_valid_cycles\":" << perf.rename_valid_cycles
      << ",\"rename_accepted\":" << perf.rename_accepted
      << ",\"rename_blocked_cycles\":" << perf.rename_blocked_cycles
      << ",\"rob_rename_update_attempts\":" << perf.rob_rename_update_attempts
      << ",\"rob_rename_update_fire\":" << perf.rob_rename_update_fire
      << ",\"rob_rename_update_blocked\":" << perf.rob_rename_update_blocked
      << ",\"rob_rename_update_ignored\":" << perf.rob_rename_update_ignored
      << "}";
  out << ",\"issue\":{"
      << "\"input_valid_cycles\":" << perf.issue_input_valid_cycles
      << ",\"enqueue_fire\":" << perf.issue_enqueue_fire
      << ",\"candidate_cycles\":" << perf.issue_selected_valid_cycles
      << ",\"pick_fire\":" << perf.issue_pick_fire
      << ",\"issue_fire\":" << perf.issue_fire
      << ",\"head_valid_cycles\":" << perf.issue_head_valid_cycles
      << ",\"head_issued_cycles\":" << perf.issue_head_issued_cycles
      << ",\"head_unissued_cycles\":" << perf.issue_head_unissued_cycles
      << ",\"head_residency_runs\":" << perf.issue_head_residency_runs
      << ",\"head_residency_max\":" << perf.issue_head_residency_max
      << ",\"blocked_bits_hist\":";
  write_json_array(out, perf.issue_blocked_bits_hist);
  out << ",\"blocked_bit_cycles\":";
  write_json_array(out, perf.issue_blocked_bit_cycles);
  out << "}";
  out << ",\"execute\":{"
      << "\"accepted\":" << perf.execute_accepted
      << ",\"busy_cycles\":" << perf.execute_busy_cycles
      << ",\"complete_valid\":" << perf.execute_complete_valid
      << ",\"unsupported\":" << perf.execute_unsupported
      << "}";
  out << ",\"completion\":{"
      << "\"accepted\":" << perf.completion_accepted
      << ",\"ignored\":" << perf.completion_ignored
      << ",\"selected_execute\":" << perf.completion_selected_execute
      << ",\"selected_replay\":" << perf.completion_selected_replay
      << ",\"replay_blocked_by_execute\":" << perf.completion_replay_blocked_by_execute
      << ",\"selected_without_accept\":" << perf.completion_selected_without_accept
      << "}";
  out << ",\"commit\":{"
      << "\"rows_per_cycle_hist\":";
  write_json_array(out, perf.commit_rows_per_cycle_hist);
  out << ",\"rob_dealloc_count_hist\":";
  write_json_array(out, perf.rob_dealloc_count_hist);
  out << "}";
  out << ",\"rob_head\":{"
      << "\"valid_cycles\":" << perf.commit_head_valid_cycles
      << ",\"invalid_cycles\":" << perf.commit_head_invalid_cycles
      << ",\"status_hist\":";
  write_json_array(out, perf.commit_head_status_hist);
  out << "}";
  out << ",\"load_store_service\":{"
      << "\"load_lookup_valid\":" << perf.load_lookup_valid
      << ",\"load_execute_granted\":" << perf.load_execute_granted
      << ",\"load_replay_granted\":" << perf.load_replay_granted
      << ",\"load_wait_cycles\":" << perf.load_wait_cycles
      << ",\"store_observe\":" << perf.store_observe
      << ",\"store_observe_pair\":" << perf.store_observe_pair
      << ",\"store_sta_dequeue\":" << perf.store_sta_dequeue
      << ",\"store_std_dequeue\":" << perf.store_std_dequeue
      << ",\"store_stq_insert_valid\":" << perf.store_stq_insert_valid
      << ",\"store_stq_insert_accepted\":" << perf.store_stq_insert_accepted
      << ",\"store_stq_insert_conflict\":" << perf.store_stq_insert_conflict
      << ",\"store_stq_full_cycles\":" << perf.store_stq_full_cycles
      << ",\"store_stq_stall_cycles\":" << perf.store_stq_stall_cycles
      << ",\"store_queue_wait_cycles\":" << perf.store_queue_wait_cycles
      << ",\"service_request_valid_cycles\":" << perf.service_request_valid_cycles
      << ",\"service_request_fire\":" << perf.service_request_fire
      << ",\"service_response_valid_cycles\":" << perf.service_response_valid_cycles
      << ",\"service_response_fire\":" << perf.service_response_fire
      << ",\"service_wait_cycles\":" << perf.service_wait_cycles
      << "}";
  out << "}";
  return out.str();
}

void write_hex_field(std::ofstream &out, const char *name, std::uint64_t value) {
  out << ",\"" << name << "\":\"0x" << std::hex << value << std::dec << "\"";
}

bool sample_changed(const EventSample &a, const EventSample &b) {
  return a.source_current_pc != b.source_current_pc ||
         a.source_blocked != b.source_blocked ||
         a.source_active != b.source_active ||
         a.source_waiting_response != b.source_waiting_response ||
         a.source_packet_valid != b.source_packet_valid ||
         a.source_restart_valid != b.source_restart_valid ||
         a.source_restart_pc != b.source_restart_pc ||
         a.source_out_ready != b.source_out_ready ||
         a.dense_slot_in_ready != b.dense_slot_in_ready ||
         a.dense_slot_out_ready != b.dense_slot_out_ready ||
         a.path_decode_ready != b.path_decode_ready ||
         a.path_renamed_out_ready != b.path_renamed_out_ready ||
         a.issue_in_ready != b.issue_in_ready ||
         a.local_incoming_blocked != b.local_incoming_blocked ||
         a.marker_retire_lifecycle_fire != b.marker_retire_lifecycle_fire ||
         a.block_marker_stop_redirect_valid != b.block_marker_stop_redirect_valid ||
         a.block_marker_stop_redirect_pc != b.block_marker_stop_redirect_pc ||
         a.marker_redirect_fire != b.marker_redirect_fire ||
         a.marker_redirect_pending != b.marker_redirect_pending ||
         a.marker_redirect_pc != b.marker_redirect_pc ||
         a.body_cut_advance_bytes != b.body_cut_advance_bytes ||
         a.f4_total_len_bytes != b.f4_total_len_bytes ||
         a.fret_condition_bits != b.fret_condition_bits ||
         a.continuation_bits != b.continuation_bits ||
         a.local_pending_counts != b.local_pending_counts ||
         a.local_ready_masks != b.local_ready_masks ||
         a.decode_block_bits != b.decode_block_bits ||
         a.decode_ready_bits != b.decode_ready_bits ||
         a.tu_rename_source_underflow_mask != b.tu_rename_source_underflow_mask ||
         a.tu_rename_blocked_by_t_alloc != b.tu_rename_blocked_by_t_alloc ||
         a.tu_rename_blocked_by_u_alloc != b.tu_rename_blocked_by_u_alloc ||
         a.tu_rename_t_used_entries != b.tu_rename_t_used_entries ||
         a.tu_rename_u_used_entries != b.tu_rename_u_used_entries ||
         a.tu_retire_command_valid != b.tu_retire_command_valid ||
         a.tu_retire_command_fire != b.tu_retire_command_fire ||
         a.tu_retire_local_block_commit_pending != b.tu_retire_local_block_commit_pending ||
         a.tu_retire_local_block_commit_valid != b.tu_retire_local_block_commit_valid ||
         a.tu_retire_local_block_commit_ready != b.tu_retire_local_block_commit_ready ||
         a.tu_retire_local_block_commit_fire != b.tu_retire_local_block_commit_fire ||
         a.tu_retire_accepted != b.tu_retire_accepted ||
         a.tu_retire_miss != b.tu_retire_miss ||
         a.tu_retire_release_mismatch != b.tu_retire_release_mismatch ||
         a.tu_retire_unsupported != b.tu_retire_unsupported ||
         a.gpr_reservation_count != b.gpr_reservation_count ||
         a.gpr_reservation_need != b.gpr_reservation_need ||
         a.gpr_free_count != b.gpr_free_count ||
         a.gpr_mapq_valid_count != b.gpr_mapq_valid_count ||
         a.gpr_mapq_free_count != b.gpr_mapq_free_count ||
         a.gpr_free_list_mismatch_count != b.gpr_free_list_mismatch_count ||
         a.gpr_commit_accepted != b.gpr_commit_accepted ||
         a.gpr_commit_block_bid != b.gpr_commit_block_bid ||
         a.gpr_committed_mapq_count != b.gpr_committed_mapq_count ||
         a.gpr_released_phys_count != b.gpr_released_phys_count ||
         a.rob_rename_update_attempt_valid != b.rob_rename_update_attempt_valid ||
         a.rob_rename_update_ready != b.rob_rename_update_ready ||
         a.rob_rename_update_fire != b.rob_rename_update_fire ||
         a.rob_rename_update_ignored != b.rob_rename_update_ignored ||
         a.commit_head_valid != b.commit_head_valid ||
         a.commit_head_status != b.commit_head_status ||
         a.commit_head_rob_value != b.commit_head_rob_value ||
         a.rob_occupied_mask != b.rob_occupied_mask ||
         a.rob_completed_mask != b.rob_completed_mask ||
         a.rob_dealloc_valid_mask != b.rob_dealloc_valid_mask ||
         a.rob_dealloc_count != b.rob_dealloc_count ||
         a.rob_dealloc_block_last_valid != b.rob_dealloc_block_last_valid ||
         a.rob_dealloc_block_last_block_bid != b.rob_dealloc_block_last_block_bid ||
         a.block_scalar_done_fire != b.block_scalar_done_fire ||
         a.block_scalar_done_bid != b.block_scalar_done_bid ||
         a.block_retire_fire != b.block_retire_fire ||
         a.block_retire_bid != b.block_retire_bid ||
         a.scalar_lr_reservation_valid_stid0 != b.scalar_lr_reservation_valid_stid0 ||
         a.scalar_lr_reservation_line_stid0 != b.scalar_lr_reservation_line_stid0 ||
         a.scalar_lr_reservation_count != b.scalar_lr_reservation_count ||
         a.scalar_lr_reservation_protocol_error != b.scalar_lr_reservation_protocol_error ||
         a.scalar_lr_reservation_blocked_by_flush != b.scalar_lr_reservation_blocked_by_flush ||
         a.scalar_lr_reservation_committed_store_invalidate !=
             b.scalar_lr_reservation_committed_store_invalidate ||
         a.store_sta_queue_valid != b.store_sta_queue_valid ||
         a.store_std_queue_valid != b.store_std_queue_valid ||
         a.store_sta_dequeue_fire != b.store_sta_dequeue_fire ||
         a.store_std_dequeue_fire != b.store_std_dequeue_fire ||
         a.store_sta_queue_count != b.store_sta_queue_count ||
         a.store_std_queue_count != b.store_std_queue_count ||
         a.store_sta_insert_ready != b.store_sta_insert_ready ||
         a.store_std_insert_ready != b.store_std_insert_ready ||
         a.store_selected_sta != b.store_selected_sta ||
         a.store_selected_std != b.store_selected_std ||
         a.store_blocked_by_sta_exec != b.store_blocked_by_sta_exec ||
         a.store_blocked_by_std_exec != b.store_blocked_by_std_exec ||
         a.store_stq_insert_valid != b.store_stq_insert_valid ||
         a.store_stq_insert_accepted != b.store_stq_insert_accepted ||
         a.store_stq_insert_conflict != b.store_stq_insert_conflict ||
         a.store_stq_insert_index != b.store_stq_insert_index ||
         a.store_stq_occupied_mask != b.store_stq_occupied_mask ||
         a.store_stq_wait_mask != b.store_stq_wait_mask ||
         a.store_stq_commit_mask != b.store_stq_commit_mask ||
         a.store_stq_addr_ready_mask != b.store_stq_addr_ready_mask ||
         a.store_stq_data_ready_mask != b.store_stq_data_ready_mask ||
         a.store_stq_resident_count != b.store_stq_resident_count ||
         a.store_stq_outstanding_wait_count != b.store_stq_outstanding_wait_count ||
         a.store_stq_empty != b.store_stq_empty ||
         a.store_stq_full != b.store_stq_full ||
         a.store_stq_stall != b.store_stq_stall ||
         a.dec_ren_head_rid_valid != b.dec_ren_head_rid_valid ||
         a.dec_ren_head_pc != b.dec_ren_head_pc ||
         a.dec_ren_head_rid_value != b.dec_ren_head_rid_value ||
         a.renamed_out_valid != b.renamed_out_valid ||
         a.renamed_accepted != b.renamed_accepted ||
         a.issue_enqueue_fire != b.issue_enqueue_fire ||
         a.issue_input_valid != b.issue_input_valid ||
         a.issue_input_pc != b.issue_input_pc ||
         a.issue_input_opcode != b.issue_input_opcode ||
         a.issue_input_bid_valid != b.issue_input_bid_valid ||
         a.issue_input_bid_wrap != b.issue_input_bid_wrap ||
         a.issue_input_bid_value != b.issue_input_bid_value ||
         a.issue_input_rid_valid != b.issue_input_rid_valid ||
         a.issue_input_rid_wrap != b.issue_input_rid_wrap ||
         a.issue_input_rid_value != b.issue_input_rid_value ||
         a.issue_input_stid != b.issue_input_stid ||
         a.issue_pick_fire != b.issue_pick_fire ||
         a.issue_fire != b.issue_fire ||
         a.issue_output_valid != b.issue_output_valid ||
         a.issue_output_pc != b.issue_output_pc ||
         a.issue_output_opcode != b.issue_output_opcode ||
         a.issue_output_bid_valid != b.issue_output_bid_valid ||
         a.issue_output_bid_wrap != b.issue_output_bid_wrap ||
         a.issue_output_bid_value != b.issue_output_bid_value ||
         a.issue_output_rid_valid != b.issue_output_rid_valid ||
         a.issue_output_rid_wrap != b.issue_output_rid_wrap ||
         a.issue_output_rid_value != b.issue_output_rid_value ||
         a.issue_output_stid != b.issue_output_stid ||
         a.issue_head_valid != b.issue_head_valid ||
         a.issue_head_issued != b.issue_head_issued ||
         a.issue_head_bid_valid != b.issue_head_bid_valid ||
         a.issue_head_bid_wrap != b.issue_head_bid_wrap ||
         a.issue_head_rid_valid != b.issue_head_rid_valid ||
         a.issue_head_rid_wrap != b.issue_head_rid_wrap ||
         a.issue_head_pc != b.issue_head_pc ||
         a.issue_head_stid != b.issue_head_stid ||
         a.issue_head_bid_value != b.issue_head_bid_value ||
         a.issue_head_rid_value != b.issue_head_rid_value ||
         a.issue_head_src_valid_mask != b.issue_head_src_valid_mask ||
         a.issue_head_src_phys_tag[0] != b.issue_head_src_phys_tag[0] ||
         a.issue_head_src_phys_tag[1] != b.issue_head_src_phys_tag[1] ||
         a.issue_head_src_phys_tag[2] != b.issue_head_src_phys_tag[2] ||
         a.issue_source_ready_mask != b.issue_source_ready_mask ||
         a.issue_all_sources_ready != b.issue_all_sources_ready ||
         a.issue_selected_valid != b.issue_selected_valid ||
         a.issue_selected_index != b.issue_selected_index ||
         a.issue_selected_read_ready != b.issue_selected_read_ready ||
         a.issue_stage_bits != b.issue_stage_bits ||
         a.issue_blocked_bits != b.issue_blocked_bits ||
         a.issue_scalar_sp_order_blocked != b.issue_scalar_sp_order_blocked ||
         a.issue_bank_scalar_sp_order_blocked_mask != b.issue_bank_scalar_sp_order_blocked_mask ||
         a.scalar_sp_stid0_issue_head_valid != b.scalar_sp_stid0_issue_head_valid ||
         a.scalar_sp_stid0_issue_head_bid_valid != b.scalar_sp_stid0_issue_head_bid_valid ||
         a.scalar_sp_stid0_issue_head_bid_wrap != b.scalar_sp_stid0_issue_head_bid_wrap ||
         a.scalar_sp_stid0_issue_head_bid_value != b.scalar_sp_stid0_issue_head_bid_value ||
         a.scalar_sp_stid0_issue_head_rid_valid != b.scalar_sp_stid0_issue_head_rid_valid ||
         a.scalar_sp_stid0_issue_head_rid_wrap != b.scalar_sp_stid0_issue_head_rid_wrap ||
         a.scalar_sp_stid0_issue_head_rid_value != b.scalar_sp_stid0_issue_head_rid_value ||
         a.rf_ready_mask != b.rf_ready_mask ||
         a.p_wakeup_valid != b.p_wakeup_valid ||
         a.p_wakeup_tag != b.p_wakeup_tag ||
         a.p_wakeup_head_match != b.p_wakeup_head_match ||
         a.execute_accepted != b.execute_accepted ||
         a.execute_accepted_identity_valid != b.execute_accepted_identity_valid ||
         a.execute_accepted_pc != b.execute_accepted_pc ||
         a.execute_accepted_opcode != b.execute_accepted_opcode ||
         a.execute_accepted_bid_valid != b.execute_accepted_bid_valid ||
         a.execute_accepted_bid_wrap != b.execute_accepted_bid_wrap ||
         a.execute_accepted_bid_value != b.execute_accepted_bid_value ||
         a.execute_accepted_rid_valid != b.execute_accepted_rid_valid ||
         a.execute_accepted_rid_wrap != b.execute_accepted_rid_wrap ||
         a.execute_accepted_rid_value != b.execute_accepted_rid_value ||
         a.execute_accepted_stid != b.execute_accepted_stid ||
         a.execute_busy != b.execute_busy ||
         a.execute_unsupported != b.execute_unsupported ||
         a.execute_unsupported_opcode != b.execute_unsupported_opcode ||
         a.execute_complete_valid != b.execute_complete_valid ||
         a.execute_complete_rob_value != b.execute_complete_rob_value ||
         a.execute_complete_pc != b.execute_complete_pc ||
         a.execute_complete_src_phys_valid_mask != b.execute_complete_src_phys_valid_mask ||
         a.execute_complete_src_phys_tag[0] != b.execute_complete_src_phys_tag[0] ||
         a.execute_complete_src_phys_tag[1] != b.execute_complete_src_phys_tag[1] ||
         a.execute_complete_src_phys_tag[2] != b.execute_complete_src_phys_tag[2] ||
         a.rob_complete_arbiter_bits != b.rob_complete_arbiter_bits ||
         a.rob_complete_result_bits != b.rob_complete_result_bits;
}

void write_event(
    std::ofstream &out,
    std::uint64_t cycle,
    const char *kind,
    const EventSample &sample,
    bool pending_fetch_valid,
    std::uint64_t pending_fetch_pc) {
  out << "{\"schema\":\"linxcore.benchmark_autonomous_natural_event.v1\""
      << ",\"cycle\":" << cycle
      << ",\"kind\":\"" << kind << "\""
      << ",\"fetch_req_fire\":" << (sample.fetch_req_fire ? "true" : "false")
      << ",\"fetch_resp_fire\":" << (sample.fetch_resp_fire ? "true" : "false")
      << ",\"commit_valid\":" << (sample.commit_valid ? "true" : "false")
      << ",\"source_out_fire\":" << (sample.source_out_fire ? "true" : "false")
      << ",\"source_restart_valid\":" << (sample.source_restart_valid ? "true" : "false")
      << ",\"source_blocked\":" << (sample.source_blocked ? "true" : "false")
      << ",\"source_active\":" << (sample.source_active ? "true" : "false")
      << ",\"source_waiting_response\":"
      << (sample.source_waiting_response ? "true" : "false")
      << ",\"source_packet_valid\":" << (sample.source_packet_valid ? "true" : "false")
      << ",\"block_marker_stop_redirect_valid\":"
      << (sample.block_marker_stop_redirect_valid ? "true" : "false")
      << ",\"marker_redirect_fire\":" << (sample.marker_redirect_fire ? "true" : "false")
      << ",\"marker_redirect_pending\":"
      << (sample.marker_redirect_pending ? "true" : "false")
      << ",\"source_out_ready\":" << (sample.source_out_ready ? "true" : "false")
      << ",\"dense_slot_in_ready\":"
      << (sample.dense_slot_in_ready ? "true" : "false")
      << ",\"dense_slot_out_ready\":"
      << (sample.dense_slot_out_ready ? "true" : "false")
      << ",\"path_decode_ready\":" << (sample.path_decode_ready ? "true" : "false")
      << ",\"path_renamed_out_ready\":"
      << (sample.path_renamed_out_ready ? "true" : "false")
      << ",\"issue_in_ready\":" << (sample.issue_in_ready ? "true" : "false")
      << ",\"local_incoming_blocked\":"
      << (sample.local_incoming_blocked ? "true" : "false")
      << ",\"marker_retire_lifecycle_fire\":"
      << (sample.marker_retire_lifecycle_fire ? "true" : "false")
      << ",\"gpr_commit_accepted\":"
      << (sample.gpr_commit_accepted ? "true" : "false")
      << ",\"rob_rename_update_attempt_valid\":"
      << (sample.rob_rename_update_attempt_valid ? "true" : "false")
      << ",\"rob_rename_update_ready\":"
      << (sample.rob_rename_update_ready ? "true" : "false")
      << ",\"rob_rename_update_fire\":"
      << (sample.rob_rename_update_fire ? "true" : "false")
      << ",\"rob_rename_update_ignored\":"
      << (sample.rob_rename_update_ignored ? "true" : "false")
      << ",\"commit_head_valid\":" << (sample.commit_head_valid ? "true" : "false")
      << ",\"dec_ren_head_rid_valid\":"
      << (sample.dec_ren_head_rid_valid ? "true" : "false")
      << ",\"renamed_out_valid\":" << (sample.renamed_out_valid ? "true" : "false")
      << ",\"renamed_accepted\":" << (sample.renamed_accepted ? "true" : "false")
      << ",\"tu_rename_blocked_by_t_alloc\":"
      << (sample.tu_rename_blocked_by_t_alloc ? "true" : "false")
      << ",\"tu_rename_blocked_by_u_alloc\":"
      << (sample.tu_rename_blocked_by_u_alloc ? "true" : "false")
      << ",\"tu_retire_command_valid\":"
      << (sample.tu_retire_command_valid ? "true" : "false")
      << ",\"tu_retire_command_fire\":"
      << (sample.tu_retire_command_fire ? "true" : "false")
      << ",\"tu_retire_local_block_commit_pending\":"
      << (sample.tu_retire_local_block_commit_pending ? "true" : "false")
      << ",\"tu_retire_local_block_commit_valid\":"
      << (sample.tu_retire_local_block_commit_valid ? "true" : "false")
      << ",\"tu_retire_local_block_commit_ready\":"
      << (sample.tu_retire_local_block_commit_ready ? "true" : "false")
      << ",\"tu_retire_local_block_commit_fire\":"
      << (sample.tu_retire_local_block_commit_fire ? "true" : "false")
      << ",\"tu_retire_accepted\":"
      << (sample.tu_retire_accepted ? "true" : "false")
      << ",\"tu_retire_miss\":" << (sample.tu_retire_miss ? "true" : "false")
      << ",\"tu_retire_release_mismatch\":"
      << (sample.tu_retire_release_mismatch ? "true" : "false")
      << ",\"tu_retire_unsupported\":"
      << (sample.tu_retire_unsupported ? "true" : "false")
      << ",\"issue_enqueue_fire\":" << (sample.issue_enqueue_fire ? "true" : "false")
      << ",\"issue_input_valid\":" << (sample.issue_input_valid ? "true" : "false")
      << ",\"issue_input_bid_valid\":" << (sample.issue_input_bid_valid ? "true" : "false")
      << ",\"issue_input_bid_wrap\":" << (sample.issue_input_bid_wrap ? "true" : "false")
      << ",\"issue_input_rid_valid\":" << (sample.issue_input_rid_valid ? "true" : "false")
      << ",\"issue_input_rid_wrap\":" << (sample.issue_input_rid_wrap ? "true" : "false")
      << ",\"issue_pick_fire\":" << (sample.issue_pick_fire ? "true" : "false")
      << ",\"issue_fire\":" << (sample.issue_fire ? "true" : "false")
      << ",\"issue_output_valid\":" << (sample.issue_output_valid ? "true" : "false")
      << ",\"issue_output_bid_valid\":" << (sample.issue_output_bid_valid ? "true" : "false")
      << ",\"issue_output_bid_wrap\":" << (sample.issue_output_bid_wrap ? "true" : "false")
      << ",\"issue_output_rid_valid\":" << (sample.issue_output_rid_valid ? "true" : "false")
      << ",\"issue_output_rid_wrap\":" << (sample.issue_output_rid_wrap ? "true" : "false")
      << ",\"issue_head_valid\":" << (sample.issue_head_valid ? "true" : "false")
      << ",\"issue_head_issued\":" << (sample.issue_head_issued ? "true" : "false")
      << ",\"issue_head_bid_valid\":"
      << (sample.issue_head_bid_valid ? "true" : "false")
      << ",\"issue_head_bid_wrap\":"
      << (sample.issue_head_bid_wrap ? "true" : "false")
      << ",\"issue_head_rid_valid\":"
      << (sample.issue_head_rid_valid ? "true" : "false")
      << ",\"issue_head_rid_wrap\":"
      << (sample.issue_head_rid_wrap ? "true" : "false")
      << ",\"issue_all_sources_ready\":"
      << (sample.issue_all_sources_ready ? "true" : "false")
      << ",\"issue_selected_valid\":"
      << (sample.issue_selected_valid ? "true" : "false")
      << ",\"issue_selected_read_ready\":"
      << (sample.issue_selected_read_ready ? "true" : "false")
      << ",\"issue_scalar_sp_order_blocked\":"
      << (sample.issue_scalar_sp_order_blocked ? "true" : "false")
      << ",\"scalar_sp_stid0_issue_head_valid\":"
      << (sample.scalar_sp_stid0_issue_head_valid ? "true" : "false")
      << ",\"scalar_sp_stid0_issue_head_bid_valid\":"
      << (sample.scalar_sp_stid0_issue_head_bid_valid ? "true" : "false")
      << ",\"scalar_sp_stid0_issue_head_bid_wrap\":"
      << (sample.scalar_sp_stid0_issue_head_bid_wrap ? "true" : "false")
      << ",\"scalar_sp_stid0_issue_head_rid_valid\":"
      << (sample.scalar_sp_stid0_issue_head_rid_valid ? "true" : "false")
      << ",\"scalar_sp_stid0_issue_head_rid_wrap\":"
      << (sample.scalar_sp_stid0_issue_head_rid_wrap ? "true" : "false")
      << ",\"p_wakeup_valid\":" << (sample.p_wakeup_valid ? "true" : "false")
      << ",\"p_wakeup_head_match\":"
      << (sample.p_wakeup_head_match ? "true" : "false")
      << ",\"execute_accepted\":" << (sample.execute_accepted ? "true" : "false")
      << ",\"execute_accepted_identity_valid\":"
      << (sample.execute_accepted_identity_valid ? "true" : "false")
      << ",\"execute_accepted_bid_valid\":"
      << (sample.execute_accepted_bid_valid ? "true" : "false")
      << ",\"execute_accepted_bid_wrap\":"
      << (sample.execute_accepted_bid_wrap ? "true" : "false")
      << ",\"execute_accepted_rid_valid\":"
      << (sample.execute_accepted_rid_valid ? "true" : "false")
      << ",\"execute_accepted_rid_wrap\":"
      << (sample.execute_accepted_rid_wrap ? "true" : "false")
      << ",\"execute_busy\":" << (sample.execute_busy ? "true" : "false")
      << ",\"execute_unsupported\":"
      << (sample.execute_unsupported ? "true" : "false")
      << ",\"execute_complete_valid\":"
      << (sample.execute_complete_valid ? "true" : "false")
      << ",\"rob_dealloc_block_last_valid\":"
      << (sample.rob_dealloc_block_last_valid ? "true" : "false")
      << ",\"block_scalar_done_fire\":"
      << (sample.block_scalar_done_fire ? "true" : "false")
      << ",\"block_retire_fire\":"
      << (sample.block_retire_fire ? "true" : "false")
      << ",\"scalar_lr_reservation_valid_stid0\":"
      << (sample.scalar_lr_reservation_valid_stid0 ? "true" : "false")
      << ",\"scalar_lr_reservation_protocol_error\":"
      << (sample.scalar_lr_reservation_protocol_error ? "true" : "false")
      << ",\"scalar_lr_reservation_blocked_by_flush\":"
      << (sample.scalar_lr_reservation_blocked_by_flush ? "true" : "false")
      << ",\"scalar_lr_reservation_committed_store_invalidate\":"
      << (sample.scalar_lr_reservation_committed_store_invalidate ? "true" : "false")
      << ",\"store_sta_queue_valid\":"
      << (sample.store_sta_queue_valid ? "true" : "false")
      << ",\"store_std_queue_valid\":"
      << (sample.store_std_queue_valid ? "true" : "false")
      << ",\"store_sta_dequeue_fire\":"
      << (sample.store_sta_dequeue_fire ? "true" : "false")
      << ",\"store_std_dequeue_fire\":"
      << (sample.store_std_dequeue_fire ? "true" : "false")
      << ",\"store_sta_insert_ready\":"
      << (sample.store_sta_insert_ready ? "true" : "false")
      << ",\"store_std_insert_ready\":"
      << (sample.store_std_insert_ready ? "true" : "false")
      << ",\"store_selected_sta\":"
      << (sample.store_selected_sta ? "true" : "false")
      << ",\"store_selected_std\":"
      << (sample.store_selected_std ? "true" : "false")
      << ",\"store_blocked_by_sta_exec\":"
      << (sample.store_blocked_by_sta_exec ? "true" : "false")
      << ",\"store_blocked_by_std_exec\":"
      << (sample.store_blocked_by_std_exec ? "true" : "false")
      << ",\"store_stq_insert_valid\":"
      << (sample.store_stq_insert_valid ? "true" : "false")
      << ",\"store_stq_insert_accepted\":"
      << (sample.store_stq_insert_accepted ? "true" : "false")
      << ",\"store_stq_insert_conflict\":"
      << (sample.store_stq_insert_conflict ? "true" : "false")
      << ",\"store_stq_empty\":"
      << (sample.store_stq_empty ? "true" : "false")
      << ",\"store_stq_full\":"
      << (sample.store_stq_full ? "true" : "false")
      << ",\"store_stq_stall\":"
      << (sample.store_stq_stall ? "true" : "false")
      << ",\"pending_fetch_valid\":" << (pending_fetch_valid ? "true" : "false");
  write_hex_field(out, "source_current_pc", sample.source_current_pc);
  write_hex_field(out, "source_restart_pc", sample.source_restart_pc);
  write_hex_field(out, "block_marker_stop_redirect_pc", sample.block_marker_stop_redirect_pc);
  write_hex_field(out, "marker_redirect_pc", sample.marker_redirect_pc);
  write_hex_field(out, "body_cut_advance_bytes", sample.body_cut_advance_bytes);
  write_hex_field(out, "f4_total_len_bytes", sample.f4_total_len_bytes);
  write_hex_field(out, "fret_condition_bits", sample.fret_condition_bits);
  write_hex_field(out, "continuation_bits", sample.continuation_bits);
  write_hex_field(out, "local_pending_counts", sample.local_pending_counts);
  write_hex_field(out, "local_ready_masks", sample.local_ready_masks);
  write_hex_field(out, "decode_block_bits", sample.decode_block_bits);
  write_hex_field(out, "decode_ready_bits", sample.decode_ready_bits);
  write_hex_field(
      out, "tu_rename_source_underflow_mask", sample.tu_rename_source_underflow_mask);
  write_hex_field(out, "tu_rename_t_used_entries", sample.tu_rename_t_used_entries);
  write_hex_field(out, "tu_rename_u_used_entries", sample.tu_rename_u_used_entries);
  write_hex_field(out, "gpr_reservation_count", sample.gpr_reservation_count);
  write_hex_field(out, "gpr_reservation_need", sample.gpr_reservation_need);
  write_hex_field(out, "gpr_free_count", sample.gpr_free_count);
  write_hex_field(out, "gpr_mapq_valid_count", sample.gpr_mapq_valid_count);
  write_hex_field(out, "gpr_mapq_free_count", sample.gpr_mapq_free_count);
  write_hex_field(out, "gpr_free_list_mismatch_count", sample.gpr_free_list_mismatch_count);
  write_hex_field(out, "gpr_commit_block_bid", sample.gpr_commit_block_bid);
  write_hex_field(out, "gpr_committed_mapq_count", sample.gpr_committed_mapq_count);
  write_hex_field(out, "gpr_released_phys_count", sample.gpr_released_phys_count);
  write_hex_field(out, "commit_head_status", sample.commit_head_status);
  write_hex_field(out, "commit_head_rob_value", sample.commit_head_rob_value);
  write_hex_field(out, "rob_occupied_mask", sample.rob_occupied_mask);
  write_hex_field(out, "rob_completed_mask", sample.rob_completed_mask);
  write_hex_field(out, "rob_dealloc_valid_mask", sample.rob_dealloc_valid_mask);
  write_hex_field(out, "rob_dealloc_count", sample.rob_dealloc_count);
  write_hex_field(
      out, "rob_dealloc_block_last_block_bid", sample.rob_dealloc_block_last_block_bid);
  write_hex_field(out, "block_scalar_done_bid", sample.block_scalar_done_bid);
  write_hex_field(out, "block_retire_bid", sample.block_retire_bid);
  write_hex_field(out, "dec_ren_head_pc", sample.dec_ren_head_pc);
  write_hex_field(out, "dec_ren_head_rid_value", sample.dec_ren_head_rid_value);
  write_hex_field(out, "issue_head_pc", sample.issue_head_pc);
  write_hex_field(out, "issue_head_stid", sample.issue_head_stid);
  write_hex_field(out, "issue_head_bid_value", sample.issue_head_bid_value);
  write_hex_field(out, "issue_head_rid_value", sample.issue_head_rid_value);
  write_hex_field(out, "issue_input_pc", sample.issue_input_pc);
  write_hex_field(out, "issue_input_opcode", sample.issue_input_opcode);
  write_hex_field(out, "issue_input_bid_value", sample.issue_input_bid_value);
  write_hex_field(out, "issue_input_rid_value", sample.issue_input_rid_value);
  write_hex_field(out, "issue_input_stid", sample.issue_input_stid);
  write_hex_field(out, "issue_output_pc", sample.issue_output_pc);
  write_hex_field(out, "issue_output_opcode", sample.issue_output_opcode);
  write_hex_field(out, "issue_output_bid_value", sample.issue_output_bid_value);
  write_hex_field(out, "issue_output_rid_value", sample.issue_output_rid_value);
  write_hex_field(out, "issue_output_stid", sample.issue_output_stid);
  write_hex_field(out, "issue_head_src_valid_mask", sample.issue_head_src_valid_mask);
  write_hex_field(out, "issue_head_src0_phys_tag", sample.issue_head_src_phys_tag[0]);
  write_hex_field(out, "issue_head_src1_phys_tag", sample.issue_head_src_phys_tag[1]);
  write_hex_field(out, "issue_head_src2_phys_tag", sample.issue_head_src_phys_tag[2]);
  write_hex_field(out, "issue_source_ready_mask", sample.issue_source_ready_mask);
  write_hex_field(out, "issue_selected_index", sample.issue_selected_index);
  write_hex_field(out, "issue_stage_bits", sample.issue_stage_bits);
  write_hex_field(out, "issue_blocked_bits", sample.issue_blocked_bits);
  write_hex_field(
      out, "issue_bank_scalar_sp_order_blocked_mask",
      sample.issue_bank_scalar_sp_order_blocked_mask);
  write_hex_field(
      out, "scalar_sp_stid0_issue_head_bid_value",
      sample.scalar_sp_stid0_issue_head_bid_value);
  write_hex_field(
      out, "scalar_sp_stid0_issue_head_rid_value",
      sample.scalar_sp_stid0_issue_head_rid_value);
  write_hex_field(out, "rf_ready_mask", sample.rf_ready_mask);
  write_hex_field(out, "p_wakeup_tag", sample.p_wakeup_tag);
  write_hex_field(out, "execute_unsupported_opcode", sample.execute_unsupported_opcode);
  write_hex_field(out, "execute_accepted_pc", sample.execute_accepted_pc);
  write_hex_field(out, "execute_accepted_opcode", sample.execute_accepted_opcode);
  write_hex_field(out, "execute_accepted_bid_value", sample.execute_accepted_bid_value);
  write_hex_field(out, "execute_accepted_rid_value", sample.execute_accepted_rid_value);
  write_hex_field(out, "execute_accepted_stid", sample.execute_accepted_stid);
  write_hex_field(out, "execute_complete_rob_value", sample.execute_complete_rob_value);
  write_hex_field(out, "execute_complete_pc", sample.execute_complete_pc);
  write_hex_field(
      out, "execute_complete_src_phys_valid_mask", sample.execute_complete_src_phys_valid_mask);
  write_hex_field(out, "execute_complete_src0_phys_tag", sample.execute_complete_src_phys_tag[0]);
  write_hex_field(out, "execute_complete_src1_phys_tag", sample.execute_complete_src_phys_tag[1]);
  write_hex_field(out, "execute_complete_src2_phys_tag", sample.execute_complete_src_phys_tag[2]);
  write_hex_field(out, "rob_complete_arbiter_bits", sample.rob_complete_arbiter_bits);
  write_hex_field(out, "rob_complete_result_bits", sample.rob_complete_result_bits);
  write_hex_field(out, "scalar_lr_reservation_line_stid0",
                  sample.scalar_lr_reservation_line_stid0);
  write_hex_field(out, "scalar_lr_reservation_count", sample.scalar_lr_reservation_count);
  write_hex_field(out, "store_sta_queue_count", sample.store_sta_queue_count);
  write_hex_field(out, "store_std_queue_count", sample.store_std_queue_count);
  write_hex_field(out, "store_stq_insert_index", sample.store_stq_insert_index);
  write_hex_field(out, "store_stq_occupied_mask", sample.store_stq_occupied_mask);
  write_hex_field(out, "store_stq_wait_mask", sample.store_stq_wait_mask);
  write_hex_field(out, "store_stq_commit_mask", sample.store_stq_commit_mask);
  write_hex_field(out, "store_stq_addr_ready_mask", sample.store_stq_addr_ready_mask);
  write_hex_field(out, "store_stq_data_ready_mask", sample.store_stq_data_ready_mask);
  write_hex_field(out, "store_stq_resident_count", sample.store_stq_resident_count);
  write_hex_field(out, "store_stq_outstanding_wait_count",
                  sample.store_stq_outstanding_wait_count);
  if (sample.fetch_req_fire) {
    write_hex_field(out, "fetch_req_pc", sample.fetch_req_pc);
  }
  if (sample.fetch_resp_fire) {
    write_hex_field(out, "fetch_resp_pc", sample.fetch_resp_pc);
  }
  if (sample.commit_valid) {
    write_hex_field(out, "commit_pc", sample.commit_pc);
  }
  if (pending_fetch_valid) {
    write_hex_field(out, "pending_fetch_pc", pending_fetch_pc);
  }
  out << "}\n";
}

void clear_inputs(VLinxCoreBenchmarkAutonomousTop &dut) {
  dut.io_startValid = 0;
  dut.io_resetPc = 0;
  dut.io_resetSp = 0;
  dut.io_restartValid = 0;
  dut.io_restartPc = 0;
  dut.io_restartSp = 0;
  dut.io_flushValid = 0;
  dut.io_peId = 0;
  dut.io_threadId = 0;
  dut.io_fetchReqReady = 0;
  dut.io_fetchRespValid = 0;
  dut.io_fetchRespWindow = 0;
  dut.io_loadLookupData = 0;
  dut.io_loadPairFirstLookupData = 0;
  dut.io_reducedServiceRequest_ready = 0;
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

ReducedServiceRequest read_service_request(const VLinxCoreBenchmarkAutonomousTop &dut) {
  ReducedServiceRequest request;
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

void drive_service_responder(
    VLinxCoreBenchmarkAutonomousTop &dut,
    const ReducedServiceResponder &responder) {
  dut.io_reducedServiceRequest_ready = responder.request_ready() ? 1 : 0;
  dut.io_reducedServiceResponse_valid = responder.response_valid() ? 1 : 0;
  if (!responder.response_valid()) {
    return;
  }
  const auto &response = responder.response();
  dut.io_reducedServiceResponse_bits_requestType = response.request_type;
  dut.io_reducedServiceResponse_bits_identity_stid = response.identity.stid;
  dut.io_reducedServiceResponse_bits_identity_bid_valid = response.identity.bid.valid ? 1 : 0;
  dut.io_reducedServiceResponse_bits_identity_bid_value = response.identity.bid.value;
  dut.io_reducedServiceResponse_bits_identity_bid_wrap = response.identity.bid.wrap ? 1 : 0;
  dut.io_reducedServiceResponse_bits_identity_gid_valid = response.identity.gid.valid ? 1 : 0;
  dut.io_reducedServiceResponse_bits_identity_gid_value = response.identity.gid.value;
  dut.io_reducedServiceResponse_bits_identity_gid_wrap = response.identity.gid.wrap ? 1 : 0;
  dut.io_reducedServiceResponse_bits_identity_rid_valid = response.identity.rid.valid ? 1 : 0;
  dut.io_reducedServiceResponse_bits_identity_rid_value = response.identity.rid.value;
  dut.io_reducedServiceResponse_bits_identity_rid_wrap = response.identity.rid.wrap ? 1 : 0;
  dut.io_reducedServiceResponse_bits_a0 = response.a0;
}

void eval_with_memory(VLinxCoreBenchmarkAutonomousTop &dut, const SparseMemory &mem) {
  dut.eval();
  if (dut.io_loadLookupValid) {
    dut.io_loadLookupData = mem.read_u64_or_zero(dut.io_loadLookupAddr);
  }
  if (dut.io_loadPairFirstLookupValid) {
    dut.io_loadPairFirstLookupData =
        mem.read_u64_or_zero(dut.io_loadPairFirstLookupAddr);
  }
  dut.eval();
}

void tick(VLinxCoreBenchmarkAutonomousTop &dut, const SparseMemory &mem) {
  dut.clock = 0;
  eval_with_memory(dut, mem);
  dut.clock = 1;
  eval_with_memory(dut, mem);
  dut.clock = 0;
  eval_with_memory(dut, mem);
}

linxcore::chisel::CommitTraceJsonRow read_commit_row(
    const VLinxCoreBenchmarkAutonomousTop &dut,
    int slot) {
#define COMMIT_FIELD(name) (slot == 0 ? dut.io_commit_rows_0_##name : dut.io_commit_rows_1_##name)
  linxcore::chisel::CommitTraceJsonRow row;
  row.valid = COMMIT_FIELD(valid);
  row.seq = COMMIT_FIELD(seq);
  row.cycle = COMMIT_FIELD(cycle);
  row.slot = COMMIT_FIELD(slot);
  row.bid = COMMIT_FIELD(identity_bid);
  row.gid = COMMIT_FIELD(identity_gid);
  row.rid = COMMIT_FIELD(identity_rid);
  row.rob_valid = COMMIT_FIELD(rob_valid);
  row.rob_wrap = COMMIT_FIELD(rob_wrap);
  row.rob_value = COMMIT_FIELD(rob_value);
  row.block_bid_valid = COMMIT_FIELD(blockBidValid);
  row.block_bid = COMMIT_FIELD(blockBid);
  row.pc = COMMIT_FIELD(pc);
  row.insn = COMMIT_FIELD(insn);
  row.len = COMMIT_FIELD(len);
  row.wb_valid = COMMIT_FIELD(wb_valid);
  row.wb_rd = COMMIT_FIELD(wb_reg);
  row.wb_data = COMMIT_FIELD(wb_data);
  row.src0_valid = COMMIT_FIELD(src0_valid);
  row.src0_reg = COMMIT_FIELD(src0_reg);
  row.src0_data = COMMIT_FIELD(src0_data);
  row.src1_valid = COMMIT_FIELD(src1_valid);
  row.src1_reg = COMMIT_FIELD(src1_reg);
  row.src1_data = COMMIT_FIELD(src1_data);
  row.dst_valid = COMMIT_FIELD(dst_valid);
  row.dst_reg = COMMIT_FIELD(dst_reg);
  row.dst_data = COMMIT_FIELD(dst_data);
  row.mem_valid = COMMIT_FIELD(mem_valid);
  row.mem_is_store = COMMIT_FIELD(mem_isStore);
  row.mem_addr = COMMIT_FIELD(mem_addr);
  row.mem_wdata = COMMIT_FIELD(mem_wdata);
  row.mem_rdata = COMMIT_FIELD(mem_rdata);
  row.mem_size = COMMIT_FIELD(mem_size);
  row.trap_valid = COMMIT_FIELD(trap_valid);
  row.trap_cause = COMMIT_FIELD(trap_cause);
  row.trap_arg0 = COMMIT_FIELD(trap_arg0);
  row.next_pc = COMMIT_FIELD(nextPc);
#undef COMMIT_FIELD
  return row;
}

int main(int argc, char **argv) {
  try {
    Verilated::commandArgs(argc, argv);
    const Args args = parse_args(argc, argv);
    SparseMemory memory;
    memory.load_sparse_hex(args.memory_hex);

    std::ofstream trace(args.commit_trace);
    std::ofstream uart_out(args.uart_output);
    if (!trace) {
      throw std::runtime_error("failed to open commit trace: " + args.commit_trace);
    }
    std::ofstream events(args.event_trace);
    if (!events) {
      throw std::runtime_error("failed to open event trace: " + args.event_trace);
    }
    if (!uart_out) {
      throw std::runtime_error("failed to open UART output: " + args.uart_output);
    }

    VLinxCoreBenchmarkAutonomousTop dut;
    std::string uart;
    std::uint64_t commits = 0;
    std::uint64_t cycles = 0;
    std::uint16_t finisher_code = 0;
    bool finisher_pass = false;
    std::string status = "timeout";
    bool have_pending_fetch = false;
    std::uint64_t pending_fetch_pc = 0;
    std::uint64_t fetch_requests = 0;
    std::uint64_t fetch_responses = 0;
    ReducedServiceResponder service_responder;
    EventSample previous_sample;
    bool have_previous_sample = false;
    PerfCounters perf;

    clear_inputs(dut);
    dut.reset = 1;
    tick(dut, memory);
    tick(dut, memory);
    dut.reset = 0;

    for (; cycles < args.max_cycles && !Verilated::gotFinish(); ++cycles) {
      const bool start_cycle = cycles == 0;
      clear_inputs(dut);
      dut.io_startValid = start_cycle ? 1 : 0;
      dut.io_resetPc = args.reset_pc;
      dut.io_resetSp = args.reset_sp;
      dut.io_fetchReqReady = have_pending_fetch ? 0 : 1;
      if (have_pending_fetch) {
        dut.io_fetchRespValid = 1;
        dut.io_fetchRespWindow = memory.read_window(pending_fetch_pc);
      }
      drive_service_responder(dut, service_responder);
      eval_with_memory(dut, memory);

      const bool fetch_resp_fire =
          have_pending_fetch && dut.io_fetchRespValid && dut.io_fetchRespReady;
      const bool service_request_valid = dut.io_reducedServiceRequest_valid;
      const bool service_request_fire =
          dut.io_reducedServiceRequest_valid && dut.io_reducedServiceRequest_ready;
      const bool service_response_valid = dut.io_reducedServiceResponse_valid;
      const bool service_response_ready = dut.io_reducedServiceResponse_ready;
      const bool service_response_fire = service_response_valid && service_response_ready;
      const ReducedServiceRequest service_request =
          service_request_fire ? read_service_request(dut) : ReducedServiceRequest{};
      service_responder.observe_cycle(service_request_fire, service_request, service_response_ready);
      EventSample sample;
      sample.fetch_resp_fire = fetch_resp_fire;
      sample.fetch_resp_pc = pending_fetch_pc;
      const std::uint64_t commit_rows_this_cycle =
          (dut.io_commit_rows_0_valid ? 1ULL : 0ULL) +
          (dut.io_commit_rows_1_valid ? 1ULL : 0ULL);
      sample.commit_valid = commit_rows_this_cycle != 0;
      sample.commit_pc =
          dut.io_commit_rows_0_valid ? dut.io_commit_rows_0_pc : dut.io_commit_rows_1_pc;
      sample.source_out_fire = dut.io_sourceOutFire;
      sample.source_restart_valid = dut.io_sourceRestartValid;
      sample.source_blocked = dut.io_debugSourceBlocked;
      sample.source_active = dut.io_debugSourceActive;
      sample.source_waiting_response = dut.io_debugSourceWaitingResponse;
      sample.source_packet_valid = dut.io_debugSourcePacketValid;
      sample.source_restart_pc = dut.io_sourceRestartPc;
      sample.block_marker_stop_redirect_valid = dut.io_debugBlockMarkerStopRedirectValid;
      sample.block_marker_stop_redirect_pc = dut.io_debugBlockMarkerStopRedirectPc;
      sample.marker_redirect_fire = dut.io_debugMarkerRedirectFire;
      sample.marker_redirect_pending = dut.io_debugMarkerRedirectPending;
      sample.marker_redirect_pc = dut.io_debugMarkerRedirectPc;
      sample.body_cut_advance_bytes = dut.io_debugBodyCutAdvanceBytes;
      sample.f4_total_len_bytes = dut.io_debugF4TotalLenBytes;
      sample.fret_condition_bits = dut.io_debugFretConditionBits;
      sample.continuation_bits = dut.io_debugContinuationBits;
      const std::uint8_t readiness = dut.io_debugReadinessBits;
      sample.source_out_ready = (readiness & 0x01U) != 0;
      sample.dense_slot_in_ready = (readiness & 0x02U) != 0;
      sample.dense_slot_out_ready = (readiness & 0x04U) != 0;
      sample.path_decode_ready = (readiness & 0x08U) != 0;
      sample.path_renamed_out_ready = (readiness & 0x10U) != 0;
      sample.issue_in_ready = (readiness & 0x20U) != 0;
      sample.local_incoming_blocked = (readiness & 0x40U) != 0;
      sample.marker_retire_lifecycle_fire = (readiness & 0x80U) != 0;
      sample.local_pending_counts = dut.io_debugLocalPendingCounts;
      sample.local_ready_masks = dut.io_debugLocalReadyMasks;
      sample.decode_block_bits = dut.io_debugDecodeBlockBits;
      sample.decode_ready_bits = dut.io_debugDecodeReadyBits;
      sample.tu_rename_source_underflow_mask = dut.io_debugTuRenameSourceUnderflowMask;
      sample.tu_rename_blocked_by_t_alloc = dut.io_debugTuRenameBlockedByTAlloc;
      sample.tu_rename_blocked_by_u_alloc = dut.io_debugTuRenameBlockedByUAlloc;
      sample.tu_rename_t_used_entries = dut.io_debugTuRenameTUsedEntries;
      sample.tu_rename_u_used_entries = dut.io_debugTuRenameUUsedEntries;
      sample.tu_retire_command_valid = dut.io_debugTuRetireCommandValid;
      sample.tu_retire_command_fire = dut.io_debugTuRetireCommandFire;
      sample.tu_retire_local_block_commit_pending =
          dut.io_debugTuRetireLocalBlockCommitPending;
      sample.tu_retire_local_block_commit_valid = dut.io_debugTuRetireLocalBlockCommitValid;
      sample.tu_retire_local_block_commit_ready = dut.io_debugTuRetireLocalBlockCommitReady;
      sample.tu_retire_local_block_commit_fire = dut.io_debugTuRetireLocalBlockCommitFire;
      sample.tu_retire_accepted = dut.io_debugTuRetireAccepted;
      sample.tu_retire_miss = dut.io_debugTuRetireMiss;
      sample.tu_retire_release_mismatch = dut.io_debugTuRetireReleaseMismatch;
      sample.tu_retire_unsupported = dut.io_debugTuRetireUnsupported;
      sample.gpr_reservation_count = dut.io_debugGprReservationCount;
      sample.gpr_reservation_need = dut.io_debugGprReservationNeed;
      sample.gpr_free_count = dut.io_debugGprFreeCount;
      sample.gpr_mapq_valid_count = dut.io_debugGprMapQValidCount;
      sample.gpr_mapq_free_count = dut.io_debugGprMapQFreeCount;
      sample.gpr_free_list_mismatch_count = dut.io_debugGprFreeListMismatchCount;
      sample.gpr_commit_accepted = dut.io_debugGprCommitAccepted;
      sample.gpr_commit_block_bid = dut.io_debugGprCommitBlockBid;
      sample.gpr_committed_mapq_count = dut.io_debugGprCommittedMapQCount;
      sample.gpr_released_phys_count = dut.io_debugGprReleasedPhysCount;
      sample.rob_rename_update_attempt_valid = dut.io_debugRobRenameUpdateAttemptValid;
      sample.rob_rename_update_ready = dut.io_debugRobRenameUpdateReady;
      sample.rob_rename_update_fire = dut.io_debugRobRenameUpdateFire;
      sample.rob_rename_update_ignored = dut.io_debugRobRenameUpdateIgnored;
      sample.commit_head_valid = dut.io_debugCommitHeadValid;
      sample.commit_head_status = dut.io_debugCommitHeadStatus;
      sample.commit_head_rob_value = dut.io_debugCommitHeadRobValue;
      sample.rob_occupied_mask = dut.io_debugRobOccupiedMask;
      sample.rob_completed_mask = dut.io_debugRobCompletedMask;
      sample.rob_dealloc_valid_mask = dut.io_debugRobDeallocValidMask;
      sample.rob_dealloc_count = dut.io_debugRobDeallocCount;
      sample.rob_dealloc_block_last_valid = dut.io_debugRobDeallocBlockLastValid;
      sample.rob_dealloc_block_last_block_bid = dut.io_debugRobDeallocBlockLastBlockBid;
      sample.block_scalar_done_fire = dut.io_debugBlockScalarDoneFire;
      sample.block_scalar_done_bid = dut.io_debugBlockScalarDoneBid;
      sample.block_retire_fire = dut.io_debugBlockRetireFire;
      sample.block_retire_bid = dut.io_debugBlockRetireBid;
      sample.scalar_lr_reservation_valid_stid0 = dut.io_scalarLrReservationValidStid0;
      sample.scalar_lr_reservation_line_stid0 = dut.io_scalarLrReservationLineStid0;
      sample.scalar_lr_reservation_count = dut.io_scalarLrReservationCount;
      sample.scalar_lr_reservation_protocol_error =
          dut.io_scalarLrReservationProtocolError;
      sample.scalar_lr_reservation_blocked_by_flush =
          dut.io_scalarLrReservationBlockedByFlush;
      sample.scalar_lr_reservation_committed_store_invalidate =
          dut.io_scalarLrReservationCommittedStoreInvalidate;
      sample.store_sta_queue_valid = dut.io_storeStaQueueValid;
      sample.store_std_queue_valid = dut.io_storeStdQueueValid;
      sample.store_sta_dequeue_fire = dut.io_storeStaDequeueFire;
      sample.store_std_dequeue_fire = dut.io_storeStdDequeueFire;
      sample.store_sta_queue_count = dut.io_storeStaQueueCount;
      sample.store_std_queue_count = dut.io_storeStdQueueCount;
      sample.store_sta_insert_ready = dut.io_storeStaInsertReady;
      sample.store_std_insert_ready = dut.io_storeStdInsertReady;
      sample.store_selected_sta = dut.io_storeSelectedSta;
      sample.store_selected_std = dut.io_storeSelectedStd;
      sample.store_blocked_by_sta_exec = dut.io_storeBlockedByStaExec;
      sample.store_blocked_by_std_exec = dut.io_storeBlockedByStdExec;
      sample.store_stq_insert_valid = dut.io_storeStqInsertValid;
      sample.store_stq_insert_accepted = dut.io_storeStqInsertAccepted;
      sample.store_stq_insert_conflict = dut.io_storeStqInsertConflict;
      sample.store_stq_insert_index = dut.io_storeStqInsertIndex;
      sample.store_stq_occupied_mask = dut.io_storeStqOccupiedMask;
      sample.store_stq_wait_mask = dut.io_storeStqWaitMask;
      sample.store_stq_commit_mask = dut.io_storeStqCommitMask;
      sample.store_stq_addr_ready_mask = dut.io_storeStqAddrReadyMask;
      sample.store_stq_data_ready_mask = dut.io_storeStqDataReadyMask;
      sample.store_stq_resident_count = dut.io_storeStqResidentCount;
      sample.store_stq_outstanding_wait_count = dut.io_storeStqOutstandingWaitCount;
      sample.store_stq_empty = dut.io_storeStqEmpty;
      sample.store_stq_full = dut.io_storeStqFull;
      sample.store_stq_stall = dut.io_storeStqStall;
      sample.dec_ren_head_rid_valid = dut.io_debugDecRenHeadRidValid;
      sample.dec_ren_head_pc = dut.io_debugLocalHeadPc;
      sample.dec_ren_head_rid_value = dut.io_debugDecRenHeadRidValue;
      sample.renamed_out_valid = dut.io_debugRenamedOutValid;
      sample.renamed_accepted = dut.io_debugRenamedAccepted;
      sample.issue_enqueue_fire = dut.io_debugIssueEnqueueFire;
      sample.issue_input_valid = dut.io_debugIssueInputValid;
      sample.issue_input_pc = dut.io_debugIssueInputPc;
      sample.issue_input_opcode = dut.io_debugIssueInputOpcode;
      sample.issue_input_bid_valid = dut.io_debugIssueInputBidValid;
      sample.issue_input_bid_wrap = dut.io_debugIssueInputBidWrap;
      sample.issue_input_bid_value = dut.io_debugIssueInputBidValue;
      sample.issue_input_rid_valid = dut.io_debugIssueInputRidValid;
      sample.issue_input_rid_wrap = dut.io_debugIssueInputRidWrap;
      sample.issue_input_rid_value = dut.io_debugIssueInputRidValue;
      sample.issue_input_stid = dut.io_debugIssueInputStid;
      sample.issue_pick_fire = dut.io_debugIssuePickFire;
      sample.issue_fire = dut.io_debugIssueFire;
      sample.issue_output_valid = dut.io_debugIssueOutputValid;
      sample.issue_output_pc = dut.io_debugIssueOutputPc;
      sample.issue_output_opcode = dut.io_debugIssueOutputOpcode;
      sample.issue_output_bid_valid = dut.io_debugIssueOutputBidValid;
      sample.issue_output_bid_wrap = dut.io_debugIssueOutputBidWrap;
      sample.issue_output_bid_value = dut.io_debugIssueOutputBidValue;
      sample.issue_output_rid_valid = dut.io_debugIssueOutputRidValid;
      sample.issue_output_rid_wrap = dut.io_debugIssueOutputRidWrap;
      sample.issue_output_rid_value = dut.io_debugIssueOutputRidValue;
      sample.issue_output_stid = dut.io_debugIssueOutputStid;
      sample.issue_head_valid = dut.io_debugIssueHeadValid;
      sample.issue_head_issued = dut.io_debugIssueHeadIssued;
      sample.issue_head_pc = dut.io_debugIssueHeadPc;
      sample.issue_head_stid = dut.io_debugIssueHeadStid;
      sample.issue_head_bid_valid = dut.io_debugIssueHeadBidValid;
      sample.issue_head_bid_wrap = dut.io_debugIssueHeadBidWrap;
      sample.issue_head_bid_value = dut.io_debugIssueHeadBidValue;
      sample.issue_head_rid_valid = dut.io_debugIssueHeadRidValid;
      sample.issue_head_rid_wrap = dut.io_debugIssueHeadRidWrap;
      sample.issue_head_rid_value = dut.io_debugIssueHeadRidValue;
      sample.issue_head_src_valid_mask = dut.io_debugIssueHeadSrcValidMask;
      sample.issue_head_src_phys_tag[0] = dut.io_debugIssueHeadSrcPhysTag_0;
      sample.issue_head_src_phys_tag[1] = dut.io_debugIssueHeadSrcPhysTag_1;
      sample.issue_head_src_phys_tag[2] = dut.io_debugIssueHeadSrcPhysTag_2;
      sample.issue_source_ready_mask = dut.io_debugIssueSourceReadyMask;
      sample.issue_all_sources_ready = dut.io_debugIssueAllSourcesReady;
      sample.issue_selected_valid = dut.io_debugIssueSelectedValid;
      sample.issue_selected_index = dut.io_debugIssueSelectedIndex;
      sample.issue_selected_read_ready = dut.io_debugIssueSelectedReadReady;
      sample.issue_stage_bits = dut.io_debugIssueStageBits;
      sample.issue_blocked_bits = dut.io_debugIssueBlockedBits;
      sample.issue_scalar_sp_order_blocked = dut.io_debugIssueScalarSpOrderBlocked;
      sample.issue_bank_scalar_sp_order_blocked_mask =
          dut.io_debugIssueBankScalarSpOrderBlockedMask;
      sample.scalar_sp_stid0_issue_head_valid = dut.io_debugScalarSpStid0IssueHeadValid;
      sample.scalar_sp_stid0_issue_head_bid_valid =
          dut.io_debugScalarSpStid0IssueHeadBidValid;
      sample.scalar_sp_stid0_issue_head_bid_wrap =
          dut.io_debugScalarSpStid0IssueHeadBidWrap;
      sample.scalar_sp_stid0_issue_head_bid_value =
          dut.io_debugScalarSpStid0IssueHeadBidValue;
      sample.scalar_sp_stid0_issue_head_rid_valid =
          dut.io_debugScalarSpStid0IssueHeadRidValid;
      sample.scalar_sp_stid0_issue_head_rid_wrap =
          dut.io_debugScalarSpStid0IssueHeadRidWrap;
      sample.scalar_sp_stid0_issue_head_rid_value =
          dut.io_debugScalarSpStid0IssueHeadRidValue;
      sample.rf_ready_mask = dut.io_debugRfReadyMask;
      sample.p_wakeup_valid = dut.io_debugPWakeupValid;
      sample.p_wakeup_tag = dut.io_debugPWakeupTag;
      sample.p_wakeup_head_match =
          sample.p_wakeup_valid &&
          (((sample.issue_head_src_valid_mask & 0x1U) != 0 &&
            sample.issue_head_src_phys_tag[0] == sample.p_wakeup_tag) ||
           ((sample.issue_head_src_valid_mask & 0x2U) != 0 &&
            sample.issue_head_src_phys_tag[1] == sample.p_wakeup_tag) ||
           ((sample.issue_head_src_valid_mask & 0x4U) != 0 &&
            sample.issue_head_src_phys_tag[2] == sample.p_wakeup_tag));
      sample.execute_accepted = dut.io_debugExecuteAccepted;
      sample.execute_accepted_identity_valid = dut.io_debugExecuteAcceptedIdentityValid;
      sample.execute_accepted_pc = dut.io_debugExecuteAcceptedPc;
      sample.execute_accepted_opcode = dut.io_debugExecuteAcceptedOpcode;
      sample.execute_accepted_bid_valid = dut.io_debugExecuteAcceptedBidValid;
      sample.execute_accepted_bid_wrap = dut.io_debugExecuteAcceptedBidWrap;
      sample.execute_accepted_bid_value = dut.io_debugExecuteAcceptedBidValue;
      sample.execute_accepted_rid_valid = dut.io_debugExecuteAcceptedRidValid;
      sample.execute_accepted_rid_wrap = dut.io_debugExecuteAcceptedRidWrap;
      sample.execute_accepted_rid_value = dut.io_debugExecuteAcceptedRidValue;
      sample.execute_accepted_stid = dut.io_debugExecuteAcceptedStid;
      sample.execute_busy = dut.io_debugExecuteBusy;
      sample.execute_unsupported = dut.io_debugExecuteUnsupported;
      sample.execute_unsupported_opcode = dut.io_debugExecuteUnsupportedOpcode;
      sample.execute_complete_valid = dut.io_debugLocalExecuteCompleteValid;
      sample.execute_complete_rob_value = dut.io_debugExecuteCompleteRobValue;
      sample.execute_complete_pc = dut.io_debugLocalCompletePc;
      sample.execute_complete_src_phys_valid_mask = dut.io_debugExecuteCompleteSrcPhysValidMask;
      sample.execute_complete_src_phys_tag[0] = dut.io_debugExecuteCompleteSrcPhysTag_0;
      sample.execute_complete_src_phys_tag[1] = dut.io_debugExecuteCompleteSrcPhysTag_1;
      sample.execute_complete_src_phys_tag[2] = dut.io_debugExecuteCompleteSrcPhysTag_2;
      sample.rob_complete_arbiter_bits = dut.io_debugRobCompleteArbiterBits;
      sample.rob_complete_result_bits = dut.io_debugRobCompleteResultBits;
      if (fetch_resp_fire) {
        have_pending_fetch = false;
        ++fetch_responses;
      }
      if (!have_pending_fetch && dut.io_fetchReqValid && dut.io_fetchReqReady) {
        have_pending_fetch = true;
        pending_fetch_pc = dut.io_fetchReqPc;
        sample.fetch_req_fire = true;
        sample.fetch_req_pc = dut.io_fetchReqPc;
        ++fetch_requests;
      }
      sample.source_current_pc = dut.io_fetchCurrentPc;
      perf.observe(
          sample,
          commit_rows_this_cycle,
          sample.rob_dealloc_count,
          dut.io_loadLookupValid,
          dut.io_loadLookupExecuteGranted,
          dut.io_loadLookupReplayGranted,
          dut.io_storeObserveValid,
          dut.io_storeObservePairValid,
          service_request_valid,
          service_request_fire,
          service_response_valid,
          service_response_fire);
      const bool detailed_cycle_event =
          cycles < 8 || sample.fetch_req_fire || sample.fetch_resp_fire ||
          sample.commit_valid || sample.source_out_fire || sample.source_restart_valid ||
          sample.block_marker_stop_redirect_valid || sample.marker_redirect_fire ||
          sample.marker_redirect_pending || !have_previous_sample ||
          sample_changed(sample, previous_sample);
      const bool periodic_cycle_event =
          args.event_sample_period > 1 && (cycles % args.event_sample_period) == 0;
      const bool exceptional_cycle_event =
          cycles < 8 || sample.execute_unsupported || dut.io_trapValid ||
          dut.io_finisherWriteValid || !have_previous_sample;
      const bool write_cycle_event =
          args.event_sample_period == 1 ? detailed_cycle_event
                                        : (periodic_cycle_event || exceptional_cycle_event);
      if (write_cycle_event) {
        write_event(events, cycles, "cycle", sample, have_pending_fetch, pending_fetch_pc);
      }
      previous_sample = sample;
      have_previous_sample = true;

      for (int slot = 0; slot < 2; ++slot) {
        const auto row = read_commit_row(dut, slot);
        if (row.valid) {
          if (args.commit_sample_period == 1 ||
              (args.commit_sample_period > 1 && (commits % args.commit_sample_period) == 0)) {
            linxcore::chisel::write_dut_commit_jsonl(trace, row);
          }
          ++commits;
        }
      }

      if (dut.io_storeObserveValid) {
        memory.apply_store(
            dut.io_storeObserveAddr,
            dut.io_storeObserveData,
            dut.io_storeObserveSize,
            dut.io_storeObserveMask);
      }
      if (dut.io_storeObservePairValid) {
        memory.apply_store(
            dut.io_storeObservePairAddr,
            dut.io_storeObservePairData,
            dut.io_storeObservePairSize,
            dut.io_storeObservePairMask);
      }
      if (dut.io_uartWriteValid) {
        const char ch = static_cast<char>(dut.io_uartWriteByte & 0xffU);
        uart.push_back(ch);
        uart_out << ch;
      }
      if (dut.io_finisherWriteValid) {
        finisher_code = dut.io_finisherCode;
        finisher_pass = finisher_code_is_pass(finisher_code);
        status = terminal_status(true, finisher_pass, false, false, false);
        tick(dut, memory);
        ++cycles;
        break;
      }
      if (dut.io_trapValid) {
        status = terminal_status(false, false, true, false, false);
        tick(dut, memory);
        ++cycles;
        break;
      }
      if (dut.io_unsupportedInstruction) {
        status = terminal_status(false, false, false, true, false);
        tick(dut, memory);
        ++cycles;
        break;
      }
      if (service_responder.unsupported()) {
        status = terminal_status(false, false, false, true, false);
        tick(dut, memory);
        ++cycles;
        break;
      }
      if (service_responder.exit_observed()) {
        finisher_pass = service_responder.exit_code() == 0;
        finisher_code = finisher_pass ? kFinisherPass : 0;
        status = terminal_status(true, finisher_pass, false, false, false);
        tick(dut, memory);
        ++cycles;
        break;
      }
      if (dut.io_halted) {
        status = terminal_status(false, false, false, false, true);
        tick(dut, memory);
        ++cycles;
        break;
      }
      tick(dut, memory);
    }

    trace.close();
    EventSample terminal_sample = previous_sample;
    perf.finish();
    write_event(events, cycles, "terminal", terminal_sample, have_pending_fetch, pending_fetch_pc);
    events.close();
    uart_out.close();
    if (!trace || !events || !uart_out) {
      throw std::runtime_error("failed to flush output artifacts");
    }
    write_manifest_atomic(
        args,
        status,
        cycles,
        commits,
        fetch_requests,
        fetch_responses,
        service_responder.service_requests(),
        service_responder.service_responses(),
        service_responder.have_last_service_nr(),
        service_responder.last_service_nr(),
        service_responder.unsupported(),
        service_responder.unsupported_service_nr(),
        service_responder.exit_observed(),
        service_responder.exit_code(),
        finisher_code,
        finisher_pass,
        uart,
        perf_manifest_json(perf, cycles, commits));
    std::cerr << "benchmark-autonomous-natural status=" << status
              << " cycles=" << cycles
              << " commits=" << commits
              << " fetch_requests=" << fetch_requests
              << " fetch_responses=" << fetch_responses
              << " service_requests=" << service_responder.service_requests()
              << " service_responses=" << service_responder.service_responses()
              << " finisher_code=0x" << std::hex << finisher_code << std::dec
              << "\n";
    return status == "finisher_pass" ? 0 : 1;
  } catch (const std::exception &exc) {
    std::cerr << "error: " << exc.what() << "\n";
    return 2;
  }
}
#endif
