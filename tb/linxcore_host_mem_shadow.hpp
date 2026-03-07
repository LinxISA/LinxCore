#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <map>
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

using pyc::cpp::Wire;

class HostMemShadow {
public:
  struct PreloadWord {
    std::uint64_t guest_addr = 0;
    std::uint64_t data = 0;
    std::uint8_t strb = 0;
  };

  explicit HostMemShadow(std::size_t mem_bytes)
      : mem_bytes_(std::max<std::size_t>(1, mem_bytes)), mem_(mem_bytes_, 0u) {}

  void clear() {
    std::fill(mem_.begin(), mem_.end(), 0u);
    preload_words_.clear();
  }

  std::size_t bytes() const { return mem_bytes_; }

  std::size_t mapGuestAddr(std::uint64_t addr) const {
    const std::uint64_t low_mask = static_cast<std::uint64_t>(mem_bytes_ - 1);
    std::size_t stack_window = mem_bytes_ / 2;
    if (stack_window == 0) {
      stack_window = 1;
    }
    const std::uint64_t stack_offset = static_cast<std::uint64_t>(mem_bytes_ - stack_window);
    const std::uint64_t stack_mask = static_cast<std::uint64_t>(stack_window - 1);
    constexpr std::uint64_t kStackBase = 0x0000000007FE0000ull;

    if (addr >= kStackBase) {
      const std::uint64_t stack_addr = ((addr - kStackBase) & stack_mask) + stack_offset;
      return static_cast<std::size_t>(stack_addr);
    }
    return static_cast<std::size_t>(addr & low_mask);
  }

  void storeGuestByte(std::uint64_t guest_addr, std::uint8_t value, bool track_preload = false) {
    const std::size_t eff = mapGuestAddr(guest_addr);
    if (eff < mem_.size()) {
      mem_[eff] = value;
    }
    if (!track_preload) {
      return;
    }
    const std::uint64_t word_addr = guest_addr & ~0x7ull;
    const unsigned byte_idx = static_cast<unsigned>(guest_addr & 0x7ull);
    WordPatch &patch = preload_words_[word_addr];
    patch.data &= ~(0xFFull << (8u * byte_idx));
    patch.data |= (static_cast<std::uint64_t>(value) << (8u * byte_idx));
    patch.strb = static_cast<std::uint8_t>(patch.strb | static_cast<std::uint8_t>(1u << byte_idx));
  }

  std::uint8_t loadGuestByte(std::uint64_t guest_addr) const {
    const std::size_t eff = mapGuestAddr(guest_addr);
    if (eff >= mem_.size()) {
      return 0;
    }
    return mem_[eff];
  }

  void storeGuestWord(std::uint64_t guest_addr, std::uint64_t data, std::uint8_t strb, bool track_preload = false) {
    for (unsigned i = 0; i < 8; ++i) {
      if (((strb >> i) & 0x1u) == 0) {
        continue;
      }
      const std::uint8_t b = static_cast<std::uint8_t>((data >> (8u * i)) & 0xFFull);
      storeGuestByte(guest_addr + static_cast<std::uint64_t>(i), b, track_preload);
    }
  }

  std::uint64_t loadGuestWord(std::uint64_t guest_addr, std::uint64_t size) const {
    const std::uint64_t n = (size == 0 || size > 8) ? 8 : size;
    std::uint64_t v = 0;
    for (std::uint64_t i = 0; i < n; ++i) {
      const std::uint64_t b = loadGuestByte(guest_addr + i);
      v |= (b << (8u * i));
    }
    return v;
  }

  Wire<512> buildIcacheLine(std::uint64_t line_addr) const {
    Wire<512> out(0);
    for (unsigned wi = 0; wi < 8; ++wi) {
      std::uint64_t w = 0;
      const std::uint64_t base = line_addr + static_cast<std::uint64_t>(wi) * 8ull;
      for (unsigned bi = 0; bi < 8; ++bi) {
        const std::uint64_t a = base + static_cast<std::uint64_t>(bi);
        w |= (static_cast<std::uint64_t>(loadGuestByte(a)) << (8u * bi));
      }
      out.setWord(wi, w);
    }
    return out;
  }

  bool loadMemh(const std::string &path, std::string *error = nullptr) {
    std::ifstream f(path);
    if (!f.is_open()) {
      if (error) {
        *error = "failed to open memh: " + path;
      }
      return false;
    }
    std::uint64_t addr = 0;
    std::string tok;
    while (f >> tok) {
      if (tok.empty()) {
        continue;
      }
      if (tok[0] == '@') {
        addr = std::stoull(tok.substr(1), nullptr, 16);
        continue;
      }
      const unsigned v = std::stoul(tok, nullptr, 16) & 0xFFu;
      storeGuestByte(addr, static_cast<std::uint8_t>(v), true);
      addr++;
    }
    return true;
  }

  std::vector<PreloadWord> takePreloadWords() {
    std::vector<PreloadWord> out;
    out.reserve(preload_words_.size());
    for (const auto &kv : preload_words_) {
      if (kv.second.strb == 0) {
        continue;
      }
      out.push_back(PreloadWord{
          kv.first,
          kv.second.data,
          kv.second.strb,
      });
    }
    preload_words_.clear();
    return out;
  }

private:
  struct WordPatch {
    std::uint64_t data = 0;
    std::uint8_t strb = 0;
  };

  std::size_t mem_bytes_ = 1;
  std::vector<std::uint8_t> mem_{};
  std::map<std::uint64_t, WordPatch> preload_words_{};
};

inline std::size_t resolveMemBytesFromEnv(std::size_t fallback = (1ull << 26)) {
  const char *cands[] = {
      std::getenv("PYC_PARAM_MEM_BYTES"),
      std::getenv("PYC_MEM_BYTES"),
      std::getenv("LINXCORE_MEM_BYTES"),
  };
  for (const char *raw : cands) {
    if (!raw || raw[0] == '\0') {
      continue;
    }
    try {
      const unsigned long long v = std::stoull(raw, nullptr, 0);
      if (v > 0) {
        return static_cast<std::size_t>(v);
      }
    } catch (...) {
      // Ignore malformed values and continue with fallback.
    }
  }
  return std::max<std::size_t>(1, fallback);
}

template <typename WriterFn>
inline void replayPreloadWords(HostMemShadow &mem, WriterFn &&writer) {
  const auto words = mem.takePreloadWords();
  for (const auto &w : words) {
    writer(w.guest_addr, w.data, w.strb);
  }
}

} // namespace linxcore::sim
