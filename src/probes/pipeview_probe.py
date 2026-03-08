from __future__ import annotations

from pycircuit import ProbeBuilder, ProbeView, probe

_TOP = "linxcore_top_root.linxcore_top_export"
_BACKEND = f"{_TOP}.janus_backend"
_TRACE = f"{_BACKEND}.backend_trace_export"
_IB = f"{_TOP}.linxcore_ib"
_DF = f"{_TRACE}.dispatch_frontend"
_EXEC = f"{_TRACE}.backend_exec_pipe"

_FRONTEND_STAGES = ("d1", "d2", "d3", "s1", "s2")
_EXEC_STAGES = ("p1", "i1", "i2", "e1", "w1", "w2")


def _emit_frontend_stage(p: ProbeBuilder, dut: ProbeView, stage: str, slot: int) -> None:
    base = f"{stage}.lane{slot}"
    src = f"{_DF}.probe_{stage}_"
    p.emit(
        base,
        {
            "valid": dut.read(f"{src}valid_{slot}"),
            "uop_uid": dut.read(f"{src}uid_{slot}"),
            "pc": dut.read(f"{src}pc_{slot}"),
            "stall": dut.read(f"{src}stall_{slot}"),
            "stall_cause": dut.read(f"{src}stall_cause_{slot}"),
        },
        at="tick",
        tags={"family": "pipeview", "stage": stage, "lane": slot},
    )


def _emit_iq_stage(p: ProbeBuilder, dut: ProbeView, slot: int) -> None:
    base = f"iq.lane{slot}"
    src = f"{_TRACE}.probe_iq_"
    p.emit(
        base,
        {
            "valid": dut.read(f"{src}valid_{slot}"),
            "uop_uid": dut.read(f"{src}uid_{slot}"),
            "pc": dut.read(f"{src}pc_{slot}"),
            "rob": dut.read(f"{src}rob_{slot}"),
            "parent_uid": dut.read(f"{src}parent_uid_{slot}"),
            "block_uid": dut.read(f"{src}block_uid_{slot}"),
        },
        at="tick",
        tags={"family": "pipeview", "stage": "iq", "lane": slot},
    )


def _emit_exec_stage(p: ProbeBuilder, dut: ProbeView, stage: str, slot: int) -> None:
    base = f"{stage}.lane{slot}"
    src = f"{_EXEC}.probe_{stage}_"
    p.emit(
        base,
        {
            "valid": dut.read(f"{src}valid_{slot}"),
            "uop_uid": dut.read(f"{src}uid_{slot}"),
            "pc": dut.read(f"{src}pc_{slot}"),
            "rob": dut.read(f"{src}rob_{slot}"),
        },
        at="tick",
        tags={"family": "pipeview", "stage": stage, "lane": slot},
    )


def define_pipeview_probe(target):
    @probe(target=target, name="pipeview")
    def pipeview_probe(p: ProbeBuilder, dut: ProbeView) -> None:
        p.emit(
            "frontend.stub",
            {
                "enable": dut.read("tb_ifu_stub_enable"),
                "valid": dut.read("tb_ifu_stub_valid"),
                "pc": dut.read("tb_ifu_stub_pc"),
                "pkt_uid": dut.read("tb_ifu_stub_pkt_uid"),
                "ready": dut.read("tb_ifu_stub_ready"),
            },
            at="tick",
            tags={"family": "pipeview", "stage": "stub", "lane": 0},
        )
        p.emit(
            "frontend.ib_raw",
            {
                "pop_valid": dut.read(f"{_IB}.pop_valid"),
                "pop_pc": dut.read(f"{_IB}.pop_pc"),
                "pop_window": dut.read(f"{_IB}.pop_window"),
                "pop_pkt_uid": dut.read(f"{_IB}.pop_pkt_uid"),
                "backend_ready": dut.read(f"{_TRACE}.frontend_ready"),
            },
            at="tick",
            tags={"family": "pipeview", "stage": "ib", "lane": 0},
        )

        for stage in _FRONTEND_STAGES:
            for slot in range(4):
                _emit_frontend_stage(p, dut, stage, slot)

        for slot in range(4):
            _emit_iq_stage(p, dut, slot)

        for stage in _EXEC_STAGES:
            for slot in range(4):
                _emit_exec_stage(p, dut, stage, slot)

    return pipeview_probe
