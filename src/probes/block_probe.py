from __future__ import annotations

from pycircuit import ProbeBuilder, ProbeView, probe

_TOP = "linxcore_top_root.linxcore_top_export"
_BACKEND = f"{_TOP}.janus_backend"
_BCTRL = f"{_TOP}.janus_bctrl"
_BROB = f"{_TOP}.janus_brob"


def define_block_probe(target):
    @probe(target=target, name="block")
    def block_probe(p: ProbeBuilder, dut: ProbeView) -> None:
        p.emit(
            "brob",
            {
                "active_bid": dut.read(f"{_BACKEND}.active_block_bid"),
                "query_state": dut.read(f"{_BROB}.brob_query_state_brob"),
                "query_allocated": dut.read(f"{_BROB}.brob_query_allocated_brob"),
                "query_ready": dut.read(f"{_BROB}.brob_query_ready_brob"),
                "query_exception": dut.read(f"{_BROB}.brob_query_exception_brob"),
                "query_retired": dut.read(f"{_BROB}.brob_query_retired_brob"),
                "count": dut.read(f"{_BROB}.brob_count_brob"),
                "alloc_ready": dut.read(f"{_BROB}.brob_alloc_ready_brob"),
                "alloc_bid": dut.read(f"{_BROB}.brob_alloc_bid_brob"),
                "rsp_valid": dut.read(f"{_BROB}.brob_to_rob_stage_rsp_valid_brob"),
                "rsp_src_rob": dut.read(f"{_BROB}.brob_to_rob_stage_rsp_src_rob_brob"),
                "rsp_bid": dut.read(f"{_BROB}.brob_to_rob_stage_rsp_bid_brob"),
                "retire_fire": dut.read(f"{_BACKEND}.brob_retire_fire"),
                "retire_bid": dut.read(f"{_BACKEND}.brob_retire_bid"),
            },
            at="tick",
            tags={"family": "block", "stage": "brob", "lane": 0},
        )
        p.emit(
            "bctrl",
            {
                "issue_fire": dut.read(f"{_BCTRL}.issue_fire_brob"),
                "issue_bid": dut.read(f"{_BCTRL}.issue_bid_brob"),
                "issue_src_rob": dut.read(f"{_BCTRL}.issue_src_rob_brob"),
            },
            at="tick",
            tags={"family": "block", "stage": "bctrl", "lane": 0},
        )

    return block_probe
