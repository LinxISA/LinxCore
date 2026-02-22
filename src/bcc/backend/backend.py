from __future__ import annotations

from pycircuit import Circuit, module

from .engine import BccOooExports, build_bcc_ooo as build_bcc_ooo_engine


@module(name="LinxCoreBackend")
def build_backend(m: Circuit, *, mem_bytes: int = (1 << 20)) -> None:
    # Thin compatibility shell: authoritative OoO logic lives in stage/component
    # modules and the backend engine implementation.
    build_bcc_ooo_engine(m, mem_bytes=mem_bytes)


def build_bcc_ooo(m: Circuit, *, mem_bytes: int, params=None) -> BccOooExports:
    return build_bcc_ooo_engine(m, mem_bytes=mem_bytes, params=params)
