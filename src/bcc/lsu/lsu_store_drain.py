from __future__ import annotations

"""LSU store-drain glue (bring-up, policy B).

This module forms the committed-store → SCB → D$ loop:
- Input: commit_store_fire/addr/data/size
- Packs into 64B-line write (line/mask/data512)
- Enqueues into SCB with a monotonically incrementing SID
- Drives a D$ stub downstream and feeds completion back to SCB

This is a stepping stone toward a full LSU (STQ/LIQ/LHQ/MDB). It deliberately
uses commit_store_* as the non-flush gate (policy B).
"""

from pycircuit import Circuit, module

from bcc.lsu.dcache_stub import build_janus_bcc_lsu_dcache_stub
from bcc.lsu.scb import build_janus_bcc_lsu_scb
from bcc.lsu.store_pack import build_janus_bcc_lsu_store_pack


@module(name="JanusBccLsuStoreDrain")
def build_janus_bcc_lsu_store_drain(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    commit_store_fire = m.input("commit_store_fire", width=1)
    commit_store_addr = m.input("commit_store_addr", width=64)
    commit_store_data = m.input("commit_store_data", width=64)
    commit_store_size = m.input("commit_store_size", width=4)

    # wires between scb and dcache
    dcache_req_ready = m.new_wire(width=1)
    dcache_resp_valid = m.new_wire(width=1)
    dcache_resp_entry_id = m.new_wire(width=4)
    dcache_resp_ok = m.new_wire(width=1)
    dcache_resp_err_code = m.new_wire(width=4)

    pack = m.instance_auto(
        build_janus_bcc_lsu_store_pack,
        name="store_pack",
        module_name="JanusBccLsuStorePack",
        clk=clk,
        rst=rst,
        st_valid=commit_store_fire,
        st_addr=commit_store_addr,
        st_data=commit_store_data,
        st_size=commit_store_size,
    )

    sid_ctr = m.out("sid_ctr", clk=clk, rst=rst, width=6, init=c(1, width=6), en=c(1, width=1))

    scb = m.instance_auto(
        build_janus_bcc_lsu_scb,
        name="scb",
        module_name="JanusBccLsuScb",
        clk=clk,
        rst=rst,
        enq_valid=pack["st_valid_packed"],
        enq_line=pack["st_line"],
        enq_mask=pack["st_mask"],
        enq_data=pack["st_data512"],
        enq_sid=sid_ctr.out(),
        dcache_req_ready=dcache_req_ready,
        dcache_resp_valid=dcache_resp_valid,
        dcache_resp_entry_id=dcache_resp_entry_id,
        dcache_resp_ok=dcache_resp_ok,
        dcache_resp_err_code=dcache_resp_err_code,
    )

    enq_fire = pack["st_valid_packed"] & scb["enq_ready"]
    sid_ctr.set((sid_ctr.out() + c(1, width=6))._trunc(width=6), when=enq_fire)

    dcache = m.instance_auto(
        build_janus_bcc_lsu_dcache_stub,
        name="dcache",
        module_name="JanusBccLsuDCacheStub",
        clk=clk,
        rst=rst,
        dcache_req_valid=scb["dcache_req_valid"],
        dcache_req_entry_id=scb["dcache_req_entry_id"],
        dcache_req_line=scb["dcache_req_line"],
        dcache_req_mask=scb["dcache_req_mask"],
        dcache_req_data=scb["dcache_req_data"],
        dcache_resp_ready=scb["dcache_resp_ready"],
    )

    m.assign(dcache_req_ready, dcache["dcache_req_ready"])
    m.assign(dcache_resp_valid, dcache["dcache_resp_valid"])
    m.assign(dcache_resp_entry_id, dcache["dcache_resp_entry_id"])
    m.assign(dcache_resp_ok, dcache["dcache_resp_ok"])
    m.assign(dcache_resp_err_code, dcache["dcache_resp_err_code"])

    # expose some debug outputs
    m.output("scb_enq_fire", enq_fire)
    m.output("scb_req_valid", scb["dcache_req_valid"])
    m.output("scb_count", scb["scb_count"])
    m.output("scb_inflight", scb["scb_inflight"])
