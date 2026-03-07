from __future__ import annotations

from dataclasses import dataclass

from pycircuit import Circuit


@dataclass(frozen=True)
class CoreCtrlBankView:
    fields: tuple[str, ...]


CORE_CTRL_FIELDS: tuple[str, ...] = (
    "halted",
    "cycles",
    "pc",
    "fpc",
    "br_kind",
    "br_epoch",
    "active_block_uid",
    "active_block_bid",
    "lsid_alloc_ctr",
    "lsid_issue_ptr",
    "lsid_complete_ptr",
)


def build_core_ctrl_bank(_m: Circuit) -> CoreCtrlBankView:
    # This bank contract is consumed by module-level composition; register
    # allocation remains in the canonical backend trace-export module.
    return CoreCtrlBankView(fields=CORE_CTRL_FIELDS)
