#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${ROOT_DIR}/tools/lib/workspace_paths.sh"
PYC_ROOT="${LINXCORE_PYC_ROOT:-${PYC_ROOT:-$(linxcore_resolve_pyc_root "${ROOT_DIR}" || true)}}"
if [[ -z "${PYC_ROOT}" || ! -d "${PYC_ROOT}" ]]; then
  echo "error: pyCircuit root not found: ${PYC_ROOT:-<unset>}" >&2
  echo "hint: set LINXCORE_PYC_ROOT=... or PYC_ROOT=..." >&2
  exit 2
fi
RUNNER_SRC="${ROOT_DIR}/cosim/linxcore_lockstep_runner.cpp"
RUNNER_BIN="${ROOT_DIR}/cosim/linxcore_lockstep_runner"
GEN_CPP_DIR="${ROOT_DIR}/generated/cpp/linxcore_top"
GEN_HDR="${GEN_CPP_DIR}/linxcore_top.hpp"
CPP_MANIFEST="${GEN_CPP_DIR}/cpp_compile_manifest.json"
TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O1 -DNDEBUG -g0}"
if [[ -z "${CXX:-}" ]]; then
  if [[ -x "/opt/homebrew/bin/g++-15" ]]; then
    export CXX="/opt/homebrew/bin/g++-15"
  elif command -v clang++ >/dev/null 2>&1; then
    export CXX="$(command -v clang++)"
  fi
fi
PYC_API_INCLUDE="${PYC_ROOT}/include"
if [[ ! -f "${PYC_API_INCLUDE}/pyc/cpp/pyc_sim.hpp" ]]; then
  cand="$(find "${PYC_ROOT}" -path '*/include/pyc/cpp/pyc_sim.hpp' -print -quit 2>/dev/null || true)"
  if [[ -n "${cand}" ]]; then
    PYC_API_INCLUDE="${cand%/pyc/cpp/pyc_sim.hpp}"
  fi
fi
PYC_COMPAT_INCLUDE="${ROOT_DIR}/generated/include_compat"
mkdir -p "${PYC_COMPAT_INCLUDE}/pyc"
ln -sfn "${PYC_ROOT}/runtime/cpp" "${PYC_COMPAT_INCLUDE}/pyc/cpp"

TMP_DIR="$(mktemp -d -t linxcore_runner_test.XXXXXX)"
SOCK="${TMP_DIR}/runner.sock"
SNAP="${TMP_DIR}/snap.bin"
MSG_OK="${TMP_DIR}/msg_ok.json"
MSG_BAD="${TMP_DIR}/msg_bad.json"

BOOT_PC=0x10000
BOOT_SP=0x20000
# Hard-cut frontend: DUT requires a QEMU-driven instruction stream via IB.
# Use a stable in-repo artifact pair by default.
MEMH="${PYC_TEST_MEMH:-${ROOT_DIR}/tests/artifacts/linx-qemu-tests-arith.memh}"
QEMU_TRACE="${PYC_TEST_QEMU_TRACE:-${ROOT_DIR}/tests/artifacts/arith.commit.jsonl}"

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
if [[ ! -f "${QEMU_TRACE}" ]]; then
  echo "missing QEMU trace jsonl for IB mode: ${QEMU_TRACE}" >&2
  exit 2
fi

# Ensure generated C++ artifacts exist (runner links against them). In IB mode,
# the DUT does not make forward progress without a QEMU-driven instruction
# stream, so skip running the TB here.
LINXCORE_BUILD_PROFILE="${LINXCORE_BUILD_PROFILE:-dev-fast}" \
PYC_CPP_SHARD_MAX_AST_NODES="${PYC_CPP_SHARD_MAX_AST_NODES:-96}" \
PYC_CPP_SHARD_THRESHOLD_LINES="${PYC_CPP_SHARD_THRESHOLD_LINES:-8000}" \
PYC_CPP_SHARD_THRESHOLD_BYTES="${PYC_CPP_SHARD_THRESHOLD_BYTES:-393216}" \
PYC_BOOT_PC="${BOOT_PC}" \
PYC_BOOT_SP="${BOOT_SP}" \
PYC_IFU_STUB_QEMU=1 \
PYC_QEMU_TRACE="${QEMU_TRACE}" \
PYC_XCHECK_MODE="${PYC_XCHECK_MODE:-diagnostic}" \
PYC_MAX_COMMITS="${PYC_MAX_COMMITS:-64}" \
PYC_MAX_CYCLES="${PYC_MAX_CYCLES:-500000}" \
PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O1 -DNDEBUG -g0}" \
PYC_SKIP_RUN=1 \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${MEMH}" >/dev/null 2>&1

if [[ ! -f "${GEN_HDR}" ]]; then
  echo "missing generated header: ${GEN_HDR}" >&2
  exit 2
fi

if [[ ! -x "${RUNNER_BIN}" || "${RUNNER_SRC}" -nt "${RUNNER_BIN}" || "${GEN_HDR}" -nt "${RUNNER_BIN}" ]]; then
  if [[ ! -f "${CPP_MANIFEST}" ]]; then
    echo "missing generated manifest: ${CPP_MANIFEST}" >&2
    exit 2
  fi
  GEN_SRC_OBJ_STR="$(
    python3 - <<'PY' "${CPP_MANIFEST}" "${GEN_CPP_DIR}" "${GEN_CPP_DIR}/.obj"
import json
import pathlib
import sys

manifest = pathlib.Path(sys.argv[1])
base = pathlib.Path(sys.argv[2])
obj_base = pathlib.Path(sys.argv[3])
data = json.loads(manifest.read_text(encoding="utf-8"))
for entry in data.get("sources", []):
    rel = entry.get("path")
    if not rel:
        continue
    src = pathlib.Path(rel)
    if not src.is_absolute():
        src = base / src
    stem = src.as_posix().replace("/", "__")
    if stem.endswith(".cpp"):
        stem = stem[:-4]
    obj = obj_base / f"{stem}.o"
    print(f"{src}\t{obj}")
PY
  )"
  GEN_SRCS=()
  GEN_OBJS=()
  HAVE_ALL_OBJS=1
  while IFS=$'\t' read -r src obj; do
    [[ -z "${src}" ]] && continue
    GEN_SRCS+=("${src}")
    GEN_OBJS+=("${obj}")
    if [[ ! -f "${obj}" ]]; then
      HAVE_ALL_OBJS=0
    fi
  done <<< "${GEN_SRC_OBJ_STR}"

  if [[ "${HAVE_ALL_OBJS}" -eq 1 ]]; then
    "${CXX:-clang++}" -std=c++17 ${TB_CXXFLAGS} \
      -I "${PYC_COMPAT_INCLUDE}" \
      -I "${PYC_API_INCLUDE}" \
      -I "${PYC_ROOT}/runtime" \
      -I "${PYC_ROOT}/runtime/cpp" \
      -I "${GEN_CPP_DIR}" \
      -o "${RUNNER_BIN}" \
      "${RUNNER_SRC}" \
      "${GEN_OBJS[@]}"
  else
    "${CXX:-clang++}" -std=c++17 ${TB_CXXFLAGS} \
      -I "${PYC_COMPAT_INCLUDE}" \
      -I "${PYC_API_INCLUDE}" \
      -I "${PYC_ROOT}/runtime" \
      -I "${PYC_ROOT}/runtime/cpp" \
      -I "${GEN_CPP_DIR}" \
      -o "${RUNNER_BIN}" \
      "${RUNNER_SRC}" \
      "${GEN_SRCS[@]}"
  fi
fi

python3 - <<PY
import json
import struct
from pathlib import Path

memh_path = Path(r"${MEMH}")
snap_path = Path(r"${SNAP}")
qemu_trace_path = Path(r"${QEMU_TRACE}")
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

first = None
with qemu_trace_path.open("r", encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except Exception:
            # Some artifact traces may contain a truncated tail row; ignore parse failures.
            continue
        if int(row.get("pc", 0)) == 0:
            continue
        if int(row.get("len", 0)) not in (2, 4, 6):
            continue
        # Pick a non-metadata row: runner treats side-effect-free boundary markers
        # as metadata and will not compare them (breaking the mismatch test).
        if int(row.get("wb_valid", 0)) or int(row.get("mem_valid", 0)) or int(row.get("trap_valid", 0)):
            first = row
            break
if first is None:
    raise SystemExit("no usable non-metadata commit row in qemu trace")

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
    "traparg0": int(first.get("traparg0", 0)),
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
  "terminate_pc": int("${BOOT_PC}", 0),
  "snapshot_path": r"${SNAP}",
  "seq_base": 0,
}
commit = json.loads(open(r"${MSG_OK}", "r", encoding="utf-8").read())

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
  "terminate_pc": int("${BOOT_PC}", 0),
  "snapshot_path": r"${SNAP}",
  "seq_base": 0,
}
commit = json.loads(open(r"${MSG_OK}", "r", encoding="utf-8").read())

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
