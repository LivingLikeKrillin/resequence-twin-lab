"""
schematic.py — CPU schematic renderer for PBS trajectory data.

Turns a PBS trajectory dict into an **animated GIF (+ PNG stills)** using
matplotlib's Agg backend (headless, no display, no GPU required).

IMPORTANT — HONESTY FRAMING
----------------------------
This is a **2D schematic / data-visualisation** of the PBS resequencing
trajectory.  It is explicitly NOT the photoreal 3D Omniverse render.
The 3D twin (pbs_twin.usda) is produced by usd_exporter.py and is
intended for viewing on an RTX machine with usdview / Omniverse.
This schematic exists because ``pip install usd-core`` ships NO imaging
(UsdImagingGL / Hydra is absent), so the .usda cannot be rendered on a
GPU-less PC.  Use this file to inspect the trajectory on any machine that
has Python + matplotlib + Pillow.

Layout per frame (top-down schematic)
--------------------------------------
- One horizontal lane row per lane (L1/L2/L3 …), labelled on the left.
  Each row shows ``capacity`` slot cells; each occupied cell is a filled
  rectangle coloured by body paint colour, with the front-of-lane (index 0 /
  release side) on the left and an arrow indicating direction.
  Light colours (WHITE/SILVER) get a dark outline so they are visible on the
  white background.
- An **Assembly-output rail** row below the lanes, accumulating released
  bodies left-to-right in release order — this is where the resequencing
  result becomes visible.
- The body released this step (if any) gets a bold orange outline.
- Two thin **colour-strip bars** above the lanes:
  * *Paint-input order*: bodies in ``dueDateSeq`` order (arrival order).
  * *Assembly-output order*: released bodies so far.
  These strips let the viewer literally see colour-batching / resequencing
  emerge step by step.
- Title annotation: seed, policy, step i / N, and a
  "SCHEMATIC (not the 3D render)" label.

GIF assembly
------------
Frames are rendered to an in-memory PIL buffer and assembled via
``PIL.Image.save(append_images=…)`` — no imageio dependency required.
"""
from __future__ import annotations

import io
import os
from typing import Any

import matplotlib
matplotlib.use("Agg")  # headless — must be set before pyplot import

import matplotlib.patches as mpatches
import matplotlib.pyplot as plt
import matplotlib.patheffects as pe
from matplotlib.figure import Figure
import numpy as np
from PIL import Image

# ---------------------------------------------------------------------------
# Color map (plain Python tuples — no USD / Gf dependency)
# Mirrors usd_exporter.COLOR_MAP but expressed as (R, G, B) float tuples
# in [0, 1] for direct matplotlib use.
# ---------------------------------------------------------------------------

#: Paint colour → (R, G, B) in [0, 1]  (linear-sRGB)
SCHEMATIC_COLOR_MAP: dict[str, tuple[float, float, float]] = {
    "RED":    (0.85, 0.10, 0.10),
    "BLUE":   (0.10, 0.25, 0.85),
    "WHITE":  (0.92, 0.92, 0.92),
    "BLACK":  (0.08, 0.08, 0.08),
    "SILVER": (0.60, 0.62, 0.65),
}

#: Colours that need a dark outline to stay visible on a light background
_NEEDS_OUTLINE: frozenset[str] = frozenset({"WHITE", "SILVER"})

#: Fallback colour for unknown paint names
_FALLBACK_COLOR: tuple[float, float, float] = (0.70, 0.70, 0.70)

# Layout constants
_CELL_W: float = 1.0      # width of each slot cell (data units)
_CELL_H: float = 0.6      # height of each slot cell
_LANE_GAP: float = 0.2    # vertical gap between lanes
_STRIP_H: float = 0.18    # height of each colour-strip bar
_STRIP_GAP: float = 0.05  # gap between colour strip bars


# ---------------------------------------------------------------------------
# Public helpers
# ---------------------------------------------------------------------------

def body_facecolor(color_name: str) -> tuple[float, float, float]:
    """Return the matplotlib (R, G, B) tuple for a body paint colour.

    Falls back to a neutral grey for unknown names so callers never crash.

    Args:
        color_name: Paint colour string from the trajectory (e.g. ``"RED"``).

    Returns:
        ``(R, G, B)`` float tuple in ``[0, 1]``.
    """
    return SCHEMATIC_COLOR_MAP.get(color_name.upper(), _FALLBACK_COLOR)


def body_needs_outline(color_name: str) -> bool:
    """Return True if the colour requires a visible dark outline.

    WHITE and SILVER are too light to be distinguishable on a white
    background without a border.

    Args:
        color_name: Paint colour string from the trajectory.

    Returns:
        ``True`` if a dark border should be drawn around the body rectangle.
    """
    return color_name.upper() in _NEEDS_OUTLINE


def count_color_changes(released_ids: list[str], bodies: dict) -> int:
    """Count consecutive colour changes in a release sequence.

    A colour change is a consecutive pair of released bodies with different
    paint colours. E.g. [RED, RED, BLUE, BLUE, RED] → 2 changes.

    Args:
        released_ids: Ordered list of body IDs (e.g. ["BODY-00000", "BODY-00001"]).
        bodies: The ``bodies`` dict from the trajectory
                (maps body_id → {color, model, options, dueDateSeq}).

    Returns:
        Number of consecutive colour changes (0 if len(released_ids) < 2).
    """
    if len(released_ids) < 2:
        return 0
    changes = 0
    for i in range(1, len(released_ids)):
        prev_color = bodies[released_ids[i - 1]]["color"]
        curr_color = bodies[released_ids[i]]["color"]
        if prev_color != curr_color:
            changes += 1
    return changes


# ---------------------------------------------------------------------------
# Internal rendering helpers
# ---------------------------------------------------------------------------

def _sorted_bodies_by_due_date(bodies: dict[str, Any]) -> list[str]:
    """Return body IDs sorted by ``dueDateSeq`` (arrival / input order)."""
    return sorted(bodies.keys(), key=lambda bid: bodies[bid].get("dueDateSeq", 0))


def _release_sequence_up_to(frames: list[dict], step: int) -> list[str]:
    """Return list of body IDs released up to (and including) ``step``."""
    released: list[str] = []
    for frame in frames:
        if frame["step"] > step:
            break
        rel = frame.get("released")
        if rel and rel not in released:
            released.append(rel)
    return released


def _draw_body_cell(
    ax: plt.Axes,
    x: float,
    y: float,
    w: float,
    h: float,
    color_name: str,
    *,
    highlight: bool = False,
    body_id: str = "",
) -> None:
    """Draw a single body cell rectangle on *ax*.

    Args:
        ax: Matplotlib axes.
        x, y: Bottom-left corner of the cell (data units).
        w, h: Width and height.
        color_name: Paint colour name (e.g. ``"RED"``).
        highlight: If True, draw a bold orange border (body released this step).
        body_id: Short label to render inside the cell (first 3 chars of suffix).
    """
    fc = body_facecolor(color_name)
    needs_outline = body_needs_outline(color_name)

    edge_color = "black" if needs_outline else fc
    lw = 2.5 if needs_outline else 0.8

    if highlight:
        edge_color = "#FF8C00"  # dark orange
        lw = 3.0

    rect = mpatches.FancyBboxPatch(
        (x + 0.04, y + 0.04),
        w - 0.08,
        h - 0.08,
        boxstyle="round,pad=0.04",
        facecolor=fc,
        edgecolor=edge_color,
        linewidth=lw,
    )
    ax.add_patch(rect)

    # Short label inside cell (last 5 chars, e.g. "00000")
    if body_id:
        label = body_id[-5:] if len(body_id) >= 5 else body_id
        text_color = "white" if fc[0] < 0.4 and fc[2] < 0.4 else "black"
        # for BLUE, use white text
        if fc[2] > 0.6 and fc[0] < 0.4:
            text_color = "white"
        ax.text(
            x + w / 2,
            y + h / 2,
            label,
            ha="center",
            va="center",
            fontsize=5.5,
            color=text_color,
            clip_on=True,
        )


def _draw_panel(
    ax: plt.Axes,
    trajectory: dict[str, Any],
    frame_idx: int,
    *,
    title: str | None = None,
    color_changes_so_far: int | None = None,
) -> None:
    """Draw a single PBS trajectory frame into an existing Axes.

    This is the primary drawing routine; it populates a caller-supplied
    ``ax`` so that composite layouts (e.g. side-by-side comparison panels)
    can reuse the same logic without creating their own Figure.

    Args:
        ax: Matplotlib Axes to draw into.  The caller is responsible for
            creating and sizing the Figure.
        trajectory: Full PBS trajectory dict.
        frame_idx: Index into ``trajectory["frames"]``.
        title: Override the default title text.  If ``None``, the default
            ``"PBS Resequencing — seed=... policy=... step ..."`` is used.
        color_changes_so_far: If not ``None``, render a KPI readout below
            the title: ``"colour changes so far: {color_changes_so_far}"``.
    """
    lanes_spec: list[dict] = trajectory["lanes"]
    bodies_spec: dict[str, Any] = trajectory["bodies"]
    frames: list[dict] = trajectory["frames"]
    seed = trajectory.get("seed", "?")
    policy = trajectory.get("policy", "?")

    frame = frames[frame_idx]
    step = frame["step"]
    n_steps = len(frames)
    released_this_step = frame.get("released")
    released_so_far = _release_sequence_up_to(frames, step)
    input_order = _sorted_bodies_by_due_date(bodies_spec)

    # Layout geometry (must match _render_single_frame)
    max_capacity = max(lane["capacity"] for lane in lanes_spec)
    n_lanes = len(lanes_spec)

    strip_total_h = 2 * (_STRIP_H + _STRIP_GAP)
    lane_block_h = n_lanes * (_CELL_H + _LANE_GAP)
    assembly_h = _CELL_H + _LANE_GAP
    total_h = strip_total_h + lane_block_h + assembly_h + 0.6  # 0.6 for title

    ax.set_xlim(-1.5, max_capacity * _CELL_W + 0.5)
    ax.set_ylim(0, total_h)
    ax.axis("off")

    # Title (top)
    title_y = total_h - 0.05
    title_text = (
        title
        if title is not None
        else (
            f"PBS Resequencing — seed={seed}  policy={policy}"
            f"  step {step}/{n_steps - 1}"
        )
    )
    ax.text(
        (max_capacity * _CELL_W) / 2,
        title_y,
        title_text,
        ha="center",
        va="top",
        fontsize=8,
        fontweight="bold",
        color="#222222",
    )
    ax.text(
        (max_capacity * _CELL_W) / 2,
        title_y - 0.22,
        "SCHEMATIC (not the 3D render) — the .usda is the 3D twin for an RTX machine",
        ha="center",
        va="top",
        fontsize=5.5,
        color="#888888",
        style="italic",
    )

    # Optional KPI readout below the subtitle
    if color_changes_so_far is not None:
        ax.text(
            (max_capacity * _CELL_W) / 2,
            title_y - 0.40,
            f"colour changes so far: {color_changes_so_far}",
            ha="center",
            va="top",
            fontsize=5.5,
            color="#336633",
        )

    # ---- Colour strips ----
    strip_top = total_h - 0.55

    # Strip 1: paint-input order
    input_strip_y = strip_top - _STRIP_H
    ax.text(-1.4, input_strip_y + _STRIP_H / 2, "Input", ha="left", va="center",
            fontsize=5, color="#555555")
    for i, bid in enumerate(input_order):
        color_name = bodies_spec[bid]["color"]
        fc = body_facecolor(color_name)
        strip_x = i * (max_capacity * _CELL_W / max(len(input_order), 1))
        strip_w = max_capacity * _CELL_W / max(len(input_order), 1) - 0.02
        ec = "grey" if body_needs_outline(color_name) else fc
        rect = mpatches.Rectangle(
            (strip_x, input_strip_y), strip_w, _STRIP_H,
            facecolor=fc, edgecolor=ec, linewidth=0.5
        )
        ax.add_patch(rect)

    # Strip 2: assembly-output order (so far)
    output_strip_y = input_strip_y - _STRIP_GAP - _STRIP_H
    ax.text(-1.4, output_strip_y + _STRIP_H / 2, "Output", ha="left", va="center",
            fontsize=5, color="#555555")
    slot_w = max_capacity * _CELL_W / max(len(input_order), 1)
    for i, bid in enumerate(released_so_far):
        color_name = bodies_spec[bid]["color"]
        fc = body_facecolor(color_name)
        strip_x = i * slot_w
        ec = "grey" if body_needs_outline(color_name) else fc
        rect = mpatches.Rectangle(
            (strip_x, output_strip_y), slot_w - 0.02, _STRIP_H,
            facecolor=fc, edgecolor=ec, linewidth=0.5
        )
        ax.add_patch(rect)
    # Remaining slots as empty grey outlines
    for i in range(len(released_so_far), len(input_order)):
        strip_x = i * slot_w
        rect = mpatches.Rectangle(
            (strip_x, output_strip_y), slot_w - 0.02, _STRIP_H,
            facecolor="#E8E8E8", edgecolor="#CCCCCC", linewidth=0.5
        )
        ax.add_patch(rect)

    # ---- Lane rows ----
    lane_top_y = output_strip_y - _STRIP_GAP - 0.1

    for l_idx, lane in enumerate(lanes_spec):
        lane_id = lane["id"]
        capacity = lane["capacity"]
        row_y = lane_top_y - (l_idx + 1) * (_CELL_H + _LANE_GAP)

        # Lane label
        ax.text(
            -0.1, row_y + _CELL_H / 2,
            lane_id,
            ha="right", va="center",
            fontsize=7, color="#333333", fontweight="bold",
        )

        # Slot background cells (empty slots)
        for slot_idx in range(capacity):
            sx = slot_idx * _CELL_W
            rect = mpatches.Rectangle(
                (sx, row_y), _CELL_W - 0.08, _CELL_H,
                facecolor="#EEEEEE", edgecolor="#CCCCCC", linewidth=0.5
            )
            ax.add_patch(rect)

        # Front-of-lane arrow (left side = index 0 = release side)
        ax.annotate(
            "",
            xy=(-0.05, row_y + _CELL_H / 2),
            xytext=(0.25, row_y + _CELL_H / 2),
            arrowprops=dict(arrowstyle="->", color="#999999", lw=0.8),
        )

        # Occupied cells
        occupants = frame["lanes"].get(lane_id, [])
        for slot_idx, body_id in enumerate(occupants):
            if slot_idx >= capacity:
                break
            color_name = bodies_spec[body_id]["color"]
            sx = slot_idx * _CELL_W
            highlight = (body_id == released_this_step)
            _draw_body_cell(
                ax, sx, row_y, _CELL_W, _CELL_H,
                color_name, highlight=highlight, body_id=body_id
            )

    # ---- Assembly-output rail ----
    assembly_y = lane_top_y - (n_lanes + 1) * (_CELL_H + _LANE_GAP)
    ax.text(
        -0.1, assembly_y + _CELL_H / 2,
        "Rail",
        ha="right", va="center",
        fontsize=7, color="#333333", fontweight="bold",
    )
    # Rail background
    rail_rect = mpatches.Rectangle(
        (0, assembly_y), max_capacity * _CELL_W, _CELL_H,
        facecolor="#E0EEE0", edgecolor="#88AA88", linewidth=1.0
    )
    ax.add_patch(rail_rect)
    # Released bodies on rail
    for rel_idx, body_id in enumerate(released_so_far):
        color_name = bodies_spec[body_id]["color"]
        rx = rel_idx * _CELL_W
        highlight = (body_id == released_this_step)
        _draw_body_cell(
            ax, rx, assembly_y, _CELL_W, _CELL_H,
            color_name, highlight=highlight, body_id=body_id
        )


def _render_single_frame(
    trajectory: dict[str, Any],
    frame_idx: int,
    fig_size: tuple[float, float],
    dpi: int,
) -> Image.Image:
    """Render a single frame to a PIL Image.

    Args:
        trajectory: Full PBS trajectory dict.
        frame_idx: Index into ``trajectory["frames"]``.
        fig_size: ``(width, height)`` in inches for matplotlib.
        dpi: Dots per inch.

    Returns:
        PIL Image of the rendered frame.
    """
    lanes_spec: list[dict] = trajectory["lanes"]
    n_lanes = len(lanes_spec)
    max_capacity = max(lane["capacity"] for lane in lanes_spec)

    strip_total_h = 2 * (_STRIP_H + _STRIP_GAP)
    lane_block_h = n_lanes * (_CELL_H + _LANE_GAP)
    assembly_h = _CELL_H + _LANE_GAP
    total_h = strip_total_h + lane_block_h + assembly_h + 0.6  # 0.6 for title

    fig_w, _ = fig_size
    fig = Figure(figsize=(fig_w, total_h), dpi=dpi)
    ax = fig.add_axes([0, 0, 1, 1])
    fig.patch.set_facecolor("#F8F8F8")

    _draw_panel(ax, trajectory, frame_idx)

    # Convert figure to PIL Image
    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=dpi, bbox_inches=None)
    plt.close(fig)
    buf.seek(0)
    return Image.open(buf).convert("RGB")


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def render_frames(
    trajectory: dict[str, Any],
    out_path: str,
    *,
    fps: int = 4,
    dpi: int = 96,
) -> None:
    """Render a PBS trajectory to an animated GIF.

    This is a **2D schematic** visualisation — NOT the 3D USD render.
    The .usda file produced by usd_exporter.py is the 3D twin for an
    RTX machine with usdview / Omniverse.  Use this function to inspect
    the trajectory on any CPU-only / GPU-less machine.

    Args:
        trajectory: PBS trajectory dict (from ``GET /api/trajectory``
            or a saved JSON file).  Must contain ``lanes``, ``bodies``,
            and ``frames`` keys.
        out_path: Destination path for the animated GIF
            (e.g. ``"pbs_twin.gif"``).
        fps: Frames per second for the GIF (default 4).
        dpi: Dots per inch for each frame (default 96).

    Returns:
        None.  Writes the GIF to *out_path*.
    """
    frames_data: list[dict] = trajectory["frames"]
    lanes_spec: list[dict] = trajectory["lanes"]
    max_capacity = max(lane["capacity"] for lane in lanes_spec)
    n_bodies = len(trajectory.get("bodies", {}))

    # Figure width: lanes + label margin, minimum 4 inches
    fig_w = max(4.0, max_capacity * _CELL_W * 1.1 + 2.0)
    fig_h = 6.0  # placeholder; overridden per-frame by total_h in _render_single_frame

    frame_images: list[Image.Image] = []
    for i in range(len(frames_data)):
        img = _render_single_frame(trajectory, i, (fig_w, fig_h), dpi)
        frame_images.append(img)

    if not frame_images:
        raise ValueError("trajectory contains no frames — nothing to render")

    # Assemble GIF via PIL (no imageio dependency)
    duration_ms = int(1000 / fps)
    first = frame_images[0]
    rest = frame_images[1:]
    first.save(
        out_path,
        format="GIF",
        save_all=True,
        append_images=rest,
        loop=0,           # loop forever
        duration=duration_ms,
        optimize=False,   # keep deterministic
    )


def render_png_sequence(
    trajectory: dict[str, Any],
    out_dir: str,
    *,
    dpi: int = 96,
) -> None:
    """Render each trajectory frame as a numbered PNG still.

    Writes ``frame_0000.png``, ``frame_0001.png``, … to *out_dir*.
    Useful for slide decks or manual inspection without a GIF viewer.

    Args:
        trajectory: PBS trajectory dict.
        out_dir: Directory to write PNG files into (must exist).
        dpi: Dots per inch for each frame (default 96).
    """
    frames_data: list[dict] = trajectory["frames"]
    lanes_spec: list[dict] = trajectory["lanes"]
    max_capacity = max(lane["capacity"] for lane in lanes_spec)

    fig_w = max(4.0, max_capacity * _CELL_W * 1.1 + 2.0)
    fig_h = 6.0

    for i in range(len(frames_data)):
        img = _render_single_frame(trajectory, i, (fig_w, fig_h), dpi)
        fname = os.path.join(out_dir, f"frame_{i:04d}.png")
        img.save(fname, format="PNG")
