# Frontend Parameter Catalog

Frontend parameters are exposed at:

- `/Users/zhoubot/LinxCore/src/linxcore_top.py`
- `/Users/zhoubot/LinxCore/src/top/top.py`
- `/Users/zhoubot/LinxCore/src/bcc/ifu/icache.py`

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

- `ifetch_bundle_bytes`:
  - default: `128`
  - legal in this milestone: `128` only
  - meaning: fetch bundle assembled from two 64B lines

- `ib_depth`:
  - default: `8`
  - legal: power-of-two, `>= 1`
  - meaning: instruction buffer depth

- `ic_miss_outstanding`:
  - default: `1`
  - legal in this milestone: `1` only
  - meaning: max concurrent I-cache misses

- `ic_enable`:
  - default: `1`
  - legal: `0|1`
  - meaning: enable/disable I-cache behavior

## TB-L2 Miss Model

- `/Users/zhoubot/LinxCore/tb/tb_linxcore_top.cpp` uses:
  - `PYC_IC_MISS_CYCLES` (default `20`)
- Contract:
  - single blocking miss,
  - one refill line (`64B`) per response.
