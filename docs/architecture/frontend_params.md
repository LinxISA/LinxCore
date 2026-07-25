# Frontend Parameter Catalog

Frontend parameters are exposed at:

- `rtl/LinxCore/src/linxcore_top.py`
- `rtl/LinxCore/src/top/top.py`
- `rtl/LinxCore/src/bcc/ifu/icache.py`

The normative structure is the I-SIDE/B-SIDE decoupled-engine contract in
`ifu.md`. Parameter implementations must not merge I-F4 with Instruction Buffer
or move predictor ownership into I-SIDE.

## Parameters

- `ic_sets`:
  - default: `32`
  - legal: power-of-two, `>= 1`
  - meaning: I-cache set count

- `ic_ways`:
  - default: `4`
  - legal: `>= 1`
  - meaning: I-cache associativity

- `ic_line_bytes`:
  - default: `64`
  - legal in this milestone: `64` only
  - meaning: I-cache line size

- `ifetch_line_bytes`:
  - default: `64`
  - legal in this milestone: equal to `ic_line_bytes`
  - meaning: one I-SIDE fetch request returns one L1I cache line

- `d1_decode_width`:
  - default: `4`
  - legal in this milestone: `4` only
  - meaning: maximum fixed-64-bit Instruction Buffer entries read by D1

- `instruction_payload_bits`:
  - default: `64`
  - legal in this milestone: `64` only
  - meaning: instruction payload width in Instruction Buffer and after D1

- `ib_depth`:
  - default: `8`
  - legal: power-of-two, `>= 1`
  - meaning: instruction buffer depth

- `bside_request_entries` / `bside_response_entries`:
  - default: implementation-defined by the selected configuration
  - legal: `>= 1`
  - meaning: decoupling capacity between I-SIDE requests, B-SIDE prediction,
    and fetch steering

- `ic_miss_outstanding`:
  - default: `1`
  - legal in this milestone: `1` only
  - meaning: max concurrent I-cache misses

- `ic_enable`:
  - default: `1`
  - legal: `0|1`
  - meaning: enable/disable I-cache behavior

## TB-L2 Miss Model

- `rtl/LinxCore/tb/tb_linxcore_top.cpp` uses:
  - `PYC_IC_MISS_CYCLES` (default `20`)
- Contract:
  - single blocking miss,
  - one refill line (`64B`) per response.

## Fixed structural requirements

- I-F1 launches ITLB and L1I in parallel.
- I-F2 generates an inner flush on ITLB miss.
- I-F4 writes complete 64-bit instructions into Instruction Buffer.
- B-F1..B-F4 correction of an accepted lower-ranked prediction generates an
  identity-qualified inner flush, restores
  GHR/RAS, and restarts I-F0; B-F4 is the final such point.
- B-F4 runs static/final arbitration and seals the complete prediction record
  carried by every valid D1 lane.
- Post-B-F4 mismatch is validated by Dispatch/BRU and uses BRU flush/recover.
- B-SIDE predictor queues advance independently from I-SIDE.
