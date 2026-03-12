from __future__ import annotations

from pycircuit import Circuit, module

from .engine import BccOooExports, build_bcc_ooo as build_bcc_ooo_engine
from .modules.trace_export_core import build_trace_export


@module(name="LinxCoreBackend")
def build_backend(m: Circuit, *, mem_bytes: int = (1 << 20)) -> None:
    # Canonical backend composition lives in the trace-export path, which owns
    # the packed-bank interfaces used by the current pyc4 hierarchy.
    build_trace_export(m, mem_bytes=mem_bytes)


def build_bcc_ooo(m: Circuit, *, mem_bytes: int, params=None) -> BccOooExports:
    return build_bcc_ooo_engine(m, mem_bytes=mem_bytes, params=params)
