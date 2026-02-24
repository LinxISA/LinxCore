from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple

from .types import (
    BlockType,
    BrobEntryState,
    CompletionSource,
    TrapPayload,
    blocktype_needs_engine,
)


@dataclass(frozen=True)
class BrobAllocReq:
    blocktype: BlockType


@dataclass(frozen=True)
class BrobCompleteEvent:
    bid: int
    source: CompletionSource
    trap: TrapPayload


@dataclass(frozen=True)
class BrobRetireEvent:
    bid: int
    trap: TrapPayload


class BrobModel:
    """Cycle-accurate BROB reference model.

    Key properties:
    - ring buffer, tail alloc, head retire
    - flush via first_killed_bid => tail rollback
    - complete is two-bit: scalar_done + engine_done
    """

    def __init__(
        self,
        brob_entries: int = 128,
        alloc_per_cycle: int = 4,
        complete_per_cycle: int = 1,
        retire_per_cycle: int = 1,
    ):
        assert brob_entries > 0 and (brob_entries & (brob_entries - 1) == 0)
        self.N = brob_entries
        self.alloc_per_cycle = alloc_per_cycle
        self.complete_per_cycle = complete_per_cycle
        self.retire_per_cycle = retire_per_cycle

        self.head = 0
        self.tail = 0
        self.count = 0

        self.state = [BrobEntryState.FREE] * self.N
        self.blocktype = [BlockType.SCALAR] * self.N
        self.scalar_done = [False] * self.N
        self.engine_done = [False] * self.N
        self.trap = [TrapPayload.none()] * self.N

    def _idx(self, bid: int) -> int:
        return bid & (self.N - 1)

    def can_alloc(self) -> bool:
        return self.count < self.N

    def alloc(self, reqs: List[Optional[BrobAllocReq]]) -> Tuple[List[bool], List[Optional[int]]]:
        """Attempt to allocate up to alloc_per_cycle bids.

        Args:
            reqs: list length == alloc_per_cycle; entry is None or alloc request.

        Returns:
            ready[i]: whether lane i accepted
            bid_out[i]: allocated bid if accepted
        """
        assert len(reqs) == self.alloc_per_cycle
        ready = [False] * self.alloc_per_cycle
        bid_out: List[Optional[int]] = [None] * self.alloc_per_cycle

        for i, r in enumerate(reqs):
            if r is None:
                continue
            if not self.can_alloc():
                break
            bid = self.tail
            idx = self._idx(bid)
            assert self.state[idx] == BrobEntryState.FREE

            self.state[idx] = BrobEntryState.ALLOC
            self.blocktype[idx] = r.blocktype
            self.scalar_done[idx] = False
            self.engine_done[idx] = False
            self.trap[idx] = TrapPayload.none()

            self.tail = (self.tail + 1) & (2 * self.N - 1)  # keep extra wrap bit for debug
            self.count += 1

            ready[i] = True
            bid_out[i] = bid

        return ready, bid_out

    def mark_issued(self, bid: int) -> None:
        idx = self._idx(bid)
        if self.state[idx] in (BrobEntryState.ALLOC, BrobEntryState.ISSUED):
            self.state[idx] = BrobEntryState.ISSUED

    def complete(self, events: List[BrobCompleteEvent]) -> None:
        """Consume up to complete_per_cycle completion events.

        If more are provided, caller is expected to arbitrate/queue.
        """
        for ev in events[: self.complete_per_cycle]:
            idx = self._idx(ev.bid)
            # drop completions for non-live entries
            if self.state[idx] == BrobEntryState.FREE:
                continue

            # merge trap payload (first exception wins)
            if ev.trap.exception and not self.trap[idx].exception:
                self.trap[idx] = ev.trap

            if ev.source == CompletionSource.SCALAR:
                self.scalar_done[idx] = True
            else:
                self.engine_done[idx] = True

            bt = self.blocktype[idx]
            done = self.scalar_done[idx] and (self.engine_done[idx] if blocktype_needs_engine(bt) else True)
            if done:
                self.state[idx] = BrobEntryState.COMPLETE

    def flush(self, first_killed_bid: int) -> None:
        """Flush all bids >= first_killed_bid and roll back tail."""
        # kill entries from first_killed_bid to current tail
        bid = first_killed_bid
        # walk until count becomes consistent; limit to N entries
        killed = 0
        while self.count > 0 and killed < self.N:
            # if bid == tail (wrapped), stop
            if bid == self.tail:
                break
            idx = self._idx(bid)
            self.state[idx] = BrobEntryState.FREE
            self.scalar_done[idx] = False
            self.engine_done[idx] = False
            self.trap[idx] = TrapPayload.none()
            killed += 1
            self.count -= 1
            bid = (bid + 1) & (2 * self.N - 1)

        # rollback tail
        self.tail = first_killed_bid

    def retire(self) -> List[BrobRetireEvent]:
        """Attempt to retire up to retire_per_cycle blocks."""
        out: List[BrobRetireEvent] = []
        for _ in range(self.retire_per_cycle):
            if self.count == 0:
                break
            idx = self._idx(self.head)
            if self.state[idx] != BrobEntryState.COMPLETE:
                break

            # exception is reported at retire of oldest block
            trap = self.trap[idx]
            out.append(BrobRetireEvent(bid=self.head, trap=trap))
            if trap.exception:
                # stop retiring further
                break

            # free entry
            self.state[idx] = BrobEntryState.FREE
            self.scalar_done[idx] = False
            self.engine_done[idx] = False
            self.trap[idx] = TrapPayload.none()

            self.head = (self.head + 1) & (2 * self.N - 1)
            self.count -= 1

        return out
