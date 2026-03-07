from __future__ import annotations

from pycircuit import Circuit, const, spec


@const
def backend_frontend_in_spec(m: Circuit):
    _ = m
    return (
        spec.bundle("backend_frontend_in")
        .field("f4_valid", width=1)
        .field("f4_pc", width=64)
        .field("f4_window", width=64)
        .field("f4_checkpoint", width=6)
        .field("f4_pkt_uid", width=64)
        .build()
    )


@const
def backend_mem_if_spec(m: Circuit):
    _ = m
    return (
        spec.bundle("backend_mem_if")
        .field("dmem_rdata", width=64)
        .field("dmem_raddr", width=64)
        .field("dmem_wvalid", width=1)
        .field("dmem_waddr", width=64)
        .field("dmem_wdata", width=64)
        .field("dmem_wstrb", width=8)
        .build()
    )


@const
def backend_block_if_spec(m: Circuit):
    _ = m
    return (
        spec.bundle("backend_block_if")
        .field("bisq_enq_ready", width=1)
        .field("brob_active_allocated", width=1)
        .field("brob_active_ready", width=1)
        .field("brob_active_exception", width=1)
        .field("brob_active_retired", width=1)
        .field("template_uid", width=64)
        .build()
    )


@const
def backend_trace_if_spec(m: Circuit):
    _ = m
    return (
        spec.bundle("backend_trace_if")
        .field("redirect_valid", width=1)
        .field("redirect_pc", width=64)
        .field("replay_cause", width=8)
        .field("active_block_bid", width=64)
        .build()
    )


@const
def top_module_io_spec(m: Circuit):
    _ = m
    return (
        spec.bundle("top_module_io")
        .field("boot_pc", width=64)
        .field("boot_sp", width=64)
        .field("boot_ra", width=64)
        .field("host_wvalid", width=1)
        .field("host_waddr", width=64)
        .field("host_wdata", width=64)
        .field("host_wstrb", width=8)
        .build()
    )


MODULE_SPECS = {
    "backend_frontend_in_spec": backend_frontend_in_spec,
    "backend_mem_if_spec": backend_mem_if_spec,
    "backend_block_if_spec": backend_block_if_spec,
    "backend_trace_if_spec": backend_trace_if_spec,
    "top_module_io_spec": top_module_io_spec,
}
