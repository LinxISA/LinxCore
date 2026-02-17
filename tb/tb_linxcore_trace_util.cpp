#include "tb_linxcore_trace_util.hpp"

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <optional>
#include <regex>
#include <sstream>
#include <string>

namespace linxcore::tb {
namespace {

static std::uint64_t maskInsn(std::uint64_t raw, std::uint8_t len) {
  switch (len) {
  case 2:
    return raw & 0xFFFFu;
  case 4:
    return raw & 0xFFFF'FFFFu;
  case 6:
    return raw & 0xFFFF'FFFF'FFFFu;
  default:
    return raw;
  }
}

static std::uint8_t normalizeLen(std::uint8_t len) {
  return (len == 2 || len == 4 || len == 6) ? len : 4;
}

static std::string insnHexToken(std::uint64_t raw, std::uint8_t len) {
  unsigned digits = 16;
  if (len == 2)
    digits = 4;
  else if (len == 4)
    digits = 8;
  else if (len == 6)
    digits = 12;
  std::ostringstream oss;
  oss << std::hex << std::nouppercase << std::setfill('0') << std::setw(static_cast<int>(digits))
      << maskInsn(raw, normalizeLen(len));
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
  if (!fp)
    return std::nullopt;
  std::string out;
  char buf[512];
  while (std::fgets(buf, sizeof(buf), fp) != nullptr) {
    out += buf;
  }
  const int rc = ::pclose(fp);
  if (rc != 0)
    return std::nullopt;
  while (!out.empty() && (out.back() == '\n' || out.back() == '\r')) {
    out.pop_back();
  }
  return out;
}

} // namespace

std::string disasmInsn(const std::string &tool, const std::string &spec, std::uint64_t raw, std::uint8_t len) {
  const std::string token = insnHexToken(raw, len);
  const std::string cmd =
      "python3 " + shellQuote(tool) + " --spec " + shellQuote(spec) + " --hex " + shellQuote(token) + " 2>/dev/null";
  const auto out = runCommandCapture(cmd);
  if (!out.has_value() || out->empty())
    return "<disasm-unavailable>";
  const std::size_t tab = out->find('\t');
  if (tab == std::string::npos || tab + 1 >= out->size())
    return *out;
  return out->substr(tab + 1);
}

std::unordered_map<std::uint64_t, std::string> loadOpNameMap(const std::string &isaPyPath) {
  std::unordered_map<std::uint64_t, std::string> out{};
  std::ifstream in(isaPyPath);
  if (!in.is_open())
    return out;

  static const std::regex kLineRe(R"(^\s*(OP_[A-Za-z0-9_]+)\s*=\s*([0-9]+)\s*$)");
  std::string line;
  while (std::getline(in, line)) {
    std::smatch m;
    if (!std::regex_match(line, m, kLineRe))
      continue;
    if (m.size() != 3)
      continue;
    const std::uint64_t opId = static_cast<std::uint64_t>(std::stoull(m[2].str(), nullptr, 10));
    out[opId] = m[1].str();
  }
  return out;
}

std::unordered_map<std::uint64_t, std::string> loadObjdumpPcMap(const std::string &objdumpTool, const std::string &elfPath) {
  std::unordered_map<std::uint64_t, std::string> out{};
  if (objdumpTool.empty() || elfPath.empty())
    return out;
  if (!std::filesystem::exists(elfPath))
    return out;

  const std::string cmd = shellQuote(objdumpTool) + " -d " + shellQuote(elfPath) + " 2>/dev/null";
  const auto text = runCommandCapture(cmd);
  if (!text.has_value() || text->empty())
    return out;

  static const std::regex kLineRe(R"(^\s*([0-9a-fA-F]+):\s*(?:[0-9a-fA-F]{2}\s+)+\s*(.*)\s*$)");
  std::istringstream iss(*text);
  std::string line;
  while (std::getline(iss, line)) {
    std::smatch m;
    if (!std::regex_match(line, m, kLineRe) || m.size() != 3)
      continue;
    const std::uint64_t pc = std::stoull(m[1].str(), nullptr, 16);
    std::string disasm = m[2].str();
    while (!disasm.empty() && (disasm.back() == '\n' || disasm.back() == '\r' || disasm.back() == ' ')) {
      disasm.pop_back();
    }
    if (!disasm.empty())
      out[pc] = disasm;
  }
  return out;
}

} // namespace linxcore::tb

