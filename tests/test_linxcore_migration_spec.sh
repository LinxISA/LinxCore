#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DRAFT_DIR="${ROOT_DIR}/docs/temp/linxcore"

require() {
  local pattern="$1"
  local file="$2"
  if ! rg -q --fixed-strings -- "${pattern}" "${file}"; then
    echo "missing required migration contract in ${file}: ${pattern}" >&2
    exit 1
  fi
}

reject() {
  local pattern="$1"
  if rg -n --fixed-strings -- "${pattern}" "${DRAFT_DIR}" >/dev/null; then
    echo "obsolete migration wording remains: ${pattern}" >&2
    exit 1
  fi
}

[[ -d "${DRAFT_DIR}" ]] || {
  echo "missing LinxCore migration draft: ${DRAFT_DIR}" >&2
  exit 1
}

require "four-wide D1 decode group" "${DRAFT_DIR}/README.md"
require "BID_W = ceil(log2(BROB_ENTRIES))" "${DRAFT_DIR}/README.md"
require "F4/IB | The fourth fetch stage" "${DRAFT_DIR}/01-linxcore-overview.md"
require "F4/IB and the D1 Ingress Group" "${DRAFT_DIR}/02-linxcore-frontend-decode.md"
require "BID   = BROB slot index[BID_W-1:0]" "${DRAFT_DIR}/04-linxcore-rob-brob-recovery.md"
require "does not mutate globally visible ROB, BROB, rename, or LSID state" "${DRAFT_DIR}/02-linxcore-frontend-decode.md"
require "P1 -> I1 -> I2 -> E1 -> E2 -> E3 -> ..." "${DRAFT_DIR}/06-linxcore-issue-execute.md"
require "W1/W2/W3 result-age overlay" "${DRAFT_DIR}/06-linxcore-issue-execute.md"
require "BROB-qualified ring-age/kill context" "${DRAFT_DIR}/01-linxcore-overview.md"
require "rsp_tag[BID_W-1:0]" "${DRAFT_DIR}/13-linxcore-block-fabric-engines-open-questions.md"

reject "BID = (uniq <<"
reject "BID remains 64 bits"
reject 'BID flush keeps `bid <= flush_bid`'
reject "Full-width BID ordering"
reject "F4 presents a four-slot decode window"
reject "P1 -> I1 -> I2 -> E1 -> W1"
reject "(BID, LSID) order"
reject "cmd_tag[7:0]"
reject "rsp_tag[7:0]"

echo "LinxCore migration specification contract passed"
