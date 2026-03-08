from __future__ import annotations

from pycircuit import Circuit, module

from probes.block_probe import define_block_probe
from probes.commit_probe import define_commit_probe
from probes.pipeview_probe import define_pipeview_probe
from top.top import build_linxcore_top


@module(name="linxcore_top", value_params={"callframe_size_i": "i64"})
def build(
    m: Circuit,
    *,
    # Default bring-up memory must cover typical Linx workloads with large
    # zero-fill regions (CoreMark/Dhrystone reserve ~16MiB .bss).
    mem_bytes: int = (1 << 26),
    ic_sets: int = 32,
    ic_ways: int = 4,
    ic_line_bytes: int = 64,
    ifetch_bundle_bits: int = 128,
    ifetch_bundle_bytes: int | None = None,
    ib_depth: int = 8,
    ic_miss_outstanding: int = 1,
    ic_enable: int = 1,
    callframe_size_i=0,
) -> None:
    if ifetch_bundle_bytes is not None:
        alias_bits = int(ifetch_bundle_bytes) * 8
        if int(ifetch_bundle_bits) == 128:
            ifetch_bundle_bits = alias_bits
        elif alias_bits != int(ifetch_bundle_bits):
            raise ValueError("ifetch_bundle_bytes alias mismatches ifetch_bundle_bits")
    clk = m.clock("clk")
    rst = m.reset("rst")
    boot_pc = m.input("boot_pc", width=64)
    boot_sp = m.input("boot_sp", width=64)
    boot_ra = m.input("boot_ra", width=64)
    host_wvalid = m.input("host_wvalid", width=1)
    host_waddr = m.input("host_waddr", width=64)
    host_wdata = m.input("host_wdata", width=64)
    host_wstrb = m.input("host_wstrb", width=8)
    ic_l2_req_ready = m.input("ic_l2_req_ready", width=1)
    ic_l2_rsp_valid = m.input("ic_l2_rsp_valid", width=1)
    ic_l2_rsp_addr = m.input("ic_l2_rsp_addr", width=64)
    ic_l2_rsp_data = m.input("ic_l2_rsp_data", width=512)
    ic_l2_rsp_error = m.input("ic_l2_rsp_error", width=1)
    tb_ifu_stub_enable = m.input("tb_ifu_stub_enable", width=1)
    tb_ifu_stub_valid = m.input("tb_ifu_stub_valid", width=1)
    tb_ifu_stub_pc = m.input("tb_ifu_stub_pc", width=64)
    tb_ifu_stub_window = m.input("tb_ifu_stub_window", width=64)
    tb_ifu_stub_checkpoint = m.input("tb_ifu_stub_checkpoint", width=6)
    tb_ifu_stub_pkt_uid = m.input("tb_ifu_stub_pkt_uid", width=64)

    top = m.instance_auto(
        build_linxcore_top,
        name="linxcore_top_root",
        module_name="LinxCoreTopRoot",
        params={
            "mem_bytes": int(mem_bytes),
            "ic_sets": int(ic_sets),
            "ic_ways": int(ic_ways),
            "ic_line_bytes": int(ic_line_bytes),
            "ifetch_bundle_bits": int(ifetch_bundle_bits),
            "ifetch_bundle_bytes": (None if ifetch_bundle_bytes is None else int(ifetch_bundle_bytes)),
            "ib_depth": int(ib_depth),
            "ic_miss_outstanding": int(ic_miss_outstanding),
            "ic_enable": int(ic_enable),
        },
        clk=clk,
        rst=rst,
        boot_pc=boot_pc,
        boot_sp=boot_sp,
        boot_ra=boot_ra,
        host_wvalid=host_wvalid,
        host_waddr=host_waddr,
        host_wdata=host_wdata,
        host_wstrb=host_wstrb,
        ic_l2_req_ready=ic_l2_req_ready,
        ic_l2_rsp_valid=ic_l2_rsp_valid,
        ic_l2_rsp_addr=ic_l2_rsp_addr,
        ic_l2_rsp_data=ic_l2_rsp_data,
        ic_l2_rsp_error=ic_l2_rsp_error,
        tb_ifu_stub_enable=tb_ifu_stub_enable,
        tb_ifu_stub_valid=tb_ifu_stub_valid,
        tb_ifu_stub_pc=tb_ifu_stub_pc,
        tb_ifu_stub_window=tb_ifu_stub_window,
        tb_ifu_stub_checkpoint=tb_ifu_stub_checkpoint,
        tb_ifu_stub_pkt_uid=tb_ifu_stub_pkt_uid,
        callframe_size_i=callframe_size_i,
    )
    for name, connector in top.items():
        m.output(str(name), connector.read())


# Canonical top-name for emit/build flows (aligns generated header name with TB).
build.__pycircuit_name__ = "linxcore_top"

pipeview_probe = define_pipeview_probe(build)
block_probe = define_block_probe(build)
commit_probe = define_commit_probe(build)
