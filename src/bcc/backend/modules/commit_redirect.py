from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreCommitRedirect")
def build_commit_redirect(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    commit_fire = m.input("commit_fire", width=1)
    commit_next_pc = m.input("commit_next_pc", width=64)
    commit_bid = m.input("commit_bid", width=64)

    # Boundary-only redirect stage:
    # keep redirect semantics combinational and explicit at module IO.
    _ = clk
    _ = rst
    redirect_valid = commit_fire._select_internal(c(1, width=1), c(0, width=1))
    redirect_pc = commit_fire._select_internal(commit_next_pc, commit_next_pc)
    redirect_bid = commit_fire._select_internal(commit_bid, commit_bid)
    redirect_hold = c(0, width=1)
    redirect_fire = commit_fire._select_internal(c(1, width=1), c(0, width=1))
    redirect_idle = redirect_valid.__eq__(c(0, width=1))
    m.output("redirect_valid_o", redirect_valid)
    m.output("redirect_pc_o", redirect_pc)
    m.output("redirect_bid_o", redirect_bid)
    m.output("redirect_hold_o", redirect_hold)
    m.output("redirect_fire_o", redirect_fire)
    m.output("redirect_idle_o", redirect_idle)
