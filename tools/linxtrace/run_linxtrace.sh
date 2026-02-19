#!/usr/bin/env bash
set -euo pipefail
echo "deprecated wrapper: use LinxCoreSight LinxTrace flow." >&2
exec bash /Users/zhoubot/LinxCore/tools/linxcoresight/run_linxtrace.sh "$@"
