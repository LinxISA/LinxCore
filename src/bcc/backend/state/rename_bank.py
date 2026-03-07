from __future__ import annotations

from dataclasses import dataclass

from pycircuit import Circuit


@dataclass(frozen=True)
class RenameBankView:
    fields: tuple[str, ...]


RENAME_FIELDS: tuple[str, ...] = (
    "smap",
    "cmap",
    "free_mask",
    "ready_mask",
    "ckpt_valid",
    "ckpt_smap",
    "ckpt_free_mask",
    "ckpt_ready_mask",
)


def build_rename_bank(_m: Circuit) -> RenameBankView:
    # Structured bank metadata for new module compositions.
    # Keep this helper explicit so callers can branch on required fields.
    required = []
    for name in RENAME_FIELDS:
        required.append(name)
    return RenameBankView(fields=tuple(required))
