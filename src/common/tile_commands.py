from __future__ import annotations

IOT_SRC0_SHIFT = 0
IOT_SRC1_SHIFT = 6
IOT_DST_SHIFT = 12
IOT_LAST_SHIFT = 15
IOT_FLAGS_SHIFT = 16
IOT_PE_MASK_SHIFT = 20
IOT_SIZE_SHIFT = 25
IOT_HAS_SIZE_SHIFT = 30
IOT_FLAGS_BY_FUNC = {4: 0, 5: 2, 6: 3}

BIOS_PE_MASK_SHIFT = 8
BIOS_SIZE_SHIFT = 12


def _bounded(name: str, value: int, bits: int) -> int:
    limit = 1 << bits
    if value < 0 or value >= limit:
        raise ValueError(f"{name} must fit {bits} bits: {value}")
    return value


def pack_bios_descriptor(shared_id: int, pe_mask: int, size_code: int) -> int:
    """Pack the authoritative Shared binding transport descriptor."""
    shared_id = _bounded("shared_id", shared_id, 8)
    pe_mask = _bounded("pe_mask", pe_mask, 4)
    size_code = _bounded("size_code", size_code, 3)
    return (
        shared_id
        | (pe_mask << BIOS_PE_MASK_SHIFT)
        | (size_code << BIOS_SIZE_SHIFT)
    )


def pack_biot_descriptor(
    src0: int,
    src1: int,
    dst: int,
    last: int,
    pe_mask: int,
    tsize: int,
    func: int,
) -> int:
    """Pack a Local binding using the runtime Tile I/O descriptor layout."""
    src0 = _bounded("src0", src0, 6)
    src1 = _bounded("src1", src1, 6)
    dst = _bounded("dst", dst, 3)
    last = _bounded("last", last, 1)
    pe_mask = _bounded("pe_mask", pe_mask, 4)
    tsize = _bounded("tsize", tsize, 3)
    try:
        flags = IOT_FLAGS_BY_FUNC[func]
    except KeyError as exc:
        raise ValueError(f"B.IOT function must be 4, 5, or 6: {func}") from exc
    local_size_code = 0 if tsize == 0 else tsize + 2
    return (
        (src0 << IOT_SRC0_SHIFT)
        | (src1 << IOT_SRC1_SHIFT)
        | (dst << IOT_DST_SHIFT)
        | (last << IOT_LAST_SHIFT)
        | (flags << IOT_FLAGS_SHIFT)
        | (pe_mask << IOT_PE_MASK_SHIFT)
        | (local_size_code << IOT_SIZE_SHIFT)
        | ((tsize != 0) << IOT_HAS_SIZE_SHIFT)
    )
