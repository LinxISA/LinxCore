from __future__ import annotations

from pycircuit import Circuit, const, ct, meta


@meta.valueclass
class OexProfileCfg:
    name: str
    # Architectural register file (ATAG) shape.
    aregs: int
    ib_depth: int
    rob_depth: int
    issueq_depth: int
    pcbuf_depth: int
    ptag_rf_entries: int
    tu_rf_entries: int
    issue_width: int
    dispatch_w: int
    commit_w: int
    alu_lanes: int
    bru_lanes: int
    agu_lanes: int
    std_lanes: int
    fsu_lanes: int
    cmd_lanes: int
    tpl_lanes: int
    # Modeled fixed latencies (cycles) per pipeline group.
    lat_alu: int
    lat_bru: int
    lat_agu: int
    lat_std: int
    lat_cmd: int
    lat_fsu: int
    lat_tpl: int
    debug_trace: int


@meta.valueclass
class OexDerivedCfg:
    aregs_w: int
    ib_w: int
    rob_w: int
    issueq_w: int
    pcbuf_w: int
    ptag_w: int
    tu_w: int
    issue_w: int
    lane_issue_cap: int
    dispatch_w: int
    commit_w: int


@const
def _pow2_check(m: Circuit, value: int, label: str) -> int:
    _ = m
    v = int(value)
    if v <= 0 or (v & (v - 1)) != 0:
        raise ValueError(f"{label} must be power-of-two, got {v}")
    return v


@const
def derive_cfg(m: Circuit, cfg: OexProfileCfg) -> OexDerivedCfg:
    aregs = _pow2_check(m, int(cfg.aregs), "aregs")
    ib = _pow2_check(m, int(cfg.ib_depth), "ib_depth")
    rob = _pow2_check(m, int(cfg.rob_depth), "rob_depth")
    iq = _pow2_check(m, int(cfg.issueq_depth), "issueq_depth")
    pcb = _pow2_check(m, int(cfg.pcbuf_depth), "pcbuf_depth")
    ptag = _pow2_check(m, int(cfg.ptag_rf_entries), "ptag_rf_entries")
    tu = _pow2_check(m, int(cfg.tu_rf_entries), "tu_rf_entries")

    issue_w = int(cfg.issue_width)
    if issue_w <= 0:
        raise ValueError("issue_width must be > 0")

    dispatch_w = int(cfg.dispatch_w)
    if dispatch_w <= 0:
        raise ValueError("dispatch_w must be > 0")
    commit_w = int(cfg.commit_w)
    if commit_w <= 0:
        raise ValueError("commit_w must be > 0")

    lane_issue_cap = (
        int(cfg.alu_lanes)
        + int(cfg.bru_lanes)
        + int(cfg.agu_lanes)
        + int(cfg.std_lanes)
        + int(cfg.cmd_lanes)
        + int(cfg.fsu_lanes)
        + int(cfg.tpl_lanes)
    )
    if lane_issue_cap <= 0:
        raise ValueError("sum of active issue lanes must be > 0")
    if issue_w > lane_issue_cap:
        raise ValueError(
            f"issue_width={issue_w} exceeds lane issue capacity={lane_issue_cap}"
        )
    if dispatch_w > issue_w:
        raise ValueError(
            f"dispatch_w={dispatch_w} exceeds issue_width={issue_w}"
        )

    for label, v in [
        ("lat_alu", int(cfg.lat_alu)),
        ("lat_bru", int(cfg.lat_bru)),
        ("lat_agu", int(cfg.lat_agu)),
        ("lat_std", int(cfg.lat_std)),
        ("lat_cmd", int(cfg.lat_cmd)),
        ("lat_fsu", int(cfg.lat_fsu)),
        ("lat_tpl", int(cfg.lat_tpl)),
    ]:
        if v <= 0:
            raise ValueError(f"{label} must be > 0, got {v}")

    return OexDerivedCfg(
        aregs_w=max(1, ct.clog2(aregs)),
        ib_w=max(1, ct.clog2(ib)),
        rob_w=max(1, ct.clog2(rob)),
        issueq_w=max(1, ct.clog2(iq)),
        pcbuf_w=max(1, ct.clog2(pcb)),
        ptag_w=max(1, ct.clog2(ptag)),
        tu_w=max(1, ct.clog2(tu)),
        issue_w=issue_w,
        lane_issue_cap=lane_issue_cap,
        dispatch_w=dispatch_w,
        commit_w=commit_w,
    )


@const
def lane_mask(m: Circuit, width: int) -> int:
    _ = m
    w = max(1, int(width))
    return ct.bitmask(w)
