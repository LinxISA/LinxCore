from __future__ import annotations

import unittest


class TestBlockStructRtlSmoke(unittest.TestCase):
    def test_elaborate_modules(self) -> None:
        """Smoke: rtl modules import and can be elaborated.

        Uses repo-local pyCircuit (via PYTHONPATH) when available.
        """

        try:
            from pycircuit import Circuit  # type: ignore
        except Exception as e:  # pragma: no cover
            raise unittest.SkipTest(f"pycircuit not available: {e}")

        from bcc.block_struct.brob_rtl import build_janus_bcc_block_struct_brob
        from bcc.block_struct.rob_rtl import build_janus_bcc_block_struct_rob

        m1 = Circuit("brob_rtl")
        build_janus_bcc_block_struct_brob(m1)

        m2 = Circuit("rob_rtl")
        build_janus_bcc_block_struct_rob(m2)


if __name__ == "__main__":
    unittest.main()
