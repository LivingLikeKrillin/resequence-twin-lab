"""
render.py — CLI entry point for the resequence-twin CPU schematic GIF renderer.

Renders a PBS trajectory as an **animated GIF schematic** (2D data-visualisation)
using matplotlib's Agg backend — no GPU, no USD viewer, no display required.

IMPORTANT: This is a 2D schematic, NOT the 3D USD render.
  The .usda file (produced by viz/export.py / usd_exporter.py) is the 3D digital
  twin intended for usdview / Omniverse on an RTX machine.
  Use this renderer on CPU-only / GPU-less machines to inspect resequencing dynamics.

Usage::

    # Fetch from running control service (default http://localhost:8081):
    python -m viz.render --seed 42 --bodies 100 --policy dynamic --out pbs_twin.gif

    # Load from a saved JSON file (fully offline):
    python -m viz.render --from-file trajectory.json --out pbs_twin.gif

    # Control FPS and resolution:
    python -m viz.render --from-file trajectory.json --out pbs_twin.gif --fps 4 --dpi 96

Environment:
    RESEQUENCE_TWIN_CONTROL_URL — override default http://localhost:8081
"""
from __future__ import annotations

import argparse
import json
import os
import sys


def _fetch_trajectory(seed: int, bodies: int, policy: str, base_url: str) -> dict:
    """Fetch a PBS trajectory from the control service REST API."""
    import httpx
    url = f"{base_url}/api/trajectory"
    params = {"seed": seed, "bodies": bodies, "policy": policy}
    resp = httpx.get(url, params=params, timeout=30.0)
    resp.raise_for_status()
    return resp.json()


def main(argv: list[str] | None = None) -> int:
    """Entry point for the schematic GIF renderer CLI."""
    parser = argparse.ArgumentParser(
        description=(
            "Render PBS trajectory as an animated GIF schematic (2D, CPU-only).\n"
            "NOTE: This is a SCHEMATIC — not the 3D USD render.\n"
            "      The .usda 3D twin is for usdview/Omniverse on an RTX machine."
        )
    )
    parser.add_argument(
        "--seed", type=int, default=42,
        help="RNG seed (default 42)",
    )
    parser.add_argument(
        "--bodies", type=int, default=100,
        help="Number of bodies (default 100)",
    )
    parser.add_argument(
        "--policy", type=str, default="dynamic",
        help="Sequencing policy (default dynamic)",
    )
    parser.add_argument(
        "--out", type=str, default="pbs_twin.gif",
        help="Output animated GIF path (default pbs_twin.gif)",
    )
    parser.add_argument(
        "--from-file", type=str, default=None, dest="from_file",
        help="Load trajectory from a JSON file instead of fetching from REST",
    )
    parser.add_argument(
        "--fps", type=int, default=4,
        help="Frames per second for the animated GIF (default 4)",
    )
    parser.add_argument(
        "--dpi", type=int, default=96,
        help="Resolution in dots per inch (default 96)",
    )
    args = parser.parse_args(argv)

    # ---- Load trajectory ----
    if args.from_file:
        with open(args.from_file, encoding="utf-8") as f:
            trajectory = json.load(f)
        print(f"Loaded trajectory from {args.from_file!r}")
    else:
        base_url = os.environ.get("RESEQUENCE_TWIN_CONTROL_URL", "http://localhost:8081")
        print(
            f"Fetching trajectory from {base_url} "
            f"(seed={args.seed}, bodies={args.bodies}, policy={args.policy})..."
        )
        trajectory = _fetch_trajectory(args.seed, args.bodies, args.policy, base_url)

    n_bodies = len(trajectory.get("bodies", {}))
    n_frames = len(trajectory.get("frames", []))
    n_lanes  = len(trajectory.get("lanes",  []))
    seed     = trajectory.get("seed", args.seed)
    policy   = trajectory.get("policy", args.policy)

    print(
        f"Rendering schematic GIF: {n_bodies} bodies, {n_frames} frames, "
        f"{n_lanes} lanes  (seed={seed}, policy={policy})..."
    )

    from .schematic import render_frames
    render_frames(trajectory, args.out, fps=args.fps, dpi=args.dpi)

    print(f"Written: {args.out}")
    print(
        f"Summary: {n_bodies} bodies, {n_frames} frames, {n_lanes} lanes, "
        f"{args.fps} fps, {args.dpi} dpi"
    )
    print()
    print(
        "NOTE: This is a 2D SCHEMATIC (data-visualisation of the PBS trajectory).\n"
        "      It is NOT the photoreal 3D render.\n"
        "      The 3D twin (.usda) is produced by 'python -m viz.export' and is\n"
        "      intended for usdview / Omniverse on an RTX machine."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
