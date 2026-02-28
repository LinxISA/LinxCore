from __future__ import annotations

"""Pack a scalar store (addr+64b data+size) into SCB 64B-line format."""

from pycircuit import Circuit, module


@module(name="JanusBccLsuStorePack")
def build_janus_bcc_lsu_store_pack(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    v_in = m.input("st_valid", width=1)
    addr = m.input("st_addr", width=64)
    data64 = m.input("st_data", width=64)
    size = m.input("st_size", width=4)  # bytes: 1/2/4/8

    v = m.out("pack_valid", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    line = m.out("pack_line", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))
    mask = m.out("pack_mask", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))
    data512 = m.out("pack_data", clk=clk, rst=rst, width=512, init=c(0, width=512), en=c(1, width=1))

    line_base = addr & (~c(63, width=64))
    byte_off = addr._trunc(width=6)

    m1 = c(0x1, width=64)
    m2 = c(0x3, width=64)
    m4 = c(0xF, width=64)
    m8 = c(0xFF, width=64)
    base_mask = m1
    base_mask = (size.eq(c(2, width=4)))._select_internal(m2, base_mask)
    base_mask = (size.eq(c(4, width=4)))._select_internal(m4, base_mask)
    base_mask = (size.eq(c(8, width=4)))._select_internal(m8, base_mask)

    shamt_mask = byte_off._zext(width=64)
    mask_shifted = (base_mask << shamt_mask)._trunc(width=64)

    bit_off = (byte_off._zext(width=9) << c(3, width=9))._zext(width=512)
    data_shifted = (data64._zext(width=512) << bit_off)._trunc(width=512)

    v.set(v_in)
    line.set(line_base, when=v_in)
    mask.set(mask_shifted, when=v_in)
    data512.set(data_shifted, when=v_in)

    m.output("st_valid_packed", v.out())
    m.output("st_line", line.out())
    m.output("st_mask", mask.out())
    m.output("st_data512", data512.out())
