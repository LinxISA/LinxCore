from __future__ import annotations

import hashlib

# Allowed stage postfix tokens for Janus/BCC naming lint.
STAGE_TOKENS = {
    "f0",
    "f1",
    "f2",
    "f3",
    "ib",
    "f4",
    "d1",
    "d2",
    "d3",
    "iq",
    "s1",
    "s2",
    "p1",
    "i1",
    "i2",
    "e1",
    "e2",
    "e3",
    "e4",
    "w1",
    "w2",
    "rob",
    "pcb",
    "fls",
    "renu",
    "bisq",
    "bctrl",
    "brenu",
    "brob",
    "liq",
    "lhq",
    "stq",
    "scb",
    "mdb",
    "l1d",
    "noc",
    "tmu",
    "tma",
    "cube",
    "vec",
    "tau",
    "top",
}

INTERFACE_PREFIXES = {
    "f0_to_f1_stage",
    "f1_to_f2_stage",
    "f2_to_f3_stage",
    "f3_to_ib_stage",
    "ib_to_f4_stage",
    "f3_to_f4_stage",
    "f3_to_pcb_stage",
    "f4_to_d1_stage",
    "d1_to_d2_stage",
    "d2_to_d3_stage",
    "d3_to_s1_stage",
    "s1_to_s2_stage",
    "s2_to_iex_stage",
    "iex_to_rob_stage",
    "rob_to_flush_ctrl_stage",
    "bctrl_to_pe_stage",
    "pe_to_brob_stage",
    "lsu_to_rob_stage",
    "pcb_to_bru_stage",
    "cmd_to_bisq_stage",
    "bisq_to_bctrl_stage",
    "bctrl_to_tma_stage",
    "bctrl_to_cube_stage",
    "bctrl_to_vec_stage",
    "bctrl_to_tau_stage",
    "tmu_to_pe_stage",
    "pe_to_tmu_stage",
    "brob_to_rob_stage",
}

# Fixed LinxTrace stage-id order used by DFX pipeview probes.
LINXTRACE_STAGE_ID_ORDER = [
    "F0",
    "F1",
    "F2",
    "F3",
    "F4",
    "D1",
    "D2",
    "D3",
    "IQ",
    "S1",
    "S2",
    "P1",
    "I1",
    "I2",
    "E1",
    "E2",
    "E3",
    "E4",
    "W1",
    "W2",
    "LIQ",
    "LHQ",
    "STQ",
    "SCB",
    "MDB",
    "L1D",
    "BISQ",
    "BCTRL",
    "TMU",
    "TMA",
    "CUBE",
    "VEC",
    "TAU",
    "BROB",
    "ROB",
    "CMT",
    "FLS",
    "XCHK",
    "IB",
]

# LinxTrace pipeline schema contract.
# Any change to LINXTRACE_STAGE_ID_ORDER changes this ID and requires
# trace-builder/linter/LinxCoreSight refresh in the same change.
LINXTRACE_PIPELINE_SCHEMA_VERSION = 1
_linxtrace_stage_csv = ",".join(LINXTRACE_STAGE_ID_ORDER)
_linxtrace_stage_hash = hashlib.sha1(_linxtrace_stage_csv.encode("utf-8")).hexdigest().upper()[:12]
LINXTRACE_PIPELINE_SCHEMA_ID = f"LC-TRACE{LINXTRACE_PIPELINE_SCHEMA_VERSION}-{_linxtrace_stage_hash}"
LINXTRACE_STAGE_ORDER_CSV = _linxtrace_stage_csv

# Compatibility aliases during migration.
KONATA_STAGE_ID_ORDER = LINXTRACE_STAGE_ID_ORDER
KONATA_PIPELINE_SCHEMA_ID = LINXTRACE_PIPELINE_SCHEMA_ID
KONATA_STAGE_ORDER_CSV = LINXTRACE_STAGE_ORDER_CSV
