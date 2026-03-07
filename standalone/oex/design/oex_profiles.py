from __future__ import annotations

from pycircuit import Circuit, const

from .oex_config import OexProfileCfg


@const
def get_profile(m: Circuit, profile_name: str = "oex_target") -> OexProfileCfg:
    _ = m
    name = str(profile_name).strip().lower()

    if name == "oex_debug":
        return OexProfileCfg(
            name="oex_debug",
            aregs=64,
            ib_depth=32,
            rob_depth=64,
            issueq_depth=16,
            pcbuf_depth=64,
            ptag_rf_entries=64,
            tu_rf_entries=64,
            issue_width=2,
            dispatch_w=2,
            commit_w=2,
            alu_lanes=2,
            bru_lanes=1,
            agu_lanes=1,
            std_lanes=1,
            fsu_lanes=0,
            cmd_lanes=0,
            tpl_lanes=1,
            lat_alu=1,
            lat_bru=1,
            lat_agu=4,
            lat_std=1,
            lat_cmd=2,
            lat_fsu=4,
            lat_tpl=2,
            debug_trace=1,
        )

    if name == "oex_target":
        return OexProfileCfg(
            name="oex_target",
            aregs=64,
            ib_depth=32,
            rob_depth=256,
            issueq_depth=16,
            pcbuf_depth=256,
            ptag_rf_entries=128,
            tu_rf_entries=128,
            issue_width=4,
            dispatch_w=4,
            commit_w=4,
            alu_lanes=2,
            bru_lanes=1,
            agu_lanes=2,
            std_lanes=2,
            fsu_lanes=1,
            cmd_lanes=1,
            tpl_lanes=1,
            lat_alu=1,
            lat_bru=1,
            lat_agu=4,
            lat_std=1,
            lat_cmd=2,
            lat_fsu=4,
            lat_tpl=2,
            debug_trace=1,
        )

    raise ValueError(f"unsupported OEX profile: {profile_name}")


@const
def profile_supports(m: Circuit, cfg: OexProfileCfg) -> int:
    _ = m
    if cfg.name == "oex_debug":
        if int(cfg.fsu_lanes) != 0 or int(cfg.cmd_lanes) != 0:
            raise ValueError("oex_debug requires fsu_lanes=0 and cmd_lanes=0")
        if int(cfg.agu_lanes) != 1 or int(cfg.std_lanes) != 1:
            raise ValueError("oex_debug requires agu_lanes=1 and std_lanes=1")
        if int(cfg.tpl_lanes) < 1:
            raise ValueError("oex_debug requires tpl_lanes>=1")
        return 1

    if cfg.name == "oex_target":
        # Phase-2 bootstrap:
        # - enable normal sizing (ROB=256, PTAG/TU=128)
        # - allow AGUx2/STDx2 lane vectors
        # - CMD/FSU stay explicit deferred interfaces in IEX.
        if int(cfg.rob_depth) < 256:
            raise ValueError("oex_target requires rob_depth >= 256")
        if int(cfg.ptag_rf_entries) < 128 or int(cfg.tu_rf_entries) < 128:
            raise ValueError("oex_target requires ptag_rf_entries>=128 and tu_rf_entries>=128")
        if int(cfg.agu_lanes) < 2 or int(cfg.std_lanes) < 2:
            raise ValueError("oex_target requires agu_lanes>=2 and std_lanes>=2")
        if int(cfg.fsu_lanes) < 1 or int(cfg.cmd_lanes) < 1:
            raise ValueError("oex_target requires fsu_lanes>=1 and cmd_lanes>=1 (stubbed in phase-2)")
        if int(cfg.tpl_lanes) < 1:
            raise ValueError("oex_target requires tpl_lanes>=1")
        return 2

    raise ValueError(f"unsupported profile in support validator: {cfg.name}")
