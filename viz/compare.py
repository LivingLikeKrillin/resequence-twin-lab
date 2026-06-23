"""
compare.py — Static-vs-dynamic PBS comparison GIF renderer.

Renders ONE animated GIF with TWO stacked panels (static policy on top,
dynamic policy on bottom) sharing the time axis. A live colour-change KPI
counter is shown per panel so the viewer instantly sees that the dynamic
multi-objective resequencing yields longer colour batches / fewer colour
changes than the static baseline.

Layout: static panel (top) / dynamic panel (bottom), synchronized by frame
index. Frame alignment: if the two trajectories have different frame counts,
the shorter trajectory's last frame is frozen (held) for the remaining steps.

KPI: colour changes so far = consecutive pairs of released bodies with
different paint colours in the released sequence (computed live from the
trajectory data — the real benchmark KPI).

Usage::

    # From running control service:
    python -m viz.compare --seed 42 --bodies 100 --out pbs_compare.gif

    # From saved JSON files (fully offline):
    python -m viz.compare --static-file static.json --dynamic-file dynamic.json --out pbs_compare.gif

Headless CPU only (matplotlib Agg backend). No GPU, no network in the
render_comparison() function itself.

HONESTY NOTE: This is a 2D schematic data-visualisation, NOT a photoreal
3D render. The colour-change counts are the real KPI computed from the
released sequence data — that is the legitimate quantitative punchline.
"""
from __future__ import annotations

import io
from typing import Any

import matplotlib
matplotlib.use("Agg")  # headless — must be set before pyplot import

import matplotlib.patches as mpatches
from matplotlib.figure import Figure
from PIL import Image

from .schematic import (
    count_color_changes,
    _draw_panel,
    _release_sequence_up_to,
    _CELL_W,
    _CELL_H,
    _LANE_GAP,
    _STRIP_H,
    _STRIP_GAP,
)


def render_comparison(
    traj_static: dict,
    traj_dynamic: dict,
    out_path: str,
    *,
    fps: int = 4,
    dpi: int = 96,
) -> None:
    """Render a side-by-side comparison GIF: static policy (top) vs dynamic policy (bottom).

    Produces ONE animated GIF with TWO stacked panels sharing the time axis.
    Each panel shows the full per-frame schematic including lane rows, assembly-output
    rail, and colour strips. A live KPI counter (colour changes so far in the released
    sequence) is shown per panel so the viewer can see the dynamic policy build
    longer colour batches.

    Frame alignment: N = max(len(static_frames), len(dynamic_frames)).
    For the shorter trajectory, the last frame is frozen (held) for remaining steps.

    Validation: both trajectories must share the same seed and bodies dict keys.
    Raises ValueError if they don't.

    Args:
        traj_static: PBS trajectory dict for the static (round-robin + FIFO) policy.
        traj_dynamic: PBS trajectory dict for the dynamic (multi-objective) policy.
        out_path: Destination path for the animated comparison GIF.
        fps: Frames per second for the GIF (default 4).
        dpi: Dots per inch for each frame (default 96).
    """
    # --- Validation ---
    if traj_static["seed"] != traj_dynamic["seed"]:
        raise ValueError(
            f"Trajectories have mismatched seeds: "
            f"static seed={traj_static['seed']!r}, dynamic seed={traj_dynamic['seed']!r}. "
            f"Both trajectories must share the same seed."
        )
    static_body_keys = set(traj_static["bodies"].keys())
    dynamic_body_keys = set(traj_dynamic["bodies"].keys())
    if static_body_keys != dynamic_body_keys:
        only_static = static_body_keys - dynamic_body_keys
        only_dynamic = dynamic_body_keys - static_body_keys
        raise ValueError(
            f"Trajectories have mismatched bodies dicts. "
            f"Only in static: {only_static!r}. Only in dynamic: {only_dynamic!r}."
        )

    seed = traj_static["seed"]
    n_bodies = len(traj_static["bodies"])

    static_frames = traj_static["frames"]
    dynamic_frames = traj_dynamic["frames"]
    N = max(len(static_frames), len(dynamic_frames))

    if N == 0:
        raise ValueError("trajectory contains no frames — nothing to render")

    # --- Layout geometry ---
    lanes_spec = traj_static["lanes"]
    max_capacity = max(lane["capacity"] for lane in lanes_spec)
    n_lanes = len(lanes_spec)

    fig_w = max(4.0, max_capacity * _CELL_W * 1.1 + 2.0)

    # panel_h mirrors total_h from _draw_panel (data-unit height of each panel's
    # content, converted to figure inches via the axes fraction later).
    panel_h = 2 * (_STRIP_H + _STRIP_GAP) + n_lanes * (_CELL_H + _LANE_GAP) + (_CELL_H + _LANE_GAP) + 0.6

    # Layout in figure-inches (top → bottom):
    #   top_margin   : 0.40 in  — room for the main figure title
    #   panel_h      : each panel's height in inches
    #   gap          : 0.60 in  — clear visual separation between panels
    #   bottom_margin: 0.20 in  — breathing room below dynamic panel
    _TOP_MARGIN: float = 0.40
    _PANEL_GAP: float = 0.60    # generous gap so dynamic header never overlaps static
    _BOTTOM_MARGIN: float = 0.20

    fig_total_h = _TOP_MARGIN + panel_h + _PANEL_GAP + panel_h + _BOTTOM_MARGIN

    # Compute axes rectangles as [left, bottom, width, height] in figure fraction.
    # Both panels span the full width (left=0.02, width=0.96 to leave a tiny margin).
    _PANEL_LEFT: float = 0.02
    _PANEL_W: float = 0.96

    # Bottom of each panel in figure fraction (matplotlib measures from bottom-left)
    bottom_panel_bottom = _BOTTOM_MARGIN / fig_total_h
    top_panel_bottom = (_BOTTOM_MARGIN + panel_h + _PANEL_GAP) / fig_total_h
    panel_h_frac = panel_h / fig_total_h

    # Horizontal separator line position in figure fraction
    sep_y_frac = (_BOTTOM_MARGIN + panel_h + _PANEL_GAP / 2) / fig_total_h

    frame_images: list[Image.Image] = []

    for i in range(N):
        static_frame_idx = min(i, len(static_frames) - 1)
        dynamic_frame_idx = min(i, len(dynamic_frames) - 1)

        # Compute step for title display (use static frame's step number)
        step = static_frames[static_frame_idx]["step"]

        # KPI: colour changes so far for each panel
        static_released = _release_sequence_up_to(static_frames, static_frames[static_frame_idx]["step"])
        static_changes = count_color_changes(static_released, traj_static["bodies"])

        dynamic_released = _release_sequence_up_to(dynamic_frames, dynamic_frames[dynamic_frame_idx]["step"])
        dynamic_changes = count_color_changes(dynamic_released, traj_dynamic["bodies"])

        # Create figure using matplotlib.figure.Figure directly (headless Agg)
        fig = Figure(figsize=(fig_w, fig_total_h), dpi=dpi)
        fig.patch.set_facecolor("white")

        # Main figure title at the very top
        fig.text(
            0.5, 1.0 - (0.06 / fig_total_h),
            f"PBS resequencing — Static vs Dynamic  ·  seed {seed}, {n_bodies} bodies  ·  SCHEMATIC (not 3D render)",
            ha="center", va="top", fontsize=9, fontweight="bold",
        )

        # Horizontal separator line between the two panels (drawn on the figure)
        line = mpatches.FancyArrowPatch(
            posA=(0.01, sep_y_frac), posB=(0.99, sep_y_frac),
            arrowstyle="-",
            color="#AAAAAA",
            linewidth=1.2,
            transform=fig.transFigure,
            figure=fig,
        )
        fig.add_artist(line)

        # (Panel background tints removed — they washed out contrast / looked hazy.
        #  Clean white figure background + the separator line keep the two panels
        #  distinct without reducing legibility.)

        # Top panel (static) — uses the computed rectangle
        ax_top = fig.add_axes([_PANEL_LEFT, top_panel_bottom, _PANEL_W, panel_h_frac])
        _draw_panel(
            ax_top,
            traj_static,
            static_frame_idx,
            title=f"STATIC (round-robin+FIFO)  step {step}/{N-1}",
            color_changes_so_far=static_changes,
        )

        # Bottom panel (dynamic) — separated by _PANEL_GAP from the top panel
        ax_bottom = fig.add_axes([_PANEL_LEFT, bottom_panel_bottom, _PANEL_W, panel_h_frac])
        _draw_panel(
            ax_bottom,
            traj_dynamic,
            dynamic_frame_idx,
            title=f"DYNAMIC (multi-objective)  step {dynamic_frames[dynamic_frame_idx]['step']}/{N-1}",
            color_changes_so_far=dynamic_changes,
        )

        # Render frame to PIL Image via in-memory buffer
        buf = io.BytesIO()
        fig.savefig(buf, format="png", dpi=dpi, bbox_inches=None)
        buf.seek(0)
        img = Image.open(buf).convert("RGB")
        # Must copy out of buffer before it's GC'd
        img.load()
        frame_images.append(img)

    # --- Assemble GIF ---
    duration_ms = int(1000 / fps)
    first = frame_images[0]
    rest = frame_images[1:]
    first.save(
        out_path,
        format="GIF",
        save_all=True,
        append_images=rest,
        loop=0,
        duration=duration_ms,
        optimize=False,
    )


def _fetch_trajectory(seed: int, bodies: int, policy: str, base_url: str) -> dict:
    """Fetch a PBS trajectory from the control service REST API."""
    import httpx
    url = f"{base_url}/api/trajectory"
    params = {"seed": seed, "bodies": bodies, "policy": policy}
    resp = httpx.get(url, params=params, timeout=30.0)
    resp.raise_for_status()
    return resp.json()


def main(argv=None):
    """CLI entry point for the static-vs-dynamic comparison GIF."""
    import argparse, json, os, sys
    parser = argparse.ArgumentParser(
        description="Render static-vs-dynamic PBS comparison as animated GIF (2D schematic, CPU-only)."
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--bodies", type=int, default=100)
    parser.add_argument("--out", type=str, default="pbs_compare.gif")
    parser.add_argument("--static-file", type=str, default=None, dest="static_file",
                        help="Load static trajectory from JSON file (offline)")
    parser.add_argument("--dynamic-file", type=str, default=None, dest="dynamic_file",
                        help="Load dynamic trajectory from JSON file (offline)")
    parser.add_argument("--fps", type=int, default=4)
    parser.add_argument("--dpi", type=int, default=96)
    args = parser.parse_args(argv)

    base_url = os.environ.get("RESEQUENCE_TWIN_CONTROL_URL", "http://localhost:8081")

    if args.static_file:
        with open(args.static_file, encoding="utf-8") as f:
            traj_static = json.load(f)
        print(f"Loaded static trajectory from {args.static_file!r}")
    else:
        print(f"Fetching static trajectory from {base_url} (seed={args.seed}, bodies={args.bodies}, policy=static)...")
        traj_static = _fetch_trajectory(args.seed, args.bodies, "static", base_url)

    if args.dynamic_file:
        with open(args.dynamic_file, encoding="utf-8") as f:
            traj_dynamic = json.load(f)
        print(f"Loaded dynamic trajectory from {args.dynamic_file!r}")
    else:
        print(f"Fetching dynamic trajectory from {base_url} (seed={args.seed}, bodies={args.bodies}, policy=dynamic)...")
        traj_dynamic = _fetch_trajectory(args.seed, args.bodies, "dynamic", base_url)

    n_bodies = len(traj_static.get("bodies", {}))
    n_frames_s = len(traj_static.get("frames", []))
    n_frames_d = len(traj_dynamic.get("frames", []))

    print(f"Rendering comparison GIF: {n_bodies} bodies, static={n_frames_s} frames, dynamic={n_frames_d} frames...")
    render_comparison(traj_static, traj_dynamic, args.out, fps=args.fps, dpi=args.dpi)

    print(f"Written: {args.out}")
    print(f"Summary: {n_bodies} bodies, {max(n_frames_s, n_frames_d)} total frames, seed={args.seed}, {args.fps} fps, {args.dpi} dpi")
    print()
    print("NOTE: This is a 2D SCHEMATIC (static-vs-dynamic comparison of the PBS trajectory).")
    print("      It is NOT a photoreal 3D render.")
    print("      Colour-change counts are the real KPI computed from the released sequence.")
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
