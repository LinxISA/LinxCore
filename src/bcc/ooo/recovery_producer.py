from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxBccOooRecoveryProducer")
def build_linx_bcc_ooo_recovery_producer(
    m: Circuit,
    *,
    bid_width: int = 64,
    tpc_width: int = 64,
    stid_width: int = 8,
    owner_width: int = 8,
) -> None:
    if bid_width <= 0:
        raise ValueError("BID width must be positive")
    if tpc_width <= 0:
        raise ValueError("restart TPC width must be positive")
    if stid_width <= 0:
        raise ValueError("STID width must be positive")
    if owner_width <= 0:
        raise ValueError("owner width must be positive")

    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    recovery_valid = m.input("recovery_valid", width=1)
    recovery_bid = m.input("recovery_bid", width=bid_width)
    recovery_restart_tpc = m.input("recovery_restart_tpc", width=tpc_width)
    recovery_stid_valid = m.input("recovery_stid_valid", width=1)
    recovery_stid = m.input("recovery_stid", width=stid_width)
    recovery_owner_valid = m.input("recovery_owner_valid", width=1)
    recovery_owner = m.input("recovery_owner", width=owner_width)
    packet_ready = m.input("packet_ready", width=1)

    packet_valid_q = m.out("packet_valid_q", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    packet_bid_q = m.out("packet_bid_q", clk=clk, rst=rst, width=bid_width, init=c(0, width=bid_width), en=c(1, width=1))
    packet_restart_tpc_q = m.out(
        "packet_restart_tpc_q", clk=clk, rst=rst, width=tpc_width, init=c(0, width=tpc_width), en=c(1, width=1)
    )
    packet_stid_q = m.out("packet_stid_q", clk=clk, rst=rst, width=stid_width, init=c(0, width=stid_width), en=c(1, width=1))
    packet_owner_q = m.out("packet_owner_q", clk=clk, rst=rst, width=owner_width, init=c(0, width=owner_width), en=c(1, width=1))

    identity_present = recovery_stid_valid & recovery_owner_valid
    recovery_fire = recovery_valid & identity_present & ((~packet_valid_q.out()) | packet_ready)
    packet_fire = packet_valid_q.out() & packet_ready

    packet_valid_next = packet_valid_q.out()
    packet_valid_next = packet_fire._select_internal(c(0, width=1), packet_valid_next)
    packet_valid_next = recovery_fire._select_internal(c(1, width=1), packet_valid_next)

    packet_valid_q.set(packet_valid_next)
    packet_bid_q.set(recovery_bid, when=recovery_fire)
    packet_restart_tpc_q.set(recovery_restart_tpc, when=recovery_fire)
    packet_stid_q.set(recovery_stid, when=recovery_fire)
    packet_owner_q.set(recovery_owner, when=recovery_fire)

    m.output("recovery_ready", identity_present & ((~packet_valid_q.out()) | packet_ready))
    m.output("recovery_fire", recovery_fire)
    m.output("packet_valid", packet_valid_q.out())
    m.output("packet_fire", packet_fire)
    m.output("packet_bid", packet_bid_q.out())
    m.output("packet_restart_tpc", packet_restart_tpc_q.out())
    m.output("packet_stid", packet_stid_q.out())
    m.output("packet_owner", packet_owner_q.out())
