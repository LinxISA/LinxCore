from __future__ import annotations

from pycircuit import ProbeBuilder, ProbeView, probe

_TOP = "linxcore_top_root"


def define_block_probe(target):
    @probe(target=target, name="block")
    def block_probe(p: ProbeBuilder, dut: ProbeView) -> None:
        p.emit(
            "brob",
            {
                "active_bid": dut.read(f"{_TOP}.active_block_bid_top"),
                "query_state": dut.read(f"{_TOP}.brob_query_state_top"),
                "query_allocated": dut.read(f"{_TOP}.brob_query_allocated_top"),
                "query_ready": dut.read(f"{_TOP}.brob_query_ready_top"),
                "query_exception": dut.read(f"{_TOP}.brob_query_exception_top"),
                "query_retired": dut.read(f"{_TOP}.brob_query_retired_top"),
                "count": dut.read(f"{_TOP}.brob_count_dbg_top"),
                "alloc_ready": dut.read(f"{_TOP}.brob_alloc_ready_dbg_top"),
                "alloc_bid": dut.read(f"{_TOP}.brob_alloc_bid_dbg_top"),
                "rsp_valid": dut.read(f"{_TOP}.brob_to_rob_stage_rsp_valid_top"),
                "rsp_src_rob": dut.read(f"{_TOP}.brob_to_rob_stage_rsp_src_rob_top"),
                "rsp_bid": dut.read(f"{_TOP}.brob_to_rob_stage_rsp_bid_top"),
                "retire_fire": dut.read(f"{_TOP}.brob_retire_fire_top"),
                "retire_bid": dut.read(f"{_TOP}.brob_retire_bid_top"),
            },
            at="tick",
            tags={"family": "block", "stage": "brob", "lane": 0},
        )
        p.emit(
            "bctrl",
            {
                "issue_fire": dut.read(f"{_TOP}.bctrl_issue_fire_top"),
                "issue_bid": dut.read(f"{_TOP}.bctrl_issue_bid_top"),
                "issue_src_rob": dut.read(f"{_TOP}.bctrl_issue_src_rob_top"),
            },
            at="tick",
            tags={"family": "block", "stage": "bctrl", "lane": 0},
        )

    return block_probe
