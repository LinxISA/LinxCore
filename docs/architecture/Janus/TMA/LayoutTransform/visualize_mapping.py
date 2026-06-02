#!/usr/bin/env python3
"""
NZ↔ND' Layout Transform Visualizer

Visualizes the mapping between two data layouts (ND on top, ZZ on bottom)
for a tile composed of R×C fractals, each with Fh=16 rows of 32B.

For R=1 C=8, also generates a swizzle diagram showing:
  Top: ND logical order
  Middle: TR physical bank position (after swizzle)
  Bottom: ZZ output order

Usage: python3 visualize_mapping.py <R>
       R × C = 8 (C is derived)
"""

import sys
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.collections import LineCollection
import numpy as np

# ============================================================
# CONFIGURATION: Layout formulas
# Modify these to test different mappings.
# Each formula takes (fr, fc, lr, R, C, Fh) and returns a linear index.
# ============================================================

Fh = 16  # Fractal height (rows per fractal)
TOTAL_FRACTALS = 8  # R * C = 8

def left_index(fr, fc, lr, R, C):
    """ND layout (top row): plain row-major, no fractal structure."""
    return (lr + fr * 16) * C + fc

def right_index(fr, fc, lr, R, C):
    """ZZ layout (bottom row): fractal-internal row-major, fractal-external row-major."""
    return (fr * C + fc) * 16 + lr

LEFT_FORMULA = "(lr + fr*16)*C + fc"
RIGHT_FORMULA = "(fr*C + fc)*16 + lr"
LEFT_LABEL = "ND"
RIGHT_LABEL = "ZZ"

# ============================================================
# Swizzle formula for R=1, C=8
# ============================================================

def swizzle_bank_pos(nd_pos, R, C):
    """Compute TR physical bank position after swizzle.

    Physical bank order = ND order (fc is fastest-varying dimension).
    For R=1 C=8:
      - Address 0 (nd_pos 0-63): no change
      - Address 1 (nd_pos 64-127): within each 8-element row, swap fc 0-3 with fc 4-7
        i.e., fc XOR 4
    For other shapes: identity (no swizzle needed).
    """
    if R != 1 or C != 8:
        return nd_pos
    lr = nd_pos // C
    fc = nd_pos % C
    addr = lr // 8
    lr_in_addr = lr % 8
    if addr == 0:
        return nd_pos
    else:
        return 64 + lr_in_addr * 8 + (fc ^ 4)

SWIZZLE_FORMULA = "addr0: identity; addr1: fc XOR 4 within each row"

# ============================================================
# END CONFIGURATION
# ============================================================

def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <R>")
        print(f"  R × C = {TOTAL_FRACTALS}, C is derived.")
        sys.exit(1)

    R = int(sys.argv[1])
    if TOTAL_FRACTALS % R != 0:
        print(f"Error: R={R} does not divide {TOTAL_FRACTALS}")
        sys.exit(1)
    C = TOTAL_FRACTALS // R

    total_elements = R * C * Fh
    print(f"Parameters: R={R}, C={C}, Fh={Fh}")
    print(f"Total elements: {total_elements}")
    print(f"Left  ({LEFT_LABEL}): {LEFT_FORMULA}")
    print(f"Right ({RIGHT_LABEL}): {RIGHT_FORMULA}")
    print()

    # Compute mappings
    mappings = []  # list of (left_idx, right_idx, fr, fc, lr)
    warnings = []

    for fr in range(R):
        for fc in range(C):
            for lr in range(Fh):
                li = left_index(fr, fc, lr, R, C)
                ri = right_index(fr, fc, lr, R, C)
                mappings.append((li, ri, fr, fc, lr))

    # Validation
    left_indices = [m[0] for m in mappings]
    right_indices = [m[1] for m in mappings]

    # Range check
    left_oor = [(i, m) for i, m in enumerate(mappings)
                if m[0] < 0 or m[0] >= total_elements]
    right_oor = [(i, m) for i, m in enumerate(mappings)
                 if m[1] < 0 or m[1] >= total_elements]

    if left_oor:
        warnings.append(
            f"WARNING: {len(left_oor)} left indices out of range [0, {total_elements-1}]")
        for i, m in left_oor[:5]:
            warnings.append(
                f"  (fr={m[2]}, fc={m[3]}, lr={m[4]}) -> left={m[0]}")

    if right_oor:
        warnings.append(
            f"WARNING: {len(right_oor)} right indices out of range [0, {total_elements-1}]")
        for i, m in right_oor[:5]:
            warnings.append(
                f"  (fr={m[2]}, fc={m[3]}, lr={m[4]}) -> right={m[1]}")

    # Duplicate check: multiple (fr,fc,lr) mapping to same left index
    left_counts = {}
    for m in mappings:
        left_counts.setdefault(m[0], []).append((m[2], m[3], m[4]))
    left_dups = {k: v for k, v in left_counts.items() if len(v) > 1}
    if left_dups:
        warnings.append(
            f"WARNING: {len(left_dups)} left positions have multiple sources (formula bug!)")
        for idx, sources in list(left_dups.items())[:5]:
            warnings.append(f"  left[{idx}] <- {sources}")

    # Duplicate check: multiple (fr,fc,lr) mapping to same right index
    right_counts = {}
    for m in mappings:
        right_counts.setdefault(m[1], []).append((m[2], m[3], m[4]))
    right_dups = {k: v for k, v in right_counts.items() if len(v) > 1}
    if right_dups:
        warnings.append(
            f"WARNING: {len(right_dups)} right positions have multiple sources (formula bug!)")
        for idx, sources in list(right_dups.items())[:5]:
            warnings.append(f"  right[{idx}] <- {sources}")

    # Print warnings
    if warnings:
        print("=" * 60)
        for w in warnings:
            print(w)
        print("=" * 60)
        print()
    else:
        print("Validation passed: no range errors, no duplicates.")
        print()

    # --- Draw ND↔ZZ mapping (horizontal: ND top, ZZ bottom) ---
    draw_nd_zz(mappings, total_elements, R, C)

    # --- Draw swizzle diagram for R=1 C=8 ---
    if R == 1 and C == 8:
        draw_swizzle(mappings, total_elements, R, C)


def draw_nd_zz(mappings, total_elements, R, C):
    """Draw the ND (top) to ZZ (bottom) mapping."""
    fig, ax = plt.subplots(1, 1, figsize=(max(16, total_elements * 0.12), 10))

    y_top = 1.0
    y_bottom = 0.0
    x_positions = np.linspace(0.0, 1.0, total_elements)

    for i in range(total_elements):
        ax.plot(x_positions[i], y_top, 'o', color='steelblue',
                markersize=3, zorder=3)
        ax.plot(x_positions[i], y_bottom, 'o', color='darkorange',
                markersize=3, zorder=3)
        if i % 8 == 0:
            ax.text(x_positions[i], y_top + 0.05, str(i),
                    ha='center', va='bottom', fontsize=6)
            ax.text(x_positions[i], y_bottom - 0.05, str(i),
                    ha='center', va='top', fontsize=6)

    colors = plt.cm.tab10(np.linspace(0, 1, C))
    for li, ri, fr, fc, lr in mappings:
        if 0 <= li < total_elements and 0 <= ri < total_elements:
            color = colors[fc % len(colors)]
            ax.plot([x_positions[li], x_positions[ri]],
                    [y_top, y_bottom],
                    color=color, alpha=0.4, linewidth=0.6, zorder=1)

    legend_patches = [mpatches.Patch(color=colors[fc], label=f'fc={fc}')
                      for fc in range(C)]
    ax.legend(handles=legend_patches, loc='center right', ncol=1, fontsize=8)

    ax.set_ylim(-0.2, 1.2)
    ax.text(-0.02, y_top, f"{LEFT_LABEL}\n{LEFT_FORMULA}",
            ha='right', va='center', fontsize=8, color='steelblue')
    ax.text(-0.02, y_bottom, f"{RIGHT_LABEL}\n{RIGHT_FORMULA}",
            ha='right', va='center', fontsize=8, color='darkorange')
    ax.set_title(f"Layout Mapping: {LEFT_LABEL} ↔ {RIGHT_LABEL}  "
                 f"(R={R}, C={C}, Fh={Fh}, elements={total_elements})",
                 fontsize=10)
    ax.axis('off')
    plt.tight_layout()
    out_path = f"mapping_R{R}_C{C}.png"
    plt.savefig(out_path, dpi=150, bbox_inches='tight')
    print(f"Saved: {out_path}")
    plt.close()


def draw_swizzle(mappings, total_elements, R, C):
    """Draw three-row swizzle diagram: ND → TR bank pos → ZZ."""
    fig, ax = plt.subplots(1, 1, figsize=(max(16, total_elements * 0.12), 14))

    y_top = 1.0     # ND logical order
    y_mid = 0.5     # TR physical bank position (after swizzle)
    y_bottom = 0.0  # ZZ output order
    x_positions = np.linspace(0.0, 1.0, total_elements)

    # Draw nodes on all three rows
    for i in range(total_elements):
        ax.plot(x_positions[i], y_top, 'o', color='steelblue',
                markersize=3, zorder=3)
        ax.plot(x_positions[i], y_mid, 's', color='green',
                markersize=3, zorder=3)
        ax.plot(x_positions[i], y_bottom, 'o', color='darkorange',
                markersize=3, zorder=3)
        if i % 8 == 0:
            ax.text(x_positions[i], y_top + 0.03, str(i),
                    ha='center', va='bottom', fontsize=5)
            ax.text(x_positions[i], y_mid + 0.03, str(i),
                    ha='center', va='bottom', fontsize=5)
            ax.text(x_positions[i], y_bottom - 0.03, str(i),
                    ha='center', va='top', fontsize=5)

    # Draw address boundary on TR row
    ax.axvline(x=x_positions[64], color='gray', linestyle='--',
               linewidth=0.8, alpha=0.5)
    ax.text(x_positions[64], y_mid + 0.06, 'addr boundary',
            ha='center', fontsize=7, color='gray')

    colors = plt.cm.tab10(np.linspace(0, 1, C))

    for li, ri, fr, fc, lr in mappings:
        if not (0 <= li < total_elements and 0 <= ri < total_elements):
            continue
        bank_pos = swizzle_bank_pos(li, R, C)
        color = colors[fc % len(colors)]

        # ND → TR bank pos
        ax.plot([x_positions[li], x_positions[bank_pos]],
                [y_top, y_mid],
                color=color, alpha=0.35, linewidth=0.5, zorder=1)
        # TR bank pos → ZZ
        ax.plot([x_positions[bank_pos], x_positions[ri]],
                [y_mid, y_bottom],
                color=color, alpha=0.35, linewidth=0.5, zorder=1)

    legend_patches = [mpatches.Patch(color=colors[fc], label=f'fc={fc}')
                      for fc in range(C)]
    ax.legend(handles=legend_patches, loc='center right', ncol=1, fontsize=8)

    ax.set_ylim(-0.15, 1.15)
    ax.text(-0.02, y_top, f"{LEFT_LABEL}\n(logical)",
            ha='right', va='center', fontsize=8, color='steelblue')
    ax.text(-0.02, y_mid, f"TR bank pos\n(swizzled)",
            ha='right', va='center', fontsize=8, color='green')
    ax.text(-0.02, y_bottom, f"{RIGHT_LABEL}\n(output)",
            ha='right', va='center', fontsize=8, color='darkorange')
    ax.set_title(f"Swizzle Diagram: {LEFT_LABEL} → TR → {RIGHT_LABEL}  "
                 f"(R={R}, C={C})\n"
                 f"Swizzle: {SWIZZLE_FORMULA}",
                 fontsize=10)
    ax.axis('off')
    plt.tight_layout()
    out_path = f"swizzle_R{R}_C{C}.png"
    plt.savefig(out_path, dpi=150, bbox_inches='tight')
    print(f"Saved: {out_path}")
    plt.close()


if __name__ == "__main__":
    main()
