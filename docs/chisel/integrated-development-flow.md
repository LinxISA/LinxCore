# LinxCore Integrated Development Flow

Date: 2026-07-07

## Purpose

This is the short launch surface for LinxCore Chisel development. Use it before
opening the long packet ledger in `docs/chisel/agent-loop.md`.

The goal is to use the existing Linx toolchain stack as a staged proof system:
ISA and model sources define intent, the compiler produces real workload ELFs,
QEMU proves architectural row streams, Chisel generated RTL proves DUT behavior,
LinxCoreModel proves model convergence, and the superproject gates prove that
the change still works across repos.

## Current Handoff

Latest packet: R624 adds
`tools/chisel/scan_replay_liq_activation_artifacts.py`, a cheap generated
artifact scanner for replay-LIQ activation coverage. The current local report
at
`generated/r624-replay-liq-activation-artifact-scan/report/replay_liq_activation_artifact_scan.json`
scans 34 sideband artifacts, finds 17 activation-positive artifacts, and
classifies all 17 as focused/synthetic with `coremark_positive_count=0`. This
is not new generated-RTL proof; it is a pre-Verilator triage surface that
confirms the current evidence gap remains natural workload selection. The next
CoreMark/natural attempt should use this scanner after each run and promote
only a run whose artifact class is CoreMark or natural and whose eligible-store,
ResolveQ, MDB, LIQ, replay-output, and W2-promotion counters are all nonzero.

R623 adds
`tools/chisel/build_replay_liq_eligible_store_proof_report.py` and reruns the
focused replay fixture at current head with the R616 selector-origin preset.
The generated-RTL/QEMU gate at
`generated/r623-replay-eligible-store-focused-xcheck` passes with 18 compared
rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. Its sideband counters now
meet the R622 next-probe contract on a focused fixture:
`load_lookup_execute_with_eligible_store=18`,
`load_lookup_execute_with_wait_store=12`, `resident_store_eligible=18`,
`resident_store_wait_store_valid=12`, `resolve_queue_push_accepted=8`,
`resolve_queue_valid=66`, `mdb_conflict_valid=6`,
`mdb_fanout_record_valid=6`, `wait_replay_capture_accepted=12`,
`liq_alloc_accepted=6`, `replay_queue_out_fire=6`,
`lret_w2_slot_accepted=6`, and `w2_promotion_live=5`. The report at
`generated/r623-replay-liq-eligible-store-proof-report/report/replay_liq_eligible_store_proof_report.json`
therefore proves current-head focused-fixture replay-LIQ activation through
resident-store overlap, ResolveQ, MDB, LIQ, replay output, and W2 promotion.
It does not convert the R621/R622 CoreMark prefix into natural replay-LIQ
replacement proof; the next CoreMark/natural workload packet still needs the
same activation counters nonzero in that workload.

R622 adds `tools/chisel/build_replay_liq_activation_gap_report.py`, which
classifies why the R621 candidate-present generated-RTL prefix still does not
activate replay-LIQ. The report at
`generated/r622-replay-liq-activation-gap-report/report/replay_liq_activation_gap_report.json`
confirms the memory path is active (`load_lookup_valid=180`,
`store_stq_resident=512`, store dequeue counters nonzero), but no load lookup
overlaps an eligible resident store (`resident_store_eligible=0`,
`load_lookup_execute_with_eligible_store=0`,
`load_lookup_execute_with_wait_store=0`). Consequently `LoadResolveQueue` stays
empty (`resolve_queue_push_accepted=0`, `resolve_queue_valid=0`), MDB sees
store probes only (`mdb_conflict_store_valid=272`,
`mdb_conflict_store_without_resolve_queue_valid=272`,
`mdb_conflict_valid=0`), and replay-LIQ never allocates
(`wait_replay_capture_accepted=0`, `liq_alloc_accepted=0`). The next proof
surface must find or construct `load_lookup_execute_with_eligible_store > 0`
before spending more CoreMark Verilator time on replay-LIQ replacement proof.

R621 finds a non-skipped CoreMark command shape for the R617
candidate and adds
`tools/chisel/build_replay_liq_selector_unskipped_prefix_report.py` to preserve
the result as machine-checkable evidence. The unskipped QEMU-only command
captures 1721 raw rows and reduces 1590 preview rows; the R617 top candidate is
present at reduced rows `1585 -> 1589`, with store PC `0x4000d7e6`, load PC
`0x4000d7f2`, address `0x4ffefb68`, and size 8. The matching generated-RTL/QEMU
CoreMark run at `generated/r621-coremark-unskipped-1721-rtl-xcheck` passes with
1169 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows, and its
preview also contains the target pair. Sideband counters still classify the run
as zero-natural-replay: `wait_replay_capture_accepted=0`,
`replay_queue_out_fire=0`, `liq_alloc_accepted=0`,
`lret_w2_slot_accepted=0`, `w2_promotion_live=0`, selector-from-promotion and
selector-from-probe counters are zero, and MDB fanout/record counters are zero.
Therefore R621 is a generated-RTL no-regression prefix with the candidate
present, not natural replay-LIQ replacement proof.

R620 runs the safe R619 preflights and adds
`tools/chisel/build_replay_liq_selector_preflight_report.py` to preserve the
result as machine-checkable evidence. The raw-window QEMU-only command
`--qemu-skip-rows 1715 --capture-rows 6 --expect-store-pcs 0x4000d7e6
--expect-load-pcs 0x4000d7f2` passes: it captures 6 raw rows, reduces 5 preview
rows, and sees the expected store/load pair at address `0x4ffefb68`. The
PC-filter command for `0x4000d7e6..0x4000d7f3` captures zero rows in the fresh
bounded run, so it is not a valid generated-RTL launch shape. The generated
report
`generated/r620-replay-liq-selector-preflight-report/report/replay_liq_selector_preflight_report.json`
therefore keeps `generated_rtl.status = "blocked"`.

R619 adds
`tools/chisel/plan_replay_liq_selector_probe.py`, a safe probe-plan generator
that consumes the R618 context pack and emits only QEMU-only preflight commands
for the R617 candidate. The generated plan at
`generated/r619-replay-liq-selector-probe-plan/report/replay_liq_selector_probe_plan.json`
contains a raw-window QEMU-only preflight for skip 1715/capture 6 with expected
store/load PCs, a PC-filter QEMU-only preflight for `0x4000d7e6..0x4000d7f3`,
and an explicit `generated_rtl: blocked` entry. This is intentional:
`--qemu-skip-rows` remains QEMU-only because the reduced Verilator top cannot
reconstruct skipped architectural state, and the known PC-filter preflight can
hit an earlier dynamic occurrence without the expected load. Future agents
must first produce a passing QEMU-only expected-memory-PC preflight for the
exact generated-RTL command shape before spending Verilator time.

R618 adds
`tools/chisel/build_replay_liq_selector_context_pack.py`, a current
replay-LIQ selector context-pack builder/validator. Its generated manifest at
`generated/r618-replay-liq-selector-context-pack/report/replay_liq_selector_context_pack.json`
keeps three evidence classes separate: R611 CoreMark generated-RTL/QEMU
no-regression with zero natural replay-LIQ activity, R616 focused replay
fixture proof with positive selector-origin counters, and the R617 QEMU-only
raw-window candidate hint. The tool validates the R611 manifest
(`3369` compared rows, zero mismatches), all required CoreMark replay-LIQ/MDB
counters at zero, the R616 manifest (`18` compared rows, zero mismatches), all
required positive selector-origin counters nonzero with probe/partial/invalid
selector counters zero, and a positive QEMU candidate hint with raw skip 1715
and capture 6. Its claim boundary is part of the gate: CoreMark is
zero-natural-replay no-regression, the focused fixture is positive
selector-origin proof, and the QEMU row window is an address-cluster hint only,
not DUT proof.

R617 extends the replay-LIQ CoreMark candidate locator so reduced-preview
candidate reports can be annotated with matching raw QEMU row windows via
`--raw-input`. Candidate reports now include `row_space`,
`probe_hint.pc_filter`, expected memory-PC preflight args, and, when raw mapping
is available, a `raw_dynamic_window` with absolute `--qemu-skip-rows` and
`--capture-rows` arguments. The R612 top reduced-preview candidate
(`0x4000d7e6 -> 0x4000d7f2`, address `0x4ffefb68`) maps to raw skip 1715 and
capture 6. A QEMU-only scanner gate at
`generated/r617-coremark-qemu-candidate-hint-scan` reproduces that raw window
with 2 memory events, 1 store, 1 load, and 1 candidate. A separate PC-filter
preflight on the same PC range proves the caveat: the first dynamic PC-range
occurrence has no load, so agents must not spend generated-RTL time on a
PC-filtered CoreMark probe until a QEMU-only expected-PC preflight passes.

R616 adds a named replay-LIQ sideband validator preset for the R610 positive
selector-origin proof and wires it through the generated-RTL and QEMU/ELF
wrappers as `FETCH_REPLAY_LIQ_REQUIRE_PRESET`. The fresh focused fixture
`generated/r616-replay-suppress-preset-xcheck` uses
`replay-ldi-sdi-ldi-sdi-ldi-ldi-loop` with promoted retained physical-bundle
suppress selection enabled. It passes 18 compared rows with zero mismatches,
and the preset requires the full positive chain: nonzero wait replay capture,
replay queue fire, LIQ allocation, LRET W2 acceptance, promotion-live,
selector-from-promotion, boundary capture, ownership, live-mask, and clear-proof
counters, while requiring the probe/partial/invalid selector counters to stay
zero. Use this preset for focused replay-fixture proof instead of copying long
counter lists by hand.

R615 extends
`tools/chisel/scan_replay_liq_qemu_intervals.py` with inclusive
`--skip-range START:STOP:STEP` generation, duplicate skip removal, aggregate
load/candidate summary fields, and flushed interval progress output. The R615
QEMU-only sweep samples skip offsets 524,288, 1,048,576, 1,572,864, and
2,097,152 with 256 raw rows each. Every interval completes its bounded trace,
times out only at the wrapper process boundary, and reports 18 memory events,
all stores, zero loads, and zero candidates. Together with R612-R614, the
sampled direct-boot CoreMark windows are not yielding natural replay-LIQ load
clusters. Do not spend Verilator time on these skipped windows as replacement
proof. Unless a future owner chooses a much broader QEMU-only sweep, return to
focused replay fixtures for positive retained physical-bundle evidence.

The next Chisel packet should start from the R550 replay-return evidence, not
from another broad CoreMark scan. The reduced frontend/rename/scalar
execute/ROB/block-marker/store/STQ/SCB path is mature enough for the current
reduced top, and the generated-RTL/QEMU comparator infrastructure is producing
usable manifests. The active blocker is narrower: replay-LIQ source-returned
rows now publish LRET payloads and drain the FIFO, and the post-FIFO
`LoadReplayReturnIexDataCandidate` now reaches model-equivalent
`IEX::setMemData` admission. R547 holds ROB deallocation for outstanding
replay-LIQ load RIDs from LIQ allocation until LRET FIFO drain, so the replay
fixture records `lret_iex_data_rob_row_valid=3`,
`lret_iex_data_set_mem_data_valid=3`,
`lret_iex_data_rob_row_blocked_by_free=0`, and
`lret_shadow_free_after_prior_commit=0`. R548 enables the E4 residency slot to
advance into W1 only when W1 is empty, so the replay fixture now records
`lret_residency_advance_valid=2`, `lret_w1_slot_accepted=2`,
`lret_w2_slot_accepted=1`, `lret_w2_slot_occupied=74`, and
`w2_atomic_evidence_valid=75`. The next owner is no longer ROB row-status
lifetime, setMemData admission, IEX insert, residency write, or W2 slot
evidence; it is W2 atomic request promotion, because
`w2_atomic_request_active=0`, `w2_atomic_blocked_by_request_disabled=111`,
`w2_side_effect_ready=0`, `w2_row_fill_candidate_valid=0`, and
`w2_lifecycle_ready=0`. R549 splits the aggregate request-disabled counter:
`w2_atomic_blocked_by_mode_disabled=0`, `w2_atomic_blocked_by_policy=111`,
`w2_atomic_blocked_by_no_side_effect_sink=74`,
`w2_atomic_blocked_by_no_evidence=36`, and the clear/row-fill/lifecycle
policy blockers remain zero. The next owner is W2 side-effect sink readiness
and live enable for the resident returned-load slot, before row-fill or
lifecycle policy promotion. R550 changes the atomic prereq snapshot to sample
pre-request sink capacity from the existing W2 resolve/writeback/wakeup
`*Armed` signals, while actual side-effect mutation remains gated by
`LoadReplayReturnPipeW2SideEffectLiveControl`. The replay-loop fixture still
passes with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The v21 sideband report records the ordered blocker movement:
`w2_atomic_blocked_by_no_side_effect_sink=7`,
`w2_atomic_blocked_by_no_clear_commit=67`,
`w2_atomic_blocked_by_no_row_fill_candidate=0`,
`w2_atomic_blocked_by_no_lifecycle_row=0`, `w2_clear_intent=0`,
`w2_clear_commit_ready=0`, `w2_side_effect_fire_complete=0`, and
`w2_atomic_request_active=0`. R551 keeps the live clear-commit guard as the
post-fire coherence diagnostic, but feeds the atomic prerequisite snapshot with
pre-request clear/ROB capacity: side-effect sink capacity, a valid resident
slot RID, and an idle replay ROB-complete sink. The replay-loop fixture still
passes with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The v21 sideband report records the ordered blocker movement:
`w2_atomic_blocked_by_no_clear_commit=0`,
`w2_atomic_blocked_by_no_row_fill_candidate=67`,
`w2_atomic_blocked_by_no_side_effect_sink=7`,
`w2_atomic_blocked_by_no_lifecycle_row=0`, `w2_clear_intent=0`,
`w2_clear_commit_ready=0`, `w2_row_fill_candidate_valid=0`,
`w2_side_effect_fire_complete=0`, and `w2_atomic_request_active=0`. The next
owner is the pre-request row-fill candidate path, not clear-commit capacity,
side-effect sink capacity, lifecycle clear, or generic W2 evidence. R552 adds
sideband counters for the commit-row trace-source and candidate prerequisites.
The replay-loop fixture still passes with 9 compared rows, zero mismatches, and
zero QEMU/DUT CBSTOP rows. The new counters show `lret_w2_slot_source_trace_valid=74`,
`w2_commit_row_trace_source_rob_lookup_row_valid=0`,
`w2_commit_row_trace_source_rob_lookup_instruction_valid=0`,
`w2_commit_row_trace_source_blocked_by_no_metadata=74`,
`w2_commit_row_trace_source_blocked_by_no_source_trace=0`,
`w2_commit_row_candidate_blocked_by_no_metadata=74`, and
`w2_commit_row_candidate_blocked_by_no_source_trace=0`. R553 drives the
read-only ROB commit-trace lookup from the LRET drain RID, latches the
instruction raw/length provider keyed by RID, and feeds that metadata when the
same returned load reaches W2. The replay-loop fixture still passes with 9
compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. The sideband
report records `w2_commit_row_trace_source_rob_lookup_instruction_valid=3`,
`w2_commit_row_trace_source_instruction_ready=33`,
`w2_commit_row_trace_source_source_ready=33`,
`w2_commit_row_fill_candidate=33`, `w2_row_fill_candidate_valid=33`,
`w2_row_fill_prerequisites_ready=0`, and `w2_lifecycle_ready=0`. The next owner
is row-fill prerequisite/lifecycle readiness, not ROB instruction metadata,
source-trace provenance, size/destination shape, clear capacity, or side-effect
sink capacity. R554 adds sideband splits for the existing row-fill and
lifecycle blocker outputs. The replay-loop fixture still passes with 9 compared
rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. The split counters show
`w2_lifecycle_candidate_valid=74`, `w2_lifecycle_slot_identity_valid=74`,
`w2_lifecycle_resolved_row_match=0`,
`w2_lifecycle_blocked_by_no_resolved_row=74`,
`w2_lifecycle_blocked_by_multiple_resolved_rows=0`,
`w2_row_fill_blocked_by_request_disabled=33`, and
`w2_row_fill_blocked_by_no_side_effect_commit=33`. The next owner is the LIQ
resolved-row lifecycle match for the returned W2 load, not identity validity,
duplicate matching, row-fill candidate formation, or sideband schema. R555
keeps return-complete replay rows resident through the W2 lifecycle match and
retires the corresponding ResolveQ row on accepted lifecycle clear. The
replay-loop fixture passes with 9 compared rows, zero mismatches, and zero
QEMU/DUT CBSTOP rows. Its sideband report records
`w2_lifecycle_resolved_row_match=6`, `w2_lifecycle_row_clear_ready=6`,
`w2_lifecycle_ready=3`, `w2_lifecycle_blocked_by_no_resolved_row=0`,
`w2_row_fill_enable=3`, and `w2_atomic_request_active=3`. R556 adds harness
and validator coverage for the W2 promotion/refill/slot-replace/advance
owners and reruns the same replay-loop fixture. The comparator still passes
with 9 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. The
new sideband counters prove the current fixture reaches live side-effect
completion, clear intent, row-fill, lifecycle, promotion, live clear,
same-cycle refill readiness, and future-advance selection:
`w2_side_effect_fire_complete=3`, `w2_clear_intent=3`,
`w2_clear_commit_ready=3`, `w2_promotion_live=3`,
`w2_promotion_live_clear=3`, `w2_promotion_advance_live=3`,
`w2_refill_ready_same_cycle_ready=3`, and `w2_advance_enable=111`.
Invalid promotion/refill/advance evidence stays zero. The same fixture does
not yet exercise a same-cycle W2 storage replacement because
`w2_slot_replace_same_cycle_eligible=0` and
`w2_slot_replace_same_cycle_ready=0`, while empty/future write acceptance does
fire. The next owner is a narrow same-cycle W2 slot replacement proof, likely
by constructing or selecting a replay-return sequence with a valid W1 write
candidate while W2 live clear fires, not replay-LIQ row identity, ResolveQ
drain, side-effect commit, or promotion-control observability. R557 adds
overlap counters that combine W1 advance-candidate, W2 occupancy, clear intent,
live clear, and advance-valid evidence in the generated-RTL harness. The same
fixture still passes with 9 compared rows and zero mismatches, and it proves
the replacement gap is stimulus phasing: `w2_slot_replace_live_clear_without_w1_candidate=3`,
while `w2_slot_replace_overlap_candidate_occupied=0`,
`w2_slot_replace_overlap_candidate_live_clear=0`,
`w2_slot_replace_same_cycle_eligible=0`, and
`w2_advance_replace_on_clear=0`. The next packet should add or select a
denser replay-return fixture that keeps W1 occupied while W2 live clear fires
before changing W2 storage or broadening to CoreMark. R558 adds the
`replay-ldi-sdi-ldi-ldi-loop` fixture, which appends a second younger load to
the existing replay loop. The fixture has stable QEMU shape for two and three
loop bodies, and the three-loop generated-RTL/QEMU gate passes with 12 compared
rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. It increases replay
traffic (`wait_replay_capture_accepted=6`, `replay_queue_out_fire=3`,
`lret_w1_slot_accepted=3`, `lret_w2_slot_accepted=3`,
`lret_w2_slot_occupied=8`) but still does not create W1/W2 overlap:
`w2_slot_replace_live_clear_without_w1_candidate=3`,
`w2_slot_replace_overlap_candidate_occupied=0`,
`w2_slot_replace_overlap_candidate_live_clear=0`,
`w2_slot_replace_same_cycle_eligible=0`, and
`w2_advance_replace_on_clear=0`. R559 adds that repeated dependency-chain
fixture as `replay-ldi-sdi-ldi-sdi-ldi-loop`. QEMU-only gates prove stable
two-loop and three-loop load/store PC shape, and the three-loop generated-RTL/QEMU gate
passes with 15 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows.
The fixture doubles replay-return/store pressure relative to R558
(`wait_replay_capture_accepted=12`, `replay_queue_out_fire=6`,
`liq_alloc_accepted=6`, `lret_w1_slot_accepted=6`,
`lret_w2_slot_accepted=6`, `lret_w2_slot_occupied=14`) and preserves W2
side-effect/lifecycle/promotion progress (`w2_side_effect_fire_complete=6`,
`w2_clear_intent=6`, `w2_row_fill_enable=6`,
`w2_lifecycle_resolved_row_match=14`, `w2_promotion_live=6`,
`w2_refill_ready_same_cycle_ready=6`). It still does not create the required
W1/W2 overlap: `w2_slot_replace_live_clear_without_w1_candidate=6`,
`w2_slot_replace_overlap_candidate_occupied=0`,
`w2_slot_replace_overlap_candidate_live_clear=0`,
`w2_slot_replace_same_cycle_eligible=0`, and
`w2_advance_replace_on_clear=0`. The next owner remains fixture/stimulus, now
biased toward constructing multiple outstanding returned loads or replay-return
phasing that presents a W1 write candidate in the same cycle as W2 live clear,
not W2 storage. R560 adds `replay-ldi-sdi-ldi-ldi-ldi-ldi-loop`, a
burst-after-dependency fixture with one store-dependent load followed by three
more consecutive younger loads. Two-loop and three-loop QEMU-only shape gates
pass, and the three-loop generated-RTL/QEMU gate passes with 18 compared rows,
zero mismatches, and zero QEMU/DUT CBSTOP rows. The burst increases resident
W2 occupancy (`lret_w2_slot_occupied=10`) but still produces only three
accepted replay returns and no replacement overlap:
`w2_slot_replace_live_clear_without_w1_candidate=3`,
`w2_slot_replace_overlap_candidate_occupied=0`,
`w2_slot_replace_overlap_candidate_live_clear=0`,
`w2_slot_replace_same_cycle_eligible=0`, and
`w2_advance_replace_on_clear=0`. The next fixture owner should therefore
target more than one replay-return allocation per loop body or explicit
return-pipe delay phasing; adding younger loads after a single learned store
dependency is not sufficient evidence for W2 storage changes.
R561 adds phase-distance sideband counters for W1-candidate one cycle before
clear/live-clear and clear/live-clear one cycle before W1-candidate. The R560
burst fixture still passes with 18 compared rows and zero mismatches, and all
new phase counters are zero:
`w2_slot_replace_w1_candidate_cycle_before_clear_intent=0`,
`w2_slot_replace_w1_candidate_cycle_before_live_clear=0`,
`w2_slot_replace_clear_intent_cycle_before_w1_candidate=0`, and
`w2_slot_replace_live_clear_cycle_before_w1_candidate=0`. The miss is not a
one-cycle offset in the current fixture family. R562 widens that sideband to
2/3/4/5+ cycle buckets and reruns the same burst fixture with 18 compared rows,
zero mismatches, and zero QEMU/DUT CBSTOP rows. It records
`w2_slot_replace_live_clear_after_w1_candidate_gap2=2` and
`w2_slot_replace_live_clear_after_w1_candidate_gap5_plus=1`, while same-cycle,
one-cycle, gap3, gap4, and reverse-gap buckets remain zero. The next owner
should therefore target a return-pipe delay or injected candidate-retention hook
that moves those W1 candidates forward by two cycles or holds a later candidate
until W2 live clear; W2 storage remains blocked on nonzero overlap evidence.
R563 exposes W1/W2 slot PC and load-LSID diagnostics and proves those R562
phase-gap clears are all the same resident load lifetime:
`w2_slot_replace_live_clear_after_w1_candidate_same_lsid=3`,
`w2_slot_replace_live_clear_after_w1_candidate_different_lsid=0`, and
`w2_slot_replace_live_clear_after_w1_candidate_unknown_lsid=0`. The next
fixture/top hook must therefore create a different returned-load candidate while
the resident W2 row live-clears; retaining the same candidate is not replacement
evidence. R564 adds different-LSID near-miss buckets in both directions and
reruns that same burst fixture. The generated RTL/QEMU comparator passes with
18 compared rows and zero mismatches, while all different-LSID gap2, gap3,
gap4, and gap5+ buckets remain zero for W1-before-W2-live-clear and
W2-live-clear-before-W1. The current fixture family therefore has no
different-LSID replacement near-miss to tune; the next packet should create one
explicitly before changing W2 storage or advance control.
R565 reruns the R559 repeated dependency-chain fixture with the R564
different-LSID buckets. It passes with 15 compared rows and zero mismatches,
records `gap2=5` and `gap4=1`, but again classifies every gap as same-LSID
(`same_lsid=6`, `different_lsid=0`) and leaves all different-LSID buckets at
zero. Both existing high-pressure fixtures are therefore same-resident-row
lifetime tests, not replacement stimulus.
R566 adds `replay-ldi-sdi-ldi-sdi-ldi-ldi-loop`, a clustered dependency-chain
fixture with one extra younger load after the second store dependency. QEMU-only
shape gates pass for two and three loop bodies, and the generated-RTL/QEMU gate
passes with 18 compared rows and zero mismatches. The fixture increases W2
residency (`lret_w2_slot_occupied=16`) but still records only same-LSID phase
gaps (`gap2=4`, `gap4=2`, `same_lsid=6`, `different_lsid=0`) and zero
same-cycle replacement. Clustered same-address dependency pressure is therefore
still insufficient; the next stimulus must change return phasing or introduce a
controlled delay/retention hook that creates a distinct returned-load candidate.
R567 bumps the Verilator replay-LIQ sideband report to schema v22 and splits
same-cycle W1-candidate/W2-live-clear overlap by W1/W2 load-LSID identity:
`w2_slot_replace_overlap_live_clear_same_lsid`,
`w2_slot_replace_overlap_live_clear_different_lsid`, and
`w2_slot_replace_overlap_live_clear_unknown_lsid`. This packet is
diagnostic-only; it does not alter W2 storage or top RTL behavior. The R566
clustered fixture rerun still passes with 18 compared rows, zero mismatches,
and zero QEMU/DUT CBSTOP rows. The v22 sideband records nonzero replay-return
progress and the same same-LSID phase shape
(`w2_slot_replace_live_clear_after_w1_candidate_gap2=4`,
`w2_slot_replace_live_clear_after_w1_candidate_gap4=2`,
`w2_slot_replace_live_clear_after_w1_candidate_same_lsid=6`) while all
same-cycle overlap identity buckets remain zero. Future phasing hooks must
require nonzero different-LSID same-cycle overlap before using
`replaceOnClear` evidence as a storage-promotion proof.
R568 adds a default-off reduced replay-LIQ emitted-top hook,
`LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES`, that gates W2 completion
readiness after the R335 side-effect readiness join. The hook preserves the
normal zero-delay path and, when enabled, holds completion rather than W2
storage clear alone so repeated side-effect fires are not created while W2 is
kept resident. The R568 generated-RTL/QEMU gate with
`LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=4` still passes with
18 compared rows, zero mismatches, and zero QEMU/DUT CBSTOP rows. It increases
W2 residence (`lret_w2_slot_occupied=30`) but still records no W1/W2
replacement overlap (`w2_slot_replace_overlap_candidate_live_clear=0`,
`w2_slot_replace_same_cycle_eligible=0`,
`w2_advance_replace_on_clear=0`) and no different-LSID near-miss buckets.
The delay hook is therefore a safe diagnostic phasing tool, not storage
promotion evidence; the next owner must create or retain a different W1
candidate while the delayed resident W2 row live-clears.
R569 tested that candidate-retention hypothesis without landing an RTL hook.
Two generated-RTL/QEMU runs on `replay-ldi-sdi-ldi-sdi-ldi-ldi-loop` passed
with zero mismatches and zero QEMU/DUT CBSTOP rows after manifest inspection:
`generated/r569-replay-w2-w1-hold-xcheck` used
`LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=4`, and
`generated/r569-replay-w2delay12-w1hold-xcheck` used delay 12. Both runs kept
architectural compare clean (`compared_rows=18`, `mismatch_count=0`), but both
proved the current stimulus still serializes upstream return admission before a
younger W1 candidate can sit behind occupied W2:
`lret_w1_advance_blocked_by_advance_disabled=0`,
`w2_slot_replace_overlap_candidate_live_clear=0`,
`w2_slot_replace_same_cycle_eligible=0`, and
`w2_advance_replace_on_clear=0`. Delay 12 increases W2 residence to
`lret_w2_slot_occupied=78`, so more W2 residency alone is not enough. The next
owner should work upstream of W1 advance, most likely at replay-return source
selection, IEX pipe admission, or E4 residency refill stimulus, and must prove a
different-LSID W1 candidate is resident before touching W2 storage.
R570 keeps RTL behavior unchanged and extends the replay-LIQ sideband schema to
v23 with upstream-overlap buckets for IEX insert, LRET residency, and W1
advance activity while W2 is occupied. The first R570 generated-RTL/QEMU probe
passed the comparator but produced zero replay-LIQ activity because the command
omitted the early store-address stimulus. Treat that shape as a harness setup
failure, not replay evidence. Replay-LIQ generated evidence for this fixture
must use both `--disable-store-memory-mutation` and
`LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1` so loads are forced through the
replay path. The corrected R570 run,
`generated/r570-replay-upstream-overlap-earlysta-xcheck`, used early STA plus
`LINXCORE_REPLAY_LIQ_W2_COMPLETION_DELAY_CYCLES=12` and passed with
`compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT CBSTOP rows. It
restored replay progress (`wait_replay_capture_accepted=12`,
`replay_queue_out_fire=6`, `liq_alloc_accepted=6`,
`lret_w2_slot_accepted=6`) and held W2 resident
(`lret_w2_slot_occupied=77`), but every new upstream-overlap bucket remained
zero: no IEX insert candidate, residency candidate, or W1 advance candidate was
present while W2 was occupied.
R571 keeps RTL behavior unchanged and moves the sideband one stage further
upstream with schema v24 buckets for LIQ return-complete, source-return,
row-mutation, return-publish, LRET payload, and LRET sink activity while W2 is
occupied. The same early-STA delay-12 gate,
`generated/r571-replay-source-w2-overlap-xcheck`, passes with
`compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT CBSTOP rows. It proves
source-side return activity does overlap occupied W2:
`liq_return_complete_valid_w2_occupied=3`,
`source_return_candidate_w2_occupied=4`,
`source_return_query_issued_w2_occupied=4`,
`source_return_response_apply_w2_occupied=4`,
`source_row_mutation_request_w2_occupied=4`,
`return_publish_candidate_w2_occupied=7`,
`return_publish_ready_w2_occupied=3`, and
`lret_payload_valid_w2_occupied=3`. The gap is now downstream of LRET payload
formation: `lret_sink_pending_w2_occupied=0`,
`lret_sink_drain_valid_w2_occupied=0`, and
`lret_sink_drain_fire_w2_occupied=0`. The next owner must therefore make
publish-to-LRET-sink admission and LRET drain/IEX-pipe capacity live enough to
hold a younger returned payload while W2 is occupied before changing W2 slot
storage.
R572 keeps RTL behavior unchanged and extends the sideband to schema v25 with
publish-control and LRET-sink enqueue timing buckets. The same early-STA
delay-12 gate, `generated/r572-replay-lret-publish-w2-timing-xcheck`, passes
with `compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT CBSTOP rows. It
proves publish and enqueue already overlap occupied W2:
`publish_control_candidate_w2_occupied=3`,
`publish_control_fire_w2_occupied=3`,
`lret_sink_enqueue_ready_w2_occupied=77`,
`lret_sink_enqueue_accepted_w2_occupied=3`, and
`lret_sink_enqueue_accepted_w2_without_drain_fire=3`, with no enqueue drops.
The sink still does not become pending or drain in that W2 window
(`lret_sink_pending_w2_occupied=0`,
`lret_sink_drain_valid_w2_occupied=0`,
`lret_sink_drain_fire_w2_occupied=0`). The next owner is therefore not
publish admission; it is the enqueue-to-pending/drain observation and
IEX-drain-capacity timing needed to make the accepted younger return visible
while W2 remains occupied.
R573 keeps RTL behavior unchanged and adds schema v26 one-cycle follow-up
buckets after an enqueue accepted while W2 was occupied. The same gate,
`generated/r573-replay-lret-followup-w2-timing-xcheck`, passes with
`compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT CBSTOP rows. The
follow-up evidence proves the accepted entry becomes visible and drains, but
only after W2 has cleared:
`lret_sink_followup_after_enqueue_accepted_w2=3`,
`lret_sink_followup_w2_cleared=3`,
`lret_sink_followup_w2_still_occupied=0`,
`lret_sink_pending_after_enqueue_accepted_w2=3`,
`lret_sink_drain_valid_after_enqueue_accepted_w2=3`,
`lret_sink_drain_fire_after_enqueue_accepted_w2=3`,
`lret_drain_permit_ready_after_enqueue_accepted_w2=3`, and
`lret_drain_permit_pipe_full_after_enqueue_accepted_w2=0`. The next owner is
therefore W2 hold/live-clear phasing relative to accepted LRET enqueue and
registered FIFO visibility, not LRET drain capacity.
R574 keeps RTL behavior unchanged and adds schema v27 same-cycle clear
classification for those accepted W2 LRET enqueues. The same early-STA delay-12
gate, `generated/r574-replay-lret-clear-followup-classifier-xcheck`, passes with
`status="pass"`, `compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT
CBSTOP rows. The classifier proves every accepted enqueue that overlaps W2 also
coincides with W2 completion clear, clear intent, side-effect fire-complete, and
live clear:
`lret_sink_enqueue_accepted_w2_completion_clear_slot=3`,
`lret_sink_enqueue_accepted_w2_clear_intent=3`,
`lret_sink_enqueue_accepted_w2_side_effect_fire_complete=3`, and
`lret_sink_enqueue_accepted_w2_live_clear=3`. The one-cycle follow-up buckets
then all observe W2 cleared:
`lret_sink_followup_after_enqueue_completion_clear_slot_w2_cleared=3`,
`lret_sink_followup_after_enqueue_clear_intent_w2_cleared=3`,
`lret_sink_followup_after_enqueue_side_effect_fire_complete_w2_cleared=3`, and
`lret_sink_followup_after_enqueue_live_clear_w2_cleared=3`, while the registered
FIFO entry is pending, drain-valid, drain-fired, and drain-permit-ready. The
next owner is therefore a deliberate W2-clear hold/retire phasing experiment or
model-derived W2 side-effect retire boundary, not LRET sink capacity,
publish-control readiness, or W2 slot storage replacement.
R575 adds a default-off W2 post-LRET-enqueue hold helper and enables it only in
the focused replay-loop evidence run with
`LINXCORE_REPLAY_LIQ_W2_POST_LRET_ENQUEUE_HOLD_CYCLES=1`. The generated
RTL/QEMU gate, `generated/r575-replay-lret-post-enqueue-w2-hold-xcheck`, passes
with `status="pass"`, `compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT
CBSTOP rows. The same accepted enqueue overlap remains present
(`lret_sink_enqueue_accepted_w2_occupied=3`,
`lret_sink_enqueue_accepted_w2_completion_clear_slot=3`,
`lret_sink_enqueue_accepted_w2_clear_intent=3`,
`lret_sink_enqueue_accepted_w2_side_effect_fire_complete=3`, and
`lret_sink_enqueue_accepted_w2_live_clear=3`), but the one-cycle follow-up now
observes retained W2 residency:
`lret_sink_followup_w2_still_occupied=3` and
`lret_sink_followup_w2_cleared=0`. The registered LRET FIFO path is also now
visible while W2 remains occupied:
`lret_sink_pending_w2_occupied=3`,
`lret_sink_drain_valid_w2_occupied=3`,
`lret_sink_drain_fire_w2_occupied=3`,
`lret_iex_insert_candidate_w2_occupied=3`, and
`lret_residency_candidate_w2_occupied=3`. The next owner is therefore to decide
whether final hardware should keep the W2 row alive through registered LRET
drain, or instead capture an explicit retire record so W2 can clear promptly.
R576 starts the explicit-retire-record path as a standalone LSU module:
`LoadReplayReturnPipeW2RetireRecord` captures a one-entry
`LoadReplayReturnLretEntry` payload when W2 has completion clear, clear intent,
and live clear. It records whether capture overlapped accepted LRET enqueue and
allows same-cycle consume/recapture. This packet deliberately does not change
the top-level W2 clear path; the next owner should wire diagnostic counters
beside the R575 hold evidence and prove capture identity before replacing the
hold experiment.
R577 wires the retire-record owner diagnostically into
`LinxCoreFrontendFetchRfAluTraceTop` without changing live W2 clear or LRET
drain behavior. The generated RTL/QEMU gate
`generated/r577-replay-w2-retire-record-xcheck` passes with `status="pass"`,
`compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT CBSTOP rows. Sideband
schema v28 records the no-hold R574 clear shape
(`lret_sink_enqueue_accepted_w2_live_clear=3`,
`lret_sink_followup_w2_cleared=3`,
`lret_sink_followup_w2_still_occupied=0`) and the explicit retire-record
capture for the same overlap (`w2_retire_record_capture_accepted=3`,
`w2_retire_record_capture_accepted_w2_occupied=3`,
`w2_retire_record_record_fire=3`,
`w2_retire_record_captured_with_lret_enqueue=3`,
`w2_retire_record_record_from_lret_enqueue=3`,
`w2_retire_record_capture_dropped=0`, and
`w2_retire_record_blocked_by_full=0`). The next owner can now consume this
record in replay-row lifecycle or LRET-drain identity logic instead of reviving
the R575 physical W2 hold as final architecture.
R578 wires that consume boundary diagnostically by feeding the retained record
into a second `LoadReplayReturnPipeW2ReplayRowLifecycleReady` instance with
`lifecycleClearEnable=false`. The generated RTL/QEMU gate
`generated/r578-replay-w2-retire-record-lifecycle-xcheck` passes with
`status="pass"`, `compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT
CBSTOP rows. Sideband schema v29 records exactly three retire-record lifecycle
matches and no missing/duplicate row blockers:
`w2_retire_record_lifecycle_candidate_valid=3`,
`w2_retire_record_lifecycle_resolved_row_match=3`,
`w2_retire_record_lifecycle_row_clear_ready=3`,
`w2_retire_record_lifecycle_blocked_by_no_resolved_row=0`, and
`w2_retire_record_lifecycle_blocked_by_multiple_resolved_rows=0`. The expected
`w2_retire_record_lifecycle_blocked_by_clear_disabled=3` keeps the path
diagnostic until a later packet promotes a live lifecycle request.
R579 makes the retained-record lifecycle match own retire-record consumption:
`LoadReplayReturnPipeW2RetireRecord.recordReady` now comes from the diagnostic
lifecycle instance's `rowClearReady`, while `lifecycleClearEnable` remains
false and LIQ mutation stays disabled. The generated RTL/QEMU gate
`generated/r579-replay-w2-retire-record-ready-xcheck` passes with
`status="pass"`, `compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT
CBSTOP rows. Sideband schema v30 records
`w2_retire_record_record_ready=3`, `w2_retire_record_record_fire=3`,
`w2_retire_record_lifecycle_resolved_row_match=3`,
`w2_retire_record_lifecycle_row_clear_ready=3`,
`w2_retire_record_lifecycle_blocked_by_no_resolved_row=0`, and
`w2_retire_record_lifecycle_blocked_by_multiple_resolved_rows=0`.
R580 adds the standalone
`LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe` to define the next
retained-record promotion predicate before live LIQ mutation: retained record
valid, unique lifecycle row, atomic request active, row-fill candidate valid,
and row-fill enable. The packet also records a top-maintenance blocker: direct
integration of the probe into `LinxCoreFrontendFetchRfAluTraceTop` pushed the
already-large top constructor over the JVM method-size limit, so top sideband
exposure must wait for a deliberate constructor/wiring split.

R581 performs that split by collecting W2 modules into a helper bundle and then
wires the retained-record lifecycle request probe into top diagnostics. The
generated RTL/QEMU gate
`generated/r581-replay-w2-retire-record-request-probe-xcheck` passes with
`status="pass"`, `compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT
CBSTOP rows. Sideband schema v31 records three request candidates from the
retained record and unique lifecycle row, zero missing lifecycle-row blockers,
zero row-fill candidate blockers, zero row-fill-enable blockers, zero live
promotion candidates, and three `blocked_by_no_atomic_request` events. The
next packet should keep LIQ mutation disabled and first align the retained
record with an atomic request source.

R582 refines that handoff with a retained-record atomic request probe. The
probe treats retained-record valid plus unique lifecycle row as request
evidence, then checks the existing row-fill candidate and row-fill enable
without driving live mutation. The generated RTL/QEMU gate
`generated/r582-replay-retire-record-atomic-request-probe-xcheck` passes with
`status="pass"`, `compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT
CBSTOP rows. Sideband schema v32 records three retained-record request
evidence events, zero missing lifecycle-row blockers, and three
missing-row-fill-candidate blockers. The next packet should build a
retained-record commit-row or row-fill candidate source before retrying live
request or row-fill enable promotion.

R583 builds that retained-record commit-row candidate source with
`LoadReplayReturnPipeW2RetireRecordCommitRowCandidate`, a wrapper over the
physical-W2 commit-row candidate that consumes the retained LRET record and
retained instruction metadata inputs. The generated RTL/QEMU gate
`generated/r583-replay-retire-record-commit-row-candidate-xcheck` passes with
`status="pass"`, `compared_rows=18`, `mismatch_count=0`, and zero QEMU/DUT
CBSTOP rows. Sideband schema v33 records three retained-record candidate-valid
cycles, zero retained row-fill candidates, and three missing retained
instruction-metadata blockers. The next packet should fix retained-record
instruction metadata lifetime before any retained row-fill enable or LIQ clear
promotion.

R584 adds `LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatch` and
extends the replay-LIQ sideband stats schema to v34 to classify that metadata
lifetime. The latch can capture from the resident W2 instruction provider at
retire-record creation or from the later LRET drain metadata fallback, and it
feeds the retained commit-row candidate without enabling row fill. The generated
RTL/QEMU gate
`generated/r584-replay-retire-record-metadata-probe-xcheck` passes with
`status="pass"`, `comparator_status=0`, `compared_rows=18`,
`mismatch_count=0`, and zero QEMU/DUT CBSTOP rows. The v34 sideband records
`w2_retire_record_instruction_metadata_capture_intent=3`,
`w2_retire_record_instruction_metadata_w2_metadata_ready=77`,
`w2_retire_record_instruction_metadata_w2_rid_matches_capture=0`,
`w2_retire_record_instruction_metadata_capture_from_w2=0`,
`w2_retire_record_instruction_metadata_capture_from_drain=6`,
`w2_retire_record_instruction_metadata_capture_blocked_by_rid_mismatch=3`,
`w2_retire_record_instruction_metadata_provider_valid=0`, and the retained
commit-row candidate remains blocked by metadata. The next owner is
retained-record payload source identity: the current top captures a live LRET
enqueue payload while the capture predicate is driven by the resident W2 slot
clear, and delayed-W2 evidence proves those RIDs do not match.

R585 fixes that identity mismatch by sourcing the retire-record payload from
the resident W2 slot at the same boundary that drives live clear, while keeping
the live LRET enqueue FIFO payload separate. It also holds W2-captured metadata
against later drain fallback overwrites. The generated RTL/QEMU gate
`generated/r585-replay-retire-record-payload-source-latch-hold-xcheck` passes
with `status="pass"`, `comparator_status=0`, 18 compared rows, zero
mismatches, and zero QEMU/DUT CBSTOP rows. The v34 sideband records
`w2_retire_record_commit_row_candidate_valid=145`,
`w2_retire_record_commit_row_fill_candidate=145`,
`w2_retire_record_commit_row_candidate_blocked_by_no_metadata=0`,
`w2_retire_record_instruction_metadata_capture_from_w2=1`,
`w2_retire_record_instruction_metadata_provider_valid=145`, and
`w2_retire_record_commit_row_candidate_blocked_by_row_fill_disabled=145`.
The next owner is retained-record row-fill enable promotion and its mutation
ordering, not payload identity or metadata lifetime.

Use this packet shape first:

```text
Packet: replay-LIQ retained-record row-fill enable promotion
Owner lane: rtl/LinxCore/chisel LSU replay-LIQ
Files allowed: retained-record row-fill enable/request-control modules, top
  diagnostic/live-gate wiring, sideband validator expectations, focused
  retire-record/top specs, and module docs; keep LIQ clear and architectural
  side effects disabled until row-fill enable is proven behind lifecycle,
  atomic-request, and commit-row candidate predicates
Source evidence: LinxCoreModel IEX::setMemData and LDAPipe/load-return W2
  handling after returned-load pipe insertion, plus LDQ resolved-row movement
Expected first gate: retained-record row-fill enable sideband shows nonzero
  enable candidates with zero metadata, lifecycle, atomic-request, and
  commit-row-candidate blockers while row mutation remains disabled
Promotion gate: R557 replay-loop fixture, R558
  `replay-ldi-sdi-ldi-ldi-loop`, R559
  `replay-ldi-sdi-ldi-sdi-ldi-loop`, R566
  `replay-ldi-sdi-ldi-sdi-ldi-ldi-loop`, R569 delay-4/delay-12
  negative retention probes, R560
  `replay-ldi-sdi-ldi-ldi-ldi-ldi-loop`, R561/R562/R563/R564/R565
  phase-distance, identity, and different-LSID near-miss sideband, or a stronger
  multiple-return-load phasing fixture through
  run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh with v24 sideband
  inspection requiring nonzero source-return, LRET-payload, publish-control,
  and LRET-sink enqueue overlap while W2 is occupied, then nonzero LRET sink
  pending/drain and IEX insert/residency/W1
  evidence before changing W2 storage; keep the existing W2 slot source trace,
  W2 policy blocker split, zero clear-commit policy blocks, nonzero ROB
  instruction metadata evidence, nonzero row-fill candidate evidence, valid
  lifecycle slot identity, nonzero lifecycle resolved-row match, nonzero
  row-fill enable, nonzero W2 promotion/live-clear and refill/advance counters,
  nonzero `live_clear_without_w1_candidate` in the old/R558/R559/R560 fixtures,
  and nonzero same-cycle slot replacement evidence in a stronger fixture before
  changing W2 storage; if overlap is zero, inspect the R563 identity buckets and
  R564 different-LSID buckets before treating phase gaps as replacement
  stimulus; R565 extends that negative check back to the repeated
  dependency-chain fixture, R566 extends it to the clustered second-dependency
  fixture, R569 shows W2 delay-4/delay-12 residency alone does not make a
  younger W1 candidate resident, R570 shows no IEX/residency/W1 upstream overlap
  while W2 is occupied even under delay 12 plus early STA, R571 narrows the
  current gap to the publish-to-LRET-sink / LRET-drain / IEX-pipe-capacity
  boundary, R572 proves publish-control fire plus LRET enqueue acceptance
  already overlap W2 while sink pending/drain still do not, R573 proves the
  accepted entry drains on the next cycle after W2 has already cleared, and R574
  proves those accepted enqueues coincide with W2 completion clear, clear
  intent, fire-complete, and live clear in the previous cycle
Do not run: long CoreMark, marker-row scaling, or superproject closure until
  same-cycle W2 replacement has a focused generated-RTL/QEMU proof
Do not change: LRET FIFO capacity, return-data extraction, ROB deallocation
  holdoff, lane/TLOAD/final metadata, ROB metadata latch, commit-row compare
  policy, or replay-LIQ lifecycle ownership before same-cycle replacement
  evidence exists
First-divergence owner if the gate fails: fixture/stimulus until the sideband
  report proves LRET sink pending/drain and then a younger W1 candidate exists
  while W2 is occupied and live clear fires; based on R574, start at a
  default-off W2-clear hold/retire phasing experiment or model-derived W2
  side-effect retire boundary before changing W2 storage or W1/W2 advance
  ordering
Closeout evidence: unit log, generated-RTL/QEMU manifest, sideband counters,
  module doc row, agent-loop row, and skill-evolve decision
```

## Toolchain Roles

| Lane | Owns | Primary evidence | Hand off when |
|---|---|---|---|
| `isa/` and `docs/isa/` | Normative opcode, register, block, trap, CSR, and memory contracts | ISA manual row, generated catalog, encoding metadata | The behavior is not specified or the generated catalog disagrees |
| `compiler/llvm` and `compiler/ptoas` | Workload ELF generation, assembly, disassembly, relocation, call/return lowering | Compile log, object/ELF, MC tests, disassembly | Source is valid but compile, assemble, link, or relocation fails |
| `emulator/qemu` | Executable architectural reference for direct-boot and Linux/runtime rows | QEMU log, JSONL trace, first architectural row | ELF fails before Chisel sees a valid reference row |
| `model/LinxCoreModel` | Cycle/model behavior, block/ROB/BROB/LSU ownership details, final model convergence | Source reference, `gfsim -f <elf>` log after QEMU pass | QEMU passes but model loops, mismatches, or times out |
| `rtl/LinxCore/chisel` | Chisel RTL, typed monitors, generated-RTL harnesses, DUT row compare | Unit tests, Verilator lint, xcheck manifest, first DUT mismatch | QEMU/reference rows are valid and generated RTL diverges |
| `tools/chisel` | Row normalization, memory fixtures, trace adapters, Chisel wrappers | Adapter self-test, dry-run, `crosscheck_manifest.json` | Schema, manifest, or row filtering is wrong while DUT/reference rows are valid |
| `tools/bringup` and superproject tests | Cross-repo smoke, benchmark, AI workload, Linux/runtime promotion | flow report JSON, PR/nightly profile, strict closure log | A leaf lane is green but integrated bring-up regresses |

## Optimized Loop

1. Pick one packet.
   Name the owner, files, source evidence, expected first gate, and promotion
   gate before editing. If the packet touches two owners, split it unless the
   interface contract itself is the change.
2. Record SHAs once.
   Capture the superproject, `rtl/LinxCore`, `model/LinxCoreModel`,
   `emulator/qemu`, and `compiler/llvm` SHAs when those lanes are involved. Do
   not repeat status scans unless the worktree, fetched refs, or generated
   artifacts changed.
3. Read only live context.
   Use this file, `docs/chisel/development-loop.md`, the assigned module spec,
   the nearest source files in LinxCoreModel or pyCircuit, and the latest
   relevant rows in `docs/chisel/agent-loop.md`.
4. Design the harness boundary first.
   Keep the DUT top, test harness, and test driver separate:
   - DUT top emits typed architectural or stage-owner rows.
   - Test harness owns clock/reset, memory image loading, trace feeding, and
     optional debug enable.
   - Test driver owns JSONL, manifests, compare policy, and first-mismatch
     reporting.
5. Edit the smallest owner.
   Update the module Markdown with the contract and evidence shape before or
   with the RTL change. Do not add permanent debug IO for one failing test;
   prefer typed monitor rows or optional probe-style diagnostics.
6. Stop at first failing tier.
   Classify the first divergence before editing again. Do not run the wider
   gate stack after a smaller required gate has already failed.
7. Promote only after a smaller pass.
   Increase from module unit evidence to generated RTL, QEMU rows, model, and
   superproject closure in order. Long CoreMark, Linux, and AI workload gates
   are promotion evidence, not the edit loop.
8. Close the packet.
   Record commands, report paths, first mismatch if any, SHAs, and whether the
   finding updates `linx-core` skill guidance. If reusable, update the skill;
   otherwise close with `skill-evolve: no-update`.

## Gate Ladder

| Tier | Use when | Typical commands from `rtl/LinxCore` | Exit condition |
|---|---|---|---|
| 0. Wrapper and static sanity | Any Chisel, adapter, or fixture edit | `bash tools/chisel/build_chisel.sh`; touched Python adapter self-tests; relevant `--dry-run` wrapper | Build and wrapper selection work without entering a broad compare |
| 1. Module contract | One module or bundle owner changed | `bash tools/chisel/run_chisel_tests.sh --only <Module>`; `bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` for ROBID/ROB semantics | The assigned module contract is green |
| 2. Adjacent generated RTL | Composition, top, monitor, or trace-owner changed | `bash tools/chisel/run_chisel_verilator_lint.sh`; nearest `run_chisel_*_xcheck.sh` wrapper | Generated RTL elaborates and the nearest harness compare is green |
| 3. QEMU row replay | The packet claims architectural row equivalence | `bash tools/chisel/run_chisel_qemu_trace_replay_xcheck.sh ...`; `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh ...` | `crosscheck_manifest.json` shows zero mismatches and records raw/arch row counts |
| 4. Model/toolchain convergence | Workload behavior or model parity is part of the claim | From superproject: `python3 tools/bringup/run_ai_workload_flow.py --profile smoke ...`; `python3 tools/bringup/run_benchmark_linux_flow.py --profile pr ...`; `model/LinxCoreModel/bin/gfsim -f <elf>` only after the same ELF passed QEMU | QEMU-passing ELF also passes the selected model or workload profile |
| 5. Closure | PR, submodule pin, or cross-lane behavior changed | `tests/test_stage_connectivity.sh`; `tests/test_runner_protocol.sh`; `tests/test_cosim_smoke.sh`; `tests/test_opcode_parity.sh`; `tests/test_trace_schema_and_mem.sh`; `tests/test_rob_bookkeeping.sh`; `tests/test_block_struct_pyc_flow.sh`; superproject strict closure | All required cross-repo gates for the claim pass |

Scale row windows in order. Start with a tiny deterministic fixture or 16 to 64
architectural rows, then 512, 4k, and only then long CoreMark or Linux windows.
For current CoreMark-style direct-boot ELFs, pass explicit QEMU memory such as
`-m 1280M` when the wrapper invokes QEMU.

## First Divergence Ownership

| First failing fact | Owner |
|---|---|
| Source or benchmark harness is invalid before compile | source/benchmark |
| Valid source fails compile, assemble, link, relocation, or disassembly | compiler |
| ELF fails in QEMU before a valid row stream exists | QEMU |
| QEMU row stream is valid but the Chisel generated-RTL row differs | Chisel |
| Reference and DUT rows are valid but schema, filtering, row count, or manifest is wrong | adapter |
| QEMU passes but `gfsim` fails, loops, times out, or computes the wrong digest | model |
| Leaf lanes pass but pinned SHAs, submodule state, Linux/runtime, or bring-up profile fails | superproject |

Do not repair a downstream lane before the upstream lane has produced a valid
artifact. A QEMU-invalid ELF is not a Chisel bug. A QEMU-passing, DUT-mismatched
row is not a compiler bug until the row normalization has been checked.

## Harness Rules

- Keep benchmark-specific address, memory, and compare setup in harness or
  driver code, not in the DUT.
- Emit typed rows for commit, trap, memory, redirect, block, and owner-specific
  diagnostics. Do not scrape waveforms as the primary compare interface.
- Generate or normalize traces once per packet and reuse the artifact for
  syntax-only Chisel iterations. Regenerate when the ELF, QEMU, adapter, or
  compare policy changes.
- Preserve `crosscheck_manifest.json` for every generated-RTL or QEMU/DUT
  compare. The manifest is the evidence bundle, not a side effect.
- Waves are opt-in failure evidence. Do not enable them in the default inner
  loop.
- pyCircuit remains a parity oracle until the Chisel path has equivalent
  module, generated-RTL, QEMU-row, and model evidence.

## Agent Packet Template

Use this shape when launching a LinxCore packet:

```text
Packet:
Owner lane:
Files allowed:
Source evidence:
Expected first gate:
Promotion gate:
Do not run:
First-divergence owner if the gate fails:
Closeout evidence:
```

Current example:

```text
Packet: replay-LIQ LRET W2 row-fill candidate readiness
Owner lane: rtl/LinxCore/chisel
Files allowed: W2 commit-row candidate/trace-source, row-fill enable control,
  ROB trace lookup provider, W2 request evidence wiring, focused specs, module
  docs
Source evidence: LinxCoreModel IEX::setMemData and load-return W2 handling
Expected first gate: focused W2 row-fill candidate readiness coverage
Promotion gate: R551 replay-loop generated-RTL/QEMU fixture plus v21 sideband
Do not run: long CoreMark or marker-row scaling before W2 row-fill candidate readiness
First-divergence owner if the gate fails: Chisel W2 commit-row candidate /
  trace-source readiness unless v21 sideband reporting is wrong
Closeout evidence: unit log, xcheck manifest, sideband counters, module doc
  row, skill-evolve decision
```

## Speed Rules

- No raw `sbt`; use `tools/chisel/chisel_env.sh` through repo wrappers.
- No repeated broad `git status` loops inside a packet.
- No full closure in the inner edit loop.
- No widening row windows until the previous smaller window is green.
- No permanent debug ports for temporary diagnosis.
- No claiming replacement evidence from a unit test alone.
- No model run on an ELF that has not passed QEMU in the same packet.
- No submodule pin or PR closeout without the superproject evidence row that
  matches the claim.
