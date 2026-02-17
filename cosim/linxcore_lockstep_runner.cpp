#include <cerrno>
#include <cctype>
#include <cinttypes>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <pyc/cpp/pyc_tb.hpp>

#include "linxcore_top.hpp"

namespace {

using pyc::cpp::Testbench;
using pyc::cpp::Wire;

struct SnapshotHeader {
  char magic[8];
  std::uint32_t version;
  std::uint32_t range_count;
};

struct SnapshotRangeEntry {
  std::uint64_t base;
  std::uint64_t size;
  std::uint64_t file_offset;
};

struct SnapshotRange {
  SnapshotRangeEntry meta{};
  std::vector<std::uint8_t> bytes{};
};

struct SnapshotImage {
  std::vector<SnapshotRange> ranges{};
};

struct CommitRecord {
  std::uint64_t cycle = 0;
  std::uint64_t seq = 0;
  std::uint64_t pc = 0;
  std::uint64_t op = 0;
  std::uint64_t insn = 0;
  std::uint64_t len = 0;
  std::uint64_t wb_valid = 0;
  std::uint64_t wb_rd = 0;
  std::uint64_t wb_data = 0;
  std::uint64_t mem_valid = 0;
  std::uint64_t mem_is_store = 0;
  std::uint64_t mem_addr = 0;
  std::uint64_t mem_wdata = 0;
  std::uint64_t mem_rdata = 0;
  std::uint64_t mem_size = 0;
  std::uint64_t trap_valid = 0;
  std::uint64_t trap_cause = 0;
  std::uint64_t traparg0 = 0;
  std::uint64_t next_pc = 0;
};

struct Mismatch {
  std::string field;
  std::uint64_t qemu = 0;
  std::uint64_t dut = 0;
};

struct RunnerOptions {
  std::string socket_path;
  bool verbose = false;
  std::uint64_t boot_sp = 0x0000'0000'0002'0000ull;
  std::uint64_t boot_ra = 0;
  std::uint64_t max_dut_cycles = 200000000ull;
  std::uint64_t deadlock_cycles = 200000ull;
  bool accept_max_commits_end = false;
  bool force_mismatch = false;
  std::string disasm_spec = "/Users/zhoubot/linxisa/isa/spec/current/linxisa-v0.3.json";
  std::string disasm_tool = "/Users/zhoubot/linxisa/tools/isa/linxdisasm.py";
};

static bool getJsonKeyPos(const std::string &line, const std::string &key, std::size_t &value_pos) {
  const std::string pattern = "\"" + key + "\"";
  std::size_t pos = line.find(pattern);
  if (pos == std::string::npos) {
    return false;
  }
  pos = line.find(':', pos + pattern.size());
  if (pos == std::string::npos) {
    return false;
  }
  pos++;
  while (pos < line.size() && std::isspace(static_cast<unsigned char>(line[pos]))) {
    pos++;
  }
  if (pos >= line.size()) {
    return false;
  }
  value_pos = pos;
  return true;
}

static bool getJsonU64(const std::string &line, const std::string &key, std::uint64_t &out) {
  std::size_t pos = 0;
  if (!getJsonKeyPos(line, key, pos)) {
    return false;
  }
  errno = 0;
  char *endp = nullptr;
  out = std::strtoull(line.c_str() + pos, &endp, 0);
  if (errno != 0 || endp == line.c_str() + pos) {
    return false;
  }
  return true;
}

static bool getJsonString(const std::string &line, const std::string &key, std::string &out) {
  std::size_t pos = 0;
  if (!getJsonKeyPos(line, key, pos)) {
    return false;
  }
  if (line[pos] != '"') {
    return false;
  }
  pos++;
  std::size_t end = pos;
  while (end < line.size()) {
    if (line[end] == '"' && line[end - 1] != '\\') {
      break;
    }
    end++;
  }
  if (end >= line.size()) {
    return false;
  }
  out = line.substr(pos, end - pos);
  return true;
}

static std::uint64_t maskInsn(std::uint64_t raw, std::uint64_t len) {
  if (len == 2) {
    return raw & 0xFFFFull;
  }
  if (len == 4) {
    return raw & 0xFFFF'FFFFull;
  }
  if (len == 6) {
    return raw & 0xFFFF'FFFF'FFFFull;
  }
  return raw;
}

static bool isBstart16(std::uint16_t hw) {
  // C.BSTART.STD / C.BSTART.FP: mask=0xc7ff, BrType in bits [13:11].
  if ((hw & 0xc7ffu) == 0x0000u || (hw & 0xc7ffu) == 0x0080u) {
    const std::uint8_t brtype = static_cast<std::uint8_t>((hw >> 11) & 0x7u);
    if (brtype != 0u) {
      return true;
    }
  }
  // C.BSTART DIRECT/COND (low nibble distinguishes forms).
  if ((hw & 0x000fu) == 0x0002u || (hw & 0x000fu) == 0x0004u) {
    return true;
  }
  switch (hw) {
  case 0x0840u: // C.BSTART.SYS FALL
  case 0x08c0u: // C.BSTART.MPAR FALL
  case 0x48c0u: // C.BSTART.MSEQ FALL
  case 0x88c0u: // C.BSTART.VPAR FALL
  case 0xc8c0u: // C.BSTART.VSEQ FALL
    return true;
  default:
    return false;
  }
}

static bool isBstart32(std::uint32_t insn) {
  // Keep this list aligned with decode masks:
  // mask=0x00007fff, matches:
  //   0x00001001 (FALL), 0x00002001 (DIRECT), 0x00003001 (COND),
  //   0x00004001 (CALL), 0x00005001/0x00006001/0x00007001 (IND/ICALL/RET via BrType).
  switch (insn & 0x00007fffu) {
  case 0x00001001u:
  case 0x00002001u:
  case 0x00003001u:
  case 0x00004001u:
  case 0x00005001u:
  case 0x00006001u:
  case 0x00007001u:
    return true;
  default:
    return false;
  }
}

static bool isBstart48(std::uint64_t raw) {
  const std::uint16_t prefix = static_cast<std::uint16_t>(raw & 0xFFFFu);
  const std::uint32_t main32 = static_cast<std::uint32_t>((raw >> 16) & 0xFFFF'FFFFu);
  if ((prefix & 0xFu) != 0xEu) {
    return false;
  }
  return ((main32 & 0xFFu) == 0x01u) && (((main32 >> 12) & 0x7u) != 0u);
}

static bool isMacroMarker32(std::uint32_t insn) {
  // FENTRY/FEXIT/FRET_RA/FRET_STK (mask=0x0000707f).
  switch (insn & 0x0000707fu) {
  case 0x00000041u:
  case 0x00001041u:
  case 0x00002041u:
  case 0x00003041u:
    return true;
  default:
    return false;
  }
}

static std::string toHex(std::uint64_t v) {
  std::ostringstream oss;
  oss << "0x" << std::hex << v << std::dec;
  return oss.str();
}

static std::string insnHexToken(std::uint64_t raw, std::uint64_t len) {
  unsigned digits = 16;
  if (len == 2) {
    digits = 4;
  } else if (len == 4) {
    digits = 8;
  } else if (len == 6) {
    digits = 12;
  }
  std::ostringstream oss;
  oss << std::hex << std::nouppercase << std::setfill('0') << std::setw(static_cast<int>(digits)) << maskInsn(raw, len);
  return oss.str();
}

static std::string shellQuote(const std::string &s) {
  std::string out;
  out.reserve(s.size() + 2);
  out.push_back('\'');
  for (char ch : s) {
    if (ch == '\'') {
      out += "'\\''";
    } else {
      out.push_back(ch);
    }
  }
  out.push_back('\'');
  return out;
}

static std::optional<std::string> runCommandCapture(const std::string &cmd) {
  FILE *fp = ::popen(cmd.c_str(), "r");
  if (!fp) {
    return std::nullopt;
  }
  std::string out;
  char buf[512];
  while (std::fgets(buf, sizeof(buf), fp) != nullptr) {
    out += buf;
  }
  const int rc = ::pclose(fp);
  if (rc != 0) {
    return std::nullopt;
  }
  while (!out.empty() && (out.back() == '\n' || out.back() == '\r')) {
    out.pop_back();
  }
  return out;
}

static std::string disasmInsn(const RunnerOptions &opts, std::uint64_t raw, std::uint64_t len) {
  if (opts.disasm_tool.empty() || opts.disasm_spec.empty()) {
    return "<disasm-unavailable>";
  }
  const std::string token = insnHexToken(raw, len);
  const std::string cmd = "python3 " + shellQuote(opts.disasm_tool) + " --spec " + shellQuote(opts.disasm_spec) + " --hex " +
                          shellQuote(token) + " 2>/dev/null";
  const auto out = runCommandCapture(cmd);
  if (!out.has_value() || out->empty()) {
    return "<disasm-unavailable>";
  }
  std::size_t tab = out->find('\t');
  if (tab == std::string::npos || tab + 1 >= out->size()) {
    return *out;
  }
  return out->substr(tab + 1);
}

static std::string formatCommit(const CommitRecord &r) {
  std::ostringstream oss;
  oss << "cycle=" << r.cycle << " pc=" << toHex(r.pc) << " op=" << r.op << " insn=" << toHex(maskInsn(r.insn, r.len)) << " len=" << r.len
      << " wb_valid=" << r.wb_valid << " wb_rd=" << r.wb_rd << " wb_data=" << toHex(r.wb_data)
      << " mem_valid=" << r.mem_valid << " mem_is_store=" << r.mem_is_store << " mem_addr=" << toHex(r.mem_addr)
      << " mem_wdata=" << toHex(r.mem_wdata) << " mem_rdata=" << toHex(r.mem_rdata) << " mem_size=" << r.mem_size
      << " trap_valid=" << r.trap_valid << " trap_cause=" << r.trap_cause << " traparg0=" << toHex(r.traparg0)
      << " next_pc=" << toHex(r.next_pc);
  return oss.str();
}

static bool recvLine(int fd, std::string &line) {
  line.clear();
  while (true) {
    char ch = '\0';
    ssize_t n = ::read(fd, &ch, 1);
    if (n < 0) {
      if (errno == EINTR) {
        continue;
      }
      return false;
    }
    if (n == 0) {
      return !line.empty();
    }
    if (ch == '\n') {
      return true;
    }
    line.push_back(ch);
  }
}

static bool sendAll(int fd, const char *buf, std::size_t len) {
  std::size_t off = 0;
  while (off < len) {
    ssize_t n = ::write(fd, buf + off, len - off);
    if (n < 0) {
      if (errno == EINTR) {
        continue;
      }
      return false;
    }
    if (n == 0) {
      return false;
    }
    off += static_cast<std::size_t>(n);
  }
  return true;
}

static bool sendLine(int fd, const std::string &line) {
  return sendAll(fd, line.data(), line.size()) && sendAll(fd, "\n", 1);
}

static int createServer(const std::string &socket_path) {
  int fd = ::socket(AF_UNIX, SOCK_STREAM, 0);
  if (fd < 0) {
    return -1;
  }

  ::unlink(socket_path.c_str());

  sockaddr_un addr{};
  addr.sun_family = AF_UNIX;
  if (socket_path.size() >= sizeof(addr.sun_path)) {
    ::close(fd);
    return -1;
  }
  std::snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", socket_path.c_str());

  if (::bind(fd, reinterpret_cast<const sockaddr *>(&addr), sizeof(addr)) != 0) {
    ::close(fd);
    return -1;
  }
  if (::listen(fd, 1) != 0) {
    ::close(fd);
    return -1;
  }
  return fd;
}

static bool loadSnapshot(const std::string &path, SnapshotImage &img) {
  std::ifstream in(path, std::ios::binary);
  if (!in.is_open()) {
    std::cerr << "runner: failed to open snapshot: " << path << "\n";
    return false;
  }

  SnapshotHeader hdr{};
  in.read(reinterpret_cast<char *>(&hdr), sizeof(hdr));
  if (!in.good()) {
    std::cerr << "runner: failed to read snapshot header\n";
    return false;
  }
  if (std::memcmp(hdr.magic, "LXCOSIM1", 8) != 0) {
    std::cerr << "runner: bad snapshot magic\n";
    return false;
  }
  if (hdr.version != 1) {
    std::cerr << "runner: unsupported snapshot version: " << hdr.version << "\n";
    return false;
  }

  std::vector<SnapshotRangeEntry> entries(hdr.range_count);
  if (!entries.empty()) {
    in.read(reinterpret_cast<char *>(entries.data()), static_cast<std::streamsize>(entries.size() * sizeof(entries[0])));
    if (!in.good()) {
      std::cerr << "runner: failed to read snapshot range table\n";
      return false;
    }
  }

  img.ranges.clear();
  img.ranges.reserve(entries.size());
  for (const auto &entry : entries) {
    SnapshotRange range{};
    range.meta = entry;
    range.bytes.resize(static_cast<std::size_t>(entry.size));
    in.seekg(static_cast<std::streamoff>(entry.file_offset), std::ios::beg);
    if (!in.good()) {
      std::cerr << "runner: failed to seek snapshot payload\n";
      return false;
    }
    if (!range.bytes.empty()) {
      in.read(reinterpret_cast<char *>(range.bytes.data()), static_cast<std::streamsize>(range.bytes.size()));
      if (!in.good()) {
        std::cerr << "runner: failed to read snapshot payload\n";
        return false;
      }
    }
    img.ranges.push_back(std::move(range));
  }

  return true;
}

struct DeadlockDebug {
  std::uint64_t cycles = 0;
  std::uint64_t pc = 0;
  std::uint64_t fpc = 0;
  std::uint64_t rob_count = 0;
  std::uint64_t rob_head_valid = 0;
  std::uint64_t rob_head_done = 0;
  std::uint64_t rob_head_pc = 0;
  std::uint64_t rob_head_insn_raw = 0;
  std::uint64_t rob_head_len = 0;
  std::uint64_t rob_head_op = 0;
  std::uint64_t halted = 0;
  std::uint64_t mmio_exit_valid = 0;
};

template <typename T, typename = void>
struct HasMember_pc : std::false_type {};
template <typename T>
struct HasMember_pc<T, std::void_t<decltype(std::declval<T &>().pc)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_fpc : std::false_type {};
template <typename T>
struct HasMember_fpc<T, std::void_t<decltype(std::declval<T &>().fpc)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_rob_count : std::false_type {};
template <typename T>
struct HasMember_rob_count<T, std::void_t<decltype(std::declval<T &>().rob_count)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_rob_head_valid : std::false_type {};
template <typename T>
struct HasMember_rob_head_valid<T, std::void_t<decltype(std::declval<T &>().rob_head_valid)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_rob_head_done : std::false_type {};
template <typename T>
struct HasMember_rob_head_done<T, std::void_t<decltype(std::declval<T &>().rob_head_done)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_rob_head_pc : std::false_type {};
template <typename T>
struct HasMember_rob_head_pc<T, std::void_t<decltype(std::declval<T &>().rob_head_pc)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_rob_head_insn_raw : std::false_type {};
template <typename T>
struct HasMember_rob_head_insn_raw<T, std::void_t<decltype(std::declval<T &>().rob_head_insn_raw)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_rob_head_len : std::false_type {};
template <typename T>
struct HasMember_rob_head_len<T, std::void_t<decltype(std::declval<T &>().rob_head_len)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_rob_head_op : std::false_type {};
template <typename T>
struct HasMember_rob_head_op<T, std::void_t<decltype(std::declval<T &>().rob_head_op)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_boot_ra : std::false_type {};
template <typename T>
struct HasMember_boot_ra<T, std::void_t<decltype(std::declval<T &>().boot_ra)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_dmem_wsrc : std::false_type {};
template <typename T>
struct HasMember_dmem_wsrc<T, std::void_t<decltype(std::declval<T &>().dmem_wsrc)>> : std::true_type {};

template <typename T, typename = void>
struct HasMember_dispatch_fire0 : std::false_type {};
template <typename T>
struct HasMember_dispatch_fire0<T, std::void_t<decltype(std::declval<T &>().dispatch_fire0)>> : std::true_type {};

class IDutStepper {
public:
  virtual ~IDutStepper() = default;
  virtual bool init(const SnapshotImage &snap, std::uint64_t boot_pc, std::uint64_t boot_sp, std::uint64_t boot_ra) = 0;
  virtual bool nextCommit(CommitRecord &out) = 0;
  virtual std::size_t pendingCommits() const = 0;
  virtual DeadlockDebug debugState() const = 0;
  virtual std::uint64_t peekMem(std::uint64_t guest_addr, std::uint64_t size) const = 0;
  virtual std::uint64_t peekIMem(std::uint64_t guest_addr, std::uint64_t size) const = 0;
  virtual std::string recentWriteSummary(std::uint64_t guest_addr) const = 0;
  virtual std::string recentDispatchSummary() const = 0;
  virtual std::string lastError() const = 0;
};

template <typename DutT>
class DutStepperImpl final : public IDutStepper {
public:
  explicit DutStepperImpl(const RunnerOptions &opts)
      : opts_(opts), max_cycles_(opts.max_dut_cycles), deadlock_cycles_(opts.deadlock_cycles) {}

  bool init(const SnapshotImage &snap, std::uint64_t boot_pc, std::uint64_t boot_sp, std::uint64_t boot_ra) override {
    dut_ = std::make_unique<DutT>();

    if (!loadSnapshotIntoMem(snap)) {
      return false;
    }

    dut_->boot_pc = Wire<64>(boot_pc);
    dut_->boot_sp = Wire<64>(boot_sp);
    if constexpr (HasMember_boot_ra<DutT>::value) {
      dut_->boot_ra = Wire<64>(boot_ra);
    }
    dut_->host_wvalid = Wire<1>(0);
    dut_->host_waddr = Wire<64>(0);
    dut_->host_wdata = Wire<64>(0);
    dut_->host_wstrb = Wire<8>(0);
    dut_->ic_l2_req_ready = Wire<1>(1);
    dut_->ic_l2_rsp_valid = Wire<1>(0);
    dut_->ic_l2_rsp_addr = Wire<64>(0);
    dut_->ic_l2_rsp_data = Wire<512>(0);
    dut_->ic_l2_rsp_error = Wire<1>(0);

    tb_ = std::make_unique<Testbench<DutT>>(*dut_);
    tb_->addClock(dut_->clk, 1);
    tb_->reset(dut_->rst, 2, 1);

    retire_q_.clear();
    ic_req_pending_ = false;
    ic_rsp_drive_now_ = false;
    ic_req_addr_pending_ = 0;
    ic_req_remain_cycles_ = 0;
    ic_req_seen_pre_ = false;
    ic_req_addr_pre_ = 0;
    return true;
  }

  bool nextCommit(CommitRecord &out) override {
    std::uint64_t stall_cycles = 0;
    while (retire_q_.empty()) {
      if (dut_->cycles.value() >= max_cycles_) {
        std::ostringstream oss;
        oss << "runner: DUT exceeded max cycles: " << max_cycles_;
        last_error_ = oss.str();
        std::cerr << last_error_ << "\n";
        return false;
      }
      driveIcacheL2();
      tb_->runCycles(1);
      updateIcacheL2State();
      sampleMemWrite();
      sampleDispatch();
      collectCommits();
      if (retire_q_.empty() && (dut_->halted.toBool() || dut_->mmio_exit_valid.toBool())) {
        last_error_ = "runner: DUT halted before next commit";
        std::cerr << last_error_ << "\n";
        return false;
      }
      if (retire_q_.empty()) {
        stall_cycles++;
        if (deadlock_cycles_ > 0 && stall_cycles >= deadlock_cycles_) {
          reportDeadlock(stall_cycles);
          return false;
        }
      }
    }
    out = retire_q_.front();
    retire_q_.pop_front();
    return true;
  }

  std::size_t pendingCommits() const override { return retire_q_.size(); }

  DeadlockDebug debugState() const override { return snapshotDebug(); }

  std::uint64_t peekMem(std::uint64_t guest_addr, std::uint64_t size) const override {
    if (!dut_) {
      return 0;
    }
    const std::size_t mem_bytes = dut_->mem2r1w.dmem.mem_.size();
    const std::size_t base = mapGuestAddr(guest_addr, mem_bytes);
    const std::uint64_t n = (size == 0 || size > 8) ? 8 : size;
    std::uint64_t v = 0;
    for (std::uint64_t i = 0; i < n; i++) {
      const std::uint64_t b = dut_->mem2r1w.dmem.peekByte(base + static_cast<std::size_t>(i));
      v |= (b << (8u * i));
    }
    return v;
  }

  std::uint64_t peekIMem(std::uint64_t guest_addr, std::uint64_t size) const override {
    if (!dut_) {
      return 0;
    }
    const std::size_t mem_bytes = dut_->mem2r1w.imem.mem_.size();
    const std::size_t base = mapGuestAddr(guest_addr, mem_bytes);
    const std::uint64_t n = (size == 0 || size > 8) ? 8 : size;
    std::uint64_t v = 0;
    for (std::uint64_t i = 0; i < n; i++) {
      const std::uint64_t b = dut_->mem2r1w.imem.peekByte(base + static_cast<std::size_t>(i));
      v |= (b << (8u * i));
    }
    return v;
  }

  std::string recentWriteSummary(std::uint64_t guest_addr) const override {
    std::ostringstream oss;
    unsigned shown = 0;
    for (auto it = write_events_.rbegin(); it != write_events_.rend() && shown < 4; ++it) {
      if (it->addr != guest_addr) {
        continue;
      }
      if (shown == 0) {
        oss << "last_writes";
      }
      oss << " [cycle=" << it->cycle << " data=" << toHex(it->data) << " strb=" << toHex(it->strb) << " src=" << it->src
          << " fire_mask=0x" << std::hex << it->fire_mask << std::dec
          << " pc0=" << toHex(it->pc0) << " pc1=" << toHex(it->pc1)
          << " pc2=" << toHex(it->pc2) << " pc3=" << toHex(it->pc3) << "]";
      shown++;
    }
    if (shown == 0) {
      return "last_writes none";
    }
    return oss.str();
  }

  std::string recentDispatchSummary() const override {
    if (dispatch_events_.empty()) {
      return "recent_dispatch none";
    }
    std::ostringstream oss;
    oss << "recent_dispatch";
    unsigned shown = 0;
    for (auto it = dispatch_events_.rbegin(); it != dispatch_events_.rend() && shown < 8; ++it) {
      oss << " [cycle=" << it->cycle << " fire_mask=0x" << std::hex << it->fire_mask << std::dec
          << " pc0=" << toHex(it->pc0) << " pc1=" << toHex(it->pc1)
          << " pc2=" << toHex(it->pc2) << " pc3=" << toHex(it->pc3) << "]";
      shown++;
    }
    return oss.str();
  }

  std::string lastError() const override { return last_error_; }

private:
  struct MemWriteEvent {
    std::uint64_t cycle = 0;
    std::uint64_t addr = 0;
    std::uint64_t data = 0;
    std::uint64_t strb = 0;
    std::uint64_t src = 0;
    std::uint64_t fire_mask = 0;
    std::uint64_t pc0 = 0;
    std::uint64_t pc1 = 0;
    std::uint64_t pc2 = 0;
    std::uint64_t pc3 = 0;
  };

  struct DispatchEvent {
    std::uint64_t cycle = 0;
    std::uint64_t fire_mask = 0;
    std::uint64_t pc0 = 0;
    std::uint64_t pc1 = 0;
    std::uint64_t pc2 = 0;
    std::uint64_t pc3 = 0;
  };

  DeadlockDebug snapshotDebug() const {
    DeadlockDebug d{};
    if (!dut_) {
      return d;
    }
    d.cycles = dut_->cycles.value();
    d.halted = dut_->halted.toBool() ? 1 : 0;
    d.mmio_exit_valid = dut_->mmio_exit_valid.toBool() ? 1 : 0;
    if constexpr (HasMember_pc<DutT>::value) {
      d.pc = dut_->pc.value();
    }
    if constexpr (HasMember_fpc<DutT>::value) {
      d.fpc = dut_->fpc.value();
    }
    if constexpr (HasMember_rob_count<DutT>::value) {
      d.rob_count = dut_->rob_count.value();
    }
    if constexpr (HasMember_rob_head_valid<DutT>::value) {
      d.rob_head_valid = dut_->rob_head_valid.value();
    }
    if constexpr (HasMember_rob_head_done<DutT>::value) {
      d.rob_head_done = dut_->rob_head_done.value();
    }
    if constexpr (HasMember_rob_head_pc<DutT>::value) {
      d.rob_head_pc = dut_->rob_head_pc.value();
    }
    if constexpr (HasMember_rob_head_insn_raw<DutT>::value) {
      d.rob_head_insn_raw = dut_->rob_head_insn_raw.value();
    }
    if constexpr (HasMember_rob_head_len<DutT>::value) {
      d.rob_head_len = dut_->rob_head_len.value() & 0x7u;
    }
    if constexpr (HasMember_rob_head_op<DutT>::value) {
      d.rob_head_op = dut_->rob_head_op.value();
    }
    return d;
  }

  void reportDeadlock(std::uint64_t stall_cycles) {
    const DeadlockDebug d = snapshotDebug();
    const std::uint64_t len = (d.rob_head_len == 2 || d.rob_head_len == 4 || d.rob_head_len == 6) ? d.rob_head_len : 4;
    const std::string disasm = disasmInsn(opts_, d.rob_head_insn_raw, len);

    std::ostringstream oss;
    oss << "runner: deadlock detected after " << stall_cycles << " cycles with no retire\n"
        << "  cycle=" << d.cycles << " halted=" << d.halted << " mmio_exit=" << d.mmio_exit_valid << "\n"
        << "  pc=" << toHex(d.pc) << " fpc=" << toHex(d.fpc) << " rob_count=" << d.rob_count << "\n"
        << "  rob_head_valid=" << d.rob_head_valid << " rob_head_done=" << d.rob_head_done << " rob_head_pc=" << toHex(d.rob_head_pc)
        << "\n"
        << "  rob_head_op=" << d.rob_head_op << " rob_head_len=" << d.rob_head_len
        << " rob_head_insn=" << toHex(maskInsn(d.rob_head_insn_raw, len)) << "\n"
        << "  rob_head_disasm=" << disasm;
    last_error_ = oss.str();
    std::cerr << last_error_ << "\n";
  }

  void sampleMemWrite() {
    if (!dut_) {
      return;
    }
    if (!dut_->dmem_wvalid.toBool()) {
      return;
    }
    MemWriteEvent ev{};
    ev.cycle = dut_->cycles.value();
    ev.addr = dut_->dmem_waddr.value();
    ev.data = dut_->dmem_wdata.value();
    ev.strb = dut_->dmem_wstrb.value();
    if constexpr (HasMember_dmem_wsrc<DutT>::value) {
      ev.src = dut_->dmem_wsrc.value();
    }
    if (dut_->commit_fire0.toBool()) {
      ev.fire_mask |= 0x1u;
      ev.pc0 = dut_->commit_pc0.value();
    }
    if (dut_->commit_fire1.toBool()) {
      ev.fire_mask |= 0x2u;
      ev.pc1 = dut_->commit_pc1.value();
    }
    if (dut_->commit_fire2.toBool()) {
      ev.fire_mask |= 0x4u;
      ev.pc2 = dut_->commit_pc2.value();
    }
    if (dut_->commit_fire3.toBool()) {
      ev.fire_mask |= 0x8u;
      ev.pc3 = dut_->commit_pc3.value();
    }
    write_events_.push_back(ev);
    if (write_events_.size() > 4096) {
      write_events_.pop_front();
    }
  }

  void sampleDispatch() {
    if (!dut_) {
      return;
    }
    if constexpr (!HasMember_dispatch_fire0<DutT>::value) {
      return;
    }
    DispatchEvent ev{};
    ev.cycle = dut_->cycles.value();
    if (dut_->dispatch_fire0.toBool()) {
      ev.fire_mask |= 0x1u;
      ev.pc0 = dut_->dispatch_pc0.value();
    }
    if (dut_->dispatch_fire1.toBool()) {
      ev.fire_mask |= 0x2u;
      ev.pc1 = dut_->dispatch_pc1.value();
    }
    if (dut_->dispatch_fire2.toBool()) {
      ev.fire_mask |= 0x4u;
      ev.pc2 = dut_->dispatch_pc2.value();
    }
    if (dut_->dispatch_fire3.toBool()) {
      ev.fire_mask |= 0x8u;
      ev.pc3 = dut_->dispatch_pc3.value();
    }
    if (ev.fire_mask == 0) {
      return;
    }
    dispatch_events_.push_back(ev);
    if (dispatch_events_.size() > 256) {
      dispatch_events_.pop_front();
    }
  }

  static std::size_t mapGuestAddr(std::uint64_t addr, std::size_t mem_bytes) {
    if (mem_bytes == 0) {
      return 0;
    }

    const std::uint64_t low_mask = static_cast<std::uint64_t>(mem_bytes - 1);
    std::size_t stack_window = mem_bytes / 2;
    if (stack_window == 0) {
      stack_window = 1;
    }
    const std::uint64_t stack_offset = static_cast<std::uint64_t>(mem_bytes - stack_window);
    const std::uint64_t stack_mask = static_cast<std::uint64_t>(stack_window - 1);
    constexpr std::uint64_t kStackBase = 0x0000000007FE0000ull;

    if (addr >= kStackBase) {
      const std::uint64_t stack_addr = ((addr - kStackBase) & stack_mask) + stack_offset;
      return static_cast<std::size_t>(stack_addr);
    }
    return static_cast<std::size_t>(addr & low_mask);
  }

  Wire<512> buildIcacheLine(std::uint64_t line_addr) const {
    Wire<512> out(0);
    const std::size_t mem_bytes = dut_->mem2r1w.imem.mem_.size();
    for (unsigned wi = 0; wi < 8; wi++) {
      std::uint64_t w = 0;
      for (unsigned bi = 0; bi < 8; bi++) {
        const std::uint64_t guest = line_addr + static_cast<std::uint64_t>(wi * 8 + bi);
        const std::size_t addr = mapGuestAddr(guest, mem_bytes);
        w |= (static_cast<std::uint64_t>(dut_->mem2r1w.imem.peekByte(addr)) << (8u * bi));
      }
      out.setWord(wi, w);
    }
    return out;
  }

  void driveIcacheL2() {
    if (!dut_) {
      return;
    }
    dut_->ic_l2_req_ready = Wire<1>(ic_req_pending_ ? 0 : 1);
    if (ic_rsp_drive_now_) {
      dut_->ic_l2_rsp_valid = Wire<1>(1);
      dut_->ic_l2_rsp_addr = Wire<64>(ic_req_addr_pending_);
      dut_->ic_l2_rsp_data = buildIcacheLine(ic_req_addr_pending_);
      dut_->ic_l2_rsp_error = Wire<1>(0);
      return;
    }
    dut_->ic_l2_rsp_valid = Wire<1>(0);
    dut_->ic_l2_rsp_addr = Wire<64>(0);
    dut_->ic_l2_rsp_data = Wire<512>(0);
    dut_->ic_l2_rsp_error = Wire<1>(0);

    ic_req_seen_pre_ = (!ic_req_pending_) && dut_->ic_l2_req_valid.toBool() && dut_->ic_l2_req_ready.toBool();
    ic_req_addr_pre_ = dut_->ic_l2_req_addr.value() & ~0x3Full;
  }

  void updateIcacheL2State() {
    if (!dut_) {
      return;
    }
    if (ic_rsp_drive_now_) {
      ic_rsp_drive_now_ = false;
      ic_req_pending_ = false;
      ic_req_addr_pending_ = 0;
      ic_req_remain_cycles_ = 0;
      return;
    }
    if (ic_req_pending_) {
      if (ic_req_remain_cycles_ > 0) {
        ic_req_remain_cycles_--;
      }
      if (ic_req_remain_cycles_ == 0) {
        ic_rsp_drive_now_ = true;
      }
      return;
    }
    const bool ic_req_seen_post = dut_->ic_l2_req_valid.toBool() && dut_->ic_l2_req_ready.toBool();
    if (ic_req_seen_pre_ || ic_req_seen_post) {
      ic_req_pending_ = true;
      ic_req_addr_pending_ = ic_req_seen_pre_ ? ic_req_addr_pre_ : (dut_->ic_l2_req_addr.value() & ~0x3Full);
      ic_req_remain_cycles_ = 20;
      ic_rsp_drive_now_ = false;
      ic_req_seen_pre_ = false;
      ic_req_addr_pre_ = 0;
    }
  }

  bool loadSnapshotIntoMem(const SnapshotImage &snap) {
    const std::size_t mem_bytes = dut_->mem2r1w.imem.mem_.size();
    if (mem_bytes == 0) {
      last_error_ = "runner: DUT memory depth is zero";
      std::cerr << last_error_ << "\n";
      return false;
    }

    for (const auto &r : snap.ranges) {
      if (r.bytes.size() > mem_bytes) {
        std::ostringstream oss;
        oss << "runner: snapshot range aliases DUT memory (range too large): base=" << toHex(r.meta.base)
            << " size=" << toHex(static_cast<std::uint64_t>(r.bytes.size()))
            << " dut_mem_bytes=" << toHex(static_cast<std::uint64_t>(mem_bytes))
            << " (set narrower LINX_COSIM_MEM_RANGES)";
        last_error_ = oss.str();
        std::cerr << last_error_ << "\n";
        return false;
      }
      std::vector<std::uint8_t> seen(mem_bytes, 0);
      const std::uint64_t base = r.meta.base;
      for (std::size_t i = 0; i < r.bytes.size(); i++) {
        const std::uint64_t guest_addr = base + static_cast<std::uint64_t>(i);
        const std::size_t addr = mapGuestAddr(guest_addr, mem_bytes);
        if (seen[addr]) {
          std::ostringstream oss;
          oss << "runner: snapshot range aliases DUT memory (wrap/collision): base=" << toHex(r.meta.base)
              << " size=" << toHex(static_cast<std::uint64_t>(r.bytes.size()))
              << " first_collision_guest=" << toHex(guest_addr)
              << " mapped_addr=" << toHex(static_cast<std::uint64_t>(addr))
              << " dut_mem_bytes=" << toHex(static_cast<std::uint64_t>(mem_bytes))
              << " (set narrower LINX_COSIM_MEM_RANGES)";
          last_error_ = oss.str();
          std::cerr << last_error_ << "\n";
          return false;
        }
        seen[addr] = 1;
        const auto b = r.bytes[i];
        dut_->mem2r1w.imem.pokeByte(addr, b);
        dut_->mem2r1w.dmem.pokeByte(addr, b);
      }
    }
    return true;
  }

  void collectSlot(int slot) {
    bool fire = false;
    CommitRecord c{};
    c.cycle = dut_->cycles.value();

    if (slot == 0) {
      fire = dut_->commit_fire0.toBool();
      c.pc = dut_->commit_pc0.value();
      c.op = dut_->commit_op0.value();
      c.insn = dut_->commit_insn_raw0.value();
      c.len = dut_->commit_len0.value() & 0x7u;
      c.wb_valid = dut_->commit_wb_valid0.toBool() ? 1 : 0;
      c.wb_rd = dut_->commit_wb_rd0.value();
      c.wb_data = dut_->commit_wb_data0.value();
      c.mem_valid = dut_->commit_mem_valid0.toBool() ? 1 : 0;
      c.mem_is_store = dut_->commit_mem_is_store0.toBool() ? 1 : 0;
      c.mem_addr = dut_->commit_mem_addr0.value();
      c.mem_wdata = dut_->commit_mem_wdata0.value();
      c.mem_rdata = dut_->commit_mem_rdata0.value();
      c.mem_size = dut_->commit_mem_size0.value();
      c.trap_valid = dut_->commit_trap_valid0.toBool() ? 1 : 0;
      c.trap_cause = dut_->commit_trap_cause0.value();
      c.next_pc = dut_->commit_next_pc0.value();
    } else if (slot == 1) {
      fire = dut_->commit_fire1.toBool();
      c.pc = dut_->commit_pc1.value();
      c.op = dut_->commit_op1.value();
      c.insn = dut_->commit_insn_raw1.value();
      c.len = dut_->commit_len1.value() & 0x7u;
      c.wb_valid = dut_->commit_wb_valid1.toBool() ? 1 : 0;
      c.wb_rd = dut_->commit_wb_rd1.value();
      c.wb_data = dut_->commit_wb_data1.value();
      c.mem_valid = dut_->commit_mem_valid1.toBool() ? 1 : 0;
      c.mem_is_store = dut_->commit_mem_is_store1.toBool() ? 1 : 0;
      c.mem_addr = dut_->commit_mem_addr1.value();
      c.mem_wdata = dut_->commit_mem_wdata1.value();
      c.mem_rdata = dut_->commit_mem_rdata1.value();
      c.mem_size = dut_->commit_mem_size1.value();
      c.trap_valid = dut_->commit_trap_valid1.toBool() ? 1 : 0;
      c.trap_cause = dut_->commit_trap_cause1.value();
      c.next_pc = dut_->commit_next_pc1.value();
    } else if (slot == 2) {
      fire = dut_->commit_fire2.toBool();
      c.pc = dut_->commit_pc2.value();
      c.op = dut_->commit_op2.value();
      c.insn = dut_->commit_insn_raw2.value();
      c.len = dut_->commit_len2.value() & 0x7u;
      c.wb_valid = dut_->commit_wb_valid2.toBool() ? 1 : 0;
      c.wb_rd = dut_->commit_wb_rd2.value();
      c.wb_data = dut_->commit_wb_data2.value();
      c.mem_valid = dut_->commit_mem_valid2.toBool() ? 1 : 0;
      c.mem_is_store = dut_->commit_mem_is_store2.toBool() ? 1 : 0;
      c.mem_addr = dut_->commit_mem_addr2.value();
      c.mem_wdata = dut_->commit_mem_wdata2.value();
      c.mem_rdata = dut_->commit_mem_rdata2.value();
      c.mem_size = dut_->commit_mem_size2.value();
      c.trap_valid = dut_->commit_trap_valid2.toBool() ? 1 : 0;
      c.trap_cause = dut_->commit_trap_cause2.value();
      c.next_pc = dut_->commit_next_pc2.value();
    } else {
      fire = dut_->commit_fire3.toBool();
      c.pc = dut_->commit_pc3.value();
      c.op = dut_->commit_op3.value();
      c.insn = dut_->commit_insn_raw3.value();
      c.len = dut_->commit_len3.value() & 0x7u;
      c.wb_valid = dut_->commit_wb_valid3.toBool() ? 1 : 0;
      c.wb_rd = dut_->commit_wb_rd3.value();
      c.wb_data = dut_->commit_wb_data3.value();
      c.mem_valid = dut_->commit_mem_valid3.toBool() ? 1 : 0;
      c.mem_is_store = dut_->commit_mem_is_store3.toBool() ? 1 : 0;
      c.mem_addr = dut_->commit_mem_addr3.value();
      c.mem_wdata = dut_->commit_mem_wdata3.value();
      c.mem_rdata = dut_->commit_mem_rdata3.value();
      c.mem_size = dut_->commit_mem_size3.value();
      c.trap_valid = dut_->commit_trap_valid3.toBool() ? 1 : 0;
      c.trap_cause = dut_->commit_trap_cause3.value();
      c.next_pc = dut_->commit_next_pc3.value();
    }

    if (!fire) {
      return;
    }

    c.insn = maskInsn(c.insn, c.len);
    retire_q_.push_back(c);
  }

  void collectCommits() {
    for (int s = 0; s < 4; s++) {
      collectSlot(s);
    }
  }

  std::unique_ptr<DutT> dut_{};
  std::unique_ptr<Testbench<DutT>> tb_{};
  std::deque<CommitRecord> retire_q_{};
  std::deque<MemWriteEvent> write_events_{};
  std::deque<DispatchEvent> dispatch_events_{};
  bool ic_req_pending_ = false;
  bool ic_rsp_drive_now_ = false;
  std::uint64_t ic_req_addr_pending_ = 0;
  std::uint64_t ic_req_remain_cycles_ = 0;
  bool ic_req_seen_pre_ = false;
  std::uint64_t ic_req_addr_pre_ = 0;
  RunnerOptions opts_{};
  std::uint64_t max_cycles_ = 0;
  std::uint64_t deadlock_cycles_ = 0;
  std::string last_error_{};
};

static bool parseQemuCommit(const std::string &line, CommitRecord &rec) {
  std::string type;
  if (!getJsonString(line, "type", type) || type != "commit") {
    return false;
  }
  return getJsonU64(line, "seq", rec.seq) && getJsonU64(line, "pc", rec.pc) && getJsonU64(line, "insn", rec.insn) &&
         getJsonU64(line, "len", rec.len) && getJsonU64(line, "wb_valid", rec.wb_valid) && getJsonU64(line, "wb_rd", rec.wb_rd) &&
         getJsonU64(line, "wb_data", rec.wb_data) && getJsonU64(line, "mem_valid", rec.mem_valid) &&
         getJsonU64(line, "mem_is_store", rec.mem_is_store) && getJsonU64(line, "mem_addr", rec.mem_addr) &&
         getJsonU64(line, "mem_wdata", rec.mem_wdata) && getJsonU64(line, "mem_rdata", rec.mem_rdata) &&
         getJsonU64(line, "mem_size", rec.mem_size) && getJsonU64(line, "trap_valid", rec.trap_valid) &&
         getJsonU64(line, "trap_cause", rec.trap_cause) && getJsonU64(line, "traparg0", rec.traparg0) &&
         getJsonU64(line, "next_pc", rec.next_pc);
}

static std::optional<Mismatch> compareCommit(const CommitRecord &qemu, const CommitRecord &dut) {
  auto cmp = [&](const char *field, std::uint64_t qv, std::uint64_t dv) -> std::optional<Mismatch> {
    if (qv != dv) {
      return Mismatch{field, qv, dv};
    }
    return std::nullopt;
  };

  if (auto mm = cmp("pc", qemu.pc, dut.pc))
    return mm;
  if (auto mm = cmp("len", qemu.len, dut.len))
    return mm;
  if (auto mm = cmp("insn", maskInsn(qemu.insn, qemu.len), maskInsn(dut.insn, dut.len)))
    return mm;
  if (auto mm = cmp("wb_valid", qemu.wb_valid, dut.wb_valid))
    return mm;
  if (qemu.wb_valid) {
    if (auto mm = cmp("wb_rd", qemu.wb_rd, dut.wb_rd))
      return mm;
    if (auto mm = cmp("wb_data", qemu.wb_data, dut.wb_data))
      return mm;
  }

  if (auto mm = cmp("mem_valid", qemu.mem_valid, dut.mem_valid))
    return mm;
  if (qemu.mem_valid) {
    if (auto mm = cmp("mem_is_store", qemu.mem_is_store, dut.mem_is_store))
      return mm;
    if (auto mm = cmp("mem_addr", qemu.mem_addr, dut.mem_addr))
      return mm;
    if (auto mm = cmp("mem_size", qemu.mem_size, dut.mem_size))
      return mm;
    if (qemu.mem_is_store) {
      if (auto mm = cmp("mem_wdata", qemu.mem_wdata, dut.mem_wdata))
        return mm;
    } else {
      if (auto mm = cmp("mem_rdata", qemu.mem_rdata, dut.mem_rdata))
        return mm;
    }
  }

  if (auto mm = cmp("trap_valid", qemu.trap_valid, dut.trap_valid))
    return mm;
  if (qemu.trap_valid) {
    if (auto mm = cmp("trap_cause", qemu.trap_cause, dut.trap_cause))
      return mm;
    if (auto mm = cmp("traparg0", qemu.traparg0, dut.traparg0))
      return mm;
  }

  if (auto mm = cmp("next_pc", qemu.next_pc, dut.next_pc))
    return mm;

  return std::nullopt;
}

static bool isMetadataCommit(const CommitRecord &r) {
  const bool zero_meta = (r.len == 0) && (r.insn == 0) && (r.pc == 0);
  const std::uint64_t insn_m = maskInsn(r.insn, r.len);
  const bool is_bstart =
      ((r.len == 2) && isBstart16(static_cast<std::uint16_t>(insn_m))) ||
      ((r.len == 4) && isBstart32(static_cast<std::uint32_t>(insn_m))) ||
      ((r.len == 6) && isBstart48(insn_m));
  const bool is_macro_marker = (r.len == 4) && isMacroMarker32(static_cast<std::uint32_t>(insn_m));
  // Boundary markers can appear as trace-only records (QEMU and DUT may differ
  // in whether a side-effect-free marker is emitted separately). Treat any
  // side-effect-free BSTART marker as metadata so commit streams stay aligned.
  const bool bstart_metadata =
      is_bstart &&
      (r.wb_valid == 0) && (r.mem_valid == 0) && (r.trap_valid == 0);
  const bool macro_metadata =
      is_macro_marker &&
      (r.wb_valid == 0) && (r.mem_valid == 0) && (r.trap_valid == 0);
  return zero_meta || bstart_metadata || macro_metadata;
}

static bool sendAckOk(int fd, std::uint64_t seq) {
  char buf[128];
  std::snprintf(buf, sizeof(buf), "{\"seq\":%" PRIu64 ",\"status\":\"ok\"}", seq);
  return sendLine(fd, buf);
}

static bool sendAckMismatch(int fd, std::uint64_t seq, const Mismatch &mm) {
  char buf[256];
  std::snprintf(buf,
                sizeof(buf),
                "{\"seq\":%" PRIu64 ",\"status\":\"mismatch\",\"field\":\"%s\",\"qemu\":%" PRIu64 ",\"dut\":%" PRIu64 "}",
                seq,
                mm.field.c_str(),
                mm.qemu,
                mm.dut);
  return sendLine(fd, buf);
}

static std::optional<RunnerOptions> parseArgs(int argc, char **argv) {
  RunnerOptions opts{};
  for (int i = 1; i < argc; i++) {
    const std::string arg = argv[i];
    if (arg == "--socket" && i + 1 < argc) {
      opts.socket_path = argv[++i];
    } else if (arg == "--boot-sp" && i + 1 < argc) {
      opts.boot_sp = std::strtoull(argv[++i], nullptr, 0);
    } else if (arg == "--boot-ra" && i + 1 < argc) {
      opts.boot_ra = std::strtoull(argv[++i], nullptr, 0);
    } else if (arg == "--max-dut-cycles" && i + 1 < argc) {
      opts.max_dut_cycles = std::strtoull(argv[++i], nullptr, 0);
    } else if (arg == "--deadlock-cycles" && i + 1 < argc) {
      opts.deadlock_cycles = std::strtoull(argv[++i], nullptr, 0);
    } else if (arg == "--accept-max-commits-end") {
      opts.accept_max_commits_end = true;
    } else if (arg == "--disasm-spec" && i + 1 < argc) {
      opts.disasm_spec = argv[++i];
    } else if (arg == "--disasm-tool" && i + 1 < argc) {
      opts.disasm_tool = argv[++i];
    } else if (arg == "--force-mismatch") {
      opts.force_mismatch = true;
    } else if (arg == "--verbose") {
      opts.verbose = true;
    } else {
      std::cerr << "runner: unknown arg: " << arg << "\n";
      return std::nullopt;
    }
  }

  if (opts.socket_path.empty()) {
    if (const char *env = std::getenv("LINX_COSIM_SOCKET")) {
      opts.socket_path = env;
    }
  }
  if (const char *env = std::getenv("LINXCORE_BOOT_SP")) {
    opts.boot_sp = std::strtoull(env, nullptr, 0);
  }
  if (const char *env = std::getenv("LINXCORE_BOOT_RA")) {
    opts.boot_ra = std::strtoull(env, nullptr, 0);
  }
  if (const char *env = std::getenv("LINXCORE_MAX_DUT_CYCLES")) {
    opts.max_dut_cycles = std::strtoull(env, nullptr, 0);
  }
  if (const char *env = std::getenv("LINXCORE_DEADLOCK_CYCLES")) {
    opts.deadlock_cycles = std::strtoull(env, nullptr, 0);
  }
  if (const char *env = std::getenv("LINXCORE_ACCEPT_MAX_COMMITS_END")) {
    opts.accept_max_commits_end = !(env[0] == '0' && env[1] == '\0');
  }
  if (const char *env = std::getenv("LINXCORE_FORCE_MISMATCH")) {
    opts.force_mismatch = !(env[0] == '0' && env[1] == '\0');
  }
  if (const char *env = std::getenv("LINXCORE_DISASM_SPEC")) {
    opts.disasm_spec = env;
  }
  if (const char *env = std::getenv("LINXCORE_DISASM_TOOL")) {
    opts.disasm_tool = env;
  }

  if (opts.socket_path.empty()) {
    std::cerr << "usage: linxcore_lockstep_runner --socket <path> [--boot-sp <hex>] [--boot-ra <hex>] [--max-dut-cycles <n>] [--deadlock-cycles <n>] [--accept-max-commits-end] [--disasm-tool <path>] [--disasm-spec <path>] [--force-mismatch] [--verbose]\n";
    return std::nullopt;
  }
  return opts;
}

} // namespace

int main(int argc, char **argv) {
  // Preserve fail-fast semantics but avoid hard-killing the runner when QEMU
  // closes the socket before reading an ack.
  std::signal(SIGPIPE, SIG_IGN);

  auto maybe_opts = parseArgs(argc, argv);
  if (!maybe_opts.has_value()) {
    return 2;
  }
  const RunnerOptions opts = *maybe_opts;

  const int server_fd = createServer(opts.socket_path);
  if (server_fd < 0) {
    std::cerr << "runner: failed to create server socket: " << opts.socket_path << "\n";
    return 2;
  }

  if (opts.verbose) {
    std::cerr << "runner: listening on " << opts.socket_path << "\n";
  }

  const int client_fd = ::accept(server_fd, nullptr, nullptr);
  if (client_fd < 0) {
    std::cerr << "runner: accept failed\n";
    ::close(server_fd);
    return 2;
  }

  bool started = false;
  std::uint64_t expected_seq = 0;
  std::uint64_t committed = 0;
  std::uint64_t active_terminate_pc = 0;
  bool active_terminate_pc_valid = false;
  bool forced_mismatch_done = false;
  CommitRecord last_qemu_match{};
  CommitRecord last_dut_match{};
  bool have_last_match = false;
  struct RecentPair {
    std::uint64_t seq = 0;
    CommitRecord qemu{};
    CommitRecord dut{};
  };
  std::deque<RecentPair> recent_pairs{};
  std::unique_ptr<IDutStepper> dut = std::make_unique<DutStepperImpl<pyc::gen::linxcore_top>>(opts);
  std::string line;

  while (recvLine(client_fd, line)) {
    if (line.empty()) {
      continue;
    }

    std::string type;
    if (!getJsonString(line, "type", type)) {
      std::cerr << "runner: malformed message (missing type): " << line << "\n";
      ::close(client_fd);
      ::close(server_fd);
      return 3;
    }

    if (type == "start") {
      std::string snapshot_path;
      std::uint64_t trigger_pc = 0;
      std::uint64_t boot_pc = 0;
      std::uint64_t terminate_pc = 0;
      std::uint64_t boot_sp = opts.boot_sp;
      std::uint64_t boot_ra = opts.boot_ra;
      std::uint64_t seq_base = 0;
      if (!getJsonString(line, "snapshot_path", snapshot_path)) {
        std::cerr << "runner: start missing snapshot_path\n";
        ::close(client_fd);
        ::close(server_fd);
        return 3;
      }
      if (!getJsonU64(line, "trigger_pc", trigger_pc)) {
        std::cerr << "runner: start missing trigger_pc\n";
        ::close(client_fd);
        ::close(server_fd);
        return 3;
      }
      active_terminate_pc_valid = getJsonU64(line, "terminate_pc", terminate_pc);
      active_terminate_pc = terminate_pc;
      if (!getJsonU64(line, "boot_pc", boot_pc)) {
        boot_pc = trigger_pc;
      }
      (void)getJsonU64(line, "boot_sp", boot_sp);
      (void)getJsonU64(line, "boot_ra", boot_ra);
      (void)getJsonU64(line, "seq_base", seq_base);

      if (trigger_pc != boot_pc) {
        const Mismatch mm{"trigger_pc_boot_pc", trigger_pc, boot_pc};
        (void)sendAckMismatch(client_fd, 0, mm);
        ::close(client_fd);
        ::close(server_fd);
        return 4;
      }

      SnapshotImage snap{};
      if (!loadSnapshot(snapshot_path, snap)) {
        ::close(client_fd);
        ::close(server_fd);
        return 3;
      }
      if (!dut->init(snap, trigger_pc, boot_sp, boot_ra)) {
        std::cerr << "runner: failed to initialize DUT\n";
        ::close(client_fd);
        ::close(server_fd);
        return 3;
      }

      started = true;
      expected_seq = seq_base;
      committed = 0;
      have_last_match = false;
      recent_pairs.clear();
      if (opts.verbose) {
        std::cerr << "runner: start trigger=0x" << std::hex << trigger_pc << " boot_sp=0x" << boot_sp
                  << " boot_ra=0x" << boot_ra << std::dec << " seq_base=" << seq_base
                  << " ranges=" << snap.ranges.size() << "\n";
      }
      continue;
    }

    if (type == "commit") {
      if (!started) {
        std::cerr << "runner: commit seen before start\n";
        ::close(client_fd);
        ::close(server_fd);
        return 3;
      }

      CommitRecord qemu{};
      if (!parseQemuCommit(line, qemu)) {
        std::cerr << "runner: malformed commit message\n";
        ::close(client_fd);
        ::close(server_fd);
        return 3;
      }
      if (qemu.seq != expected_seq) {
        const Mismatch mm{"seq", qemu.seq, expected_seq};
        (void)sendAckMismatch(client_fd, qemu.seq, mm);
        ::close(client_fd);
        ::close(server_fd);
        return 4;
      }

      // QEMU may emit metadata-only boundary commits that do not correspond to
      // a retired DUT instruction:
      // 1) zeroed pseudo-commit records (pc/insn/len all zero),
      // 2) C.BSTART shadow records that hold at the same PC with no WB/MEM.
      const bool qemu_meta_commit = isMetadataCommit(qemu);
      if (qemu_meta_commit) {
        if (!sendAckOk(client_fd, qemu.seq)) {
          std::cerr << "runner: failed to send ack for metadata commit\n";
          ::close(client_fd);
          ::close(server_fd);
          return 3;
        }
        if (opts.verbose) {
          std::cerr << "runner: skip metadata commit seq=" << qemu.seq
                    << " wb_valid=" << qemu.wb_valid
                    << " mem_valid=" << qemu.mem_valid << "\n";
        }
        expected_seq++;
        continue;
      }

      CommitRecord dut_rec{};
      while (true) {
        if (!dut->nextCommit(dut_rec)) {
          const Mismatch mm{"dut_no_commit", 1, 0};
          (void)sendAckMismatch(client_fd, qemu.seq, mm);
          const DeadlockDebug dbg = dut->debugState();
          std::cerr << "runner: DUT could not produce commit for seq=" << qemu.seq
                    << " (pc=" << toHex(dbg.pc) << " rob_head_pc=" << toHex(dbg.rob_head_pc) << ")\n";
          if (!dut->lastError().empty()) {
            std::cerr << dut->lastError() << "\n";
          }
          ::close(client_fd);
          ::close(server_fd);
          return 4;
        }
        if (!isMetadataCommit(dut_rec)) {
          break;
        }
        if (opts.verbose) {
          std::cerr << "runner: skip dut metadata pc=" << toHex(dut_rec.pc) << " insn=" << toHex(maskInsn(dut_rec.insn, dut_rec.len))
                    << " next_pc=" << toHex(dut_rec.next_pc) << "\n";
        }
      }
      dut_rec.seq = qemu.seq;
      if (opts.force_mismatch && !forced_mismatch_done) {
        dut_rec.pc ^= 1u;
        forced_mismatch_done = true;
      }

      const auto mismatch = compareCommit(qemu, dut_rec);
      if (mismatch.has_value()) {
        (void)sendAckMismatch(client_fd, qemu.seq, *mismatch);
        if (opts.verbose) {
          std::cerr << "runner: mismatch seq=" << qemu.seq << " field=" << mismatch->field << " qemu=" << mismatch->qemu
                    << " dut=" << mismatch->dut << "\n"
                    << "  qemu: " << formatCommit(qemu) << "\n"
                    << "  dut : " << formatCommit(dut_rec) << "\n";
          if (qemu.mem_valid && !qemu.mem_is_store) {
            const std::uint64_t mem_peek = dut->peekMem(qemu.mem_addr, qemu.mem_size);
            std::cerr << "  dut_mem_peek[" << toHex(qemu.mem_addr) << "]=" << toHex(mem_peek)
                      << " size=" << qemu.mem_size << "\n";
            std::cerr << "  " << dut->recentWriteSummary(qemu.mem_addr) << "\n";
          }
          if (mismatch->field == "insn") {
            const std::uint64_t imem_peek = dut->peekIMem(qemu.pc, qemu.len);
            const std::uint64_t dmem_peek = dut->peekMem(qemu.pc, qemu.len);
            std::cerr << "  dut_imem_peek[" << toHex(qemu.pc) << "]=" << toHex(imem_peek)
                      << " len=" << qemu.len << "\n";
            std::cerr << "  dut_dmem_peek[" << toHex(qemu.pc) << "]=" << toHex(dmem_peek)
                      << " len=" << qemu.len << "\n";
            std::cerr << "  " << dut->recentWriteSummary(qemu.pc) << "\n";
          }
          std::cerr << "  " << dut->recentDispatchSummary() << "\n";
          if (!recent_pairs.empty()) {
            std::cerr << "  recent_commits:\n";
            for (const auto &rp : recent_pairs) {
              std::cerr << "    seq=" << rp.seq << " qemu_pc=" << toHex(rp.qemu.pc) << " dut_pc=" << toHex(rp.dut.pc)
                        << " dut_cycle=" << rp.dut.cycle
                        << " qemu_mem=(" << rp.qemu.mem_valid << "," << rp.qemu.mem_is_store << "," << toHex(rp.qemu.mem_addr)
                        << "," << toHex(rp.qemu.mem_wdata) << "," << toHex(rp.qemu.mem_rdata) << ")"
                        << " dut_mem=(" << rp.dut.mem_valid << "," << rp.dut.mem_is_store << "," << toHex(rp.dut.mem_addr)
                        << "," << toHex(rp.dut.mem_wdata) << "," << toHex(rp.dut.mem_rdata) << ")\n";
            }
          }
        }
        ::close(client_fd);
        ::close(server_fd);
        return 4;
      }

      if (!sendAckOk(client_fd, qemu.seq)) {
        std::cerr << "runner: failed to send ack\n";
        ::close(client_fd);
        ::close(server_fd);
        return 3;
      }
      recent_pairs.push_back(RecentPair{qemu.seq, qemu, dut_rec});
      if (recent_pairs.size() > 64) {
        recent_pairs.pop_front();
      }
      have_last_match = true;
      last_qemu_match = qemu;
      last_dut_match = dut_rec;
      expected_seq++;
      committed++;
      continue;
    }

    if (type == "end") {
      std::string reason;
      (void)getJsonString(line, "reason", reason);
      if (!started) {
        std::cerr << "runner: end seen before start\n";
        ::close(client_fd);
        ::close(server_fd);
        return 3;
      }
      // Drain buffered metadata-only DUT commits so end-of-window checks remain
      // strict for architectural commits while tolerating shadow records.
      std::uint64_t extra_nonmeta = 0;
      std::uint64_t extra_nonmeta_terminate_tail = 0;
      std::uint64_t drained_meta = 0;
      while (dut->pendingCommits() != 0) {
        CommitRecord tail{};
        if (!dut->nextCommit(tail)) {
          break;
        }
        if (isMetadataCommit(tail)) {
          drained_meta++;
        } else {
          extra_nonmeta++;
          const bool terminate_tail_ok =
              (reason == "terminate_pc") &&
              have_last_match &&
              active_terminate_pc_valid &&
              (last_qemu_match.pc == active_terminate_pc) &&
              (tail.cycle == last_dut_match.cycle);
          if (terminate_tail_ok) {
            extra_nonmeta_terminate_tail++;
          }
        }
      }
      const std::uint64_t extra_nonmeta_strict = extra_nonmeta - extra_nonmeta_terminate_tail;
      const bool strict_end = (reason == "terminate_pc") || (reason == "guest_exit");
      if (extra_nonmeta_strict != 0 && strict_end) {
        const Mismatch mm{"extra_dut_commits", 0, extra_nonmeta_strict};
        (void)sendAckMismatch(client_fd, expected_seq, mm);
        std::cerr << "runner: one-to-one violation, DUT has " << extra_nonmeta_strict
                  << " extra non-metadata commit(s) after QEMU end\n";
        ::close(client_fd);
        ::close(server_fd);
        return 4;
      }
      if (opts.verbose) {
        if (drained_meta != 0) {
          std::cerr << "runner: drained " << drained_meta << " trailing metadata commit(s) at end\n";
        }
        if (extra_nonmeta_terminate_tail != 0) {
          std::cerr << "runner: tolerated " << extra_nonmeta_terminate_tail
                    << " same-cycle non-metadata tail commit(s) at terminate_pc end\n";
        }
        if (extra_nonmeta != 0 && !strict_end) {
          std::cerr << "runner: tolerated " << extra_nonmeta
                    << " trailing non-metadata commit(s) at end (reason=" << reason << ")\n";
        }
        std::cerr << "runner: end reason=" << reason << " commits=" << committed << "\n";
      }
      ::close(client_fd);
      ::close(server_fd);
      if (reason == "terminate_pc") {
        return 0;
      }
      if (reason == "max_commits" && opts.accept_max_commits_end) {
        return 0;
      }
      return 5;
    }

    std::cerr << "runner: unknown message type: " << type << "\n";
    ::close(client_fd);
    ::close(server_fd);
    return 3;
  }

  if (started) {
    const std::size_t pending = dut->pendingCommits();
    if (pending != 0) {
      std::cerr << "runner: socket closed before end; DUT has " << pending
                << " extra buffered commit(s)\n";
      ::close(client_fd);
      ::close(server_fd);
      return 4;
    }
    if (opts.verbose) {
      std::cerr << "runner: socket closed before end message (implicit guest_exit), commits=" << committed << "\n";
    }
    ::close(client_fd);
    ::close(server_fd);
    return 0;
  }

  std::cerr << "runner: socket closed before start/end handshake\n";
  ::close(client_fd);
  ::close(server_fd);
  return 5;
}
