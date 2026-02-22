from __future__ import annotations

# NOTE: Opcode IDs are generated from /Users/zhoubot/LinxCore/src/common/opcode_catalog.yaml
# via tools/generate/gen_opcode_tables.py.
from common.opcode_ids_gen import *  # noqa: F401,F403

REG_INVALID = 0x3F

# Bring-up stage IDs (legacy in-order core).
ST_IF = 0
ST_ID = 1
ST_EX = 2
ST_MEM = 3
ST_WB = 4

# Block transition kinds (internal control state, not ISA encodings).
BK_FALL = 0
BK_COND = 1
BK_CALL = 2
BK_RET = 3
BK_DIRECT = 4
BK_IND = 5
BK_ICALL = 6

# Trap causes (software-visible in commit trace / cosim).
TRAP_BRU_RECOVERY_NOT_BSTART = 0x0000B001
