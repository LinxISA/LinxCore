from __future__ import annotations

from pycircuit import ProbeBuilder, ProbeView, probe

_TOP = "linxcore_top_root"
_BACKEND = f"{_TOP}.janus_backend"
_TRACE = _BACKEND
_COMMIT = f"{_BACKEND}.backend_commit_trace"
_IB = f"{_TOP}.linxcore_ib"
_DF = f"{_TRACE}.dispatch_frontend"
_EXEC = f"{_TRACE}.backend_exec_pipe"

_FRONTEND_STAGES = ("d1", "d2", "d3")
_EXEC_STAGES = ("p1", "i1", "i2", "e1", "w1", "w2")


def _emit_f0_stage(p: ProbeBuilder, dut: ProbeView) -> None:
    p.emit(
        "f0.lane0",
        {
            "valid": dut.read("tb_ifu_stub_valid"),
            "uop_uid": dut.read("tb_ifu_stub_pkt_uid"),
            "pc": dut.read("tb_ifu_stub_pc"),
        },
        at="tick",
        tags={"family": "pipeview", "stage": "f0", "lane": 0},
    )


def _emit_f3_stage(p: ProbeBuilder, dut: ProbeView) -> None:
    p.emit(
        "f3.lane0",
        {
            "valid": dut.read(f"{_IB}.pop_valid"),
            "uop_uid": dut.read(f"{_IB}.pop_pkt_uid"),
            "pc": dut.read(f"{_IB}.pop_pc"),
        },
        at="tick",
        tags={"family": "pipeview", "stage": "f3", "lane": 0},
    )


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


def _emit_brob_stage(p: ProbeBuilder, dut: ProbeView) -> None:
    for slot in range(4):
        p.emit(
            f"brob.lane{slot}",
            {
                "valid": dut.read(f"{_COMMIT}.commit_block_bid{slot}"),
                "uop_uid": dut.read(f"{_COMMIT}.commit_uop_uid{slot}"),
                "pc": dut.read(f"{_COMMIT}.commit_pc{slot}"),
            },
            at="tick",
            tags={"family": "pipeview", "stage": "brob", "lane": slot},
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

        _emit_f0_stage(p, dut)
        _emit_f3_stage(p, dut)

        for stage in _FRONTEND_STAGES:
            for slot in range(4):
                _emit_frontend_stage(p, dut, stage, slot)

        for slot in range(4):
            _emit_iq_stage(p, dut, slot)

        for stage in _EXEC_STAGES:
            for slot in range(4):
                _emit_exec_stage(p, dut, stage, slot)

        _emit_brob_stage(p, dut)

    return pipeview_probe
