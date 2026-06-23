"""Typed loading of committed instance fixtures (the Kotlin↔Python seam)."""
from __future__ import annotations
import json
from dataclasses import dataclass


@dataclass(frozen=True)
class Instance:
    """One PBS instance + the heuristic's measured KPIs, as exported by the Kotlin side.

    colors are the body colours in *arrival order* (length N). lane_caps are the lane
    capacities (length K). The CP-SAT oracle consumes only colors + lane_caps; the
    heuristic_* fields are used by the gap/report layer (honesty), never re-computed here.
    """
    seed: int
    lane_caps: list[int]
    colors: list[str]
    due_dates: list[int]
    heuristic_color_changes: int
    heuristic_due_date_deviation: float


def load_fixture(path: str) -> Instance:
    with open(path, "r", encoding="utf-8") as f:
        d = json.load(f)
    h = d["heuristic"]  # KeyError if absent — fixtures must carry the heuristic block
    return Instance(
        seed=int(d["seed"]),
        lane_caps=[int(l["cap"]) for l in d["lanes"]],
        colors=[str(b["color"]) for b in d["bodies"]],
        due_dates=[int(b["dueDateSeq"]) for b in d["bodies"]],
        heuristic_color_changes=int(h["colorChanges"]),
        heuristic_due_date_deviation=float(h["dueDateDeviation"]),
    )
