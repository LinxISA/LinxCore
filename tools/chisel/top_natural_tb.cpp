#include "VCoreTOP.h"
#include "verilated.h"
#include "verilated_vpi.h"
#include "vpi_user.h"

#include <array>
#include <cstdint>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr std::uint64_t kUartAddress = 0x10000000ULL;
constexpr std::uint64_t kFinisherAddress = 0x10009000ULL;
constexpr std::uint16_t kPassCode = 0x5555U;
constexpr int kDataLanes = 4;
constexpr int kTraceLanes = 4;
constexpr int kArchitecturalRegisters = 32;
constexpr int kHardwareThreads = 2;

struct Arguments {
  std::string memory_hex;
  std::string manifest;
  std::string uart_output;
  std::uint64_t reset_sp = 0x0000000007fefff0ULL;
  std::uint64_t max_cycles = 1000000;
  bool validate_ports = false;
};

struct Response {
  bool valid = false;
  std::uint64_t identity = 0;
  std::uint64_t generation = 0;
  std::uint64_t address = 0;
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
    } else if (option == "--reset-sp" && index + 1 < argc) {
      result.reset_sp = parse_integer(argv[++index]);
    } else if (option == "--max-cycles" && index + 1 < argc) {
      result.max_cycles = parse_integer(argv[++index]);
    } else if (option == "--validate-ports") {
      result.validate_ports = true;
    } else {
      throw std::runtime_error("unknown or incomplete option: " + option);
    }
  }
  if (result.memory_hex.empty() || result.manifest.empty() || result.uart_output.empty()) {
    throw std::runtime_error("--memory-hex, --uart-output, and --manifest are required");
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
  std::uint64_t get(const std::string &leaf) const {
    auto handle = find(leaf);
    s_vpi_value value{};
    value.format = vpiVectorVal;
    vpi_get_value(handle, &value);
    const auto low = static_cast<std::uint32_t>(value.value.vector[0].aval);
    const auto width = vpi_get(vpiSize, handle);
    if (width <= 32) return low;
    const auto high = static_cast<std::uint32_t>(value.value.vector[1].aval);
    return low | (static_cast<std::uint64_t>(high) << 32);
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
  static vpiHandle find(const std::string &leaf) {
    for (const auto &prefix : {std::string("TOP.CoreTOPHarness.dut."),
                               std::string("TOP.dut."), std::string("CoreTOPHarness.dut."),
                               std::string("TOP."), std::string()}) {
      const auto name = prefix + leaf;
      if (auto handle = vpi_handle_by_name(reinterpret_cast<PLI_BYTE8 *>(const_cast<char *>(name.c_str())), nullptr)) {
        return handle;
      }
    }
    throw std::runtime_error("missing TOP port in VPI model: " + leaf);
  }
};

std::string data_port(int lane, const std::string &suffix) {
  return "io_dataMemory" + suffix.substr(0, 1) + "_" + std::to_string(lane) + suffix.substr(1);
}

void drive_idle_inputs(const Pins &pins) {
  pins.put("io_interrupt_valid", 0);
  pins.put("io_debugRequest_valid", 0);
  pins.put("io_storeCommit_valid", 0);
  pins.put("io_storeClassify_valid", 0);
  pins.put("io_loadReissueRequest_valid", 0);
  pins.put("io_maintenance_valid", 0);
  pins.put("io_debugResponse_ready", 1);
  pins.put("io_cmdIssue_ready", 1);
  pins.put("io_memoryFault_ready", 1);
  pins.put("io_maintenanceResult_ready", 1);
  pins.put("io_commit_ready", 1);
  pins.put("io_trap_ready", 1);
  pins.put("io_trace_ready", 1);
  for (int lane = 0; lane < 8; ++lane) {
    try { pins.put("io_systemIssue_" + std::to_string(lane) + "_ready", 1); }
    catch (const std::runtime_error &) { break; }
  }
}

void drive_response(const Pins &pins, const std::string &stem, const Response &response,
                    const SparseMemory &memory) {
  pins.put(stem + "_valid", response.valid);
  pins.put(stem + "_bits_identity_value", response.identity);
  pins.put(stem + "_bits_identity_generation", response.generation);
  pins.put(stem + "_bits_address", response.address);
  pins.put(stem + "_bits_data", memory.read_word(response.address));
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
  return response;
}

void validate_top_ports(const Pins &pins, const SparseMemory &memory) {
  Response empty;
  drive_idle_inputs(pins);
  pins.put("io_pInit_valid", 0);
  pins.put("io_bootstrapComplete", 0);
  pins.put("io_instructionMemoryRequest_ready", 1);
  drive_response(pins, "io_instructionMemoryResponse", empty, memory);
  (void)capture_request(pins, "io_instructionMemoryRequest");
  for (int lane = 0; lane < kDataLanes; ++lane) {
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

    VCoreTOP dut;
    dut.reset = 1;
    dut.clock = 0;
    dut.eval();
    VerilatedVpi::callValueCbs();
    Pins pins;
    if (arguments.validate_ports) {
      validate_top_ports(pins, memory);
      dut.eval();
      std::cout << "top-vpi-port-validation=pass\n";
      dut.final();
      return 0;
    }
    drive_idle_inputs(pins);
    pins.put("io_instructionMemoryResponse_valid", 0);
    for (int lane = 0; lane < kDataLanes; ++lane) {
      pins.put("io_dataMemoryResponse_" + std::to_string(lane) + "_valid", 0);
    }
    pins.put("io_pInit_valid", 0);
    pins.put("io_bootstrapComplete", 0);
    for (int cycle = 0; cycle < 5; ++cycle) {
      dut.clock = 0; dut.eval(); dut.clock = 1; dut.eval();
    }
    dut.reset = 0;

    Response instruction_response;
    std::array<Response, kDataLanes> data_response{};
    Activation activation;
    int init_index = 0;
    bool bootstrap_pulse = false;
    std::uint16_t finisher = 0;
    std::string status = "timeout";
    std::uint64_t cycle = 0;

    for (; cycle < arguments.max_cycles && status == "timeout"; ++cycle) {
      drive_idle_inputs(pins);
      const bool initializing = init_index < kHardwareThreads * kArchitecturalRegisters;
      pins.put("io_pInit_valid", initializing);
      if (initializing) {
        const int stid = init_index / kArchitecturalRegisters;
        const int atag = init_index % kArchitecturalRegisters;
        pins.put("io_pInit_bits_stid", stid);
        pins.put("io_pInit_bits_atag", atag);
        pins.put("io_pInit_bits_epoch", 1);
        pins.put("io_pInit_bits_ptag", init_index);
        pins.put("io_pInit_bits_generation", 0);
        pins.put("io_pInit_bits_value", atag == 2 ? arguments.reset_sp : 0);
      }
      pins.put("io_bootstrapComplete", bootstrap_pulse);

      pins.put("io_instructionMemoryRequest_ready", !instruction_response.valid);
      drive_response(pins, "io_instructionMemoryResponse", instruction_response, memory);
      for (int lane = 0; lane < kDataLanes; ++lane) {
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
      std::array<bool, kDataLanes> data_response_fire{};
      std::array<bool, kDataLanes> data_request_fire{};
      std::array<DataRequest, kDataLanes> accepted_data{};
      for (int lane = 0; lane < kDataLanes; ++lane) {
        data_response_fire[lane] = data_response[lane].valid &&
          pins.get("io_dataMemoryResponse_" + std::to_string(lane) + "_ready");
        data_request_fire[lane] = !data_response[lane].valid &&
          pins.get("io_dataMemoryRequest_" + std::to_string(lane) + "_valid");
        if (data_request_fire[lane]) {
          const auto stem = "io_dataMemoryRequest_" + std::to_string(lane);
          accepted_data[lane].response = capture_request(pins, stem);
          accepted_data[lane].command = pins.get(stem + "_bits_command");
          accepted_data[lane].data = pins.get(stem + "_bits_data");
          accepted_data[lane].mask = pins.get(stem + "_bits_byteMask");
        }
      }

      if (pins.get("io_trace_valid") && pins.get("io_trace_ready")) {
        const auto count = pins.get("io_trace_bits_count");
        for (std::uint64_t lane = 0; lane < count && lane < kTraceLanes; ++lane) {
          const auto source = pins.get("io_trace_bits_entries_" + std::to_string(lane) + "_source");
          if (source < activation.trace.size()) ++activation.trace[source];
        }
      }
      if (pins.get("io_commit_valid") && pins.get("io_commit_ready")) {
        activation.commits += pins.get("io_commit_bits_count");
      }
      if (pins.get("io_trap_valid") && pins.get("io_trap_ready")) status = "trap";

      dut.clock = 1;
      dut.eval();
      VerilatedVpi::callValueCbs();
      dut.clock = 0;
      dut.eval();

      bootstrap_pulse = false;
      if (init_fire) {
        ++init_index;
        if (init_index == kHardwareThreads * kArchitecturalRegisters) bootstrap_pulse = true;
      }
      if (instruction_response_fire) instruction_response.valid = false;
      if (instruction_request_fire) {
        instruction_response = next_instruction_response;
        ++activation.instruction_requests;
      }
      for (int lane = 0; lane < kDataLanes; ++lane) {
        if (data_response_fire[lane]) data_response[lane].valid = false;
        if (!data_request_fire[lane]) continue;
        const auto &request = accepted_data[lane];
        const auto address = request.response.address;
        if (request.command == 1) {
          memory.write(address, request.data, request.mask);
          if (address == kUartAddress) uart.put(static_cast<char>(request.data & 0xffU));
          if (address == kFinisherAddress) {
            finisher = request.data & 0xffffU;
            status = finisher == kPassCode ? "finisher_pass" : "finisher_fail";
          }
        }
        data_response[lane] = request.response;
        ++activation.data_requests;
      }
      if (pins.get("io_lsuProtocolError")) status = "lsu_protocol_error";
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
