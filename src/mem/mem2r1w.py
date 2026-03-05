from __future__ import annotations

from pycircuit import Circuit, function, module, u, unsigned


@function
def _map_addr(m: Circuit, *, addr, stack_base, stack_mask, stack_off, data_base, data_mask, data_off, low_mask):
    # NOTE: keep mapping logic aligned with:
    # - tb/tb_linxcore_top.cpp (mapBringupMemAddrEff)
    # - tools/image/ihex_to_memh.py (ELF->memh address mapping)
    is_stack = ~addr.ult(stack_base)
    stack_addr = ((addr - stack_base) & stack_mask) + stack_off
    is_data = ~addr.ult(data_base)
    data_addr = ((addr - data_base) & data_mask) + data_off
    low_addr = addr & low_mask

    # Avoid `_select_internal` to satisfy pyc4 strict frontend lint.
    #
    # mask64 = 0xFFFF.. when cond=1 else 0, then:
    #   out = (a & mask64) | (b & ~mask64)
    d_data = unsigned(is_data) + u(64, 0)
    m_data = u(64, 0) - d_data
    mapped = (data_addr & m_data) | (low_addr & ~m_data)
    d_stack = unsigned(is_stack) + u(64, 0)
    m_stack = u(64, 0) - d_stack
    mapped = (stack_addr & m_stack) | (mapped & ~m_stack)
    return mapped


@module(name="LinxCoreMem2R1W")
def build_mem2r1w(m: Circuit, *, mem_bytes: int = (1 << 20)) -> None:
    c = m.const
    clk = m.clock("clk")
    rst = m.reset("rst")

    if_raddr = m.input("if_raddr", width=64)
    d_raddr = m.input("d_raddr", width=64)

    d_wvalid = m.input("d_wvalid", width=1)
    d_waddr = m.input("d_waddr", width=64)
    d_wdata = m.input("d_wdata", width=64)
    d_wstrb = m.input("d_wstrb", width=8)

    host_wvalid = m.input("host_wvalid", width=1)
    host_waddr = m.input("host_waddr", width=64)
    host_wdata = m.input("host_wdata", width=64)
    host_wstrb = m.input("host_wstrb", width=8)

    wvalid = d_wvalid | host_wvalid
    waddr = host_wvalid._select_internal(host_waddr, d_waddr)
    wdata = host_wvalid._select_internal(host_wdata, d_wdata)
    wstrb = host_wvalid._select_internal(host_wstrb, d_wstrb)

    # Bounded memory mapping:
    # - low addresses map directly into the low region,
    # - high data (.data/.bss) addresses map into a dedicated middle window,
    # - high stack addresses map into a dedicated upper window.
    #
    # This avoids destructive aliasing between:
    # - low code/rodata (pc ~ 0x1xxxx),
    # - high data region (0x01xx_xxxx),
    # - stack region (0x07fe_xxxx),
    # while keeping `mem_bytes` small for bring-up performance.
    low_mask = c(mem_bytes - 1, width=64)
    # Partition the memory into: [low | data | stack] windows.
    stack_window = max(1, mem_bytes // 4)
    data_window = max(1, mem_bytes // 4)
    stack_offset = mem_bytes - stack_window
    data_offset = max(0, mem_bytes - stack_window - data_window)
    stack_base = c(0x0000000007FE0000, width=64)
    data_base = c(0x0000000001000000, width=64)
    stack_mask = c(stack_window - 1, width=64)
    data_mask = c(data_window - 1, width=64)
    stack_off = c(stack_offset, width=64)
    data_off = c(data_offset, width=64)

    if_raddr_eff = _map_addr(
        m=m,
        addr=if_raddr,
        stack_base=stack_base,
        stack_mask=stack_mask,
        stack_off=stack_off,
        data_base=data_base,
        data_mask=data_mask,
        data_off=data_off,
        low_mask=low_mask,
    )
    d_raddr_eff = _map_addr(
        m=m,
        addr=d_raddr,
        stack_base=stack_base,
        stack_mask=stack_mask,
        stack_off=stack_off,
        data_base=data_base,
        data_mask=data_mask,
        data_off=data_off,
        low_mask=low_mask,
    )
    waddr_eff = _map_addr(
        m=m,
        addr=waddr,
        stack_base=stack_base,
        stack_mask=stack_mask,
        stack_off=stack_off,
        data_base=data_base,
        data_mask=data_mask,
        data_off=data_off,
        low_mask=low_mask,
    )

    if_rdata = m.byte_mem(
        clk,
        rst,
        raddr=if_raddr_eff,
        wvalid=wvalid,
        waddr=waddr_eff,
        wdata=wdata,
        wstrb=wstrb,
        depth=mem_bytes,
        name="imem",
    )

    d_rdata = m.byte_mem(
        clk,
        rst,
        raddr=d_raddr_eff,
        wvalid=wvalid,
        waddr=waddr_eff,
        wdata=wdata,
        wstrb=wstrb,
        depth=mem_bytes,
        name="dmem",
    )

    m.output("if_rdata", if_rdata)
    m.output("d_rdata", d_rdata)
    m.output("waddr_eff", waddr_eff)
    m.output("wvalid_eff", wvalid)
    m.output("wdata_eff", wdata)
    m.output("wstrb_eff", wstrb)


build_mem2r1w.__pycircuit_name__ = "LinxCoreMem2R1W"
