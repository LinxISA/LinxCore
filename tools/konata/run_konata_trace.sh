#!/usr/bin/env bash
set -euo pipefail
echo "deprecated: Konata flow removed; using LinxCoreSight LinxTrace flow instead." >&2
exec bash /Users/zhoubot/LinxCore/tools/linxcoresight/run_linxtrace.sh "$@"

