from __future__ import annotations

# Compose-only root. Heavy integration logic lives in `export_core.py`.
from .export_core import build_top_export

__all__ = ["build_top_export"]
