# LinxTrace v1

`linxtrace.v1` is the canonical LinxCore pipeview exchange format for LinxCoreSight.

## Files

1. Event stream: `*.linxtrace.jsonl`
2. Metadata sidecar: `*.linxtrace.meta.json`

Both files are required.

## Meta Schema

Required top-level fields:

- `format`: `linxtrace.v1`
- `contract_id`: deterministic hash for stage/lane/row schema
- `pipeline_schema_id`: stage token schema id from LinxCore
- `stage_order_csv`
- `stage_catalog`: `[{stage_id,label,color,group}]`
- `lane_catalog`: `[{lane_id,label}]`
- `row_catalog`: `[{row_id,row_kind,core_id,block_uid,uop_uid,left_label,detail_defaults}]`
- `render_prefs` (optional)

## Event Types

Each JSONL row includes `type`.

- `OP_DEF`: row identity attach
- `LABEL`: left/detail label updates
- `OCC`: occupancy event (`cycle,row_id,stage_id,lane_id,stall,cause`)
- `RETIRE`: row terminal status (`cycle,row_id,status`)
- `BLOCK_EVT`: block lifecycle (`open|close|redirect|fault`)
- `XCHECK`: cross-check mismatch annotation (optional)

## Design Rules

- Renderer performs no lifecycle reconstruction.
- Row order/grouping is emitter-owned.
- Unknown stage/lane/row is a hard error.
- Zero `OCC` rows is a hard error.

