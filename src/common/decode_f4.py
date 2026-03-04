from __future__ import annotations

from pycircuit import Circuit, Wire, function

from .decode import Decode, DecodeBundle, build_decode_bundle_8b


@function
def decode_f4_bundle(m: Circuit, f4_window: Wire, *, name: str) -> DecodeBundle:
    """Decode wrapper for 64-bit F4 fetch windows.

    This helper instantiates a forced-hierarchy decode bundle to avoid inlining
    the instruction decode tree multiple times into every stage that needs it.
    """
    b = m.instance_auto(build_decode_bundle_8b, name=str(name), module_name="LinxCoreDecodeBundle8B", window=f4_window)
    valid = [b[f"valid{i}"] for i in range(4)]
    off_bytes = [b[f"off_bytes{i}"] for i in range(4)]
    dec = []
    for i in range(4):
        dec.append(
            Decode(
                op=b[f"op{i}"],
                len_bytes=b[f"len_bytes{i}"],
                regdst=b[f"regdst{i}"],
                srcl=b[f"srcl{i}"],
                srcr=b[f"srcr{i}"],
                srcr_type=b[f"srcr_type{i}"],
                shamt=b[f"shamt{i}"],
                srcp=b[f"srcp{i}"],
                imm=b[f"imm{i}"],
            )
        )
    return DecodeBundle(valid=valid, off_bytes=off_bytes, dec=dec, total_len_bytes=b["total_len_bytes"])
