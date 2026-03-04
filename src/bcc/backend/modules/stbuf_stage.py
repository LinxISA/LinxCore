from __future__ import annotations

from pycircuit import Circuit, module, u

from ..helpers import mux_by_uindex


@module(name="LinxCoreStbufStage")
def build_stbuf_stage(
    m: Circuit,
    *,
    sq_entries: int = 32,
    sq_w: int = 5,
) -> None:
    if sq_entries <= 0:
        raise ValueError("sq_entries must be > 0")
    if sq_w <= 0:
        raise ValueError("sq_w must be > 0")

    clk = m.clock("clk")
    rst = m.reset("rst")

    commit_store_fire = m.input("commit_store_fire", width=1)
    commit_store_addr = m.input("commit_store_addr", width=64)
    commit_store_data = m.input("commit_store_data", width=64)
    commit_store_size = m.input("commit_store_size", width=4)

    macro_store_fire = m.input("macro_store_fire", width=1)
    macro_store_addr = m.input("macro_store_addr", width=64)
    macro_store_data = m.input("macro_store_data", width=64)
    macro_store_size = m.input("macro_store_size", width=4)

    macro_load_fire = m.input("macro_load_fire", width=1)
    macro_load_addr = m.input("macro_load_addr", width=64)

    lsu_load_fire = m.input("lsu_load_fire", width=1)
    lsu_load_addr = m.input("lsu_load_addr", width=64)

    # LSU forwarding probe: a raw lane0 load candidate (used by the LSU
    # disambiguation stage to suppress unnecessary D$ reads when stbuf hits).
    lsu_probe_fire = m.input("lsu_probe_fire", width=1)
    lsu_probe_addr = m.input("lsu_probe_addr", width=64)

    dmem_rdata_i = m.input("dmem_rdata_i", width=64)

    head = m.out("head", clk=clk, rst=rst, width=sq_w, init=0, en=1)
    tail = m.out("tail", clk=clk, rst=rst, width=sq_w, init=0, en=1)
    count = m.out("count", clk=clk, rst=rst, width=sq_w + 1, init=0, en=1)

    st_valid = []
    st_addr = []
    st_data = []
    st_size = []
    for i in range(int(sq_entries)):
        st_valid.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=0, en=1))
        st_addr.append(m.out(f"a{i}", clk=clk, rst=rst, width=64, init=0, en=1))
        st_data.append(m.out(f"d{i}", clk=clk, rst=rst, width=64, init=0, en=1))
        st_size.append(m.out(f"s{i}", clk=clk, rst=rst, width=4, init=0, en=1))

    empty = count.out() == u(sq_w + 1, 0)
    has_space = count.out() < u(sq_w + 1, int(sq_entries))

    mmio_uart = commit_store_fire & (commit_store_addr == u(64, 0x1000_0000))
    mmio_exit = commit_store_fire & (commit_store_addr == u(64, 0x1000_0004))
    mmio_any = mmio_uart | mmio_exit
    mmio_uart_data = commit_store_data[0:8] if mmio_uart else u(8, 0)
    mmio_exit_code = commit_store_data[0:32] if mmio_exit else u(32, 0)

    commit_store_defer = commit_store_fire & (~mmio_any) & (macro_store_fire | (~empty))
    enq_fire = commit_store_defer
    enq_idx = tail.out()

    drain_fire = (~macro_store_fire) & (~commit_store_fire) & (~empty)
    drain_addr = mux_by_uindex(m, idx=head.out(), items=st_addr, default=u(64, 0))
    drain_data = mux_by_uindex(m, idx=head.out(), items=st_data, default=u(64, 0))
    drain_size = mux_by_uindex(m, idx=head.out(), items=st_size, default=u(4, 0))

    write_through_fire = commit_store_fire & (~mmio_any) & (~commit_store_defer)
    wvalid = macro_store_fire | write_through_fire | drain_fire
    waddr = macro_store_addr if macro_store_fire else (commit_store_addr if write_through_fire else drain_addr)
    wdata = macro_store_data if macro_store_fire else (commit_store_data if write_through_fire else drain_data)
    wsize = macro_store_size if macro_store_fire else (commit_store_size if write_through_fire else drain_size)

    wsrc = u(2, 0)
    wsrc = u(2, 1) if macro_store_fire else wsrc
    wsrc = u(2, 2) if write_through_fire else wsrc
    wsrc = u(2, 3) if drain_fire else wsrc

    wstrb = u(8, 0)
    wstrb = u(8, 0x01) if (wsize == u(4, 1)) else wstrb
    wstrb = u(8, 0x03) if (wsize == u(4, 2)) else wstrb
    wstrb = u(8, 0x0F) if (wsize == u(4, 4)) else wstrb
    wstrb = u(8, 0xFF) if (wsize == u(4, 8)) else wstrb

    # Update stbuf entries (single enqueue, single drain).
    for i in range(int(sq_entries)):
        idx = u(sq_w, i)
        do_enq = enq_fire & (enq_idx == idx)
        do_drain = drain_fire & (head.out() == idx)
        v_next = st_valid[i].out()
        v_next = u(1, 0) if do_drain else v_next
        v_next = u(1, 1) if do_enq else v_next
        st_valid[i].set(v_next)
        st_addr[i].set(commit_store_addr, when=do_enq)
        st_data[i].set(commit_store_data, when=do_enq)
        st_size[i].set(commit_store_size, when=do_enq)

    head_next = (head.out() + u(sq_w, 1)) if drain_fire else head.out()
    tail_next = (tail.out() + u(sq_w, 1)) if enq_fire else tail.out()
    count_next = count.out()
    count_next = (count_next + u(sq_w + 1, 1)) if enq_fire else count_next
    count_next = (count_next - u(sq_w + 1, 1)) if drain_fire else count_next
    head.set(head_next)
    tail.set(tail_next)
    count.set(count_next)

    # D-memory read arbitration: macro restore-load > LSU load.
    dmem_raddr = macro_load_addr if macro_load_fire else (lsu_load_addr if lsu_load_fire else u(64, 0))

    macro_fwd_hit = u(1, 0)
    macro_fwd_data = u(64, 0)
    for i in range(int(sq_entries)):
        st_match = st_valid[i].out() & (st_addr[i].out() == macro_load_addr)
        take = macro_load_fire & st_match
        macro_fwd_hit = u(1, 1) if take else macro_fwd_hit
        macro_fwd_data = st_data[i].out() if take else macro_fwd_data
    macro_load_data = macro_fwd_data if macro_fwd_hit else dmem_rdata_i

    lsu_fwd_hit = u(1, 0)
    lsu_fwd_data = u(64, 0)
    for i in range(int(sq_entries)):
        st_match = st_valid[i].out() & (st_addr[i].out() == lsu_probe_addr)
        take = lsu_probe_fire & st_match
        lsu_fwd_hit = u(1, 1) if take else lsu_fwd_hit
        lsu_fwd_data = st_data[i].out() if take else lsu_fwd_data

    m.output("has_space", has_space)
    m.output("head", head.out())
    m.output("tail", tail.out())
    m.output("count", count.out())
    for i in range(int(sq_entries)):
        m.output(f"valid{i}", st_valid[i].out())
        m.output(f"addr{i}", st_addr[i].out())
        m.output(f"data{i}", st_data[i].out())
        m.output(f"size{i}", st_size[i].out())

    m.output("enq_fire", enq_fire)
    m.output("drain_fire", drain_fire)
    m.output("commit_store_write_through", write_through_fire)

    m.output("mmio_uart_valid", mmio_uart)
    m.output("mmio_uart_data", mmio_uart_data)
    m.output("mmio_exit_valid", mmio_exit)
    m.output("mmio_exit_code", mmio_exit_code)

    m.output("dmem_raddr", dmem_raddr)
    m.output("dmem_wvalid", wvalid)
    m.output("dmem_waddr", waddr)
    m.output("dmem_wdata", wdata)
    m.output("dmem_wstrb", wstrb)
    m.output("dmem_wsrc", wsrc)
    m.output("macro_load_data", macro_load_data)
    m.output("lsu_fwd_hit", lsu_fwd_hit)
    m.output("lsu_fwd_data", lsu_fwd_data)
