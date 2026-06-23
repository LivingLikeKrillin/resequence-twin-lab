"""
export.py — CLI entry point for the resequence-twin viz USD exporter.

Usage::

    # Fetch from running control service:
    python -m viz.export --seed 42 --bodies 100 --policy dynamic --out pbs_twin.usda

    # Load from a saved JSON file:
    python -m viz.export --from-file trajectory.json --out pbs_twin.usda

Environment:
    RESEQUENCE_TWIN_CONTROL_URL — override default http://localhost:8081
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import httpx

from .usd_exporter import build_stage


def _fetch_trajectory(
    seed: int,
    bodies: int,
    policy: str,
    base_url: str,
) -> dict:
    """Fetch a PBS trajectory from the control service."""
    url = f"{base_url}/api/trajectory"
    params = {"seed": seed, "bodies": bodies, "policy": policy}
    resp = httpx.get(url, params=params, timeout=30.0)
    resp.raise_for_status()
    return resp.json()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Export PBS trajectory to OpenUSD (.usda) 3D twin scene"
    )
    parser.add_argument("--seed",    type=int, default=42,        help="RNG seed (default 42)")
    parser.add_argument("--bodies",  type=int, default=100,       help="Number of bodies (default 100)")
    parser.add_argument("--policy",  type=str, default="dynamic", help="Sequencing policy (default dynamic)")
    parser.add_argument("--out",     type=str, default="pbs_twin.usda", help="Output .usda path")
    parser.add_argument(
        "--from-file", type=str, default=None, dest="from_file",
        help="Load trajectory from a JSON file instead of fetching from control service",
    )
    args = parser.parse_args(argv)

    if args.from_file:
        with open(args.from_file, encoding="utf-8") as f:
            trajectory = json.load(f)
        print(f"Loaded trajectory from {args.from_file}")
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

    print(f"Building USD stage: {n_bodies} bodies, {n_frames} frames, {n_lanes} lanes...")
    build_stage(trajectory, args.out)

    print(
        f"{n_bodies} bodies, {n_frames} frames, {n_lanes} lanes "
        f"→ {args.out}; open in usdview/Blender/Omniverse"
    )
    print(f"  usdview: usdview {args.out}")
    print(f"  Blender: File > Import > Universal Scene Description (.usd)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
