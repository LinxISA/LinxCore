#pragma once

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#if __has_include(<pyc/cpp/pyc_bits.hpp>)
#include <pyc/cpp/pyc_bits.hpp>
#elif __has_include(<cpp/pyc_bits.hpp>)
#include <cpp/pyc_bits.hpp>
#elif __has_include(<pyc_bits.hpp>)
#include <pyc_bits.hpp>
#else
#error "pyc_bits.hpp not found; set include path for pyCircuit runtime headers"
#endif

namespace linxcore::sim {

inline std::size_t resolveMemBytesFromEnv() {
  if (const char *env = std::getenv("PYC_MEM_BYTES")) {
    try {
      const unsigned long long v = std::stoull(env, nullptr, 0);
      if (v > 0)
        return static_cast<std::size_t>(v);
    } catch (...) {
    }
  }
  return static_cast<std::size_t>(1u << 20);
}

inline std::size_t mapBringupMemAddr(std::uint64_t addr, std::size_t mem_bytes) {
  if (mem_bytes == 0)
    return 0;
  const std::uint64_t low_mask = static_cast<std::uint64_t>(mem_bytes - 1);
  std::size_t stack_window = std::max<std::size_t>(1, mem_bytes / 4);
  std::size_t data_window = std::max<std::size_t>(1, mem_bytes / 4);
  const std::uint64_t stack_offset = static_cast<std::uint64_t>(mem_bytes - stack_window);
  const std::uint64_t data_offset = static_cast<std::uint64_t>(std::max<std::size_t>(0, mem_bytes - stack_window - data_window));
  constexpr std::uint64_t kStackBase = 0x0000000007FE0000ull;
  constexpr std::uint64_t kDataBase = 0x0000000001000000ull;
  if (addr >= kStackBase) {
    return static_cast<std::size_t>((((addr - kStackBase) & static_cast<std::uint64_t>(stack_window - 1)) + stack_offset) & low_mask);
  }
  if (addr >= kDataBase) {
    return static_cast<std::size_t>((((addr - kDataBase) & static_cast<std::uint64_t>(data_window - 1)) + data_offset) & low_mask);
  }
  return static_cast<std::size_t>(addr & low_mask);
}

class HostMemShadow {
public:
  explicit HostMemShadow(std::size_t mem_bytes) : mem_(mem_bytes, 0), valid_(mem_bytes, 0) {}

  bool loadMemh(const std::string &path, std::string *err) {
    std::ifstream in(path);
    if (!in.is_open()) {
      if (err)
        *err = "cannot open file";
      return false;
    }

    std::size_t addr = 0;
    bool have_addr = false;
    std::string line;
    unsigned lineno = 0;
    while (std::getline(in, line)) {
      ++lineno;
      const auto hash = line.find('#');
      if (hash != std::string::npos)
        line.erase(hash);
      std::stringstream ss(line);
      std::string tok;
      while (ss >> tok) {
        if (!tok.empty() && tok[0] == '@') {
          try {
            addr = static_cast<std::size_t>(std::stoull(tok.substr(1), nullptr, 16));
          } catch (...) {
            if (err)
              *err = "invalid @addr at line " + std::to_string(lineno);
            return false;
          }
          have_addr = true;
          continue;
        }
        if (!have_addr) {
          if (err)
            *err = "byte token before @addr at line " + std::to_string(lineno);
          return false;
        }
        unsigned value = 0;
        try {
          value = static_cast<unsigned>(std::stoul(tok, nullptr, 16));
        } catch (...) {
          if (err)
            *err = "invalid byte token at line " + std::to_string(lineno);
          return false;
        }
        if (value > 0xFFu) {
          if (err)
            *err = "byte token out of range at line " + std::to_string(lineno);
          return false;
        }
        if (addr >= mem_.size()) {
          if (err)
            *err = "memh address out of range at line " + std::to_string(lineno);
          return false;
        }
        mem_[addr] = static_cast<std::uint8_t>(value);
        valid_[addr] = 1;
        ++addr;
      }
    }
    return true;
  }

  std::uint8_t loadGuestByte(std::uint64_t guest_addr) const {
    if (mem_.empty())
      return 0;
    const std::size_t addr = mapBringupMemAddr(guest_addr, mem_.size());
    return (addr < mem_.size()) ? mem_[addr] : 0;
  }

  void storeGuestWord(std::uint64_t guest_addr, std::uint64_t data, std::uint8_t strb) {
    if (mem_.empty())
      return;
    const std::size_t base = mapBringupMemAddr(guest_addr, mem_.size());
    for (unsigned i = 0; i < 8; ++i) {
      if (((strb >> i) & 1u) == 0)
        continue;
      const std::size_t addr = base + i;
      if (addr >= mem_.size())
        break;
      mem_[addr] = static_cast<std::uint8_t>((data >> (8u * i)) & 0xFFu);
      valid_[addr] = 1;
    }
  }

  pyc::cpp::Wire<512> buildIcacheLine(std::uint64_t line_addr) const {
    pyc::cpp::Wire<512> out(0);
    for (unsigned wi = 0; wi < 8; ++wi) {
      std::uint64_t w = 0;
      for (unsigned bi = 0; bi < 8; ++bi) {
        const std::uint64_t guest = line_addr + static_cast<std::uint64_t>(wi * 8 + bi);
        w |= static_cast<std::uint64_t>(loadGuestByte(guest)) << (8u * bi);
      }
      out.setWord(wi, w);
    }
    return out;
  }

  std::size_t memBytes() const { return mem_.size(); }

  std::uint8_t loadEffectiveByte(std::size_t addr) const {
    return (addr < mem_.size()) ? mem_[addr] : 0;
  }

  bool validEffectiveByte(std::size_t addr) const {
    return addr < valid_.size() && valid_[addr] != 0;
  }

private:
  std::vector<std::uint8_t> mem_;
  std::vector<std::uint8_t> valid_;
};

template <class Fn>
inline void replayPreloadWords(const HostMemShadow &mem, Fn &&fn) {
  const std::size_t mem_bytes = mem.memBytes();
  for (std::size_t base = 0; base < mem_bytes; base += 8) {
    std::uint64_t data = 0;
    std::uint8_t strb = 0;
    for (unsigned i = 0; i < 8; ++i) {
      const std::size_t addr = base + i;
      if (addr >= mem_bytes || !mem.validEffectiveByte(addr))
        continue;
      data |= static_cast<std::uint64_t>(mem.loadEffectiveByte(addr)) << (8u * i);
      strb |= static_cast<std::uint8_t>(1u << i);
    }
    if (strb != 0)
      fn(static_cast<std::uint64_t>(base), data, strb);
  }
}

}  // namespace linxcore::sim
