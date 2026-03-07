from __future__ import annotations

from pycircuit import Circuit, const, meta


@const
def trace_row_spec(m: Circuit, *, aregs_w: int) -> object:
    """QEMU-commit compatible row shape (standalone oracle path).

    This struct is intentionally "fat": in shadow mode, OEX uses QEMU as the
    architectural oracle and carries these fields through to retire for
    verification, while the microarchitecture model focuses on timing/IPC.
    """
    _ = m
    rw = max(1, int(aregs_w))
    return (
        meta.struct("trace_row")
        .field("seq", width=64)
        .field("pc", width=64)
        .field("insn", width=64)
        .field("len", width=8)
        .field("src0_valid", width=1)
        .field("src0_reg", width=rw)
        .field("src0_data", width=64)
        .field("src1_valid", width=1)
        .field("src1_reg", width=rw)
        .field("src1_data", width=64)
        .field("dst_valid", width=1)
        .field("dst_reg", width=rw)
        .field("dst_data", width=64)
        .field("mem_valid", width=1)
        .field("mem_is_store", width=1)
        .field("mem_addr", width=64)
        .field("mem_wdata", width=64)
        .field("mem_rdata", width=64)
        .field("mem_size", width=8)
        .field("trap_valid", width=1)
        .field("trap_cause", width=32)
        .field("traparg0", width=64)
        .field("next_pc", width=64)
        .field("valid", width=1)
        .build()
    )


@const
def wb_event_spec(m: Circuit, *, rob_w: int, ptag_w: int) -> object:
    _ = m
    rw = max(1, int(rob_w))
    pw = max(1, int(ptag_w))
    return (
        meta.struct("wb_event")
        .field("rob", width=rw)
        .field("dst_valid", width=1)
        .field("dst_ptag", width=pw)
        .field("valid", width=1)
        .build()
    )


@const
def issue_uop_spec(m: Circuit, *, rob_w: int, ptag_w: int) -> object:
    _ = m
    rw = max(1, int(rob_w))
    pw = max(1, int(ptag_w))
    return (
        meta.struct("issue_uop")
        .field("rob", width=rw)
        .field("src0_valid", width=1)
        .field("src0_ptag", width=pw)
        .field("src1_valid", width=1)
        .field("src1_ptag", width=pw)
        .field("dst_valid", width=1)
        .field("dst_ptag", width=pw)
        .field("valid", width=1)
        .build()
    )

