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
