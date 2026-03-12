from __future__ import annotations

from pycircuit import Circuit, Wire, function

from .decode import Decode, DecodeBundle, build_decode_bundle_8b


@function
def decode_f4_bundle_view(m: Circuit, *, bundle: object) -> DecodeBundle:
    """Wrap a decode-bundle instance view into the shared DecodeBundle shape."""
    b = bundle
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
