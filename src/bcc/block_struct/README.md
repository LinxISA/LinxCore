# bcc.block_struct

Reference models + unit tests for block structured execution.

- `brob.py`: BID-based Block Reorder Buffer model.
- `rob.py`: RID-based ROB model for scalar uops.

Unit tests:

- `test_block_struct_ut.py`

Run:

```bash
python3 -m unittest -q bcc.block_struct.test_block_struct_ut
```

These models are used to validate invariants before integrating full pycircuit RTL.

## Signal-level pycircuit RTL

- `brob_rtl.py`: BROB signal-level module (BID low bits select slot; scalar_done + engine_done).
- `rob_rtl.py`: minimal block-aware ROB helper (retire EOB -> scalar_done pulse).

Smoke elaboration (requires `pycircuit` importable via PYTHONPATH):

```bash
PYTHONPATH="${PYC_ROOT}/compiler/frontend:src" python3 -m unittest -q bcc.block_struct.test_block_struct_rtl_smoke
```

## PYC flow (CPP + Verilog + Verilator)

Requires `pycc` built (or set `PYCC=/path/to/pycc`).

```bash
bash tests/test_block_struct_pyc_flow.sh
```
