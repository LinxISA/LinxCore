#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>

namespace linxcore::tb {

std::string disasmInsn(const std::string &tool, const std::string &spec, std::uint64_t raw, std::uint8_t len);

std::unordered_map<std::uint64_t, std::string> loadOpNameMap(const std::string &isaPyPath);

std::unordered_map<std::uint64_t, std::string> loadObjdumpPcMap(const std::string &objdumpTool,
                                                                 const std::string &elfPath);

} // namespace linxcore::tb

