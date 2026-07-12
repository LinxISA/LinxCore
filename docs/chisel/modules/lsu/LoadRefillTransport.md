# LoadRefillTransport

## Purpose

`LoadRefillTransport` is the canonical bounded handoff between scalar refill
producers and `LoadInflightQueue`. It replaces the former combinational
miss-refill priority mux, which treated simultaneous miss-queue and external
refills as a protocol collision and could not backpressure either producer.

The mechanism is ISA-neutral. Linx block, recovery, and full-LSID semantics
remain in the producer and LIQ owners; this transport stores only completed
physical line data and source provenance.

## Model Evidence

- `L1Top::dispL2Resp` queues lower-level responses per L1 cluster.
- `L1Clusters::refillCache` retains refill work until the cache/wakeup path can
  consume it and serializes wakeup publication.
- `LDQInfo::handleL1Wakeup` consumes the resulting line wakeup and scans live
  load rows.

The Chisel transport mirrors the bounded retention and serialization, not the
model's cache arrays or replacement policy.

## Parameterization

`loadRefillQueueEntries` is a power-of-two physical depth greater than one. It
is independent of LIQ, miss queue, ROB, STQ, return-queue, and LSID sizing.
Each entry contains one `LoadRefillWakeupRequest` plus a one-bit source tag.

## Ingress Contract

Two ready/valid sources may enqueue in one cycle:

1. the exact valid-read response emitted by `LoadMissQueue`;
2. the external cache/refill source on `ScalarLSULoadPathIO`.

When both fire together, the miss-queue packet is older within that cycle and
occupies the first FIFO position; the external packet occupies the second.
Neither packet is dropped or overwritten. Each source receives independent
readiness derived from post-dequeue free capacity. Thus a full queue may accept
new work in the same cycle that its head is consumed.

`LoadMissQueue` may accept and retire an exact read response only when the
transport accepts the corresponding refill. Malformed responses remain
independently consumable and diagnostic because they do not require a refill
slot.

## Egress Contract

The FIFO exposes one stable ready/valid refill stream. Head payload and source
tag remain stable under backpressure. `ScalarLSULoadPath` connects the stream
to the LIQ refill owner and consumes at most one line per cycle.

Hard flush clears buffered refills because no pre-flush load dependent remains
authoritative. Typed precise recovery holds the transport for the recovery
cycle but retains buffered physical line data, allowing non-matching LIQ rows
to consume it afterward.

## Diagnostics

The transport publishes:

- valid mask and resident count;
- miss-source and external-source accepted pulses;
- simultaneous dual-ingress acceptance;
- output source provenance;
- full/backpressure and pending/empty state;
- protocol error for impossible count or pointer state only.

Simultaneous legal ingress is observability, not a protocol error.

## Exclusions

This module does not implement L1D arrays, tags, replacement, coherence,
memory-attribute classification, Device/MMIO routing, cross-line assembly,
atomics, fences, ARM barriers, ARM exclusives, acquire/release opcode behavior,
or exception-level policy.

## Verification

Required coverage includes dual enqueue order, independent source
backpressure, stable output hold, full-queue dequeue/enqueue reuse, precise
recovery hold, hard-flush clear, independent sizing, Chisel elaboration, and a
generated-RTL probe.
