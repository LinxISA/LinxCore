#include "VCoreTOP.h"
#include "verilated.h"
#include "verilated_vpi.h"
#include "vpi_user.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr std::uint64_t kUartAddress = 0x10000000ULL;
constexpr std::uint64_t kFinisherAddress = 0x10009000ULL;
constexpr std::uint16_t kPassCode = 0x5555U;
constexpr std::uint64_t kInstructionTranslationAccessKind = 1;
constexpr int kPageOffsetBits = 12;

struct Arguments {
  std::string memory_hex;
  std::string manifest;
  std::string uart_output;
  std::string commit_trace;
  std::uint64_t reset_sp = 0x0000000007fefff0ULL;
  std::uint64_t max_cycles = 1000000;
  std::uint64_t heartbeat_cycles = 10000;
  std::uint64_t deadlock_cycles = 100000;
  bool validate_ports = false;
  int stid_count = 0;
  int gpr_arch_regs = 0;
  int sp_atag = 1;
  int trace_width = 0;
  int data_lanes = 0;
  int system_issue_lanes = 0;
  int retire_width = 0;
};

struct Response {
  bool valid = false;
  std::uint64_t identity = 0;
  std::uint64_t generation = 0;
  std::uint64_t address = 0;
  std::uint64_t access_kind = 0;
};

struct DataRequest {
  Response response;
  std::uint64_t command = 0;
  std::uint64_t data = 0;
  std::uint64_t mask = 0;
};

struct Activation {
  std::array<std::uint64_t, 6> trace{};
  std::uint64_t instruction_requests = 0;
  std::uint64_t data_requests = 0;
  std::uint64_t commits = 0;
};

struct LastTraceEvent {
  bool valid = false;
  std::uint64_t cycle = 0;
  std::uint64_t source = 0;
  std::uint64_t kind = 0;
  std::uint64_t pc = 0;
  std::uint64_t opcode = 0;
  std::uint64_t instruction_valid = 0;
  std::uint64_t instruction_id = 0;
  std::uint64_t instruction_epoch = 0;
  std::uint64_t rob_valid = 0;
  std::uint64_t rid_slot = 0;
  std::uint64_t member_index = 0;
};

std::uint64_t trace_count(const Activation &activation) {
  std::uint64_t total = 0;
  for (const auto count : activation.trace) total += count;
  return total;
}

std::uint64_t parse_integer(const std::string &text) {
  std::size_t consumed = 0;
  const auto value = std::stoull(text, &consumed, 0);
  if (consumed != text.size()) throw std::runtime_error("invalid integer: " + text);
  return value;
}

Arguments parse_arguments(int argc, char **argv) {
  Arguments result;
  for (int index = 1; index < argc; ++index) {
    const std::string option(argv[index]);
    if (option == "--memory-hex" && index + 1 < argc) {
      result.memory_hex = argv[++index];
    } else if (option == "--manifest" && index + 1 < argc) {
      result.manifest = argv[++index];
    } else if (option == "--uart-output" && index + 1 < argc) {
      result.uart_output = argv[++index];
    } else if (option == "--commit-trace" && index + 1 < argc) {
      result.commit_trace = argv[++index];
    } else if (option == "--reset-sp" && index + 1 < argc) {
      result.reset_sp = parse_integer(argv[++index]);
    } else if (option == "--max-cycles" && index + 1 < argc) {
      result.max_cycles = parse_integer(argv[++index]);
    } else if (option == "--heartbeat-cycles" && index + 1 < argc) {
      result.heartbeat_cycles = parse_integer(argv[++index]);
    } else if (option == "--deadlock-cycles" && index + 1 < argc) {
      result.deadlock_cycles = parse_integer(argv[++index]);
    } else if (option == "--validate-ports") {
      result.validate_ports = true;
    } else if (option == "--stid-count" && index + 1 < argc) {
      result.stid_count = parse_integer(argv[++index]);
    } else if (option == "--gpr-arch-regs" && index + 1 < argc) {
      result.gpr_arch_regs = parse_integer(argv[++index]);
    } else if (option == "--sp-atag" && index + 1 < argc) {
      result.sp_atag = parse_integer(argv[++index]);
    } else if (option == "--trace-width" && index + 1 < argc) {
      result.trace_width = parse_integer(argv[++index]);
    } else if (option == "--data-lanes" && index + 1 < argc) {
      result.data_lanes = parse_integer(argv[++index]);
    } else if (option == "--system-issue-lanes" && index + 1 < argc) {
      result.system_issue_lanes = parse_integer(argv[++index]);
    } else if (option == "--retire-width" && index + 1 < argc) {
      result.retire_width = parse_integer(argv[++index]);
    } else {
      throw std::runtime_error("unknown or incomplete option: " + option);
    }
  }
  if (result.memory_hex.empty() || result.manifest.empty() || result.uart_output.empty()) {
    throw std::runtime_error("--memory-hex, --uart-output, and --manifest are required");
  }
  if (result.stid_count <= 0 || result.gpr_arch_regs <= 0 ||
      result.trace_width <= 0 || result.data_lanes <= 0 ||
      result.system_issue_lanes <= 0 || result.retire_width <= 0) {
    throw std::runtime_error("profile metadata arguments are required");
  }
  return result;
}

class SparseMemory {
 public:
  explicit SparseMemory(const std::string &path) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("cannot open memory image: " + path);
    std::string address;
    std::string byte;
    while (input >> address) {
      if (!address.empty() && address.front() == '#') {
        std::getline(input, byte);
        continue;
      }
      if (!(input >> byte)) throw std::runtime_error("truncated memory image");
      bytes_[parse_integer(address)] = static_cast<std::uint8_t>(parse_integer(byte));
    }
  }

  std::size_t size() const { return bytes_.size(); }

  std::uint8_t read_byte(std::uint64_t address) const {
    const auto found = bytes_.find(address);
    return found == bytes_.end() ? 0 : found->second;
  }

  std::uint64_t read_word(std::uint64_t address) const {
    std::uint64_t value = 0;
    for (int byte = 0; byte < 8; ++byte) {
      value |= static_cast<std::uint64_t>(read_byte(address + byte)) << (8 * byte);
    }
    return value;
  }

  std::array<std::uint32_t, 16> read_line(std::uint64_t address) const {
    std::array<std::uint32_t, 16> words{};
    const auto base = address & ~std::uint64_t{63};
    for (int byte = 0; byte < 64; ++byte) {
      words[byte / 4] |= static_cast<std::uint32_t>(read_byte(base + byte)) << (8 * (byte % 4));
    }
    return words;
  }

  void write(std::uint64_t address, std::uint64_t data, std::uint64_t mask) {
    for (int byte = 0; byte < 8; ++byte) {
      if ((mask >> byte) & 1U) bytes_[address + byte] = (data >> (8 * byte)) & 0xffU;
    }
  }

 private:
  std::map<std::uint64_t, std::uint8_t> bytes_;
};

class Pins {
 public:
  std::optional<std::uint64_t> try_get(const std::string &leaf) const {
    const auto handle = try_find(leaf);
    if (handle == nullptr) return std::nullopt;
    s_vpi_value value{};
    value.format = vpiVectorVal;
    vpi_get_value(handle, &value);
    const auto low = static_cast<std::uint32_t>(value.value.vector[0].aval);
    const auto width = vpi_get(vpiSize, handle);
    if (width <= 32) return low;
    const auto high = static_cast<std::uint32_t>(value.value.vector[1].aval);
    return low | (static_cast<std::uint64_t>(high) << 32);
  }

  std::uint64_t get(const std::string &leaf) const {
    const auto value = try_get(leaf);
    if (value.has_value()) return *value;
    throw std::runtime_error("missing TOP port in VPI model: " + leaf);
  }

  void put(const std::string &leaf, std::uint64_t data) const {
    auto handle = find(leaf);
    std::array<s_vpi_vecval, 2> words{};
    words[0].aval = static_cast<PLI_UINT32>(data);
    words[1].aval = static_cast<PLI_UINT32>(data >> 32);
    s_vpi_value value{};
    value.format = vpiVectorVal;
    value.value.vector = words.data();
    vpi_put_value(handle, &value, nullptr, vpiNoDelay);
  }

  void put_wide(const std::string &leaf, const std::array<std::uint32_t, 16> &data) const {
    auto handle = find(leaf);
    std::array<s_vpi_vecval, 16> words{};
    for (std::size_t index = 0; index < words.size(); ++index) words[index].aval = data[index];
    s_vpi_value value{};
    value.format = vpiVectorVal;
    value.value.vector = words.data();
    vpi_put_value(handle, &value, nullptr, vpiNoDelay);
  }

 private:
  static vpiHandle try_find(const std::string &leaf) {
    for (const auto &prefix : {
                               std::string("TOP.CoreTOPHarness.dut."),
                               std::string("TOP."),
                               std::string("TOP.CoreTOP."),
                               std::string("CoreTOP."),
                               std::string("TOP.dut."), std::string("CoreTOPHarness.dut."),
                               std::string()}) {
      const auto name = prefix + leaf;
      if (auto handle = vpi_handle_by_name(reinterpret_cast<PLI_BYTE8 *>(const_cast<char *>(name.c_str())), nullptr)) {
        return handle;
      }
    }
    return nullptr;
  }

  static vpiHandle find(const std::string &leaf) {
    if (const auto handle = try_find(leaf)) return handle;
    throw std::runtime_error("missing TOP port in VPI model: " + leaf);
  }
};

void report_deadlock_state(const Pins &pins, const Arguments &arguments) {
  const std::string rob = "ooo.d3s1.rob.io_headStatus_";
  for (int stid = 0; stid < arguments.stid_count; ++stid) {
    const auto stem = rob + std::to_string(stid) + "_";
    const auto valid = pins.try_get(stem + "valid");
    if (!valid.has_value()) continue;
    std::cout << "top-natural-rob-head stid=" << stid
              << " valid=" << *valid
              << " completed=" << pins.get(stem + "completed")
              << " retired=" << pins.get(stem + "retired")
              << " transaction=" << pins.get(stem + "transactionId")
              << " pc=0x" << std::hex << pins.get(stem + "pc")
              << " instruction-bits=0x" << pins.get(stem + "instructionBits")
              << " opcode=0x" << pins.get(stem + "opcode") << std::dec
              << " instruction-id=" << pins.get(stem + "instruction_instructionId")
              << " epoch=" << pins.get(stem + "instruction_epoch")
              << " rid-slot=" << pins.get(stem + "rob_ridSlot")
              << " rid-generation=" << pins.get(stem + "rob_ridGeneration")
              << " member-index=" << pins.get(stem + "rob_memberIndex")
              << " resident-generation=" << pins.get(stem + "rob_residentGeneration")
              << std::endl;
  }

  const std::string iex = "iex.owner.implementation.io_";
  const auto issue_empty = pins.try_get(iex + "issueEmpty");
  const auto execution_empty = pins.try_get(iex + "executionEmpty");
  if (!issue_empty.has_value() || !execution_empty.has_value()) return;
  std::cout << "top-natural-iex-state issue-empty=" << *issue_empty
            << " execution-empty=" << *execution_empty;
  for (int issue_class = 0; issue_class < 8; ++issue_class) {
    for (int bank = 0; bank < 2; ++bank) {
      const auto suffix = std::to_string(issue_class) + "_" +
        std::to_string(bank);
      const auto resident = pins.try_get(iex + "residentEntries_" + suffix);
      const auto in_flight = pins.try_get(iex + "inFlightEntries_" + suffix);
      if (!resident.has_value() || !in_flight.has_value()) continue;
      std::cout << " class=" << issue_class << " bank=" << bank
                << " resident=" << *resident
                << " in-flight=" << *in_flight;
    }
  }
  std::cout << std::endl;

  const std::string bru =
    "iex.owner.implementation.issue.issue.issue.issue.scheduleRows_1_";
  for (int bank = 0; bank < 2; ++bank) {
    const auto stem = bru + std::to_string(bank) + "_0_";
    const auto valid = pins.try_get(stem + "valid");
    if (!valid.has_value()) continue;
    std::cout << "top-natural-bru-resident bank=" << bank
              << " valid=" << *valid
              << " in-flight=" << pins.get(stem + "inFlight")
              << " transaction=" << pins.get(stem + "transactionId")
              << " rid-slot=" << pins.get(stem + "member_group_ridSlot")
              << " member-index=" << pins.get(stem + "member_memberIndex")
              << " resident-generation="
              << pins.get(stem + "member_residentGeneration");
    for (int source = 0; source < 2; ++source) {
      const auto source_stem = stem + "sources_" +
        std::to_string(source) + "_";
      std::cout << " source" << source << "-valid="
                << pins.get(source_stem + "valid")
                << " source" << source << "-ready="
                << pins.get(source_stem + "ready")
                << " source" << source << "-spec-ready="
                << pins.get(source_stem + "specReady")
                << " source" << source << "-class="
                << pins.get(source_stem + "operandClass")
                << " source" << source << "-ptag="
                << pins.get(source_stem + "ptag")
                << " source" << source << "-p-generation="
                << pins.get(source_stem + "ptagGeneration")
                << " source" << source << "-local-tag="
                << pins.get(source_stem + "localTag")
                << " source" << source << "-sequence-valid="
                << pins.get(source_stem + "localSequence_valid")
                << " source" << source << "-sequence-index="
                << pins.get(source_stem + "localSequence_index")
                << " source" << source << "-sequence-generation="
                << pins.get(source_stem + "localSequence_generation");
    }
    std::cout << std::endl;
  }
}

std::string data_port(int lane, const std::string &suffix) {
  return "io_dataMemory" + suffix.substr(0, 1) + "_" + std::to_string(lane) + suffix.substr(1);
}

void drive_idle_inputs(const Pins &pins, const Arguments &arguments) {
  pins.put("io_interrupt_valid", 0);
  pins.put("io_debugRequest_valid", 0);
  pins.put("io_loadReissueRequest_valid", 0);
  pins.put("io_maintenance_valid", 0);
  pins.put("io_debugResponse_ready", 1);
  pins.put("io_cmdIssue_ready", 1);
  pins.put("io_memoryFault_ready", 1);
  pins.put("io_maintenanceResult_ready", 1);
  pins.put("io_commit_ready", 1);
  pins.put("io_trap_ready", 1);
  pins.put("io_trace_ready", 1);
  for (int lane = 0; lane < arguments.system_issue_lanes; ++lane) {
    pins.put("io_systemIssue_" + std::to_string(lane) + "_ready", 1);
  }
}

void drive_response(const Pins &pins, const std::string &stem, const Response &response,
                    const SparseMemory &memory) {
  pins.put(stem + "_valid", response.valid);
  pins.put(stem + "_bits_identity_value", response.identity);
  pins.put(stem + "_bits_identity_generation", response.generation);
  pins.put(stem + "_bits_address", response.address);
  const auto response_data =
    response.access_kind == kInstructionTranslationAccessKind
      ? response.address >> kPageOffsetBits
      : memory.read_word(response.address);
  pins.put(stem + "_bits_data", response_data);
  pins.put_wide(stem + "_bits_lineData", memory.read_line(response.address));
  pins.put(stem + "_bits_denied", 0);
  pins.put(stem + "_bits_corrupt", 0);
  pins.put(stem + "_bits_errorCause", 0);
  pins.put(stem + "_bits_attributesValid", 1);
  pins.put(stem + "_bits_readable", 1);
  pins.put(stem + "_bits_writable", 1);
  pins.put(stem + "_bits_cacheable", 1);
  pins.put(stem + "_bits_device", 0);
}

Response capture_request(const Pins &pins, const std::string &stem) {
  Response response;
  response.valid = true;
  response.identity = pins.get(stem + "_bits_identity_value");
  response.generation = pins.get(stem + "_bits_identity_generation");
  response.address = pins.get(stem + "_bits_address");
  response.access_kind = pins.get(stem + "_bits_accessKind");
  return response;
}

void validate_top_ports(const Pins &pins, const SparseMemory &memory,
                        const Arguments &arguments) {
  Response empty;
  drive_idle_inputs(pins, arguments);
  pins.put("io_pInit_valid", 0);
  pins.put("io_bootstrapComplete", 0);
  pins.put("io_instructionMemoryRequest_ready", 1);
  drive_response(pins, "io_instructionMemoryResponse", empty, memory);
  (void)capture_request(pins, "io_instructionMemoryRequest");
  for (int lane = 0; lane < arguments.data_lanes; ++lane) {
    const auto request = "io_dataMemoryRequest_" + std::to_string(lane);
    const auto response = "io_dataMemoryResponse_" + std::to_string(lane);
    pins.put(request + "_ready", 1);
    drive_response(pins, response, empty, memory);
    (void)capture_request(pins, request);
  }
  (void)pins.get("io_bootstrapReady");
  (void)pins.get("io_commit_valid");
  (void)pins.get("io_trace_valid");
  (void)pins.get("io_lsuQuiescent");
  (void)pins.get("io_lsuProtocolError");
}

void write_manifest(const Arguments &arguments, const SparseMemory &memory,
                    const Activation &activation, std::uint64_t cycles,
                    const std::string &status, std::uint16_t finisher,
                    std::uint64_t captured, std::uint64_t mismatches) {
  std::filesystem::create_directories(std::filesystem::path(arguments.manifest).parent_path());
  std::ofstream output(arguments.manifest);
  if (!output) throw std::runtime_error("cannot write manifest: " + arguments.manifest);
  output << "{\n"
         << "  \"schema\": 1,\n  \"top\": \"TOP\",\n  \"task\": 18,\n"
         << "  \"terminal_status\": \"" << status << "\",\n"
         << "  \"cycles\": " << cycles << ",\n"
         << "  \"loaded_bytes\": " << memory.size() << ",\n"
         << "  \"finisher_code\": " << finisher << ",\n"
         << "  \"captured_prefix\": " << captured << ",\n"
         << "  \"captured_prefix_mismatch\": " << mismatches << ",\n"
         << "  \"comparison_kind\": \"none\",\n"
         << "  \"instruction_oracle\": false,\n  \"commit_oracle\": false,\n"
         << "  \"activation\": {\n"
         << "    \"IFU\": " << (activation.trace[1] + activation.instruction_requests) << ",\n"
         << "    \"CTU\": " << activation.trace[2] << ",\n"
         << "    \"OOO\": " << (activation.trace[3] + activation.commits) << ",\n"
         << "    \"IEX\": " << activation.trace[4] << ",\n"
         << "    \"LSU\": " << (activation.trace[5] + activation.data_requests) << "\n"
         << "  }\n}\n";
}

}  // namespace

int main(int argc, char **argv) {
  try {
    Verilated::commandArgs(argc, argv);
    const auto arguments = parse_arguments(argc, argv);
    SparseMemory memory(arguments.memory_hex);
    std::filesystem::create_directories(std::filesystem::path(arguments.uart_output).parent_path());
    std::ofstream uart(arguments.uart_output);
    if (!uart) throw std::runtime_error("cannot write UART output");
    std::ofstream commit_trace;
    if (!arguments.commit_trace.empty()) {
      std::filesystem::create_directories(
        std::filesystem::path(arguments.commit_trace).parent_path());
      commit_trace.open(arguments.commit_trace);
      if (!commit_trace)
        throw std::runtime_error("cannot write commit trace: " +
                                 arguments.commit_trace);
    }

    VCoreTOP dut;
    dut.reset = 1;
    dut.clock = 0;
    dut.eval();
    VerilatedVpi::callValueCbs();
    Pins pins;
    if (arguments.validate_ports) {
      validate_top_ports(pins, memory, arguments);
      dut.eval();
    }
    drive_idle_inputs(pins, arguments);
    pins.put("io_instructionMemoryResponse_valid", 0);
    for (int lane = 0; lane < arguments.data_lanes; ++lane) {
      pins.put("io_dataMemoryResponse_" + std::to_string(lane) + "_valid", 0);
    }
    pins.put("io_pInit_valid", 0);
    pins.put("io_bootstrapComplete", 0);
    for (int cycle = 0; cycle < 5; ++cycle) {
      dut.clock = 0; dut.eval(); dut.clock = 1; dut.eval();
    }
    dut.reset = 0;

    if (arguments.validate_ports) {
      bool observed_bootstrap_ready = false;
      const int entries = arguments.stid_count * arguments.gpr_arch_regs;
      for (int index = 0; index < entries; ++index) {
        drive_idle_inputs(pins, arguments);
        pins.put("io_pInit_valid", 1);
        const int stid = index / arguments.gpr_arch_regs;
        const int atag = index % arguments.gpr_arch_regs;
        pins.put("io_pInit_bits_stid", stid);
        pins.put("io_pInit_bits_atag", atag);
        pins.put("io_pInit_bits_epoch", 1);
        pins.put("io_pInit_bits_ptag", index);
        pins.put("io_pInit_bits_generation", 0);
        pins.put("io_pInit_bits_value",
                 atag == arguments.sp_atag ? arguments.reset_sp : 0);
        pins.put("io_bootstrapComplete", 0);
        dut.clock = 0; dut.eval(); VerilatedVpi::callValueCbs();
        if (!pins.get("io_pInit_ready"))
          throw std::runtime_error("bootstrap validation P-map backpressured");
        observed_bootstrap_ready |= pins.get("io_bootstrapReady") != 0;
        dut.clock = 1; dut.eval(); VerilatedVpi::callValueCbs();
      }
      pins.put("io_pInit_valid", 0);
      pins.put("io_bootstrapComplete", 1);
      dut.clock = 0; dut.eval(); VerilatedVpi::callValueCbs();
      observed_bootstrap_ready |= pins.get("io_bootstrapReady") != 0;
      dut.clock = 1; dut.eval(); VerilatedVpi::callValueCbs();
      observed_bootstrap_ready |= pins.get("io_bootstrapReady") != 0;
      pins.put("io_bootstrapComplete", 0);
      if (!observed_bootstrap_ready)
        throw std::runtime_error("bootstrap validation never observed bootstrapReady");
      std::cout << "top-vpi-port-validation=pass\n"
                << "top-vpi-bootstrap-validation=pass entries=" << entries << "\n";
      write_manifest(arguments, memory, Activation{}, 5 + entries + 1,
                     "port_validation_pass", 0, 0, 0);
      dut.final();
      return 0;
    }

    Response instruction_response;
    std::vector<Response> data_response(arguments.data_lanes);
    Activation activation;
    LastTraceEvent last_trace;
    std::deque<LastTraceEvent> recent_trace;
    int init_index = 0;
    bool bootstrap_pulse = false;
    std::uint16_t finisher = 0;
    std::string status = "timeout";
    std::uint64_t cycle = 0;
    std::uint64_t last_progress_cycle = 0;

    for (; cycle < arguments.max_cycles && status == "timeout"; ++cycle) {
      drive_idle_inputs(pins, arguments);
      const bool initializing = init_index < arguments.stid_count * arguments.gpr_arch_regs;
      pins.put("io_pInit_valid", initializing);
      if (initializing) {
        const int stid = init_index / arguments.gpr_arch_regs;
        const int atag = init_index % arguments.gpr_arch_regs;
        pins.put("io_pInit_bits_stid", stid);
        pins.put("io_pInit_bits_atag", atag);
        pins.put("io_pInit_bits_epoch", 1);
        pins.put("io_pInit_bits_ptag", init_index);
        pins.put("io_pInit_bits_generation", 0);
        pins.put("io_pInit_bits_value", atag == arguments.sp_atag ? arguments.reset_sp : 0);
      }
      pins.put("io_bootstrapComplete", bootstrap_pulse);

      pins.put("io_instructionMemoryRequest_ready", !instruction_response.valid);
      drive_response(pins, "io_instructionMemoryResponse", instruction_response, memory);
      for (int lane = 0; lane < arguments.data_lanes; ++lane) {
        const auto request = "io_dataMemoryRequest_" + std::to_string(lane);
        const auto response = "io_dataMemoryResponse_" + std::to_string(lane);
        pins.put(request + "_ready", !data_response[lane].valid);
        drive_response(pins, response, data_response[lane], memory);
      }

      dut.clock = 0;
      dut.eval();
      VerilatedVpi::callValueCbs();

      const bool init_fire = initializing && pins.get("io_pInit_ready");
      const bool instruction_response_fire = instruction_response.valid &&
        pins.get("io_instructionMemoryResponse_ready");
      const bool instruction_request_fire = !instruction_response.valid &&
        pins.get("io_instructionMemoryRequest_valid");
      const Response next_instruction_response = instruction_request_fire
        ? capture_request(pins, "io_instructionMemoryRequest") : Response{};
      std::vector<bool> data_response_fire(arguments.data_lanes);
      std::vector<bool> data_request_fire(arguments.data_lanes);
      std::vector<DataRequest> accepted_data(arguments.data_lanes);
      bool any_data_response_fire = false;
      bool any_data_request_fire = false;
      for (int lane = 0; lane < arguments.data_lanes; ++lane) {
        data_response_fire[lane] = data_response[lane].valid &&
          pins.get("io_dataMemoryResponse_" + std::to_string(lane) + "_ready");
        data_request_fire[lane] = !data_response[lane].valid &&
          pins.get("io_dataMemoryRequest_" + std::to_string(lane) + "_valid");
        any_data_response_fire |= data_response_fire[lane];
        any_data_request_fire |= data_request_fire[lane];
        if (data_request_fire[lane]) {
          const auto stem = "io_dataMemoryRequest_" + std::to_string(lane);
          accepted_data[lane].response = capture_request(pins, stem);
          accepted_data[lane].command = pins.get(stem + "_bits_command");
          accepted_data[lane].data = pins.get(stem + "_bits_data");
          accepted_data[lane].mask = pins.get(stem + "_bits_byteMask");
          if (commit_trace && accepted_data[lane].command == 1) {
            const auto &request = accepted_data[lane];
            commit_trace
              << "{\"event\":\"memory_request\",\"command\":\"Write\""
              << ",\"transaction\":{\"value\":"
              << request.response.identity
              << ",\"generation\":" << request.response.generation << "}"
              << ",\"address\":" << request.response.address
              << ",\"data\":" << request.data
              << ",\"mask\":" << request.mask << "}\n";
          }
        }
      }

      const bool trace_fire = pins.get("io_trace_valid") &&
        pins.get("io_trace_ready");
      if (trace_fire) {
        const auto count = pins.get("io_trace_bits_count");
        for (std::uint64_t lane = 0; lane < count && lane < static_cast<std::uint64_t>(arguments.trace_width); ++lane) {
          const auto stem = "io_trace_bits_entries_" + std::to_string(lane) + "_";
          const auto source = pins.get(stem + "source");
          if (source < activation.trace.size()) ++activation.trace[source];
          LastTraceEvent event;
          event.valid = true;
          event.cycle = cycle;
          event.source = source;
          event.kind = pins.get(stem + "kind");
          event.pc = pins.get(stem + "pc");
          event.opcode = pins.get(stem + "opcode");
          event.instruction_valid = pins.get(stem + "instructionValid");
          event.instruction_id = pins.get(stem + "instruction_instructionId");
          event.instruction_epoch = pins.get(stem + "instruction_epoch");
          event.rob_valid = pins.get(stem + "robValid");
          event.rid_slot = pins.get(stem + "rob_ridSlot");
          event.member_index = pins.get(stem + "rob_memberIndex");
          last_trace = event;
          recent_trace.push_back(event);
          if (recent_trace.size() > 128) recent_trace.pop_front();
        }
      }
      const bool commit_fire = pins.get("io_commit_valid") &&
        pins.get("io_commit_ready");
      if (commit_fire) {
        const auto count = pins.get("io_commit_bits_count");
        activation.commits += count;
        if (commit_trace) {
          const auto bounded_count = std::min<std::uint64_t>(
            count, static_cast<std::uint64_t>(arguments.retire_width));
          for (std::uint64_t lane = 0; lane < bounded_count; ++lane) {
            const auto stem = "io_commit_bits_entries_" +
              std::to_string(lane) + "_";
            commit_trace
              << "{\"event\":\"commit\""
              << ",\"pc\":" << pins.get(stem + "pc")
              << ",\"insn\":" << pins.get(stem + "instructionBits")
              << ",\"wb_valid\":" << pins.get(stem + "resultValid")
              << ",\"wb_data\":" << pins.get(stem + "result")
              << ",\"mem_valid\":" << pins.get(stem + "memoryValid")
              << ",\"mem_is_store\":" << pins.get(stem + "memoryStore")
              << ",\"transaction\":{\"value\":"
              << pins.get(stem + "memory_transaction_value")
              << ",\"generation\":"
              << pins.get(stem + "memory_transaction_generation") << "}"
              << "}\n";
          }
        }
      }
      const bool trap_fire = pins.get("io_trap_valid") &&
        pins.get("io_trap_ready");
      if (trap_fire) status = "trap";

      dut.clock = 1;
      dut.eval();
      VerilatedVpi::callValueCbs();
      dut.clock = 0;
      dut.eval();

      bootstrap_pulse = false;
      if (init_fire) {
        ++init_index;
        if (init_index == arguments.stid_count * arguments.gpr_arch_regs) bootstrap_pulse = true;
      }
      if (instruction_response_fire) instruction_response.valid = false;
      if (instruction_request_fire) {
        instruction_response = next_instruction_response;
        ++activation.instruction_requests;
      }
      bool finisher_fire = false;
      for (int lane = 0; lane < arguments.data_lanes; ++lane) {
        if (data_response_fire[lane]) data_response[lane].valid = false;
        if (!data_request_fire[lane]) continue;
        const auto &request = accepted_data[lane];
        const auto address = request.response.address;
        if (request.command == 1) {
          memory.write(address, request.data, request.mask);
          if (address == kUartAddress) uart.put(static_cast<char>(request.data & 0xffU));
          if (address == kFinisherAddress) {
            finisher_fire = true;
            finisher = request.data & 0xffffU;
            status = finisher == kPassCode ? "finisher_pass" : "finisher_fail";
          }
        }
        data_response[lane] = request.response;
        ++activation.data_requests;
      }
      if (pins.get("io_lsuProtocolError")) status = "lsu_protocol_error";
      const bool architectural_progress = init_fire ||
        instruction_request_fire || instruction_response_fire ||
        any_data_request_fire || any_data_response_fire || commit_fire ||
        trace_fire || trap_fire || finisher_fire;
      if (architectural_progress) last_progress_cycle = cycle;
      if (arguments.heartbeat_cycles != 0 && cycle != 0 &&
          cycle % arguments.heartbeat_cycles == 0) {
        std::cout << "top-natural-heartbeat cycle=" << cycle
                  << " commit=" << activation.commits
                  << " instruction=" << activation.instruction_requests
                  << " data=" << activation.data_requests
                  << " trace=" << trace_count(activation)
                  << std::endl;
      }
      if (status == "timeout" && arguments.deadlock_cycles != 0 &&
          cycle - last_progress_cycle >= arguments.deadlock_cycles) {
        status = "deadlock";
        std::cout << "top-natural-deadlock cycle=" << cycle
                  << " last-progress-cycle=" << last_progress_cycle
                  << " last-trace-valid=" << last_trace.valid
                  << " last-trace-source=" << last_trace.source
                  << " last-trace-kind=" << last_trace.kind
                  << " last-trace-pc=0x" << std::hex << last_trace.pc
                  << " last-trace-opcode=0x" << last_trace.opcode
                  << std::dec << std::endl;
        report_deadlock_state(pins, arguments);
        std::size_t trace_index = 0;
        for (const auto &event : recent_trace) {
          std::cout << "top-natural-trace-event index=" << trace_index++
                    << " cycle=" << event.cycle
                    << " source=" << event.source
                    << " kind=" << event.kind
                    << " pc=0x" << std::hex << event.pc
                    << " opcode=0x" << event.opcode << std::dec
                    << " instruction-valid=" << event.instruction_valid
                    << " instruction-id=" << event.instruction_id
                    << " epoch=" << event.instruction_epoch
                    << " rob-valid=" << event.rob_valid
                    << " rid-slot=" << event.rid_slot
                    << " member-index=" << event.member_index
                    << std::endl;
        }
      }
    }

    uart.close();
    write_manifest(arguments, memory, activation, cycle, status, finisher,
                   0, 0);
    dut.final();
    const bool activated = activation.instruction_requests != 0 && activation.trace[2] != 0 &&
      activation.commits != 0 && activation.trace[4] != 0 && activation.data_requests != 0;
    return status == "finisher_pass" && activated ? 0 : 1;
  } catch (const std::exception &error) {
    std::cerr << "top natural harness: " << error.what() << '\n';
    return 2;
  }
}
