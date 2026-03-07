from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path


def _norm_dir(path: Path | None) -> Path | None:
    if path is None:
        return None
    try:
        path = path.expanduser().resolve()
    except Exception:
        return None
    return path if path.is_dir() else None


def resolve_linxcore_root(start: Path | None = None) -> Path:
    seed = (start or Path(__file__)).resolve()
    for cand in [seed, *seed.parents]:
        if (cand / "src" / "linxcore_top.py").is_file() and (cand / "tools").is_dir():
            return cand
    raise RuntimeError(f"failed to locate LinxCore root from {seed}")


def _git_superproject_root(repo_root: Path) -> Path | None:
    try:
        out = subprocess.check_output(
            ["git", "-C", str(repo_root), "rev-parse", "--show-superproject-working-tree"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except Exception:
        return None
    return _norm_dir(Path(out)) if out else None


def resolve_linxisa_root(linxcore_root: Path | None = None) -> Path | None:
    root = resolve_linxcore_root(linxcore_root)
    for key in ("LINXISA_ROOT", "LINXISA_DIR"):
        cand = _norm_dir(Path(os.environ[key])) if os.environ.get(key) else None
        if cand is not None:
            return cand

    super_root = _git_superproject_root(root)
    if super_root is not None:
        return super_root

    for cand in (
        root.parent / "linx-isa",
        root.parent.parent / "linx-isa",
        Path.home() / "linx-isa",
        Path("/Users/zhoubot/linx-isa"),
    ):
        norm = _norm_dir(cand)
        if norm is not None:
            return norm
    return None


def resolve_qemu_linx_dir(linxcore_root: Path | None = None) -> Path | None:
    root = resolve_linxcore_root(linxcore_root)
    if os.environ.get("QEMU_LINX_DIR"):
        cand = _norm_dir(Path(os.environ["QEMU_LINX_DIR"]))
        if cand is not None:
            return cand

    if os.environ.get("LINXCORE_QEMU_ROOT"):
        cand = _norm_dir(Path(os.environ["LINXCORE_QEMU_ROOT"]) / "target" / "linx")
        if cand is not None:
            return cand

    linxisa_root = resolve_linxisa_root(root)
    if linxisa_root is not None:
        cand = _norm_dir(linxisa_root / "emulator" / "qemu" / "target" / "linx")
        if cand is not None:
            return cand

    for cand in (
        root.parent / "qemu" / "target" / "linx",
        root.parent.parent / "emulator" / "qemu" / "target" / "linx",
        Path.home() / "qemu" / "target" / "linx",
        Path("/Users/zhoubot/qemu/target/linx"),
    ):
        norm = _norm_dir(cand)
        if norm is not None:
            return norm
    return None


def resolve_llvm_readelf() -> Path | None:
    if os.environ.get("LLVM_READELF"):
        cand = _norm_dir(Path(os.environ["LLVM_READELF"]).parent)
        if cand is not None:
            p = cand / Path(os.environ["LLVM_READELF"]).name
            if p.is_file() and os.access(p, os.X_OK):
                return p

    for cand in (
        Path.home() / "llvm-project" / "build-linxisa-clang" / "bin" / "llvm-readelf",
        Path("/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-readelf"),
    ):
        try:
            c = cand.expanduser().resolve()
        except Exception:
            continue
        if c.is_file() and os.access(c, os.X_OK):
            return c

    for tool in ("llvm-readelf", "readelf"):
        found = shutil.which(tool)
        if found:
            try:
                p = Path(found).resolve()
            except Exception:
                continue
            if p.is_file() and os.access(p, os.X_OK):
                return p
    return None
