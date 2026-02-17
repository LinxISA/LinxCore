#!/usr/bin/env bash
set -euo pipefail

TRACE="${1:-}"
if [[ -z "${TRACE}" ]]; then
  TRACE="$(find /Users/zhoubot/LinxCore/generated/konata -name '*.konata' -type f -print0 2>/dev/null | xargs -0 ls -t 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "${TRACE}" ]]; then
  TRACE="/Users/zhoubot/LinxCore/generated/cpp/linxcore_top/tb_linxcore_top_cpp_program.konata"
fi
if [[ ! -f "${TRACE}" ]]; then
  echo "error: missing trace file: ${TRACE}" >&2
  exit 1
fi

header="$(head -n 1 "${TRACE}" 2>/dev/null || true)"
if [[ "${header}" != $'Kanata\t0005' ]]; then
  echo "warn: unexpected trace header (expected 'Kanata\\t0005'): ${header}" >&2
fi

if [[ "${KONATA_FORCE_RESTART:-1}" == "1" ]]; then
  if command -v osascript >/dev/null 2>&1; then
    osascript -e 'tell application "Konata" to quit' >/dev/null 2>&1 || true
  fi
  pkill -f "/Konata.app/Contents/MacOS/konata" >/dev/null 2>&1 || true
  sleep 0.4
fi

if [[ -n "${KONATA_APP:-}" ]]; then
  KONATA_APP_CANDIDATE="${KONATA_APP}"
elif [[ -d "/Applications/Konata.app" ]]; then
  KONATA_APP_CANDIDATE="/Applications/Konata.app"
elif [[ -d "${HOME}/Applications/Konata.app" ]]; then
  KONATA_APP_CANDIDATE="${HOME}/Applications/Konata.app"
else
  KONATA_APP_CANDIDATE="/Users/zhoubot/Konata/packaging-work/konata-darwin-x64/konata.app"
fi
KONATA_APP="${KONATA_APP_CANDIDATE}"
if [[ -d "${KONATA_APP}" ]]; then
  exec_path="${KONATA_APP}/Contents/MacOS/konata"
  open_ok=0
  if command -v open >/dev/null 2>&1; then
    if open -a "${KONATA_APP}" "${TRACE}"; then
      open_ok=1
    fi
  fi
  sleep 0.8
  if ! pgrep -f "${exec_path}" >/dev/null 2>&1; then
    if [[ -x "${exec_path}" ]]; then
      log="${KONATA_OPEN_LOG:-/tmp/konata_open.log}"
      "${exec_path}" "${TRACE}" >"${log}" 2>&1 &
      sleep 0.8
      if ! pgrep -f "${exec_path}" >/dev/null 2>&1; then
        echo "error: Konata failed to stay running for trace: ${TRACE}" >&2
        echo "  app: ${KONATA_APP}" >&2
        echo "  log: ${log}" >&2
        tail -n 40 "${log}" 2>/dev/null || true
        exit 1
      fi
      echo "opened (direct): ${TRACE}"
    else
      echo "error: Konata executable missing: ${exec_path}" >&2
      exit 1
    fi
  else
    if [[ "${open_ok}" -eq 1 ]]; then
      echo "opened: ${TRACE}"
    else
      echo "opened (process detected): ${TRACE}"
    fi
  fi
elif command -v open >/dev/null 2>&1; then
  if ! open "${TRACE}"; then
    echo "error: no Konata app association for .konata; run install tool:" >&2
    echo "  bash /Users/zhoubot/LinxCore/tools/konata/install_konata_app.sh" >&2
    exit 1
  fi
else
  echo "trace ready: ${TRACE}"
fi
