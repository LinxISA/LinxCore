from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Widths:
    xlen: int = 64
    reg: int = 6
    op: int = 12
    lenb: int = 3


@dataclass(frozen=True)
class FrontendToDecode:
    valid: object
    pc: object
    window: object
    checkpoint_id: object


@dataclass(frozen=True)
class DecodeToRename:
    valid: object
    pc: object
    op: object
    insn_raw: object
    length: object


@dataclass(frozen=True)
class RenameToDispatch:
    valid: object
    pc: object
    op: object
    rob: object
    pdst: object


@dataclass(frozen=True)
class IssueReq:
    valid: object
    rob: object
    op: object
    src_ready: object


@dataclass(frozen=True)
class IssueResp:
    fire: object
    rob: object
    op: object
    result: object


@dataclass(frozen=True)
class LQEntry:
    valid: object
    rob: object
    addr_ready: object
    addr: object
    size: object


@dataclass(frozen=True)
class SQEntry:
    valid: object
    rob: object
    committed: object
    addr_ready: object
    data_ready: object
    addr: object
    data: object
    size: object


@dataclass(frozen=True)
class ROBEntry:
    valid: object
    done: object
    pc: object
    op: object
    insn_raw: object
    length: object
    trap_valid: object
    trap_cause: object
