#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER_SRC="${ROOT_DIR}/cosim/linxcore_lockstep_runner.cpp"
RUNNER_BIN="${ROOT_DIR}/cosim/linxcore_lockstep_runner"
GEN_CPP_DIR="${ROOT_DIR}/generated/cpp/linxcore_top"
GEN_HDR="${GEN_CPP_DIR}/LinxcoreTop.hpp"
CPP_MANIFEST="${GEN_CPP_DIR}/cpp_compile_manifest.json"
GEN_OBJ_DIR="${GEN_CPP_DIR}/.obj"
TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O2 -DNDEBUG}"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
find_pyc_root() {
  if [[ -n "${PYC_ROOT:-}" && -d "${PYC_ROOT}" ]]; then
    echo "${PYC_ROOT}"
    return 0
  fi
  if [[ -d "${LINX_ROOT}/tools/pyCircuit" ]]; then
    echo "${LINX_ROOT}/tools/pyCircuit"
    return 0
  fi
  return 1
}
PYC_ROOT_DIR="$(find_pyc_root)" || {
  echo "error: cannot locate pyCircuit; set PYC_ROOT=..." >&2
  exit 2
}

TMP_DIR="$(mktemp -d -t linxcore_runner_test.XXXXXX)"
SOCK="${TMP_DIR}/runner.sock"
SNAP="${TMP_DIR}/snap.bin"
TRACE="${TMP_DIR}/dut_trace.jsonl"
MSG_OK="${TMP_DIR}/msg_ok.json"
MSG_BAD="${TMP_DIR}/msg_bad.json"

BOOT_SP=0x20000
MEMH="${PYC_TEST_MEMH:-${PYC_ROOT_DIR}/designs/examples/linx_cpu/programs/test_or.memh}"

cleanup() {
  if [[ -n "${RUNNER_PID:-}" ]]; then
    kill "${RUNNER_PID}" >/dev/null 2>&1 || true
    wait "${RUNNER_PID}" >/dev/null 2>&1 || true
  fi
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT INT TERM

wait_for_socket() {
  local sock_path="$1"
  for _ in $(seq 1 500); do
    if [[ -S "${sock_path}" ]]; then
      return 0
    fi
    sleep 0.01
  done
  echo "timeout waiting for socket: ${sock_path}" >&2
  return 1
}

if [[ ! -f "${MEMH}" ]]; then
  build_out="$("${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh")"
  memh2="$(printf "%s\n" "${build_out}" | sed -n '2p')"
  if [[ -n "${memh2}" && -f "${memh2}" ]]; then
    MEMH="${memh2}"
  fi
fi
if [[ ! -f "${MEMH}" ]]; then
  echo "missing benchmark memh after build: ${MEMH}" >&2
  exit 2
fi

BOOT_PC="$(
python3 - <<'PY' "${MEMH}"
from pathlib import Path
import sys

p = Path(sys.argv[1])
addr = None
for tok in p.read_text().split():
    if tok.startswith("@"):
        addr = int(tok[1:], 16)
        break
if addr is None:
    raise SystemExit(f"no @addr found in memh: {p}")
print(f"0x{addr:x}")
PY
)"

if [[ ! -f "${GEN_HDR}" ]]; then
  LINXCORE_SKIP_VERILOG=1 bash "${ROOT_DIR}/tools/generate/update_generated_linxcore.sh" >/dev/null
fi

if [[ ! -x "${RUNNER_BIN}" || "${RUNNER_SRC}" -nt "${RUNNER_BIN}" || "${GEN_HDR}" -nt "${RUNNER_BIN}" ]]; then
  if [[ ! -f "${CPP_MANIFEST}" ]]; then
    echo "missing generated manifest: ${CPP_MANIFEST}" >&2
    exit 2
  fi

  # Avoid monolithic `clang++ ... <all generated .cpp>` builds. They bypass our
  # per-kind optimization flags (tick/xfer) and can take minutes on huge TUs.
  # Instead: build the generated model objects via Ninja, then link them.
  PYC_MODEL_CXXFLAGS="${TB_CXXFLAGS}" \
    bash "${ROOT_DIR}/tools/generate/build_linxcore_model_objects.sh" >/dev/null

  gen_objects=()
  while IFS= read -r obj_path; do
    [[ -z "${obj_path}" ]] && continue
    gen_objects+=("${obj_path}")
  done < <(
    python3 - <<'PY' "${CPP_MANIFEST}" "${GEN_CPP_DIR}" "${GEN_OBJ_DIR}"
import json
import pathlib
import sys

manifest = pathlib.Path(sys.argv[1])
base = pathlib.Path(sys.argv[2])
obj_base = pathlib.Path(sys.argv[3])
data = json.loads(manifest.read_text(encoding="utf-8"))
for entry in data.get("sources", []):
    rel = entry.get("path", "")
    if not rel:
        continue
    src = pathlib.Path(rel)
    if not src.is_absolute():
        src = base / src
    stem = src.as_posix().replace("/", "__")
    if stem.endswith(".cpp"):
        stem = stem[:-4]
    print(obj_base / f"{stem}.o")
PY
  )
  if [[ "${#gen_objects[@]}" -eq 0 ]]; then
    echo "error: missing generated model objects (manifest: ${CPP_MANIFEST})" >&2
    exit 2
  fi
  for obj in "${gen_objects[@]}"; do
    if [[ ! -f "${obj}" ]]; then
      echo "error: missing generated object: ${obj}" >&2
      exit 2
    fi
  done

  "${CXX:-clang++}" -std=c++17 ${TB_CXXFLAGS} \
    -I "${PYC_ROOT_DIR}/runtime" \
    -I "${GEN_CPP_DIR}" \
    -o "${RUNNER_BIN}" \
    "${RUNNER_SRC}" \
    "${gen_objects[@]}"
fi

# Produce one known-good commit record from DUT TB.
PYC_BOOT_PC="${BOOT_PC}" \
PYC_BOOT_SP="${BOOT_SP}" \
PYC_MAX_CYCLES=30000 \
  PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O2 -DNDEBUG}" \
PYC_COMMIT_TRACE="${TRACE}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${MEMH}" >/dev/null 2>&1 || true

if [[ ! -s "${TRACE}" ]]; then
  echo "failed to produce DUT trace for protocol test" >&2
  exit 2
fi

python3 - <<PY
import json
import struct
from pathlib import Path

memh_path = Path(r"${MEMH}")
snap_path = Path(r"${SNAP}")
trace_path = Path(r"${TRACE}")
msg_ok = Path(r"${MSG_OK}")
msg_bad = Path(r"${MSG_BAD}")

# Build sparse snapshot with one range from 0..max_addr+1.
mem = {}
addr = 0
for tok in memh_path.read_text().split():
    if tok.startswith('@'):
        addr = int(tok[1:], 16)
        continue
    mem[addr] = int(tok, 16) & 0xFF
    addr += 1

if not mem:
    raise SystemExit("empty memh")

max_addr = max(mem.keys())
size = max_addr + 1
payload = bytearray(size)
for a, b in mem.items():
    payload[a] = b

hdr = b"LXCOSIM1" + struct.pack("<II", 1, 1)
entry_off = len(hdr) + 24
entry = struct.pack("<QQQ", 0, size, entry_off)
snap_path.write_bytes(hdr + entry + bytes(payload))

rows = [json.loads(line) for line in trace_path.read_text().splitlines() if line.strip()]
if not rows:
    raise SystemExit("empty dut trace")

def mask_insn(raw: int, length: int) -> int:
    if length == 2:
        return raw & 0xFFFF
    if length == 4:
        return raw & 0xFFFF_FFFF
    if length == 6:
        return raw & 0xFFFF_FFFF_FFFF
    return raw

def is_bstart16(hw: int) -> bool:
    if (hw & 0xC7FF) == 0x0000 or (hw & 0xC7FF) == 0x0080:
        brtype = (hw >> 11) & 0x7
        if brtype != 0:
            return True
    if (hw & 0x000F) == 0x0002 or (hw & 0x000F) == 0x0004:
        return True
    return hw in (0x0840, 0x08C0, 0x48C0, 0x88C0, 0xC8C0)

def is_bstart32(insn: int) -> bool:
    return (insn & 0x0000_7FFF) in (0x0000_1001, 0x0000_2001, 0x0000_3001, 0x0000_4001, 0x0000_5001, 0x0000_6001, 0x0000_7001)

def is_bstart48(raw: int) -> bool:
    prefix = raw & 0xFFFF
    main32 = (raw >> 16) & 0xFFFF_FFFF
    if (prefix & 0xF) != 0xE:
        return False
    return ((main32 & 0xFF) == 0x01) and (((main32 >> 12) & 0x7) != 0)

def is_macro_marker32(insn: int) -> bool:
    return (insn & 0x0000_707F) in (0x0000_0041, 0x0000_1041, 0x0000_2041, 0x0000_3041)

def is_metadata_commit(r: dict) -> bool:
    pc = int(r.get("pc", 0))
    insn = int(r.get("insn", 0))
    length = int(r.get("len", 0))
    wb = int(r.get("wb_valid", 0))
    mem_v = int(r.get("mem_valid", 0))
    trap_v = int(r.get("trap_valid", 0))
    if length == 0 and insn == 0 and pc == 0:
        return True
    insn_m = mask_insn(insn, length)
    is_bstart = (
        (length == 2 and is_bstart16(insn_m & 0xFFFF)) or
        (length == 4 and is_bstart32(insn_m & 0xFFFF_FFFF)) or
        (length == 6 and is_bstart48(insn_m))
    )
    is_macro_marker = (length == 4) and is_macro_marker32(insn_m & 0xFFFF_FFFF)
    if is_bstart and wb == 0 and mem_v == 0 and trap_v == 0:
        return True
    if is_macro_marker and wb == 0 and mem_v == 0 and trap_v == 0:
        return True
    return False

first = None
for row in rows:
    if not is_metadata_commit(row):
        first = row
        break
if first is None:
    raise SystemExit("no non-metadata commit in dut trace")

commit = {
    "type": "commit",
    "seq": 0,
    "pc": int(first.get("pc", 0)),
    "insn": int(first.get("insn", 0)),
    "len": int(first.get("len", 0)),
    "wb_valid": int(first.get("wb_valid", 0)),
    "wb_rd": int(first.get("wb_rd", 0)),
    "wb_data": int(first.get("wb_data", 0)),
    "mem_valid": int(first.get("mem_valid", 0)),
    "mem_is_store": int(first.get("mem_is_store", 0)),
    "mem_addr": int(first.get("mem_addr", 0)),
    "mem_wdata": int(first.get("mem_wdata", 0)),
    "mem_rdata": int(first.get("mem_rdata", 0)),
    "mem_size": int(first.get("mem_size", 0)),
    "trap_valid": int(first.get("trap_valid", 0)),
    "trap_cause": int(first.get("trap_cause", 0)),
    "traparg0": 0,
    "next_pc": int(first.get("next_pc", 0)),
}

bad = dict(commit)
bad["wb_data"] ^= 1

msg_ok.write_text(json.dumps(commit))
msg_bad.write_text(json.dumps(bad))
PY

# Positive case: exact match should ack OK and exit 0 on end(reason=terminate_pc).
"${RUNNER_BIN}" --socket "${SOCK}" --boot-sp "${BOOT_SP}" --max-dut-cycles 20000000 >/dev/null 2>&1 &
RUNNER_PID=$!
wait_for_socket "${SOCK}"

python3 - <<PY
import json
import socket

sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
sock.connect(r"${SOCK}")

start = {
  "type":"start",
  "boot_pc": int("${BOOT_PC}", 0),
  "trigger_pc": int("${BOOT_PC}", 0),
  "snapshot_path": r"${SNAP}",
  "seq_base": 0,
}
commit = json.loads(open(r"${MSG_OK}", "r", encoding="utf-8").read())
start["terminate_pc"] = int(commit.get("pc", 0))

sock.sendall((json.dumps(start) + "\n").encode())
sock.sendall((json.dumps(commit) + "\n").encode())

ack = b""
while not ack.endswith(b"\n"):
    chunk = sock.recv(1)
    if not chunk:
        raise RuntimeError("socket closed before ack")
    ack += chunk

if b'"status":"ok"' not in ack:
    raise RuntimeError(f"unexpected ack: {ack!r}")

sock.sendall(b'{"type":"end","reason":"terminate_pc"}\n')
sock.close()
PY

wait "${RUNNER_PID}"
RUNNER_PID=""

# Negative case: force mismatch on seq0, expect mismatch ack and non-zero exit.
"${RUNNER_BIN}" --socket "${SOCK}" --boot-sp "${BOOT_SP}" --max-dut-cycles 20000000 --force-mismatch >/dev/null 2>&1 &
RUNNER_PID=$!
wait_for_socket "${SOCK}"

python3 - <<PY
import json
import socket

sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
sock.connect(r"${SOCK}")

start = {
  "type":"start",
  "boot_pc": int("${BOOT_PC}", 0),
  "trigger_pc": int("${BOOT_PC}", 0),
  "snapshot_path": r"${SNAP}",
  "seq_base": 0,
}
commit = json.loads(open(r"${MSG_OK}", "r", encoding="utf-8").read())
start["terminate_pc"] = int(commit.get("pc", 0))

sock.sendall((json.dumps(start) + "\n").encode())
sock.sendall((json.dumps(commit) + "\n").encode())

ack = b""
while not ack.endswith(b"\n"):
    chunk = sock.recv(1)
    if not chunk:
        break
    ack += chunk

if b'"status":"mismatch"' not in ack:
    raise RuntimeError(f"expected mismatch ack, got: {ack!r}")

sock.close()
PY

if wait "${RUNNER_PID}"; then
  echo "expected mismatch run to fail" >&2
  exit 1
fi
RUNNER_PID=""

echo "runner protocol tests: ok"
