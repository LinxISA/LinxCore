from __future__ import annotations

from pycircuit import Circuit, const, ct, spec


@const
def frontend_config(m: Circuit, *, ibuf_depth: int = 8, ftq_depth: int = 16):
    _ = m
    param_spec = (
        spec.params()
        .add("ibuf_depth", default=8, min_value=1)
        .add("ftq_depth", default=16, min_value=1)
    )
    return param_spec.build(
        {
            "ibuf_depth": int(ct.pow2_ceil(max(1, int(ibuf_depth)))),
            "ftq_depth": int(ct.pow2_ceil(max(1, int(ftq_depth)))),
        }
    )


@const
def icache_config(
    m: Circuit,
    *,
    ic_sets: int,
    ic_ways: int,
    ic_line_bytes: int,
    ifetch_bundle_bits: int,
    ifetch_bundle_bytes: int | None,
    ic_miss_outstanding: int,
    ic_enable: int,
):
    _ = m
    bundle_bits = int(ifetch_bundle_bits)
    if ifetch_bundle_bytes is not None:
        alias_bits = int(ifetch_bundle_bytes) * 8
        if bundle_bits in {128, 1024, alias_bits}:
            bundle_bits = alias_bits
        elif alias_bits != bundle_bits:
            raise ValueError("ifetch_bundle_bytes alias mismatches ifetch_bundle_bits")

    line_b = int(ct.pow2_ceil(max(8, int(ic_line_bytes))))
    if bundle_bits <= 0 or bundle_bits % 8 != 0:
        raise ValueError("ifetch_bundle_bits must be a positive multiple of 8")
    # Milestone contract: fetch bundle may span up to two cache lines.
    if bundle_bits > (line_b * 16):
        raise ValueError("ifetch bundle width cannot exceed two cache lines")

    param_spec = (
        spec.params()
        .add("ic_sets", default=32, min_value=1)
        .add("ic_ways", default=4, min_value=1)
        .add("ic_line_bytes", default=64, min_value=8)
        .add("ifetch_bundle_bits", default=1024, min_value=8)
        .add("ifetch_bundle_bytes", default=128, min_value=1)
        .add("ic_miss_outstanding", default=1, min_value=1)
        .add("ic_enable", default=1, min_value=0, max_value=1)
    )
    return param_spec.build(
        {
            "ic_sets": int(ct.pow2_ceil(max(1, int(ic_sets)))),
            "ic_ways": int(max(1, int(ic_ways))),
            "ic_line_bytes": line_b,
            "ifetch_bundle_bits": bundle_bits,
            "ifetch_bundle_bytes": int(bundle_bits // 8),
            "ic_miss_outstanding": int(max(1, int(ic_miss_outstanding))),
            "ic_enable": int(1 if int(ic_enable) != 0 else 0),
        }
    )


@const
def top_config(
    m: Circuit,
    *,
    mem_bytes: int,
    ic_sets: int,
    ic_ways: int,
    ic_line_bytes: int,
    ifetch_bundle_bits: int,
    ifetch_bundle_bytes: int | None,
    ib_depth: int,
    ic_miss_outstanding: int,
    ic_enable: int,
):
    _ = m
    ic_cfg = icache_config(
        m,
        ic_sets=ic_sets,
        ic_ways=ic_ways,
        ic_line_bytes=ic_line_bytes,
        ifetch_bundle_bits=ifetch_bundle_bits,
        ifetch_bundle_bytes=ifetch_bundle_bytes,
        ic_miss_outstanding=ic_miss_outstanding,
        ic_enable=ic_enable,
    )
    param_spec = (
        spec.params()
        # Default bring-up memory must cover typical Linx workloads with large
        # zero-fill regions (e.g. CoreMark/Dhrystone ship with ~16MiB .bss).
        .add("mem_bytes", default=1 << 26, min_value=1)
        .add("ic_sets", default=32, min_value=1)
        .add("ic_ways", default=4, min_value=1)
        .add("ic_line_bytes", default=64, min_value=8)
        .add("ifetch_bundle_bits", default=1024, min_value=8)
        .add("ifetch_bundle_bytes", default=128, min_value=1)
        .add("ib_depth", default=8, min_value=1)
        .add("ic_miss_outstanding", default=1, min_value=1)
        .add("ic_enable", default=1, min_value=0, max_value=1)
    )
    return param_spec.build(
        {
            "mem_bytes": int(ct.pow2_ceil(max(1, int(mem_bytes)))),
            "ic_sets": int(ic_cfg["ic_sets"]),
            "ic_ways": int(ic_cfg["ic_ways"]),
            "ic_line_bytes": int(ic_cfg["ic_line_bytes"]),
            "ifetch_bundle_bits": int(ic_cfg["ifetch_bundle_bits"]),
            "ifetch_bundle_bytes": int(ic_cfg["ifetch_bundle_bytes"]),
            "ib_depth": int(ct.pow2_ceil(max(1, int(ib_depth)))),
            "ic_miss_outstanding": int(ic_cfg["ic_miss_outstanding"]),
            "ic_enable": int(ic_cfg["ic_enable"]),
        }
    )
