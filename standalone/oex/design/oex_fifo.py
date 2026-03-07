from __future__ import annotations

from pycircuit import Circuit, module

from .stage_specs import mem_req_spec, retire_spec


@module(name="StandaloneOexRetireFifo")
def build_retire_fifo(m: Circuit) -> None:
    in_s = retire_spec(m)
    out_s = retire_spec(m)
    ins = m.inputs(in_s, prefix="in_")

    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "pc": ins["pc"],
            "raw": ins["raw"],
            "len": ins["len"],
            "src0_valid": ins["src0_valid"],
            "src0_reg": ins["src0_reg"],
            "src0_data": ins["src0_data"],
            "src1_valid": ins["src1_valid"],
            "src1_reg": ins["src1_reg"],
            "src1_data": ins["src1_data"],
            "dst_valid": ins["dst_valid"],
            "dst_reg": ins["dst_reg"],
            "dst_data": ins["dst_data"],
            "mem_valid": ins["mem_valid"],
            "mem_is_store": ins["mem_is_store"],
            "mem_addr": ins["mem_addr"],
            "mem_wdata": ins["mem_wdata"],
            "mem_rdata": ins["mem_rdata"],
            "mem_size": ins["mem_size"],
            "trap_valid": ins["trap_valid"],
            "trap_cause": ins["trap_cause"],
            "traparg0": ins["traparg0"],
            "next_pc": ins["next_pc"],
            "valid": ins["valid"],
        },
        prefix="out_",
    )
    m.output("count", ins["valid"])


@module(name="StandaloneOexMemReqFifo")
def build_mem_req_fifo(m: Circuit) -> None:
    in_s = mem_req_spec(m)
    out_s = mem_req_spec(m)
    ins = m.inputs(in_s, prefix="in_")

    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "mem_idx": ins["mem_idx"],
            "kind": ins["kind"],
            "pc": ins["pc"],
            "addr": ins["addr"],
            "size": ins["size"],
            "data": ins["data"],
            "valid": ins["valid"],
        },
        prefix="out_",
    )
    m.output("count", ins["valid"])
