from __future__ import annotations

from dataclasses import dataclass

from pycircuit import Circuit


@dataclass(frozen=True)
class IqBankView:
    fields: tuple[str, ...]


IQ_FIELDS: tuple[str, ...] = (
    "valid",
    "rob",
    "op",
    "pc",
    "imm",
    "srcl",
    "srcr",
    "srcr_type",
    "srcp",
    "pdst",
    "has_dst",
)


def build_iq_bank(_m: Circuit) -> IqBankView:
    # Structured bank metadata for module-level wiring.
    return IqBankView(fields=IQ_FIELDS)
