from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple

from .types import RobEntryState, TrapPayload


@dataclass(frozen=True)
class RobAllocUop:
    bid: int
    eob: bool


@dataclass(frozen=True)
class RobCompleteEvent:
    rid: int
    trap: TrapPayload


@dataclass(frozen=True)
class RobRetireEvent:
    rid: int
    bid: int
    eob: bool
    trap: TrapPayload


class RobModel:
    """Cycle-accurate ROB model for scalar uops.

    - in-order retire
    - each uop has a RID and carries BID + EOB (derived upstream)
    """

    def __init__(
        self,
        rob_entries: int = 256,
        alloc_per_cycle: int = 4,
        retire_per_cycle: int = 4,
    ):
        assert rob_entries > 0 and (rob_entries & (rob_entries - 1) == 0)
        self.N = rob_entries
        self.alloc_per_cycle = alloc_per_cycle
        self.retire_per_cycle = retire_per_cycle

        self.head = 0
        self.tail = 0
        self.count = 0

        self.state = [RobEntryState.FREE] * self.N
        self.bid = [0] * self.N
        self.eob = [False] * self.N
        self.trap = [TrapPayload.none()] * self.N

    def _idx(self, rid: int) -> int:
        return rid & (self.N - 1)

    def can_alloc(self) -> bool:
        return self.count < self.N

    def alloc(self, uops: List[Optional[RobAllocUop]]) -> Tuple[List[bool], List[Optional[int]]]:
        assert len(uops) == self.alloc_per_cycle
        ready = [False] * self.alloc_per_cycle
        rid_out: List[Optional[int]] = [None] * self.alloc_per_cycle

        for i, u in enumerate(uops):
            if u is None:
                continue
            if not self.can_alloc():
                break

            rid = self.tail
            idx = self._idx(rid)
            assert self.state[idx] == RobEntryState.FREE

            self.state[idx] = RobEntryState.ALLOC
            self.bid[idx] = u.bid
            self.eob[idx] = bool(u.eob)
            self.trap[idx] = TrapPayload.none()

            self.tail = (self.tail + 1) & (2 * self.N - 1)
            self.count += 1

            ready[i] = True
            rid_out[i] = rid

        return ready, rid_out

    def complete(self, evs: List[RobCompleteEvent]) -> None:
        for ev in evs:
            idx = self._idx(ev.rid)
            if self.state[idx] != RobEntryState.ALLOC:
                continue
            self.state[idx] = RobEntryState.COMPLETE
            if ev.trap.exception:
                self.trap[idx] = ev.trap

    def retire(self) -> List[RobRetireEvent]:
        out: List[RobRetireEvent] = []
        for _ in range(self.retire_per_cycle):
            if self.count == 0:
                break
            idx = self._idx(self.head)
            if self.state[idx] != RobEntryState.COMPLETE:
                break
            trap = self.trap[idx]
            out.append(RobRetireEvent(rid=self.head, bid=self.bid[idx], eob=self.eob[idx], trap=trap))
            if trap.exception:
                break
            self.state[idx] = RobEntryState.FREE
            self.trap[idx] = TrapPayload.none()
            self.head = (self.head + 1) & (2 * self.N - 1)
            self.count -= 1
        return out
