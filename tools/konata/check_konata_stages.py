#!/usr/bin/env python3
from __future__ import annotations

import os
import subprocess
import sys


def main() -> int:
    sys.stderr.write("deprecated: check_konata_stages.py replaced by tools/linxcoresight/lint_linxtrace.py\n")
    argv = [
        "python3",
        "/Users/zhoubot/LinxCore/tools/linxcoresight/lint_linxtrace.py",
        *sys.argv[1:],
    ]
    return subprocess.call(argv, env=os.environ.copy())


if __name__ == "__main__":
    raise SystemExit(main())

