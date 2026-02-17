#!/usr/bin/env bash
set -euo pipefail

KONATA_SRC_ROOT="${KONATA_SRC_ROOT:-/Users/zhoubot/Konata}"
KONATA_BUILD_APP="${KONATA_BUILD_APP:-${KONATA_SRC_ROOT}/packaging-work/konata-darwin-x64/konata.app}"
INSTALL_TARGET="${KONATA_INSTALL_TARGET:-/Applications/Konata.app}"
FALLBACK_TARGET="${KONATA_INSTALL_FALLBACK:-${HOME}/Applications/Konata.app}"
PLIST_BUDDY="${PLIST_BUDDY:-/usr/libexec/PlistBuddy}"
LSREGISTER="${LSREGISTER:-/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister}"

if [[ ! -d "${KONATA_SRC_ROOT}" ]]; then
  echo "error: missing Konata source root: ${KONATA_SRC_ROOT}" >&2
  exit 1
fi

register_file_assoc() {
  local app_path="$1"
  local plist="${app_path}/Contents/Info.plist"
  if [[ ! -f "${plist}" ]]; then
    echo "warn: missing Info.plist, skip file association update: ${plist}" >&2
    return 0
  fi
  if [[ ! -x "${PLIST_BUDDY}" ]]; then
    echo "warn: missing PlistBuddy, skip file association update" >&2
    return 0
  fi

  "${PLIST_BUDDY}" -c "Delete :UTExportedTypeDeclarations" "${plist}" >/dev/null 2>&1 || true
  "${PLIST_BUDDY}" -c "Add :UTExportedTypeDeclarations array" "${plist}"
  "${PLIST_BUDDY}" -c "Add :UTExportedTypeDeclarations:0 dict" "${plist}"
  "${PLIST_BUDDY}" -c "Add :UTExportedTypeDeclarations:0:UTTypeIdentifier string com.linxisa.konata.trace" "${plist}"
  "${PLIST_BUDDY}" -c "Add :UTExportedTypeDeclarations:0:UTTypeDescription string LinxCore Kanata Trace" "${plist}"
  "${PLIST_BUDDY}" -c "Add :UTExportedTypeDeclarations:0:UTTypeConformsTo array" "${plist}"
  "${PLIST_BUDDY}" -c "Add :UTExportedTypeDeclarations:0:UTTypeConformsTo:0 string public.text" "${plist}"
  "${PLIST_BUDDY}" -c "Add :UTExportedTypeDeclarations:0:UTTypeTagSpecification dict" "${plist}"
  "${PLIST_BUDDY}" -c "Add :UTExportedTypeDeclarations:0:UTTypeTagSpecification:public.filename-extension array" "${plist}"
  "${PLIST_BUDDY}" -c "Add :UTExportedTypeDeclarations:0:UTTypeTagSpecification:public.filename-extension:0 string konata" "${plist}"
  "${PLIST_BUDDY}" -c "Add :UTExportedTypeDeclarations:0:UTTypeTagSpecification:public.filename-extension:1 string kanata" "${plist}"

  "${PLIST_BUDDY}" -c "Delete :CFBundleDocumentTypes" "${plist}" >/dev/null 2>&1 || true
  "${PLIST_BUDDY}" -c "Add :CFBundleDocumentTypes array" "${plist}"
  "${PLIST_BUDDY}" -c "Add :CFBundleDocumentTypes:0 dict" "${plist}"
  "${PLIST_BUDDY}" -c "Add :CFBundleDocumentTypes:0:CFBundleTypeName string LinxCore Kanata Trace" "${plist}"
  "${PLIST_BUDDY}" -c "Add :CFBundleDocumentTypes:0:CFBundleTypeRole string Viewer" "${plist}"
  "${PLIST_BUDDY}" -c "Add :CFBundleDocumentTypes:0:LSHandlerRank string Owner" "${plist}"
  "${PLIST_BUDDY}" -c "Add :CFBundleDocumentTypes:0:LSItemContentTypes array" "${plist}"
  "${PLIST_BUDDY}" -c "Add :CFBundleDocumentTypes:0:LSItemContentTypes:0 string com.linxisa.konata.trace" "${plist}"
  "${PLIST_BUDDY}" -c "Add :CFBundleDocumentTypes:0:CFBundleTypeExtensions array" "${plist}"
  "${PLIST_BUDDY}" -c "Add :CFBundleDocumentTypes:0:CFBundleTypeExtensions:0 string konata" "${plist}"
  "${PLIST_BUDDY}" -c "Add :CFBundleDocumentTypes:0:CFBundleTypeExtensions:1 string kanata" "${plist}"

  if [[ -x "${LSREGISTER}" ]]; then
    "${LSREGISTER}" -f "${app_path}" >/dev/null 2>&1 || true
  fi
}

echo "[0/3] stop running Konata process (if any)"
if command -v osascript >/dev/null 2>&1; then
  osascript -e 'tell application "Konata" to quit' >/dev/null 2>&1 || true
fi
pkill -f "/Konata.app/Contents/MacOS/konata" >/dev/null 2>&1 || true
sleep 0.5

echo "[1/3] build Konata app bundle"
make -C "${KONATA_SRC_ROOT}" build

if [[ ! -d "${KONATA_BUILD_APP}" ]]; then
  echo "error: built app not found: ${KONATA_BUILD_APP}" >&2
  exit 1
fi

install_path="${INSTALL_TARGET}"
echo "[2/3] install app bundle -> ${install_path}"
if ! rm -rf "${install_path}" 2>/dev/null; then
  true
fi
if ! cp -R "${KONATA_BUILD_APP}" "${install_path}" 2>/dev/null; then
  echo "warn: cannot install to ${install_path}, falling back to ${FALLBACK_TARGET}" >&2
  mkdir -p "$(dirname "${FALLBACK_TARGET}")"
  rm -rf "${FALLBACK_TARGET}"
  cp -R "${KONATA_BUILD_APP}" "${FALLBACK_TARGET}"
  install_path="${FALLBACK_TARGET}"
fi

echo "[3/4] register .konata/.kanata file association"
register_file_assoc "${install_path}"

echo "[4/4] installed: ${install_path}"
echo "tip: open trace with:"
echo "  KONATA_APP='${install_path}' bash /Users/zhoubot/LinxCore/tools/konata/open_konata_trace.sh <trace.konata>"
