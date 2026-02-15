from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Tuple


@dataclass(frozen=True)
class InterfaceField:
    name: str
    width: int


InterfaceSpec = Dict[str, Tuple[InterfaceField, ...]]


INTERFACE_SPEC: InterfaceSpec = {
    "f0_to_f1_stage": (
        InterfaceField("pc", 64),
        InterfaceField("valid", 1),
        InterfaceField("redirect", 1),
    ),
    "f1_to_f2_stage": (
        InterfaceField("pc", 64),
        InterfaceField("window", 64),
        InterfaceField("valid", 1),
    ),
    "f2_to_f3_stage": (
        InterfaceField("pc", 64),
        InterfaceField("window", 64),
        InterfaceField("next_pc", 64),
        InterfaceField("valid", 1),
    ),
    "f3_to_f4_stage": (
        InterfaceField("pc", 64),
        InterfaceField("window", 64),
        InterfaceField("valid", 1),
        InterfaceField("checkpoint_id", 6),
    ),
    "f3_to_pcb_stage": (
        InterfaceField("bstart_valid", 1),
        InterfaceField("bstart_pc", 64),
        InterfaceField("bstart_kind", 3),
        InterfaceField("bstart_target", 64),
        InterfaceField("pred_take", 1),
    ),
    "f4_to_d1_stage": (
        InterfaceField("pc", 64),
        InterfaceField("window", 64),
        InterfaceField("valid", 1),
        InterfaceField("checkpoint_id", 6),
    ),
    "d1_to_d2_stage": (
        InterfaceField("op", 12),
        InterfaceField("pc", 64),
        InterfaceField("valid", 1),
    ),
    "d2_to_d3_stage": (
        InterfaceField("op", 12),
        InterfaceField("pc", 64),
        InterfaceField("valid", 1),
        InterfaceField("regdst", 6),
    ),
    "d3_to_s1_stage": (
        InterfaceField("op", 12),
        InterfaceField("pc", 64),
        InterfaceField("valid", 1),
        InterfaceField("rob", 6),
    ),
    "s1_to_s2_stage": (
        InterfaceField("op", 12),
        InterfaceField("pc", 64),
        InterfaceField("valid", 1),
        InterfaceField("iq", 5),
    ),
    "s2_to_iex_stage": (
        InterfaceField("op", 12),
        InterfaceField("pc", 64),
        InterfaceField("valid", 1),
        InterfaceField("rob", 6),
    ),
    "iex_to_rob_stage": (
        InterfaceField("wb_valid", 1),
        InterfaceField("wb_rob", 6),
        InterfaceField("wb_value", 64),
        InterfaceField("store_valid", 1),
        InterfaceField("load_valid", 1),
    ),
    "rob_to_flush_ctrl_stage": (
        InterfaceField("redirect_valid", 1),
        InterfaceField("redirect_pc", 64),
        InterfaceField("checkpoint_id", 6),
    ),
    "bctrl_to_pe_stage": (
        InterfaceField("cmd_valid", 1),
        InterfaceField("cmd_kind", 3),
        InterfaceField("cmd_tag", 8),
        InterfaceField("cmd_tile", 6),
        InterfaceField("cmd_payload", 64),
        InterfaceField("cmd_src_rob", 6),
        InterfaceField("cmd_epoch", 8),
    ),
    "pe_to_brob_stage": (
        InterfaceField("rsp_valid", 1),
        InterfaceField("rsp_tag", 8),
        InterfaceField("rsp_status", 4),
        InterfaceField("rsp_data0", 64),
        InterfaceField("rsp_data1", 64),
        InterfaceField("rsp_trap_valid", 1),
        InterfaceField("rsp_trap_cause", 32),
    ),
    "lsu_to_rob_stage": (
        InterfaceField("load_valid", 1),
        InterfaceField("load_rob", 6),
        InterfaceField("load_addr", 64),
        InterfaceField("load_data", 64),
        InterfaceField("store_valid", 1),
        InterfaceField("store_rob", 6),
    ),
    "pcb_to_bru_stage": (
        InterfaceField("lookup_hit", 1),
        InterfaceField("lookup_kind", 3),
        InterfaceField("lookup_target", 64),
        InterfaceField("lookup_pred_take", 1),
    ),
}


def iter_signal_names(prefix: str) -> Tuple[str, ...]:
    fields = INTERFACE_SPEC[prefix]
    return tuple(f"{prefix}_{field.name}" for field in fields)
