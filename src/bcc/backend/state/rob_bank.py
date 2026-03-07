from __future__ import annotations

from dataclasses import dataclass

from pycircuit import Circuit


@dataclass(frozen=True)
class RobBankView:
    fields: tuple[str, ...]


ROB_FIELDS: tuple[str, ...] = (
    "head",
    "tail",
    "count",
    "valid",
    "done",
    "pc",
    "op",
    "len_bytes",
    "pdst",
    "value",
    "block_uid",
    "block_bid",
    "load_store_id",
)


def build_rob_bank(_m: Circuit) -> RobBankView:
    # Structured bank metadata for module-level wiring.
    return RobBankView(fields=ROB_FIELDS)
