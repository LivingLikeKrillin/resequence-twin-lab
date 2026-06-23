"""Gap wrapper + CLI: optimal (CP-SAT) vs heuristic (from fixture) colour changes."""
from __future__ import annotations
import argparse
import json
import sys
from dataclasses import dataclass, asdict
from solver.fixture import Instance, load_fixture
from solver.model import optimal_color_changes


@dataclass(frozen=True)
class GapResult:
    seed: int
    optimal_color_changes: int
    heuristic_color_changes: int
    gap: int                     # heuristic - optimal (>= 0 by construction)
    proven: bool
    due_date_deviation: float    # the heuristic's due-date "price" (honesty)


def gap(inst: Instance, time_limit_s: float = 10.0) -> GapResult:
    n = len(inst.colors)
    budget = round(n * inst.heuristic_due_date_deviation)
    res = optimal_color_changes(inst.colors, inst.lane_caps,
                                due_dates=inst.due_dates, max_due_dev_sum=budget,
                                time_limit_s=time_limit_s)
    return GapResult(
        seed=inst.seed,
        optimal_color_changes=res.optimal_color_changes,
        heuristic_color_changes=inst.heuristic_color_changes,
        gap=inst.heuristic_color_changes - res.optimal_color_changes,
        proven=res.proven,
        due_date_deviation=inst.heuristic_due_date_deviation,
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="CP-SAT optimality-gap oracle (offline, colour-axis).")
    ap.add_argument("--fixture", required=True, help="path to an instance fixture JSON")
    ap.add_argument("--time-limit", type=float, default=10.0)
    ap.add_argument("--json", action="store_true", help="emit machine-readable JSON on stdout")
    args = ap.parse_args(argv)
    try:
        r = gap(load_fixture(args.fixture), time_limit_s=args.time_limit)
    except Exception as e:  # clean error -> nonzero exit
        print(f"error: {e}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(asdict(r)))
    else:
        flag = "" if r.proven else "  (bound: solver hit time limit)"
        print(f"seed={r.seed} optimal={r.optimal_color_changes} "
              f"heuristic={r.heuristic_color_changes} gap={r.gap}{flag}", file=sys.stderr)
        print(f"  honesty: conservative upper bound (capacity-respecting relaxation); "
              f"heuristic dueDateDeviation={r.due_date_deviation}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
