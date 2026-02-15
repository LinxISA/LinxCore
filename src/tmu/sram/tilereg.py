from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusTmuTileReg")
def build_janus_tmu_tilereg(m: Circuit, *, regs: int = 32) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    wr_valid_tmu = m.input("wr_valid_tmu", width=1)
    wr_idx_tmu = m.input("wr_idx_tmu", width=6)
    wr_data_tmu = m.input("wr_data_tmu", width=64)
    rd_idx_tmu = m.input("rd_idx_tmu", width=6)

    c = m.const

    regs_tmu = []
    for i in range(regs):
        regs_tmu.append(m.out(f"r{i}_tmu", clk=clk_top, rst=rst_top, width=64, init=c(0, width=64), en=c(1, width=1)))

    rd_data_tmu = c(0, width=64)
    for i in range(regs):
        hit_wr_tmu = wr_idx_tmu == c(i, width=6)
        hit_rd_tmu = rd_idx_tmu == c(i, width=6)
        regs_tmu[i].set(wr_data_tmu, when=wr_valid_tmu & hit_wr_tmu)
        rd_data_tmu = hit_rd_tmu._select_internal(regs_tmu[i].out(), rd_data_tmu)

    m.output("rd_data_tmu", rd_data_tmu)
