from __future__ import annotations

# Internal IQ class encoding for standalone OEX superscalar model.
# Keep stable to simplify trace/debug tooling.

IQ_ALU = 0
IQ_BRU = 1
IQ_AGU = 2  # load address
IQ_STD = 3  # store data/address
IQ_CMD = 4
IQ_FSU = 5
IQ_TPL = 6  # template/micro-sequencer

