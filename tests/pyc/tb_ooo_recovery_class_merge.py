from __future__ import annotations

from pycircuit import Tb, testbench

from bcc.ooo.recovery_class_merge import (
    FAST_REPLAY,
    GLOBAL_FLUSH,
    GLOBAL_REPLAY,
    INNER_FLUSH,
    NUKE_FLUSH,
    PE_REPLAY,
    PE_SCOPED,
    build_linx_bcc_ooo_recovery_class_merge as build,
)


@testbench
def tb(t: Tb) -> None:
    # CROSS_RTL_SCENARIO: blocked-output-stability
    # CROSS_RTL_SCENARIO: same-bid-nuke-cancels-pe
    # CROSS_RTL_SCENARIO: older-report-rejection
    # CROSS_RTL_SCENARIO: replay-cancels-flush
    # CROSS_RTL_SCENARIO: completed-oldest-replay-drop
    # CROSS_RTL_SCENARIO: invalid-scope-rejection
    # CROSS_RTL_SCENARIO: inner-merge-transformation
    # CROSS_RTL_SCENARIO: independent-pe-lanes
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=1)
    t.timeout(96)
    for cyc in range(48):
        t.drive("in_valid", 0, at=cyc)
        t.drive("in_type", NUKE_FLUSH, at=cyc)
        t.drive("in_block_bid", 0, at=cyc)
        t.drive("in_stid", 0, at=cyc)
        t.drive("in_pe", 0, at=cyc)
        t.drive("in_tid", 0, at=cyc)
        t.drive("in_gid", 0, at=cyc)
        t.drive("in_gid_wrap", 0, at=cyc)
        t.drive("in_rid", 0, at=cyc)
        t.drive("in_rid_wrap", 0, at=cyc)
        t.drive("in_lsid", 0, at=cyc)
        t.drive("in_lsid_wrap", 0, at=cyc)
        t.drive("in_exec_engine", 0, at=cyc)
        t.drive("in_fetch_tpc_valid", 1, at=cyc)
        t.drive("in_fetch_tpc", 0, at=cyc)
        t.drive("in_immediate_flush", 0, at=cyc)
        t.drive("oldest_block_complete", 0, at=cyc)
        t.drive("oldest_bid0", 0, at=cyc)
        t.drive("oldest_bid1", 0, at=cyc)
        t.drive("out_ready", 0, at=cyc)

    def request(
        cyc: int,
        typ: int,
        bid: int,
        stid: int,
        pe: int,
        rid: int,
        *,
        tid: int = 0,
        gid: int = 0,
        lsid: int = 0,
        immediate: int = 0,
        tpc: int = 0,
    ) -> None:
        t.drive("in_valid", 1, at=cyc)
        t.drive("in_type", typ, at=cyc)
        t.drive("in_block_bid", bid, at=cyc)
        t.drive("in_stid", stid, at=cyc)
        t.drive("in_pe", pe, at=cyc)
        t.drive("in_tid", tid, at=cyc)
        t.drive("in_gid", gid, at=cyc)
        t.drive("in_rid", rid, at=cyc)
        t.drive("in_lsid", lsid, at=cyc)
        t.drive("in_immediate_flush", immediate, at=cyc)
        t.drive("in_fetch_tpc", tpc, at=cyc)

    # Stage a STID1 global flush into the irrevocable output slot.
    request(0, NUKE_FLUSH, 0x21, 1, 0, 0, tid=7, gid=3, lsid=5, immediate=1, tpc=0x2100)
    t.expect("in_accepted", 1, at=0, phase="pre")
    t.expect("global_flush_pending_mask", 0x2, at=0)
    t.expect("out_valid", 0, at=0)
    t.expect("out_valid", 1, at=1)
    t.expect("out_block_bid", 0x21, at=1)
    t.expect("out_stid", 1, at=1)
    t.expect("out_tid", 7, at=1)
    t.expect("out_gid", 3, at=1)
    t.expect("out_lsid", 5, at=1)
    t.expect("out_immediate_flush", 1, at=1)
    t.expect("out_fetch_tpc", 0x2100, at=1)

    # A different STID can mutate queued class state without changing output.
    request(2, PE_REPLAY, 0x12, 0, 0, 2, tpc=0x1202)
    t.expect("in_accepted", 1, at=2, phase="pre")
    t.expect("pe_pending_mask", 0x1, at=2)
    t.expect("out_block_bid", 0x21, at=2)

    # Same-BID nuke cancels the queued PE replay.
    request(3, NUKE_FLUSH, 0x12, 0, 0, 2, tpc=0x1200)
    t.expect("in_accepted", 1, at=3, phase="pre")
    t.expect("global_flush_pending_mask", 0x1, at=3)
    t.expect("pe_pending_mask", 0, at=3)

    # An equal inner flush loses behind the resident nuke.
    request(4, INNER_FLUSH, 0x12, 0, 0, 2, tpc=0x12F0)
    t.expect("in_accepted", 1, at=4, phase="pre")
    t.expect("in_dropped_by_older", 1, at=4, phase="pre")

    # Fast replay remains independent until class dispatch, where it cancels
    # the queued nuke and follows the older blocked STID1 output.
    request(5, FAST_REPLAY, 0x12, 0, 0, 2, tpc=0x12A0)
    t.expect("in_accepted", 1, at=5, phase="pre")
    t.expect("global_flush_pending_mask", 0x1, at=5)
    t.expect("global_replay_pending_mask", 0x1, at=5)
    t.drive("out_ready", 1, at=6)
    t.expect("out_accepted", 1, at=6, phase="pre")

    t.expect("out_valid", 1, at=6)
    t.expect("out_class", GLOBAL_REPLAY, at=6)
    t.expect("out_type", FAST_REPLAY, at=6)
    t.expect("out_block_bid", 0x12, at=6)
    t.expect("out_stid", 0, at=6)
    t.expect("out_fetch_tpc", 0x12A0, at=6)
    t.expect("global_flush_pending_mask", 0, at=6)
    t.expect("global_replay_pending_mask", 0, at=6)
    t.drive("out_ready", 1, at=7)

    # Completed-oldest global replay is accepted at the input boundary but
    # deliberately leaves no class state.
    t.drive("oldest_block_complete", 0x1, at=8)
    request(8, FAST_REPLAY, 0x13, 0, 0, 1, tpc=0x1300)
    t.expect("in_accepted", 1, at=8, phase="pre")
    t.expect("in_dropped_by_complete", 1, at=8, phase="pre")
    t.expect("pending", 0, at=8)

    # Invalid instantiated scope is backpressured visibly.
    request(9, NUKE_FLUSH, 0x40, 2, 0, 0)
    t.expect("in_ready", 0, at=9, phase="pre")
    t.expect("in_blocked_by_stid", 1, at=9, phase="pre")
    request(10, NUKE_FLUSH, 0x40, 0, 2, 0)
    t.expect("in_ready", 0, at=10, phase="pre")
    t.expect("in_blocked_by_pe", 1, at=10, phase="pre")

    # Stage another blocked STID1 output, then prove model mergeSignal:
    # an older same-PE replay transforms a resident inner flush into one
    # global inner action carrying the older replay identity.
    request(11, NUKE_FLUSH, 0x31, 1, 0, 0, tpc=0x3100)
    t.expect("global_flush_pending_mask", 0x2, at=11)
    t.expect("out_valid", 1, at=12)
    t.expect("out_block_bid", 0x31, at=12)
    request(13, INNER_FLUSH, 0x14, 0, 0, 3, tpc=0x1430)
    t.expect("in_accepted", 1, at=13, phase="pre")
    request(14, PE_REPLAY, 0x14, 0, 0, 2, tid=4, gid=2, lsid=6, immediate=1, tpc=0x1420)
    t.expect("in_accepted", 1, at=14, phase="pre")
    t.expect("in_merged", 1, at=14, phase="pre")
    t.expect("global_flush_pending_mask", 0x1, at=14)
    t.expect("pe_pending_mask", 0, at=14)
    t.drive("out_ready", 1, at=15)

    t.expect("out_valid", 1, at=15)
    t.expect("out_class", GLOBAL_FLUSH, at=15)
    t.expect("out_type", INNER_FLUSH, at=15)
    t.expect("out_block_bid", 0x14, at=15)
    t.expect("out_rid", 2, at=15)
    t.expect("out_tid", 4, at=15)
    t.expect("out_gid", 2, at=15)
    t.expect("out_lsid", 6, at=15)
    t.expect("out_immediate_flush", 1, at=15)
    t.expect("out_fetch_tpc", 0x1420, at=15)

    # Independent PE lanes remain resident and serialize without loss.
    request(16, PE_REPLAY, 0x15, 0, 0, 1, tpc=0x1500)
    request(17, PE_REPLAY, 0x16, 0, 1, 1, tpc=0x1600)
    t.expect("pe_pending_mask", 0x3, at=17)
    t.drive("out_ready", 1, at=18)
    t.expect("out_accepted", 1, at=18, phase="pre")

    t.expect("out_valid", 1, at=18)
    t.expect("out_class", PE_SCOPED, at=18)
    t.expect("out_pe", 0, at=18)
    t.expect("out_block_bid", 0x15, at=18)
    t.drive("out_ready", 1, at=19)

    t.expect("out_valid", 1, at=19)
    t.expect("out_class", PE_SCOPED, at=19)
    t.expect("out_pe", 1, at=19)
    t.expect("out_block_bid", 0x16, at=19)
    t.drive("out_ready", 1, at=20)
    t.expect("pending", 0, at=20)

    t.finish(at=23)
