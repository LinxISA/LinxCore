from __future__ import annotations

from pycircuit import Circuit, module

from ..components.issue_queue_age_matrix import build_issue_queue_age_matrix


@module(name="LinxCoreIssueAgeMatrix")
def build_issue_age_matrix_module(m: Circuit, *, depth: int = 16, rob_w: int = 6, ptag_w: int = 6) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    _ = m.instance_auto(
        build_issue_queue_age_matrix,
        name="issq_age_matrix",
        module_name="LinxCoreIssueQueueAgeMatrix",
        params={"depth": int(depth), "rob_w": int(rob_w), "ptag_w": int(ptag_w)},
        clk=clk,
        rst=rst,
        enq_valid=m.input("enq_valid", width=1),
        enq_rob=m.input("enq_rob", width=rob_w),
        enq_src0_valid=m.input("enq_src0_valid", width=1),
        enq_src0_ptag=m.input("enq_src0_ptag", width=ptag_w),
        enq_src1_valid=m.input("enq_src1_valid", width=1),
        enq_src1_ptag=m.input("enq_src1_ptag", width=ptag_w),
        pick_i=m.input("pick_i", width=1),
        wb_valid=m.input("wb_valid", width=1),
        wb_ptag=m.input("wb_ptag", width=ptag_w),
    )
