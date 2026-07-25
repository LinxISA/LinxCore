#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path


THIS = Path(__file__).resolve()
LINXCORE_ROOT = THIS.parents[1]
SUPERPROJECT_ROOT = THIS.parents[3]
PYC_ROOT = Path(os.environ.get("PYC_ROOT", SUPERPROJECT_ROOT / "tools" / "pyCircuit"))
PYC_FRONTEND = PYC_ROOT / "compiler" / "frontend"

for path in (PYC_FRONTEND, LINXCORE_ROOT / "src"):
    text = str(path)
    if text not in sys.path:
        sys.path.insert(0, text)

from pycircuit import Circuit, Tb, module, testbench  # noqa: E402

from common.exec_uop import ExecOut, exec_uop, exec_uop_comb  # noqa: E402
from common.isa import OP_LBU  # noqa: E402
from common.util import Consts, make_consts  # noqa: E402


MASK64 = (1 << 64) - 1
OP_LBU_ID = 346
BASE = 0x0000001000001000
SRCR = 0x1234567880000001
PC = 0x4000
IMM = 0
SRCP = 0


@dataclass(frozen=True)
class Case:
    srcr_type: int
    shamt: int
    expected_addr: int


CASES = [
    Case(srcr_type=0, shamt=0, expected_addr=0x0000000F80001001),
    Case(srcr_type=1, shamt=0, expected_addr=0x0000001080001001),
    Case(srcr_type=2, shamt=0, expected_addr=0xEDCBA99780000FFF),
    Case(srcr_type=3, shamt=0, expected_addr=0x1234568880001001),
    Case(srcr_type=0, shamt=1, expected_addr=0x0000000F00001002),
    Case(srcr_type=1, shamt=1, expected_addr=0x0000001100001002),
    Case(srcr_type=2, shamt=1, expected_addr=0xDB97531F00000FFE),
    Case(srcr_type=3, shamt=1, expected_addr=0x2468AD0100001002),
]


@module(name="LinxCoreExecUopSrcRTypeProbe")
def build(m: Circuit) -> None:
    m.input("clk", width=1)
    m.input("rst", width=1)
    op = m.input("op", width=12)
    pc = m.input("pc", width=64)
    imm = m.input("imm", width=64)
    srcl_val = m.input("srcl_val", width=64)
    srcr_val = m.input("srcr_val", width=64)
    srcr_type = m.input("srcr_type", width=2)
    shamt = m.input("shamt", width=6)
    srcp_val = m.input("srcp_val", width=64)

    consts = make_consts(m)
    comb = exec_uop_comb(
        m,
        op=op,
        pc=pc,
        imm=imm,
        srcl_val=srcl_val,
        srcr_val=srcr_val,
        srcr_type=srcr_type,
        shamt=shamt,
        srcp_val=srcp_val,
        consts=consts,
    )
    m.output("comb_alu", comb.alu)
    m.output("comb_is_load", comb.is_load)
    m.output("comb_is_store", comb.is_store)
    m.output("comb_size", comb.size)
    m.output("comb_addr", comb.addr)
    m.output("comb_wdata", comb.wdata)


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=0, cycles_deasserted=0)
    t.timeout(64)

    for cycle, case in enumerate(CASES):
        t.drive("op", OP_LBU_ID, at=cycle)
        t.drive("pc", PC, at=cycle)
        t.drive("imm", IMM, at=cycle)
        t.drive("srcl_val", BASE, at=cycle)
        t.drive("srcr_val", SRCR, at=cycle)
        t.drive("srcr_type", case.srcr_type, at=cycle)
        t.drive("shamt", case.shamt, at=cycle)
        t.drive("srcp_val", SRCP, at=cycle)
        t.print(
            f"COMB cycle={cycle} srcr_type={case.srcr_type} shamt={case.shamt} "
            "alu={} is_load={} is_store={} size={} addr={} wdata={}",
            at=cycle,
            ports=(
                "comb_alu",
                "comb_is_load",
                "comb_is_store",
                "comb_size",
                "comb_addr",
                "comb_wdata",
            ),
        )

    t.finish(at=len(CASES) + 1)


class Value:
    def __init__(self, value: int, *, width: int = 64, signed: bool = False) -> None:
        self.width = int(width)
        self.signed = bool(signed)
        self.value = int(value) & ((1 << self.width) - 1)

    def out(self) -> "Value":
        return self

    def _mask(self) -> int:
        return (1 << self.width) - 1

    def _signed_value(self) -> int:
        sign = 1 << (self.width - 1)
        return self.value - (1 << self.width) if self.value & sign else self.value

    def _coerce(self, other: object, *, width: int | None = None) -> "Value":
        if isinstance(other, Value):
            return other
        if isinstance(other, int):
            return Value(other, width=self.width if width is None else width)
        raise TypeError(f"unsupported value: {type(other).__name__}")

    def _binary(self, other: object, op) -> "Value":
        rhs = self._coerce(other)
        width = max(self.width, rhs.width)
        return Value(op(self.value, rhs.value), width=width, signed=self.signed or rhs.signed)

    def __bool__(self) -> bool:
        return bool(self.value)

    def __int__(self) -> int:
        return self.value

    def __eq__(self, other: object) -> "Value":  # type: ignore[override]
        rhs = self._coerce(other)
        return Value(1 if self.value == rhs.value else 0, width=1)

    def __ne__(self, other: object) -> "Value":  # type: ignore[override]
        rhs = self._coerce(other)
        return Value(1 if self.value != rhs.value else 0, width=1)

    def __add__(self, other: object) -> "Value":
        return self._binary(other, lambda a, b: a + b)

    def __radd__(self, other: object) -> "Value":
        return self.__add__(other)

    def __sub__(self, other: object) -> "Value":
        return self._binary(other, lambda a, b: a - b)

    def __rsub__(self, other: object) -> "Value":
        lhs = self._coerce(other)
        return lhs.__sub__(self)

    def __mul__(self, other: object) -> "Value":
        return self._binary(other, lambda a, b: a * b)

    def __floordiv__(self, other: object) -> "Value":
        rhs = self._coerce(other)
        if self.signed or rhs.signed:
            return Value(self._signed_value() // rhs._signed_value(), width=max(self.width, rhs.width), signed=True)
        return self._binary(rhs, lambda a, b: a // b)

    def __mod__(self, other: object) -> "Value":
        rhs = self._coerce(other)
        if self.signed or rhs.signed:
            return Value(self._signed_value() % rhs._signed_value(), width=max(self.width, rhs.width), signed=True)
        return self._binary(rhs, lambda a, b: a % b)

    def __and__(self, other: object) -> "Value":
        return self._binary(other, lambda a, b: a & b)

    def __rand__(self, other: object) -> "Value":
        return self.__and__(other)

    def __or__(self, other: object) -> "Value":
        return self._binary(other, lambda a, b: a | b)

    def __ror__(self, other: object) -> "Value":
        return self.__or__(other)

    def __xor__(self, other: object) -> "Value":
        return self._binary(other, lambda a, b: a ^ b)

    def __invert__(self) -> "Value":
        return Value(~self.value, width=self.width, signed=self.signed)

    def _trunc(self, *, width: int) -> "Value":
        return Value(self.value, width=width, signed=self.signed)

    def _zext(self, *, width: int) -> "Value":
        return Value(self.value, width=width, signed=False)

    def _sext(self, *, width: int) -> "Value":
        sign = 1 << (self.width - 1)
        raw = self.value | (~self._mask()) if self.value & sign else self.value
        return Value(raw, width=width, signed=True)

    def as_signed(self) -> "Value":
        return Value(self.value, width=self.width, signed=True)

    def shl(self, *, amount: int | "Value") -> "Value":
        amt = int(amount) if isinstance(amount, Value) else int(amount)
        return Value(self.value << amt, width=self.width, signed=self.signed)

    def lshr(self, *, amount: int | "Value") -> "Value":
        amt = int(amount) if isinstance(amount, Value) else int(amount)
        return Value(self.value >> amt, width=self.width, signed=False)

    def ashr(self, *, amount: int | "Value") -> "Value":
        amt = int(amount) if isinstance(amount, Value) else int(amount)
        return Value(self._signed_value() >> amt, width=self.width, signed=True)

    def _select_internal(self, a: object, b: object) -> "Value":
        chosen = a if bool(self) else b
        return self._coerce(chosen)

    def ult(self, other: object) -> "Value":
        rhs = self._coerce(other)
        return Value(1 if self.value < rhs.value else 0, width=1)

    def uge(self, other: object) -> "Value":
        rhs = self._coerce(other)
        return Value(1 if self.value >= rhs.value else 0, width=1)

    def ule(self, other: object) -> "Value":
        rhs = self._coerce(other)
        return Value(1 if self.value <= rhs.value else 0, width=1)

    def slt(self, other: object) -> "Value":
        rhs = self._coerce(other)
        return Value(1 if self._signed_value() < rhs._signed_value() else 0, width=1)

    def __getitem__(self, idx: int | slice) -> "Value":
        if isinstance(idx, slice):
            lsb = 0 if idx.start is None else int(idx.start)
            stop = self.width if idx.stop is None else int(idx.stop)
            width = stop - lsb
            return Value((self.value >> lsb) & ((1 << width) - 1), width=width)
        return Value((self.value >> int(idx)) & 1, width=1)


class ValueCircuit:
    def const(self, value: int, *, width: int) -> Value:
        return Value(value, width=width)

    def scope(self, _name: str):
        class Scope:
            def __enter__(self) -> None:
                return None

            def __exit__(self, *args: object) -> None:
                return None

        return Scope()


def _value_consts(m: ValueCircuit) -> Consts:
    _ = m
    return Consts(
        one1=Value(1, width=1),
        zero1=Value(0, width=1),
        zero3=Value(0, width=3),
        zero4=Value(0, width=4),
        zero6=Value(0, width=6),
        zero8=Value(0, width=8),
        zero32=Value(0, width=32),
        zero64=Value(0, width=64),
        one64=Value(1, width=64),
    )


def _find_python() -> str:
    for candidate in (
        os.environ.get("PYC_PYTHON"),
        os.environ.get("PYC_PYTHON_BIN"),
        "/opt/homebrew/bin/python3",
        "python3.14",
        "python3.13",
        "python3.12",
        "python3.11",
        "python3.10",
        "python3",
    ):
        if not candidate:
            continue
        resolved = shutil.which(candidate) if not candidate.startswith("/") else candidate
        if not resolved:
            continue
        proc = subprocess.run(
            [resolved, "-c", "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if proc.returncode == 0:
            return resolved
    raise RuntimeError("Python 3.10 or newer is required")


def _find_pycc(env: dict[str, str]) -> str:
    if env.get("PYCC"):
        return env["PYCC"]
    lib = PYC_ROOT / "flows" / "scripts" / "lib.sh"
    proc = subprocess.run(
        [
            "bash",
            "-lc",
            f"source {str(lib)!r}; pyc_find_pycc >/dev/null; printf '%s\\n' \"$PYCC\"",
        ],
        cwd=str(LINXCORE_ROOT),
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "failed to locate pycc")
    return proc.stdout.strip()


def _run_checked(cmd: list[str], *, cwd: Path, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    print("+ " + " ".join(cmd), flush=True)
    return subprocess.run(cmd, cwd=str(cwd), env=env, text=True, check=True)


def _run_capture(cmd: list[str], *, cwd: Path, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    print("+ " + " ".join(cmd), flush=True)
    proc = subprocess.run(
        cmd,
        cwd=str(cwd),
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=True,
    )
    print(proc.stdout, end="")
    return proc


COMB_RE = re.compile(
    r"^\[tb\] cyc=(?P<cycle>\d+) COMB cycle=\d+ srcr_type=(?P<srcr_type>\d+) "
    r"shamt=(?P<shamt>\d+) .* comb_alu=(?P<alu>0x[0-9a-fA-F]+|\d+) "
    r"comb_is_load=(?P<is_load>0x[0-9a-fA-F]+|\d+) "
    r"comb_is_store=(?P<is_store>0x[0-9a-fA-F]+|\d+) "
    r"comb_size=(?P<size>0x[0-9a-fA-F]+|\d+) "
    r"comb_addr=(?P<addr>0x[0-9a-fA-F]+|\d+) "
    r"comb_wdata=(?P<wdata>0x[0-9a-fA-F]+|\d+)$"
)


def _check_outputs(surface: str, case: Case, out: dict[str, int], failures: list[str]) -> None:
    expected = {
        "alu": 0,
        "is_load": 1,
        "is_store": 0,
        "size": 1,
        "addr": case.expected_addr,
        "wdata": 0,
    }
    for field, expected_value in expected.items():
        actual = out[field] & MASK64 if field in {"alu", "addr", "wdata"} else out[field]
        if actual != expected_value:
            failures.append(
                f"{surface} srcr_type={case.srcr_type} shamt={case.shamt} "
                f"{field}: got 0x{actual:x}, expected 0x{expected_value:x}"
            )


def _parse_comb_stdout(text: str) -> list[dict[str, int]]:
    rows: list[dict[str, int]] = []
    for line in text.splitlines():
        match = COMB_RE.match(line.strip())
        if not match:
            continue
        rows.append({key: int(value, 0) for key, value in match.groupdict().items()})
    return rows


def _decorated_outputs(case: Case) -> dict[str, int]:
    m = ValueCircuit()
    out: ExecOut = exec_uop(
        m,
        op=Value(OP_LBU_ID, width=12),
        pc=Value(PC, width=64),
        imm=Value(IMM, width=64),
        srcl_val=Value(BASE, width=64),
        srcr_val=Value(SRCR, width=64),
        srcr_type=Value(case.srcr_type, width=2),
        shamt=Value(case.shamt, width=6),
        srcp_val=Value(SRCP, width=64),
        consts=_value_consts(m),
    )
    return {
        "alu": int(out.alu),
        "is_load": int(out.is_load),
        "is_store": int(out.is_store),
        "size": int(out.size),
        "addr": int(out.addr),
        "wdata": int(out.wdata),
    }


def main() -> int:
    if OP_LBU != OP_LBU_ID:
        print(f"error: imported OP_LBU is {OP_LBU}, expected {OP_LBU_ID}", file=sys.stderr)
        return 2
    if not PYC_FRONTEND.is_dir():
        print(f"error: pyCircuit frontend not found: {PYC_FRONTEND}", file=sys.stderr)
        return 2

    env = os.environ.copy()
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["PYTHONPATH"] = os.pathsep.join(
        [str(PYC_FRONTEND), str(LINXCORE_ROOT / "src"), env.get("PYTHONPATH", "")]
    )
    env["PYCC"] = _find_pycc(env)

    with tempfile.TemporaryDirectory(prefix="linxcore-exec-uop-srcrtype-") as td:
        out_dir = Path(td) / "pyc"
        python_bin = _find_python()
        _run_checked(
            [
                python_bin,
                "-m",
                "pycircuit.cli",
                "build",
                str(THIS),
                "--out-dir",
                str(out_dir),
                "--target",
                "cpp",
                "--jobs",
                os.environ.get("PYC_SIM_JOBS", "4"),
                "--logic-depth",
                os.environ.get("PYC_SIM_LOGIC_DEPTH", "128"),
                "--profile",
                "dev",
            ],
            cwd=LINXCORE_ROOT,
            env=env,
        )
        manifest = json.loads((out_dir / "project_manifest.json").read_text(encoding="utf-8"))
        cpp_exe = Path(manifest["cpp_executable"])
        comb_proc = _run_capture([str(cpp_exe)], cwd=out_dir, env=env)

    failures: list[str] = []
    comb_rows = _parse_comb_stdout(comb_proc.stdout)
    if len(comb_rows) != len(CASES):
        print(f"error: expected {len(CASES)} COMB rows, saw {len(comb_rows)}", file=sys.stderr)
        return 2

    for cycle, (case, row) in enumerate(zip(CASES, comb_rows, strict=True)):
        if row["cycle"] != cycle or row["srcr_type"] != case.srcr_type or row["shamt"] != case.shamt:
            failures.append(f"exec_uop_comb row {cycle} metadata mismatch: {row}")
            continue
        _check_outputs("exec_uop_comb", case, row, failures)
        _check_outputs("decorated exec_uop", case, _decorated_outputs(case), failures)

    if failures:
        print("FAIL: OP_LBU SrcRType address/metadata mismatches:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print(
        "ok: exec_uop SrcRType OP_LBU probe passed for "
        "exec_uop_comb and decorated exec_uop surfaces"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
