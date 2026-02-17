#!/usr/bin/env bash
set -euo pipefail
echo "deprecated: Konata GUI flow removed; opening in LinxCoreSight." >&2
exec bash /Users/zhoubot/LinxCore/tools/linxcoresight/open_linxcoresight.sh "$@"

