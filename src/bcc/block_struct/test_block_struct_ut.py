import unittest

from bcc.block_struct import (
    BlockType,
    BrobModel,
    BrobAllocReq,
    BrobCompleteEvent,
    CompletionSource,
    TrapPayload,
    RobModel,
    RobAllocUop,
    RobCompleteEvent,
)


class TestBrob(unittest.TestCase):
    def test_scalar_then_engine_complete(self):
        brob = BrobModel(brob_entries=16, alloc_per_cycle=4, complete_per_cycle=4, retire_per_cycle=4)

        # alloc one engine block
        ready, bids = brob.alloc([BrobAllocReq(BlockType.VEC), None, None, None])
        self.assertTrue(ready[0])
        bid = bids[0]

        # scalar done only => not complete
        brob.complete([BrobCompleteEvent(bid, CompletionSource.SCALAR, TrapPayload.none())])
        self.assertNotEqual(brob.state[bid & 15], 3)  # not COMPLETE

        # engine done => complete
        brob.complete([BrobCompleteEvent(bid, CompletionSource.ENGINE, TrapPayload.none())])
        self.assertEqual(brob.state[bid & 15], 3)  # COMPLETE

        retired = brob.retire()
        self.assertEqual(len(retired), 1)
        self.assertEqual(retired[0].bid, bid)

    def test_flush_rolls_back_tail(self):
        brob = BrobModel(brob_entries=16, alloc_per_cycle=4, complete_per_cycle=4, retire_per_cycle=4)
        _, bids = brob.alloc([BrobAllocReq(BlockType.SCALAR), BrobAllocReq(BlockType.SCALAR), None, None])
        b0, b1 = bids[0], bids[1]
        self.assertIsNotNone(b0)
        self.assertIsNotNone(b1)
        # kill b1 and younger
        brob.flush(b1)
        # b0 still allocated
        self.assertNotEqual(brob.state[b0 & 15], 0)
        # b1 cleared
        self.assertEqual(brob.state[b1 & 15], 0)


class TestRobToBrobGlue(unittest.TestCase):
    def test_rob_eob_retire_triggers_scalar_complete(self):
        brob = BrobModel(brob_entries=16, alloc_per_cycle=4, complete_per_cycle=4, retire_per_cycle=4)
        rob = RobModel(rob_entries=32, alloc_per_cycle=4, retire_per_cycle=4)

        # allocate scalar block
        _, bids = brob.alloc([BrobAllocReq(BlockType.SCALAR), None, None, None])
        bid = bids[0]

        # allocate two uops in ROB, second is EOB
        _, rids = rob.alloc([RobAllocUop(bid=bid, eob=False), RobAllocUop(bid=bid, eob=True), None, None])
        r0, r1 = rids[0], rids[1]

        rob.complete([
            RobCompleteEvent(r0, TrapPayload.none()),
            RobCompleteEvent(r1, TrapPayload.none()),
        ])

        retired = rob.retire()
        # when we see EOB, scalar_done should be sent to BROB
        for ev in retired:
            if ev.eob:
                brob.complete([BrobCompleteEvent(ev.bid, CompletionSource.SCALAR, ev.trap)])

        # scalar block completes immediately (no engine needed)
        self.assertEqual(brob.state[bid & 15], 3)


if __name__ == '__main__':
    unittest.main()
