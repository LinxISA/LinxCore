from __future__ import annotations

from pycircuit import Circuit, module


def build_fixed_pipe_inline(
    m: Circuit,
    *,
    prefix: str,
    clk,
    rst,
    latency: int = 1,
    rob_w: int = 8,
    ptag_w: int = 7,
    in_valid,
    in_rob,
    in_dst_valid,
    in_dst_ptag,
) -> dict[str, object]:
    """Inline fixed-latency pipe builder (no submodule instances).

    This is used as a workaround for very-large-module C++ emission where
    submodule shared_ptr initialization can be unreliable. The behavior matches
    `StandaloneOexFixedPipe` while flattening the registers into the parent
    module.
    """
    c = m.const

    lat = max(1, int(latency))
    rob_w_i = max(1, int(rob_w))
    ptag_w_i = max(1, int(ptag_w))

    v = []
    rob = []
    dv = []
    dt = []
    for i in range(lat):
        v.append(m.out(f"{prefix}__v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        rob.append(
            m.out(
                f"{prefix}__rob{i}", clk=clk, rst=rst, width=rob_w_i, init=c(0, width=rob_w_i), en=c(1, width=1)
            )
        )
        dv.append(m.out(f"{prefix}__dv{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        dt.append(
            m.out(
                f"{prefix}__dt{i}", clk=clk, rst=rst, width=ptag_w_i, init=c(0, width=ptag_w_i), en=c(1, width=1)
            )
        )

    # Stage0 capture.
    v[0].set(in_valid)
    rob[0].set(in_rob, when=in_valid)
    dv[0].set(in_dst_valid, when=in_valid)
    dt[0].set(in_dst_ptag, when=in_valid)

    # Shift pipeline.
    for i in range(1, lat):
        v[i].set(v[i - 1].out())
        rob[i].set(rob[i - 1].out(), when=v[i - 1].out())
        dv[i].set(dv[i - 1].out(), when=v[i - 1].out())
        dt[i].set(dt[i - 1].out(), when=v[i - 1].out())

    return {
        "out_valid": v[-1].out(),
        "out_rob": rob[-1].out(),
        "out_dst_valid": dv[-1].out(),
        "out_dst_ptag": dt[-1].out(),
    }


@module(name="StandaloneOexFixedPipe")
def build_fixed_pipe(
    m: Circuit,
    *,
    latency: int = 1,
    rob_w: int = 8,
    ptag_w: int = 7,
) -> None:
    """A simple fully-pipelined fixed-latency completion pipe.

    The pipe accepts 1 uop/cycle and emits 1 completion event/cycle after a
    fixed number of cycles. This is a timing model only; payload values are
    carried elsewhere (ROB oracle metadata path).
    """
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    lat = max(1, int(latency))
    rob_w_i = max(1, int(rob_w))
    ptag_w_i = max(1, int(ptag_w))

    in_valid = m.input("in_valid", width=1)
    in_rob = m.input("in_rob", width=rob_w_i)
    in_dst_valid = m.input("in_dst_valid", width=1)
    in_dst_ptag = m.input("in_dst_ptag", width=ptag_w_i)

    v = []
    rob = []
    dv = []
    dt = []
    for i in range(lat):
        v.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        rob.append(m.out(f"rob{i}", clk=clk, rst=rst, width=rob_w_i, init=c(0, width=rob_w_i), en=c(1, width=1)))
        dv.append(m.out(f"dv{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        dt.append(m.out(f"dt{i}", clk=clk, rst=rst, width=ptag_w_i, init=c(0, width=ptag_w_i), en=c(1, width=1)))

    # Stage0 capture.
    v[0].set(in_valid)
    rob[0].set(in_rob, when=in_valid)
    dv[0].set(in_dst_valid, when=in_valid)
    dt[0].set(in_dst_ptag, when=in_valid)

    # Shift pipeline.
    for i in range(1, lat):
        v[i].set(v[i - 1].out())
        rob[i].set(rob[i - 1].out(), when=v[i - 1].out())
        dv[i].set(dv[i - 1].out(), when=v[i - 1].out())
        dt[i].set(dt[i - 1].out(), when=v[i - 1].out())

    m.output("out_valid", v[-1].out())
    m.output("out_rob", rob[-1].out())
    m.output("out_dst_valid", dv[-1].out())
    m.output("out_dst_ptag", dt[-1].out())
