from __future__ import annotations

from pycircuit import Circuit, module, u, unsigned


@module(name="LinxCoreRobEventPipeStage")
def build_rob_event_pipe_stage(
    m: Circuit,
    *,
    issue_w: int = 4,
    rob_w: int = 6,
) -> None:
    """1-cycle pipe stage for ROB update events.

    Purpose:
    - Break large combinational SCCs at the backend top by registering the
      issue/execute event bundle before it fans into the ROB.
    - Keep the ROB module interface stable (still consumes wb/store/load/ex
      events), but now sourced from this pipe stage.
    """

    if issue_w <= 0:
        raise ValueError("issue_w must be > 0")
    if rob_w <= 0:
        raise ValueError("rob_w must be > 0")

    clk = m.clock("clk")
    rst = m.reset("rst")

    do_flush = m.input("do_flush", width=1)

    # Full-width clear masks: 0 when not flushing, all-ones when flushing
    # (wraparound subtraction).
    clr1 = u(1, 0) - unsigned(do_flush)
    clr4 = u(4, 0) - unsigned(do_flush)
    clr64 = u(64, 0) - unsigned(do_flush)
    clr_rob = u(rob_w, 0) - unsigned(do_flush)

    wb_fire_regs = []
    wb_rob_regs = []
    wb_value_regs = []
    store_fire_regs = []
    load_fire_regs = []
    ex_addr_regs = []
    ex_wdata_regs = []
    ex_size_regs = []
    ex_src0_regs = []
    ex_src1_regs = []

    for slot in range(issue_w):
        wb_fire_i = m.input(f"wb_fire_i{slot}", width=1)
        wb_rob_i = m.input(f"wb_rob_i{slot}", width=rob_w)
        wb_value_i = m.input(f"wb_value_i{slot}", width=64)
        store_fire_i = m.input(f"store_fire_i{slot}", width=1)
        load_fire_i = m.input(f"load_fire_i{slot}", width=1)
        ex_addr_i = m.input(f"ex_addr_i{slot}", width=64)
        ex_wdata_i = m.input(f"ex_wdata_i{slot}", width=64)
        ex_size_i = m.input(f"ex_size_i{slot}", width=4)
        ex_src0_i = m.input(f"ex_src0_i{slot}", width=64)
        ex_src1_i = m.input(f"ex_src1_i{slot}", width=64)

        wb_fire_o = m.out(f"wb_fire{slot}", clk=clk, rst=rst, width=1, init=0, en=1)
        wb_rob_o = m.out(f"wb_rob{slot}", clk=clk, rst=rst, width=rob_w, init=0, en=1)
        wb_value_o = m.out(f"wb_value{slot}", clk=clk, rst=rst, width=64, init=0, en=1)
        store_fire_o = m.out(f"store_fire{slot}", clk=clk, rst=rst, width=1, init=0, en=1)
        load_fire_o = m.out(f"load_fire{slot}", clk=clk, rst=rst, width=1, init=0, en=1)
        ex_addr_o = m.out(f"ex_addr{slot}", clk=clk, rst=rst, width=64, init=0, en=1)
        ex_wdata_o = m.out(f"ex_wdata{slot}", clk=clk, rst=rst, width=64, init=0, en=1)
        ex_size_o = m.out(f"ex_size{slot}", clk=clk, rst=rst, width=4, init=0, en=1)
        ex_src0_o = m.out(f"ex_src0{slot}", clk=clk, rst=rst, width=64, init=0, en=1)
        ex_src1_o = m.out(f"ex_src1{slot}", clk=clk, rst=rst, width=64, init=0, en=1)

        # Clear all fires and payload on flush to avoid feeding stale events
        # into the ROB after a redirect.
        wb_fire_o.set(wb_fire_i & ~clr1)
        wb_rob_o.set(wb_rob_i & ~clr_rob)
        wb_value_o.set(wb_value_i & ~clr64)
        store_fire_o.set(store_fire_i & ~clr1)
        load_fire_o.set(load_fire_i & ~clr1)
        ex_addr_o.set(ex_addr_i & ~clr64)
        ex_wdata_o.set(ex_wdata_i & ~clr64)
        ex_size_o.set(ex_size_i & ~clr4)
        ex_src0_o.set(ex_src0_i & ~clr64)
        ex_src1_o.set(ex_src1_i & ~clr64)

        wb_fire_regs.append(wb_fire_o)
        wb_rob_regs.append(wb_rob_o)
        wb_value_regs.append(wb_value_o)
        store_fire_regs.append(store_fire_o)
        load_fire_regs.append(load_fire_o)
        ex_addr_regs.append(ex_addr_o)
        ex_wdata_regs.append(ex_wdata_o)
        ex_size_regs.append(ex_size_o)
        ex_src0_regs.append(ex_src0_o)
        ex_src1_regs.append(ex_src1_o)

    for slot in range(issue_w):
        m.output(f"wb_fire{slot}", wb_fire_regs[slot])
        m.output(f"wb_rob{slot}", wb_rob_regs[slot])
        m.output(f"wb_value{slot}", wb_value_regs[slot])
        m.output(f"store_fire{slot}", store_fire_regs[slot])
        m.output(f"load_fire{slot}", load_fire_regs[slot])
        m.output(f"ex_addr{slot}", ex_addr_regs[slot])
        m.output(f"ex_wdata{slot}", ex_wdata_regs[slot])
        m.output(f"ex_size{slot}", ex_size_regs[slot])
        m.output(f"ex_src0{slot}", ex_src0_regs[slot])
        m.output(f"ex_src1{slot}", ex_src1_regs[slot])

