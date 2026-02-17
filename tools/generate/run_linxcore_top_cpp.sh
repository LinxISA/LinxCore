#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
PYC_ROOT="/Users/zhoubot/pyCircuit"
GEN_CPP_DIR="${ROOT_DIR}/generated/cpp/linxcore_top"
HDR="${GEN_CPP_DIR}/linxcore_top.hpp"
CPP_MANIFEST="${PYC_MANIFEST_PATH:-${GEN_CPP_DIR}/cpp_compile_manifest.json}"
CALLFRAME_CFG="${GEN_CPP_DIR}/.callframe_size"
TB_SRC="${ROOT_DIR}/tb/tb_linxcore_top.cpp"
TB_TRACE_UTIL_SRC="${ROOT_DIR}/tb/tb_linxcore_trace_util.cpp"
TB_TRACE_UTIL_HDR="${ROOT_DIR}/tb/tb_linxcore_trace_util.hpp"
TB_EXE="${GEN_CPP_DIR}/tb_linxcore_top_cpp"
TB_OBJ_DIR="${GEN_CPP_DIR}/.tb_obj"
GEN_OBJ_DIR="${GEN_CPP_DIR}/.obj"
TB_MAIN_OBJ="${TB_OBJ_DIR}/tb_linxcore_top.o"
TB_TRACE_UTIL_OBJ="${TB_OBJ_DIR}/tb_linxcore_trace_util.o"
TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O3 -DNDEBUG}"
if [[ -z "${PYC_TB_CXXFLAGS:-}" && "${PYC_TB_FAST:-0}" == "1" ]]; then
  TB_CXXFLAGS="-O3 -DNDEBUG -march=native -flto"
fi
if [[ -z "${CXX:-}" ]]; then
  if command -v clang++ >/dev/null 2>&1; then
    export CXX="$(command -v clang++)"
  elif [[ -x "/opt/homebrew/bin/g++-15" ]]; then
    export CXX="/opt/homebrew/bin/g++-15"
  fi
fi
TB_CXX="${CXX:-clang++}"
TB_CXXFLAGS_CFG="${GEN_CPP_DIR}/.tb_cxxflags"
TB_MANIFEST_HASH_CFG="${GEN_CPP_DIR}/.tb_manifest_hash"
BUILD_LOCK_DIR="${GEN_CPP_DIR}/.tb_build_lock"
BUILD_LOCK_OWNER="${BUILD_LOCK_DIR}/owner.pid"
LOCK_STALE_SECS="${PYC_TB_LOCK_STALE_SECS:-900}"
TRACE_TXT_TOOL="${ROOT_DIR}/tools/trace/commit_jsonl_to_text.py"
CALLFRAME_SIZE_RAW="${PYC_CALLFRAME_SIZE:-0}"
CALLFRAME_SIZE="$(
python3 - <<'PY' "${CALLFRAME_SIZE_RAW}"
import sys
s = sys.argv[1]
try:
    v = int(s, 0)
except ValueError:
    v = 0
if v < 0 or (v & 0x7):
    v = 0
print(v & ((1 << 64) - 1))
PY
)"

PYC_API_INCLUDE="${PYC_ROOT}/include"
if [[ ! -f "${PYC_API_INCLUDE}/pyc/cpp/pyc_sim.hpp" ]]; then
  cand="$(find "${PYC_ROOT}" -path '*/include/pyc/cpp/pyc_sim.hpp' -print -quit 2>/dev/null || true)"
  if [[ -n "${cand}" ]]; then
    PYC_API_INCLUDE="${cand%/pyc/cpp/pyc_sim.hpp}"
  fi
fi
PYC_COMPAT_INCLUDE="${ROOT_DIR}/generated/include_compat"
mkdir -p "${PYC_COMPAT_INCLUDE}/pyc"
if [[ -L "${PYC_COMPAT_INCLUDE}/pyc/cpp" || -f "${PYC_COMPAT_INCLUDE}/pyc/cpp" ]]; then
  rm -f "${PYC_COMPAT_INCLUDE}/pyc/cpp"
elif [[ -d "${PYC_COMPAT_INCLUDE}/pyc/cpp" ]]; then
  rmdir "${PYC_COMPAT_INCLUDE}/pyc/cpp" 2>/dev/null || true
fi
ln -s "${PYC_ROOT}/runtime/cpp" "${PYC_COMPAT_INCLUDE}/pyc/cpp"

read_manifest_hash() {
  python3 - <<'PY' "${CPP_MANIFEST}"
import hashlib
import pathlib
import sys
p = pathlib.Path(sys.argv[1])
if not p.exists():
    print("")
else:
    print(hashlib.sha256(p.read_bytes()).hexdigest())
PY
}

manifest_sources() {
  python3 - <<'PY' "${CPP_MANIFEST}" "${GEN_CPP_DIR}" "${GEN_OBJ_DIR}"
import json
import pathlib
import sys

manifest = pathlib.Path(sys.argv[1])
base = pathlib.Path(sys.argv[2])
obj_base = pathlib.Path(sys.argv[3])
if not manifest.exists():
    sys.exit(0)
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
    obj = obj_base / f"{stem}.o"
    print(f"{src}\t{obj}")
PY
}

auto_build_jobs() {
  if [[ -n "${PYC_BUILD_JOBS:-}" ]]; then
    printf '%s\n' "${PYC_BUILD_JOBS}"
    return
  fi
  if command -v nproc >/dev/null 2>&1; then
    nproc
    return
  fi
  if command -v sysctl >/dev/null 2>&1; then
    sysctl -n hw.logicalcpu 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || true
    return
  fi
  printf '4\n'
}

need_regen=0
if [[ ! -f "${HDR}" ]]; then
  need_regen=1
elif [[ ! -f "${CPP_MANIFEST}" ]]; then
  need_regen=1
elif find "${ROOT_DIR}/src" -name '*.py' -newer "${HDR}" | grep -q .; then
  need_regen=1
elif [[ ! -f "${CALLFRAME_CFG}" ]]; then
  need_regen=1
elif [[ "$(cat "${CALLFRAME_CFG}")" != "${CALLFRAME_SIZE}" ]]; then
  need_regen=1
fi

if [[ "${need_regen}" -ne 0 ]]; then
  LINXCORE_CALLFRAME_SIZE="${CALLFRAME_SIZE}" \
    bash "${ROOT_DIR}/tools/generate/update_generated_linxcore.sh" >/dev/null
fi

CURRENT_MANIFEST_HASH="$(read_manifest_hash)"
GEN_SRC_OBJ_PAIRS=()
while IFS= read -r pair; do
  GEN_SRC_OBJ_PAIRS+=("${pair}")
done < <(manifest_sources)

need_build=0
if [[ ! -x "${TB_EXE}" ]]; then
  need_build=1
elif [[ ! -f "${TB_MAIN_OBJ}" || ! -f "${TB_TRACE_UTIL_OBJ}" ]]; then
  need_build=1
elif [[ ! -f "${CPP_MANIFEST}" ]]; then
  need_build=1
elif [[ -z "${CURRENT_MANIFEST_HASH}" ]]; then
  need_build=1
elif [[ ! -f "${TB_MANIFEST_HASH_CFG}" || "$(cat "${TB_MANIFEST_HASH_CFG}")" != "${CURRENT_MANIFEST_HASH}" ]]; then
  need_build=1
elif [[ ! -f "${TB_CXXFLAGS_CFG}" || "$(cat "${TB_CXXFLAGS_CFG}")" != "${TB_CXXFLAGS}" ]]; then
  need_build=1
elif [[ "${TB_SRC}" -nt "${TB_MAIN_OBJ}" || "${HDR}" -nt "${TB_MAIN_OBJ}" || "${TB_TRACE_UTIL_HDR}" -nt "${TB_MAIN_OBJ}" ]]; then
  need_build=1
elif [[ "${TB_TRACE_UTIL_SRC}" -nt "${TB_TRACE_UTIL_OBJ}" || "${TB_TRACE_UTIL_HDR}" -nt "${TB_TRACE_UTIL_OBJ}" ]]; then
  need_build=1
elif [[ "${TB_MAIN_OBJ}" -nt "${TB_EXE}" || "${TB_TRACE_UTIL_OBJ}" -nt "${TB_EXE}" ]]; then
  need_build=1
fi

if [[ "${need_build}" -eq 0 ]]; then
  for pair in "${GEN_SRC_OBJ_PAIRS[@]}"; do
    gen_src="${pair%%$'\t'*}"
    gen_obj="${pair#*$'\t'}"
    if [[ ! -f "${gen_src}" || ! -f "${gen_obj}" ]]; then
      need_build=1
      break
    fi
    if [[ "${gen_src}" -nt "${gen_obj}" || "${gen_obj}" -nt "${TB_EXE}" ]]; then
      need_build=1
      break
    fi
    if find "${GEN_CPP_DIR}" -maxdepth 1 -name '*.hpp' -newer "${gen_obj}" | grep -q .; then
      need_build=1
      break
    fi
  done
fi

if [[ "${need_build}" -ne 0 ]]; then
  lock_held=0
  acquire_build_lock() {
    for _ in $(seq 1 1800); do
      if mkdir "${BUILD_LOCK_DIR}" 2>/dev/null; then
        printf '%s\n' "$$" > "${BUILD_LOCK_OWNER}" 2>/dev/null || true
        lock_held=1
        return 0
      fi

      if [[ -d "${BUILD_LOCK_DIR}" ]]; then
        stale=0
        owner_pid=""
        if [[ -f "${BUILD_LOCK_OWNER}" ]]; then
          owner_pid="$(tr -cd '0-9' < "${BUILD_LOCK_OWNER}" || true)"
        fi
        if [[ -n "${owner_pid}" ]]; then
          if ! kill -0 "${owner_pid}" 2>/dev/null; then
            stale=1
          fi
        else
          lock_age="$(
          python3 - <<'PY' "${BUILD_LOCK_DIR}"
import os
import sys
import time
path = sys.argv[1]
try:
    print(int(max(0, time.time() - os.path.getmtime(path))))
except Exception:
    print(0)
PY
          )"
          if [[ "${lock_age}" =~ ^[0-9]+$ ]] && [[ "${lock_age}" -ge "${LOCK_STALE_SECS}" ]]; then
            stale=1
          fi
        fi
        if [[ "${stale}" -eq 1 ]]; then
          rm -f "${BUILD_LOCK_OWNER}" >/dev/null 2>&1 || true
          rmdir "${BUILD_LOCK_DIR}" >/dev/null 2>&1 || true
        fi
      fi
      sleep 0.1
    done
    return 1
  }

  cleanup_lock() {
    if [[ "${lock_held}" -eq 1 ]]; then
      rm -f "${BUILD_LOCK_OWNER}" >/dev/null 2>&1 || true
      rmdir "${BUILD_LOCK_DIR}" >/dev/null 2>&1 || true
      lock_held=0
    fi
  }

  if ! acquire_build_lock; then
    echo "error: timeout waiting for TB build lock: ${BUILD_LOCK_DIR}" >&2
    exit 3
  fi
  trap cleanup_lock EXIT INT TERM

  # Re-check after acquiring lock in case another process already built.
  need_build_locked=0
  if [[ ! -x "${TB_EXE}" ]]; then
    need_build_locked=1
  elif [[ ! -f "${TB_MAIN_OBJ}" || ! -f "${TB_TRACE_UTIL_OBJ}" ]]; then
    need_build_locked=1
  elif [[ ! -f "${CPP_MANIFEST}" ]]; then
    need_build_locked=1
  elif [[ -z "${CURRENT_MANIFEST_HASH}" ]]; then
    need_build_locked=1
  elif [[ ! -f "${TB_MANIFEST_HASH_CFG}" || "$(cat "${TB_MANIFEST_HASH_CFG}")" != "${CURRENT_MANIFEST_HASH}" ]]; then
    need_build_locked=1
  elif [[ ! -f "${TB_CXXFLAGS_CFG}" || "$(cat "${TB_CXXFLAGS_CFG}")" != "${TB_CXXFLAGS}" ]]; then
    need_build_locked=1
  elif [[ "${TB_SRC}" -nt "${TB_MAIN_OBJ}" || "${HDR}" -nt "${TB_MAIN_OBJ}" || "${TB_TRACE_UTIL_HDR}" -nt "${TB_MAIN_OBJ}" ]]; then
    need_build_locked=1
  elif [[ "${TB_TRACE_UTIL_SRC}" -nt "${TB_TRACE_UTIL_OBJ}" || "${TB_TRACE_UTIL_HDR}" -nt "${TB_TRACE_UTIL_OBJ}" ]]; then
    need_build_locked=1
  elif [[ "${TB_MAIN_OBJ}" -nt "${TB_EXE}" || "${TB_TRACE_UTIL_OBJ}" -nt "${TB_EXE}" ]]; then
    need_build_locked=1
  fi

  if [[ "${need_build_locked}" -eq 0 ]]; then
    for pair in "${GEN_SRC_OBJ_PAIRS[@]}"; do
      gen_src="${pair%%$'\t'*}"
      gen_obj="${pair#*$'\t'}"
      if [[ ! -f "${gen_src}" || ! -f "${gen_obj}" ]]; then
        need_build_locked=1
        break
      fi
      if [[ "${gen_src}" -nt "${gen_obj}" || "${gen_obj}" -nt "${TB_EXE}" ]]; then
        need_build_locked=1
        break
      fi
      if find "${GEN_CPP_DIR}" -maxdepth 1 -name '*.hpp' -newer "${gen_obj}" | grep -q .; then
        need_build_locked=1
        break
      fi
    done
  fi

  if [[ "${need_build_locked}" -ne 0 ]]; then
    mkdir -p "${TB_OBJ_DIR}" "${GEN_OBJ_DIR}"
    build_jobs="$(auto_build_jobs)"
    if [[ -z "${build_jobs}" || ! "${build_jobs}" =~ ^[0-9]+$ || "${build_jobs}" -lt 1 ]]; then
      build_jobs=4
    fi
    common_flags=(
      -std=c++17
      ${TB_CXXFLAGS}
      -I "${PYC_COMPAT_INCLUDE}"
      -I "${PYC_API_INCLUDE}"
      -I "${PYC_ROOT}/runtime"
      -I "${PYC_ROOT}/runtime/cpp"
      -I "${GEN_CPP_DIR}"
    )
    if [[ "$(uname -s)" == "Darwin" ]]; then
      tb_sysroot="${PYC_TB_SYSROOT:-}"
      if [[ -z "${tb_sysroot}" ]]; then
        tb_sysroot="$(xcrun --show-sdk-path 2>/dev/null || true)"
      fi
      if [[ -n "${tb_sysroot}" ]]; then
        common_flags+=(
          -isysroot "${tb_sysroot}"
        )
        if [[ "$(basename "${TB_CXX}")" == *clang* ]]; then
          common_flags+=(-isystem "${tb_sysroot}/usr/include/c++/v1")
        fi
      fi
    fi

    compile_pairs=()
    if [[ ! -f "${TB_TRACE_UTIL_OBJ}" || "${TB_TRACE_UTIL_SRC}" -nt "${TB_TRACE_UTIL_OBJ}" || "${TB_TRACE_UTIL_HDR}" -nt "${TB_TRACE_UTIL_OBJ}" ]]; then
      compile_pairs+=("${TB_TRACE_UTIL_SRC}"$'\t'"${TB_TRACE_UTIL_OBJ}")
    fi
    if [[ ! -f "${TB_MAIN_OBJ}" || "${TB_SRC}" -nt "${TB_MAIN_OBJ}" || "${HDR}" -nt "${TB_MAIN_OBJ}" || "${TB_TRACE_UTIL_HDR}" -nt "${TB_MAIN_OBJ}" ]]; then
      compile_pairs+=("${TB_SRC}"$'\t'"${TB_MAIN_OBJ}")
    fi

    manifest_hash_cfg=""
    if [[ -f "${TB_MANIFEST_HASH_CFG}" ]]; then
      manifest_hash_cfg="$(cat "${TB_MANIFEST_HASH_CFG}")"
    fi
    cxxflags_cfg=""
    if [[ -f "${TB_CXXFLAGS_CFG}" ]]; then
      cxxflags_cfg="$(cat "${TB_CXXFLAGS_CFG}")"
    fi
    stale_gen_pairs=()
    for pair in "${GEN_SRC_OBJ_PAIRS[@]}"; do
      gen_src="${pair%%$'\t'*}"
      gen_obj="${pair#*$'\t'}"
      mkdir -p "$(dirname "${gen_obj}")"
      if [[ ! -f "${gen_obj}" ]]; then
        stale_gen_pairs+=("${pair}")
        continue
      fi
      if [[ "${gen_src}" -nt "${gen_obj}" ]]; then
        stale_gen_pairs+=("${pair}")
        continue
      fi
      if find "${GEN_CPP_DIR}" -maxdepth 1 -name '*.hpp' -newer "${gen_obj}" | grep -q .; then
        stale_gen_pairs+=("${pair}")
        continue
      fi
      if [[ "${manifest_hash_cfg}" != "${CURRENT_MANIFEST_HASH}" ]]; then
        stale_gen_pairs+=("${pair}")
        continue
      fi
      if [[ "${cxxflags_cfg}" != "${TB_CXXFLAGS}" ]]; then
        stale_gen_pairs+=("${pair}")
        continue
      fi
    done

    if [[ "${#stale_gen_pairs[@]}" -gt 0 ]]; then
      for pair in "${stale_gen_pairs[@]}"; do
        compile_pairs+=("${pair}")
      done
    fi

    if [[ "${#compile_pairs[@]}" -gt 0 ]]; then
      compile_fail=0
      if [[ "${build_jobs}" -le 1 ]]; then
        for pair in "${compile_pairs[@]}"; do
          src="${pair%%$'\t'*}"
          obj="${pair#*$'\t'}"
          if ! "${TB_CXX}" "${common_flags[@]}" -c "${src}" -o "${obj}"; then
            compile_fail=1
            break
          fi
        done
      else
        pids=()
        for pair in "${compile_pairs[@]}"; do
          src="${pair%%$'\t'*}"
          obj="${pair#*$'\t'}"
          "${TB_CXX}" "${common_flags[@]}" -c "${src}" -o "${obj}" &
          pids+=("$!")
          if [[ "${#pids[@]}" -ge "${build_jobs}" ]]; then
            if ! wait "${pids[0]}"; then
              compile_fail=1
            fi
            if [[ "${#pids[@]}" -gt 1 ]]; then
              pids=("${pids[@]:1}")
            else
              pids=()
            fi
          fi
        done
        if [[ "${#pids[@]}" -gt 0 ]]; then
          for pid in "${pids[@]}"; do
            if ! wait "${pid}"; then
              compile_fail=1
            fi
          done
        fi
      fi
      if [[ "${compile_fail}" -ne 0 ]]; then
        echo "error: parallel compile failed" >&2
        exit 2
      fi
    fi

    gen_objs=()
    for pair in "${GEN_SRC_OBJ_PAIRS[@]}"; do
      gen_obj="${pair#*$'\t'}"
      gen_objs+=("${gen_obj}")
    done

    link_flags=(-std=c++17 ${TB_CXXFLAGS})
    if [[ "$(uname -s)" == "Darwin" ]]; then
      tb_sysroot="${PYC_TB_SYSROOT:-}"
      if [[ -z "${tb_sysroot}" ]]; then
        tb_sysroot="$(xcrun --show-sdk-path 2>/dev/null || true)"
      fi
      if [[ -n "${tb_sysroot}" ]]; then
        link_flags+=(-isysroot "${tb_sysroot}")
      fi
    fi
    tmp_exe="${TB_EXE}.tmp.$$"
    "${TB_CXX}" "${link_flags[@]}" -o "${tmp_exe}" "${TB_MAIN_OBJ}" "${TB_TRACE_UTIL_OBJ}" "${gen_objs[@]}"
    mv -f "${tmp_exe}" "${TB_EXE}"
    printf '%s\n' "${TB_CXXFLAGS}" > "${TB_CXXFLAGS_CFG}"
    printf '%s\n' "${CURRENT_MANIFEST_HASH}" > "${TB_MANIFEST_HASH_CFG}"
  fi

  cleanup_lock
  trap - EXIT INT TERM
fi

run_rc=0
if [[ "${PYC_SKIP_RUN:-0}" == "1" ]]; then
  exit 0
fi
if [[ $# -gt 0 ]]; then
  "${TB_EXE}" "$@" || run_rc=$?
else
  "${TB_EXE}" || run_rc=$?
fi

commit_trace_path="${PYC_COMMIT_TRACE:-}"
if [[ -n "${commit_trace_path}" && -f "${commit_trace_path}" && -s "${commit_trace_path}" ]]; then
  trace_txt_path="${PYC_COMMIT_TRACE_TEXT:-}"
  if [[ -z "${trace_txt_path}" ]]; then
    if [[ "${commit_trace_path}" == *.jsonl ]]; then
      trace_txt_path="${commit_trace_path%.jsonl}.txt"
    else
      trace_txt_path="${commit_trace_path}.txt"
    fi
  fi
  if [[ -f "${TRACE_TXT_TOOL}" ]]; then
    txt_cmd=(python3 "${TRACE_TXT_TOOL}" --input "${commit_trace_path}" --output "${trace_txt_path}")
    if [[ -n "${PYC_OBJDUMP_ELF:-}" && -f "${PYC_OBJDUMP_ELF}" ]]; then
      txt_cmd+=(--objdump-elf "${PYC_OBJDUMP_ELF}")
    fi
    if [[ -n "${PYC_OBJDUMP_TOOL:-}" ]]; then
      txt_cmd+=(--objdump-tool "${PYC_OBJDUMP_TOOL}")
    fi
    if ! "${txt_cmd[@]}"; then
      if [[ "${PYC_TRACE_TEXT_REQUIRED:-0}" == "1" ]]; then
        echo "error: failed to generate text trace: ${trace_txt_path}" >&2
        exit 11
      fi
      echo "warn: failed to generate text trace: ${trace_txt_path}" >&2
    fi
  fi
fi

exit "${run_rc}"
