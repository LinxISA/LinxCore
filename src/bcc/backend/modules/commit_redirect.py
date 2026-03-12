from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreCommitRedirect")
def build_commit_redirect(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    commit_fire = m.input("commit_fire", width=1)
    commit_next_pc = m.input("commit_next_pc", width=64)
    commit_bid = m.input("commit_bid", width=64)

    c = m.const
    redirect_valid = m.out("redirect_valid_q", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    redirect_pc = m.out("redirect_pc_q", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))
    redirect_bid = m.out("redirect_bid_q", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))

    redirect_valid.set(commit_fire)
    redirect_pc.set(commit_next_pc, when=commit_fire)
    redirect_bid.set(commit_bid, when=commit_fire)

    m.output("redirect_valid_o", redirect_valid.out())
    m.output("redirect_pc_o", redirect_pc.out())
    m.output("redirect_bid_o", redirect_bid.out())


build_commit_redirect.__pycircuit_name__ = "LinxCoreCommitRedirect"
