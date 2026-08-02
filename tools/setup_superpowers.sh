#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SUPERPOWERS_DIR="${ROOT_DIR}/tools/superpowers"
EXPECTED_TAG="v6.2.0"
EXPECTED_COMMIT="3dcbd5c4b48e02263fbf4a3c01e3fe4f81d584d9"

git -C "${ROOT_DIR}" submodule update --init --recursive tools/superpowers

actual_commit="$(git -C "${SUPERPOWERS_DIR}" rev-parse HEAD)"
if [[ "${actual_commit}" != "${EXPECTED_COMMIT}" ]]; then
  echo "error: Superpowers pin ${actual_commit} does not match ${EXPECTED_COMMIT}" >&2
  exit 1
fi

actual_tag="$(git -C "${SUPERPOWERS_DIR}" describe --tags --exact-match)"
if [[ "${actual_tag}" != "${EXPECTED_TAG}" ]]; then
  echo "error: Superpowers tag ${actual_tag} does not match ${EXPECTED_TAG}" >&2
  exit 1
fi

codex plugin marketplace add "${SUPERPOWERS_DIR}" --json
codex plugin add superpowers@superpowers-dev --json
codex plugin list | sed -n '/Marketplace `superpowers-dev`/,+4p'
