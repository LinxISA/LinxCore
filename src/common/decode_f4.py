from __future__ import annotations

from pycircuit import Circuit, Wire

from .decode import DecodeBundle, decode_bundle_8B


def decode_f4_bundle(m: Circuit, f4_window: Wire) -> DecodeBundle:
    """Decode wrapper for 64-bit F4 fetch windows."""
    return decode_bundle_8B(m, f4_window)
