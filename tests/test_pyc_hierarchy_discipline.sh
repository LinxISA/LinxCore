#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${ROOT_DIR}/tools/lib/workspace_paths.sh"

PYC_ROOT="${LINXCORE_PYC_ROOT:-${PYC_ROOT:-$(linxcore_resolve_pyc_root "${ROOT_DIR}" || true)}}"
if [[ ! -d "${PYC_ROOT}" ]]; then
  echo "error: pyCircuit root not found: ${PYC_ROOT}" >&2
  exit 1
fi

PYC_FRONTEND_DIR="${PYC_ROOT}/compiler/frontend"
if [[ ! -d "${PYC_FRONTEND_DIR}/pycircuit" ]]; then
  echo "error: pyCircuit frontend not found: ${PYC_FRONTEND_DIR}" >&2
  exit 1
fi

PYCC_BIN="${PYCC:-}"
if [[ -z "${PYCC_BIN}" ]]; then
  for cand in \
    "${PYC_ROOT}/build/bin/pycc" \
    "${PYC_ROOT}/build-top/bin/pycc" \
    "${PYC_ROOT}/compiler/mlir/build/bin/pycc" \
    "${PYC_ROOT}/compiler/mlir/build2/bin/pycc"
  do
    if [[ -x "${cand}" ]]; then
      PYCC_BIN="${cand}"
      break
    fi
  done
fi
if [[ -z "${PYCC_BIN}" ]]; then
  PYCC_BIN="$(command -v pycc 2>/dev/null || true)"
fi
if [[ -z "${PYCC_BIN}" || ! -x "${PYCC_BIN}" ]]; then
  echo "error: pycc not found; build ${PYC_ROOT}/build/bin/pycc first" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d -t pyc_hierarchy.XXXXXX)"
trap 'rm -rf "${TMP_DIR}"' EXIT

python3 - <<'PY' "${TMP_DIR}"
from pathlib import Path
import sys
import textwrap

root = Path(sys.argv[1])

root.joinpath("good_family.py").write_text(
    textwrap.dedent(
        """
        from pycircuit import Circuit, const, module, spec, u, wiring

        @const
        def lane_in(m: Circuit):
            _ = m
            return spec.struct("lane_in").field("x", width=8).build()

        @module
        def lane(m: Circuit, *, gain: int = 1):
            ins = m.inputs(lane_in(m), prefix="in_")
            m.output("y", (ins["x"].read() + u(8, int(gain)))[0:8])

        @module
        def build(m: Circuit):
            family = spec.module_family("lane_family", module=lane)
            vec = family.dict({"a": {"gain": 1}, "b": {"gain": 2}}, name="lane_vec")
            per = {}
            for key in vec.keys():
                per[key] = {"in": wiring.bind(lane_in(m), {"x": u(8, 3)})}
            lanes = m.array(vec, name="lane", bind={}, per=per)
            m.output("y", lanes.output("a").read() + lanes.output("b").read())
        """
    ).strip()
    + "\n",
    encoding="utf-8",
)

root.joinpath("bad_instance.py").write_text(
    textwrap.dedent(
        """
        from pycircuit import Circuit, function, module

        @module
        def leaf(m: Circuit):
            a = m.input("a", width=1)
            m.output("y", a)

        @function
        def bad_helper(m: Circuit, a):
            inst = m.instance_auto(leaf, name="leaf_inst", a=a)
            return inst

        @module
        def build(m: Circuit):
            a = m.input("a", width=1)
            y = bad_helper(m, a=a)
            m.output("y", y)
        """
    ).strip()
    + "\n",
    encoding="utf-8",
)

bad_inline_lines = [
    "from pycircuit import Circuit, function, module",
    "",
    "@function",
    "def bad_inline(m: Circuit, a):",
    "    x = a",
]
for i in range(700):
    bad_inline_lines.append(f"    x = x | a  # {i}")
bad_inline_lines += [
    "",
    "@module",
    "def build(m: Circuit):",
    "    a = m.input(\"a\", width=1)",
    "    y = bad_inline(m, a)",
    "    m.output(\"y\", y)",
    "",
]
root.joinpath("bad_inline.py").write_text("\n".join(bad_inline_lines), encoding="utf-8")

root.joinpath("bad_module_repeat.py").write_text(
    textwrap.dedent(
        """
        from pycircuit import Circuit, module

        @module
        def leaf(m: Circuit):
            a = m.input("a", width=1)
            m.output("y", a)

        @module
        def build(m: Circuit):
            a = m.input("a", width=1)
            y = a
            for i in range(40):
                y = m.instance_auto(leaf, name=f"leaf_{i}", a=y)
            m.output("y", y)
        """
    ).strip()
    + "\n",
    encoding="utf-8",
)
PY

emit_pyc() {
  local src="$1"
  local out="$2"
  PYTHONPATH="${PYC_FRONTEND_DIR}" python3 -m pycircuit.cli emit "${src}" -o "${out}"
}

emit_pyc "${TMP_DIR}/good_family.py" "${TMP_DIR}/good_family.pyc"
if ! rg -q 'pyc\.struct\.metrics|pyc\.struct\.collections' "${TMP_DIR}/good_family.pyc"; then
  echo "missing structural attrs in emitted .pyc" >&2
  exit 1
fi
"${PYCC_BIN}" "${TMP_DIR}/good_family.pyc" --emit=none >/dev/null

set +e
bad_instance_out="$(
  emit_pyc "${TMP_DIR}/bad_instance.py" "${TMP_DIR}/bad_instance.pyc" 2>&1
)"
bad_instance_rc=$?
set -e
if [[ "${bad_instance_rc}" -eq 0 ]]; then
  echo "bad_instance unexpectedly passed" >&2
  exit 1
fi
if ! grep -q "must not instantiate modules/collections" <<<"${bad_instance_out}"; then
  echo "bad_instance failed for the wrong reason" >&2
  echo "${bad_instance_out}" >&2
  exit 1
fi

set +e
bad_inline_out="$(
  emit_pyc "${TMP_DIR}/bad_inline.py" "${TMP_DIR}/bad_inline.pyc" 2>&1
)"
bad_inline_rc=$?
set -e
if [[ "${bad_inline_rc}" -eq 0 ]]; then
  echo "bad_inline unexpectedly passed" >&2
  exit 1
fi
if ! grep -q "exceeds inline complexity cap" <<<"${bad_inline_out}"; then
  echo "bad_inline failed for the wrong reason" >&2
  echo "${bad_inline_out}" >&2
  exit 1
fi

emit_pyc "${TMP_DIR}/bad_module_repeat.py" "${TMP_DIR}/bad_module_repeat.pyc"
set +e
bad_module_out="$(
  "${PYCC_BIN}" "${TMP_DIR}/bad_module_repeat.pyc" --emit=none 2>&1
)"
bad_module_rc=$?
set -e
if [[ "${bad_module_rc}" -eq 0 ]]; then
  echo "bad_module_repeat unexpectedly passed" >&2
  exit 1
fi
if ! grep -q "PYC990" <<<"${bad_module_out}"; then
  echo "bad_module_repeat failed for the wrong reason" >&2
  echo "${bad_module_out}" >&2
  exit 1
fi

echo "pyc hierarchy discipline gate passed"
