from __future__ import annotations

from pycircuit import Circuit, module, u


@module(name="LinxCoreMemReadArbStage")
def build_mem_read_arb_stage(
    m: Circuit,
    *,
    issue_w: int = 4,
) -> None:
    """Select a single D-memory read request from up to `issue_w` issue slots.

    This is used for bring-up where:
    - The D-memory has a single read port.
    - We prioritize later slots over earlier slots (stable with the legacy
      `_select_internal` chain behavior).

    Outputs:
    - `fire`: any slot requested a load read.
    - `addr`: selected address (don't-care when `fire=0`).
    """

    if issue_w <= 0:
        raise ValueError("issue_w must be > 0")

    fires = []
    is_loads = []
    addrs = []
    for slot in range(issue_w):
        fires.append(m.input(f"fire{slot}", width=1))
        is_loads.append(m.input(f"is_load{slot}", width=1))
        addrs.append(m.input(f"addr{slot}", width=64))

    # Local const anchors (avoid using the deprecated const helper in this module).
    zero1 = fires[0] & 0

    any_fire = zero1
    # JIT phi base must be a Wire/int/bool (LiteralValue is not allowed).
    sel_addr = addrs[0] & 0
    for slot in range(issue_w):
        take = fires[slot] & is_loads[slot]
        any_fire = any_fire | take
        if take:
            sel_addr = addrs[slot]

    m.output("fire", any_fire)
    m.output("addr", sel_addr)
