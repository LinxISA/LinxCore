from __future__ import annotations

from pycircuit import Circuit, module

from .modules.trace_export import build_trace_export


@module(name="LinxCoreBackend", value_params={"callframe_size_i": "i64"})
def build_backend(m: Circuit, *, mem_bytes: int = (1 << 20), callframe_size_i=0) -> None:
    # BACKEND_COMPOSITION_ROOT
    # Canonical backend entrypoint. Keep hierarchy explicit by instantiating the
    # backend composition module (do not inline by direct function invocation).
    clk = m.clock("clk")
    rst = m.reset("rst")

    boot_pc = m.input("boot_pc", width=64)
    boot_sp = m.input("boot_sp", width=64)
    boot_ra = m.input("boot_ra", width=64)
    f4_valid_i = m.input("f4_valid_i", width=1)
    f4_pc_i = m.input("f4_pc_i", width=64)
    f4_window_i = m.input("f4_window_i", width=64)
    f4_checkpoint_i = m.input("f4_checkpoint_i", width=6)
    f4_pkt_uid_i = m.input("f4_pkt_uid_i", width=64)
    dmem_rdata_i = m.input("dmem_rdata_i", width=64)
    bisq_enq_ready_i = m.input("bisq_enq_ready_i", width=1)
    brob_active_allocated_i = m.input("brob_active_allocated_i", width=1)
    brob_active_ready_i = m.input("brob_active_ready_i", width=1)
    brob_active_exception_i = m.input("brob_active_exception_i", width=1)
    brob_active_retired_i = m.input("brob_active_retired_i", width=1)
    brob_alloc_ready_i = m.input("brob_alloc_ready_i", width=1)
    brob_alloc_bid_i = m.input("brob_alloc_bid_i", width=64)
    template_uid_i = m.input("template_uid_i", width=64)

    backend = m.instance_auto(
        build_trace_export,
        name="backend_trace_export",
        module_name="LinxCoreBackendCompose",
        params={"mem_bytes": int(mem_bytes)},
        clk=clk,
        rst=rst,
        boot_pc=boot_pc,
        boot_sp=boot_sp,
        boot_ra=boot_ra,
        f4_valid_i=f4_valid_i,
        f4_pc_i=f4_pc_i,
        f4_window_i=f4_window_i,
        f4_checkpoint_i=f4_checkpoint_i,
        f4_pkt_uid_i=f4_pkt_uid_i,
        dmem_rdata_i=dmem_rdata_i,
        bisq_enq_ready_i=bisq_enq_ready_i,
        brob_active_allocated_i=brob_active_allocated_i,
        brob_active_ready_i=brob_active_ready_i,
        brob_active_exception_i=brob_active_exception_i,
        brob_active_retired_i=brob_active_retired_i,
        brob_alloc_ready_i=brob_alloc_ready_i,
        brob_alloc_bid_i=brob_alloc_bid_i,
        template_uid_i=template_uid_i,
        callframe_size_i=callframe_size_i,
    )
    for name, connector in backend.items():
        m.output(str(name), connector.read())


__all__ = ["build_backend"]
