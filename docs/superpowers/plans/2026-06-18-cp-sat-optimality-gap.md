# CP-SAT Optimality-Gap Oracle Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an offline CP-SAT oracle that measures the PBS dynamic heuristic's distance to the buffer-constrained colour-batching optimum, neutralizing the "weak baseline" critique with a falsifiable, honest gap.

**Architecture:** Kotlin is the single source of truth (generates each instance via `PbsLoadGenerator` and runs `DynamicSequencingPolicy` via `PbsSimRunner`), exporting committed JSON fixtures. An isolated Python `solver/` package (own venv, OR-Tools) computes the capacity-respecting K-FIFO colour optimum per fixture; a pytest regression asserts the `gap ≥ 0` invariant + a documented ceiling. The JVM test suite stays Python-free.

**Tech Stack:** Python 3.13 + OR-Tools CP-SAT (`ortools` 9.15.x, py3.13 wheel confirmed) + pytest, in a dedicated `solver/.venv`; Kotlin (Spring Boot 3.3.1, Maven) for the exporter; JSON seam.

**Spec (source of truth):** `docs/superpowers/specs/2026-06-18-cp-sat-optimality-gap-design.md`

---

## Honesty invariants (carry into every task)
- **Oracle, not replacement.** `DynamicSequencingPolicy` stays the production policy; CP-SAT is offline measurement only. Do NOT wire CP-SAT as a `SequencingPolicy`.
- **gap ≥ 0 is sound by construction** (the heuristic's run is a feasible point of the relaxed model); the regression test is the soundness net.
- The reported gap is a **conservative upper bound** (capacity-respecting *relaxation* of the simulator's greedy admission). State this wherever the gap is reported.
- **Colour-axis only**; the fixture carries the heuristic's `dueDateDeviation` so the report can show the due-date the colour gap "bought". **Small, synthetic, capacity-binding instances only.**

## Baseline confirmation (do BEFORE Task 1)

- [ ] **Step 0: Capture green baselines**

Run (paths contain a space + brackets — quote them):
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q test
```
Expected: `BUILD SUCCESS`, record the `Tests run:` total (currently **227**). The control suite must stay green after the Kotlin exporter is added.

> If not green, STOP and surface to the human.

## File structure

| File | Responsibility | Chunk |
|------|----------------|-------|
| `solver/pyproject.toml` | `solver/` package metadata + deps (`ortools`, `pytest`) | 1 |
| `solver/.venv/` | dedicated venv (gitignored) | 1 |
| `solver/solver/__init__.py` | package marker | 1 |
| `solver/solver/model.py` | pure CP-SAT core: `optimal_color_changes(...) -> OracleResult` + `OracleResult` | 1 |
| `solver/solver/fixture.py` | typed fixture loading (`load_fixture`, `Instance`) | 1 |
| `solver/solver/optimality_gap.py` | `gap(fixture) -> GapResult` + CLI `__main__` | 1 |
| `solver/tests/test_smoke.py` | OR-Tools import/solve smoke test | 1 |
| `solver/tests/test_fixture.py` | fixture loader unit tests | 1 |
| `solver/tests/test_model.py` | pure model unit tests (hand-verified optima) | 1 |
| `solver/tests/test_gap_cli.py` | gap wrapper + CLI tests (synthetic fixture) | 1 |
| `solver/tests/test_regression.py` | gap≥0 invariant + ceiling over committed fixtures | 2 |
| `solver/fixtures/seed-*.json` | committed small capacity-binding instances + heuristic KPIs | 2 |
| `control/src/main/kotlin/com/resequencetwin/control/bench/OptimalityInstanceExporter.kt` | pure: instance + heuristic run → fixture JSON string | 2 |
| `control/src/test/kotlin/com/resequencetwin/control/bench/OptimalityInstanceExporterTest.kt` | determinism + content unit test | 2 |
| `control/src/test/kotlin/com/resequencetwin/control/bench/OptimalityFixtureExportTest.kt` | on-demand generator that writes `solver/fixtures/*.json` | 2 |
| `docs/adr/ADR-002-sequencing-solver.md` | updated: CP-SAT realized as offline oracle | 3 |
| `research/optimality-gap.md` | research note: measured gaps + honest scope | 3 |
| `.gitignore` | add `solver/.venv/` | 1 |

---

## Chunk 1: Python solver package + CP-SAT model

### Task 1: `solver/` package scaffold + OR-Tools

**Files:**
- Create: `solver/pyproject.toml`, `solver/solver/__init__.py`, `solver/tests/__init__.py`
- Create: `solver/tests/test_smoke.py`
- Modify: `.gitignore`

- [ ] **Step 1: Create the package files**

`solver/pyproject.toml`:
```toml
[project]
name = "resequence-twin-solver"
version = "0.1.0"
description = "Offline CP-SAT optimality-gap oracle for the resequence-twin PBS resequencing heuristic"
requires-python = ">=3.11"
dependencies = ["ortools>=9.11"]

[project.optional-dependencies]
dev = ["pytest>=8"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

`solver/solver/__init__.py`: empty file.
`solver/tests/__init__.py`: empty file.

`solver/tests/test_smoke.py`:
```python
def test_ortools_cp_model_imports():
    from ortools.sat.python import cp_model
    model = cp_model.CpModel()
    x = model.NewIntVar(0, 1, "x")
    model.Add(x == 1)
    solver = cp_model.CpSolver()
    assert solver.Solve(model) == cp_model.OPTIMAL
    assert solver.Value(x) == 1
```

Append to `.gitignore` (repo root):
```gitignore
solver/.venv/
solver/**/__pycache__/
```

- [ ] **Step 2: Create the venv and install**

Run (bash):
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && py -3.13 -m venv .venv && ./.venv/Scripts/python.exe -m pip install -q -e ".[dev]"
```
Expected: installs `ortools` + `pytest` (no error). If `py -3.13` is unavailable, use the existing interpreter at `../sim/.venv/Scripts/python.exe -m venv .venv` is NOT correct — instead use `python -m venv .venv`. The OR-Tools py3.13 wheel resolves (confirmed).

- [ ] **Step 3: Run the smoke test**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m pytest tests/test_smoke.py -v`
Expected: PASS (1 test) — confirms OR-Tools CP-SAT works in the venv.

- [ ] **Step 4: Commit**
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin"
git add "solver/pyproject.toml" "solver/solver/__init__.py" "solver/tests/__init__.py" "solver/tests/test_smoke.py" ".gitignore"
git commit -m "build(solver): scaffold solver package + OR-Tools venv"
```

---

### Task 2: Fixture model + loader

**Files:**
- Create: `solver/solver/fixture.py`
- Test: `solver/tests/test_fixture.py`

- [ ] **Step 1: Write the failing test**

`solver/tests/test_fixture.py`:
```python
from solver.fixture import load_fixture, Instance


def test_load_fixture_parses_bodies_lanes_and_heuristic(tmp_path):
    p = tmp_path / "seed-42.json"
    p.write_text(
        '{"seed":42,'
        '"lanes":[{"id":"L1","cap":2},{"id":"L2","cap":2},{"id":"L3","cap":2}],'
        '"bodies":[{"id":"BODY-00000","color":"RED"},{"id":"BODY-00001","color":"BLUE"}],'
        '"heuristic":{"colorChanges":1,"dueDateDeviation":2.4}}'
    )
    inst: Instance = load_fixture(str(p))
    assert inst.seed == 42
    assert inst.lane_caps == [2, 2, 2]
    assert inst.colors == ["RED", "BLUE"]
    assert inst.heuristic_color_changes == 1
    assert inst.heuristic_due_date_deviation == 2.4


def test_load_fixture_rejects_missing_fields(tmp_path):
    p = tmp_path / "bad.json"
    p.write_text('{"seed":1,"lanes":[],"bodies":[]}')  # missing heuristic
    import pytest
    with pytest.raises(KeyError):
        load_fixture(str(p))
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m pytest tests/test_fixture.py -v`
Expected: FAIL — `solver.fixture` not found.

- [ ] **Step 3: Implement**

`solver/solver/fixture.py`:
```python
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
        heuristic_color_changes=int(h["colorChanges"]),
        heuristic_due_date_deviation=float(h["dueDateDeviation"]),
    )
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m pytest tests/test_fixture.py -v`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin"
git add "solver/solver/fixture.py" "solver/tests/test_fixture.py"
git commit -m "feat(solver): typed fixture loader"
```

---

### Task 3: CP-SAT model — `optimal_color_changes`

This is the core. The model encodes the **capacity-respecting K-FIFO relaxation** (spec §C): routing
`lane[i]` + output permutation `pos[i]` + per-lane FIFO + an in-arrival-order, capacity-limited `pull[i]`
schedule whose coupling makes capacity genuinely bind. The provided model has been hand-verified against
the unit cases below; **if any unit test fails, fix the model (do NOT weaken the test)** — the capacity
test in particular pins the spec §C fidelity requirement.

**Files:**
- Create: `solver/solver/model.py`
- Test: `solver/tests/test_model.py`

- [ ] **Step 1: Write the failing tests**

`solver/tests/test_model.py`:
```python
from solver.model import optimal_color_changes, OracleResult


def opt(colors, caps, t=10.0):
    return optimal_color_changes(colors, caps, time_limit_s=t)


def test_n1_is_zero_regardless_of_capacity():
    r = opt(["RED"], [1, 1])
    assert r.optimal_color_changes == 0
    assert r.proven is True


def test_single_colour_is_zero():
    r = opt(["RED", "RED", "RED", "RED"], [2, 2])
    assert r.optimal_color_changes == 0
    assert r.proven is True


def test_two_colours_separable_within_capacity_is_one():
    # [R,R,B,B], K=2 cap=2: route RR->L0, BB->L1, release RRBB -> 1 transition.
    r = opt(["RED", "RED", "BLUE", "BLUE"], [2, 2])
    assert r.optimal_color_changes == 1
    assert r.proven is True


def test_single_lane_forces_input_order_fifo_fidelity():
    # K=1: the buffer is one FIFO lane, so output == input order exactly.
    # [R,B,R] -> R,B,R -> 2 transitions (cannot reorder within a lane).
    r = opt(["RED", "BLUE", "RED"], [3])
    assert r.optimal_color_changes == 2
    assert r.proven is True


def test_capacity_binds_above_free_permutation_bound():
    # [R,B,R,B,R,B], K=2 cap=1 (total buffer 2): cannot fully separate 3R/3B.
    # The free-permutation bound is (#distinct_colours - 1) = 1; a real K-FIFO buffer
    # this small cannot reach it. Assert the optimum is STRICTLY greater than 1, proving
    # capacity-over-time actually binds (and the model is not the loose free-merge).
    r = opt(["RED", "BLUE", "RED", "BLUE", "RED", "BLUE"], [1, 1])
    assert r.optimal_color_changes > 1
    assert r.proven is True


def test_returns_oracle_result_type():
    r = opt(["RED"], [1])
    assert isinstance(r, OracleResult)
    assert hasattr(r, "optimal_color_changes") and hasattr(r, "proven")
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m pytest tests/test_model.py -v`
Expected: FAIL — `solver.model` not found.

- [ ] **Step 3: Implement the model**

`solver/solver/model.py`:
```python
"""Capacity-respecting K-FIFO colour-minimisation oracle (spec §C).

Models the PBS resequencing buffer: bodies arrive in order, each is routed to a FIFO
lane (capacity cap_l), and released from a lane head to the output. Minimises colour
transitions in the output. Pull *timing* is a free (but in-arrival-order, capacity-
limited) decision — a relaxation of the simulator's greedy admission. The simulator's
run is a feasible point, so the optimum is a true lower bound on the heuristic
(gap >= 0 sound) and a conservative bound on the same-discipline optimum.
"""
from __future__ import annotations
from dataclasses import dataclass
from ortools.sat.python import cp_model


@dataclass(frozen=True)
class OracleResult:
    optimal_color_changes: int
    proven: bool  # True iff CP-SAT proved optimality within the time limit


def optimal_color_changes(colors: list[str], lane_caps: list[int],
                          time_limit_s: float = 10.0) -> OracleResult:
    n = len(colors)
    k = len(lane_caps)
    if n <= 1:
        return OracleResult(0, True)

    # Distinct colour indices.
    palette = sorted(set(colors))
    cidx = {c: i for i, c in enumerate(palette)}
    col = [cidx[c] for c in colors]
    n_col = len(palette)

    m = cp_model.CpModel()

    lane = [m.NewIntVar(0, k - 1, f"lane_{i}") for i in range(n)]
    pos = [m.NewIntVar(0, n - 1, f"pos_{i}") for i in range(n)]       # output position of body i
    pull = [m.NewIntVar(0, n - 1, f"pull_{i}") for i in range(n)]     # output-step index it is pulled at
    m.AddAllDifferent(pos)

    # Pulls are in arrival order, and a body must be pulled no later than it is released.
    for i in range(n - 1):
        m.Add(pull[i] <= pull[i + 1])
    for i in range(n):
        m.Add(pull[i] <= pos[i])

    # Per-lane FIFO: same-lane bodies are released in arrival order.
    for i in range(n):
        for j in range(i + 1, n):
            s = m.NewBoolVar(f"same_{i}_{j}")
            m.Add(lane[i] == lane[j]).OnlyEnforceIf(s)
            m.Add(lane[i] != lane[j]).OnlyEnforceIf(s.Not())
            m.Add(pos[i] < pos[j]).OnlyEnforceIf(s)

    # Capacity-over-time: at each output step `t` and lane `l`, the number of bodies
    # "in the buffer" (pulled at or before t AND released at or after t) assigned to l
    # must be <= cap_l. `present[i][t]` == (pull[i] <= t <= pos[i]).
    for t in range(n):
        for li in range(k):
            terms = []
            for i in range(n):
                pulled_by_t = m.NewBoolVar(f"pb_{i}_{t}")     # pull[i] <= t
                m.Add(pull[i] <= t).OnlyEnforceIf(pulled_by_t)
                m.Add(pull[i] > t).OnlyEnforceIf(pulled_by_t.Not())
                not_yet_out = m.NewBoolVar(f"ny_{i}_{t}")      # pos[i] >= t
                m.Add(pos[i] >= t).OnlyEnforceIf(not_yet_out)
                m.Add(pos[i] < t).OnlyEnforceIf(not_yet_out.Not())
                in_lane = m.NewBoolVar(f"il_{i}_{li}_{t}")     # lane[i] == li
                m.Add(lane[i] == li).OnlyEnforceIf(in_lane)
                m.Add(lane[i] != li).OnlyEnforceIf(in_lane.Not())
                present = m.NewBoolVar(f"pr_{i}_{li}_{t}")
                m.AddBoolAnd([pulled_by_t, not_yet_out, in_lane]).OnlyEnforceIf(present)
                m.AddBoolOr([pulled_by_t.Not(), not_yet_out.Not(), in_lane.Not()]).OnlyEnforceIf(present.Not())
                terms.append(present)
            m.Add(sum(terms) <= lane_caps[li])

    # Objective: colour transitions in the output sequence.
    # out_col[t] = colour index of the body at output position t (channelled via pos).
    out_col = [m.NewIntVar(0, n_col - 1, f"outc_{t}") for t in range(n)]
    for i in range(n):
        m.AddElement(pos[i], out_col, col[i])  # out_col[pos[i]] == col[i]
    trans = []
    for t in range(n - 1):
        d = m.NewBoolVar(f"trans_{t}")
        m.Add(out_col[t] != out_col[t + 1]).OnlyEnforceIf(d)
        m.Add(out_col[t] == out_col[t + 1]).OnlyEnforceIf(d.Not())
        trans.append(d)
    m.Minimize(sum(trans))

    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = time_limit_s
    status = solver.Solve(m)
    if status == cp_model.OPTIMAL:
        return OracleResult(int(solver.ObjectiveValue()), True)
    if status == cp_model.FEASIBLE:
        return OracleResult(int(solver.ObjectiveValue()), False)
    raise RuntimeError(f"CP-SAT returned non-feasible status {status} "
                       f"(model should always be feasible — the heuristic is a witness)")
```

> Note for the implementer: `AddElement(index, variables, target)` requires `target` to be a variable or
> integer constant; `col[i]` is an int constant, which is accepted. If your OR-Tools version rejects a
> constant target, wrap it: `cst = m.NewConstant(col[i]); m.AddElement(pos[i], out_col, cst)`. Do NOT
> change the model semantics. If the `[R,B,R,B,R,B]` capacity test does not yield `> 1`, the capacity
> constraint is wrong — debug it, do not relax the assertion.

- [ ] **Step 4: Run to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m pytest tests/test_model.py -v`
Expected: PASS (6 tests). (Each tiny instance solves in well under a second.)

- [ ] **Step 5: Commit**
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin"
git add "solver/solver/model.py" "solver/tests/test_model.py"
git commit -m "feat(solver): capacity-respecting K-FIFO CP-SAT colour-optimum model"
```

---

### Task 4: gap wrapper + CLI

**Files:**
- Create: `solver/solver/optimality_gap.py`
- Test: `solver/tests/test_gap_cli.py`

- [ ] **Step 1: Write the failing test**

`solver/tests/test_gap_cli.py`:
```python
import json
import subprocess
import sys
from solver.optimality_gap import gap, GapResult
from solver.fixture import load_fixture


def _write(tmp_path, colors, caps, heuristic_cc, dd=0.0):
    bodies = [{"id": f"BODY-{i:05d}", "color": c} for i, c in enumerate(colors)]
    lanes = [{"id": f"L{j+1}", "cap": caps[j]} for j in range(len(caps))]
    p = tmp_path / "f.json"
    p.write_text(json.dumps({"seed": 1, "lanes": lanes, "bodies": bodies,
                             "heuristic": {"colorChanges": heuristic_cc, "dueDateDeviation": dd}}))
    return str(p)


def test_gap_computes_heuristic_minus_optimal(tmp_path):
    # [R,R,B,B] K2 cap2: optimal=1. Pretend heuristic achieved 3 -> gap 2.
    f = _write(tmp_path, ["RED", "RED", "BLUE", "BLUE"], [2, 2], heuristic_cc=3, dd=1.5)
    r: GapResult = gap(load_fixture(f))
    assert r.optimal_color_changes == 1
    assert r.heuristic_color_changes == 3
    assert r.gap == 2
    assert r.proven is True
    assert r.due_date_deviation == 1.5


def test_gap_invariant_holds_when_heuristic_equals_optimal(tmp_path):
    f = _write(tmp_path, ["RED", "RED", "BLUE", "BLUE"], [2, 2], heuristic_cc=1)
    r = gap(load_fixture(f))
    assert r.gap == 0  # gap >= 0 always


def test_cli_json_output(tmp_path):
    f = _write(tmp_path, ["RED", "BLUE"], [2, 2], heuristic_cc=1)
    out = subprocess.run(
        [sys.executable, "-m", "solver.optimality_gap", "--fixture", f, "--json"],
        capture_output=True, text=True, cwd=_solver_dir())
    assert out.returncode == 0
    payload = json.loads(out.stdout)
    assert payload["seed"] == 1 and payload["gap"] >= 0


def _solver_dir():
    import os
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m pytest tests/test_gap_cli.py -v`
Expected: FAIL — `solver.optimality_gap` not found.

- [ ] **Step 3: Implement**

`solver/solver/optimality_gap.py`:
```python
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
    res = optimal_color_changes(inst.colors, inst.lane_caps, time_limit_s=time_limit_s)
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
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m pytest tests/test_gap_cli.py -v`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the whole solver suite**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m pytest -v`
Expected: PASS (smoke 1 + fixture 2 + model 6 + gap 3 = 12).

- [ ] **Step 6: Commit**
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin"
git add "solver/solver/optimality_gap.py" "solver/tests/test_gap_cli.py"
git commit -m "feat(solver): gap wrapper + CLI"
```

**🚩 Chunk 1 gate:** the Python oracle is complete and self-tested on synthetic instances. No Kotlin/JVM coupling yet.

---

## Chunk 2: Kotlin exporter + fixtures + regression

### Task 5: Kotlin instance exporter (pure)

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/bench/OptimalityInstanceExporter.kt`
- Test: `control/src/test/kotlin/com/resequencetwin/control/bench/OptimalityInstanceExporterTest.kt`

Context: `PbsLoadGenerator(seed, bodies).generate(): List<Body>` (Body has `.id`, `.color`).
`PbsSimRunner().run(bodies, laneSpecs, DynamicSequencingPolicy()): PbsKpiCollector.Snapshot` (has
`.colorChanges: Int`, `.dueDateDeviation: Double`). `PbsSimRunner.LaneSpec(id, capacity)`. Use the
project's Jackson (`jacksonObjectMapper()` from `com.fasterxml.jackson.module.kotlin`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.resequencetwin.control.bench

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OptimalityInstanceExporterTest {

    private val lanes = listOf(
        PbsSimRunner.LaneSpec("L1", 2), PbsSimRunner.LaneSpec("L2", 2), PbsSimRunner.LaneSpec("L3", 2)
    )

    @Test
    fun `export is deterministic for the same seed and config`() {
        val a = OptimalityInstanceExporter.exportInstanceJson(seed = 42L, bodies = 12, lanes = lanes)
        val b = OptimalityInstanceExporter.exportInstanceJson(seed = 42L, bodies = 12, lanes = lanes)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `export carries seed, lanes, N bodies with colours, and heuristic KPIs`() {
        val json = OptimalityInstanceExporter.exportInstanceJson(seed = 7L, bodies = 12, lanes = lanes)
        val node = jacksonObjectMapper().readTree(json)

        assertThat(node["seed"].asInt()).isEqualTo(7)
        assertThat(node["lanes"]).hasSize(3)
        assertThat(node["lanes"][0]["id"].asText()).isEqualTo("L1")
        assertThat(node["lanes"][0]["cap"].asInt()).isEqualTo(2)
        assertThat(node["bodies"]).hasSize(12)
        assertThat(node["bodies"][0]["color"].asText()).isNotBlank()
        assertThat(node["heuristic"]["colorChanges"].asInt()).isGreaterThanOrEqualTo(0)
        assertThat(node["heuristic"]["dueDateDeviation"].asDouble()).isGreaterThanOrEqualTo(0.0)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=OptimalityInstanceExporterTest test`
Expected: FAIL — `OptimalityInstanceExporter` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.resequencetwin.control.bench

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.resequencetwin.control.pbs.DynamicSequencingPolicy

/**
 * Exports a PBS instance + the dynamic heuristic's measured KPIs as a JSON fixture for the offline
 * CP-SAT optimality-gap oracle (`solver/`). Pure: same (seed, bodies, lanes) → identical JSON.
 *
 * Honesty: `heuristic.*` are the *actual* numbers from running [DynamicSequencingPolicy] here — the
 * Python oracle never re-computes them (avoids cross-language divergence).
 */
object OptimalityInstanceExporter {

    private val mapper = jacksonObjectMapper()

    fun exportInstanceJson(seed: Long, bodies: Int, lanes: List<PbsSimRunner.LaneSpec>): String {
        val stream = PbsLoadGenerator(seed, bodies).generate()
        val kpi = PbsSimRunner().run(stream, lanes, DynamicSequencingPolicy())
        val payload = linkedMapOf(
            "seed" to seed,
            "lanes" to lanes.map { linkedMapOf("id" to it.id, "cap" to it.capacity) },
            "bodies" to stream.map { linkedMapOf("id" to it.id, "color" to it.color) },
            "heuristic" to linkedMapOf(
                "colorChanges" to kpi.colorChanges,
                "dueDateDeviation" to kpi.dueDateDeviation,
            ),
        )
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
    }
}
```

> Implementer check: confirm `PbsSimRunner.LaneSpec` exposes `.id` / `.capacity` and `Snapshot` exposes
> `.colorChanges` / `.dueDateDeviation` (read `PbsSimRunner.kt` / `PbsKpiCollector.kt`); adjust accessors
> if they differ. Confirm `Body` exposes `.id` / `.color`.

- [ ] **Step 4: Run to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=OptimalityInstanceExporterTest test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin"
git add "control/src/main/kotlin/com/resequencetwin/control/bench/OptimalityInstanceExporter.kt" "control/src/test/kotlin/com/resequencetwin/control/bench/OptimalityInstanceExporterTest.kt"
git commit -m "feat(bench): pure optimality-gap instance exporter"
```

---

### Task 6: Generate & commit capacity-binding fixtures

**Files:**
- Create: `control/src/test/kotlin/com/resequencetwin/control/bench/OptimalityFixtureExportTest.kt`
- Create (generated, committed): `solver/fixtures/seed-1.json`, `seed-7.json`, `seed-42.json`, `seed-99.json`, `seed-2024.json`

Instances must be **capacity-binding** (spec §D): small N with total buffer well below N. Use **N=12,
3 lanes, cap=2** (total buffer 6 < 12) → genuine deferral pressure. Seeds: {1, 7, 42, 99, 2024}.

- [ ] **Step 1: Write the generator test**

```kotlin
package com.resequencetwin.control.bench

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * On-demand fixture generator (not a behavioural assertion): writes committed instance fixtures for the
 * `solver/` oracle. Re-run after any change to the heuristic or the instance config, then commit the
 * regenerated JSON. Determinism is asserted by [OptimalityInstanceExporterTest].
 */
class OptimalityFixtureExportTest {

    private val lanes = listOf(
        PbsSimRunner.LaneSpec("L1", 2), PbsSimRunner.LaneSpec("L2", 2), PbsSimRunner.LaneSpec("L3", 2)
    )
    private val seeds = listOf(1L, 7L, 42L, 99L, 2024L)
    private val bodies = 12

    @Test
    fun `generate fixtures into solver fixtures`() {
        // control/ is the module dir; fixtures live at ../solver/fixtures
        val outDir = Paths.get("..", "solver", "fixtures").toAbsolutePath().normalize()
        Files.createDirectories(outDir)
        for (seed in seeds) {
            val json = OptimalityInstanceExporter.exportInstanceJson(seed, bodies, lanes)
            Files.writeString(outDir.resolve("seed-$seed.json"), json)
        }
    }
}
```

- [ ] **Step 2: Run the generator**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=OptimalityFixtureExportTest test`
Expected: PASS; 5 files written under `solver/fixtures/`.

- [ ] **Step 3: Sanity-check capacity actually binds**

Run the oracle on one fixture and eyeball that the optimum is non-trivial (not 0 or 1):
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m solver.optimality_gap --fixture fixtures/seed-42.json
```
Expected: a line like `seed=42 optimal=<O> heuristic=<H> gap=<H-O>` with `gap ≥ 0`. **Capacity-binding
check:** the optimum must **exceed the free-permutation bound** for that instance — i.e.
`optimal > (#distinct colours in the instance − 1)`. (`PbsLoadGenerator`'s default palette is 5 colours,
so on a 12-body instance using all 5, the free bound is 4 and a binding instance has `optimal ≥ 5`; if the
instance happens to use fewer colours, compare against that instance's own distinct-colour count.) If the
optimum merely *equals* the free bound, the buffer is NOT binding — reduce cap (e.g. cap=1) or raise N and
regenerate (update the config in the generator test + re-run). Eyeball the body colours in the fixture to
get the instance's distinct-colour count.

- [ ] **Step 4: Commit the fixtures + generator**
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin"
git add "control/src/test/kotlin/com/resequencetwin/control/bench/OptimalityFixtureExportTest.kt" "solver/fixtures/"
git commit -m "feat(solver): commit capacity-binding instance fixtures (seeds 1,7,42,99,2024)"
```

---

### Task 7: Python regression test (gap≥0 invariant + ceiling)

**Files:**
- Create: `solver/tests/test_regression.py`

- [ ] **Step 1: Measure the gaps to set the ceiling**

Run the oracle on every committed fixture and record the gaps:
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && for f in fixtures/seed-*.json; do ./.venv/Scripts/python.exe -m solver.optimality_gap --fixture "$f"; done
```
Record `max(gap)` across seeds. Set `CEILING = max(gap) + 1` (a small slack) and note the observed gaps in
the commit message (they go into the research note in Task 8).

- [ ] **Step 2: Write the regression test** (substitute the measured `CEILING`)

`solver/tests/test_regression.py`:
```python
import glob
import os
import pytest
from solver.optimality_gap import gap
from solver.fixture import load_fixture

CEILING = 2  # <-- set in Step 1 from measured max(gap) + 1; documented in research note.

_FIX = sorted(glob.glob(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                     "fixtures", "seed-*.json")))


@pytest.mark.parametrize("path", _FIX, ids=[os.path.basename(p) for p in _FIX])
def test_gap_is_non_negative_invariant(path):
    # gap >= 0 is sound by construction (the heuristic is a feasible policy); a negative gap
    # would mean an over-constrained / buggy model. This is the model-soundness net.
    r = gap(load_fixture(path))
    assert r.optimal_color_changes <= r.heuristic_color_changes, (
        f"{os.path.basename(path)}: optimal {r.optimal_color_changes} > heuristic "
        f"{r.heuristic_color_changes} — model bug (gap<0)")
    assert r.proven, f"{os.path.basename(path)}: optimum not proven within time limit"


@pytest.mark.parametrize("path", _FIX, ids=[os.path.basename(p) for p in _FIX])
def test_heuristic_gap_within_ceiling(path):
    r = gap(load_fixture(path))
    assert r.gap <= CEILING, (
        f"{os.path.basename(path)}: gap {r.gap} exceeds ceiling {CEILING} — the heuristic "
        f"regressed, or the committed fixtures are stale (regenerate via OptimalityFixtureExportTest)")


def test_fixtures_exist():
    assert _FIX, "no committed fixtures found under solver/fixtures/"
```

- [ ] **Step 3: Run the regression + full solver suite**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m pytest -v`
Expected: PASS — smoke 1 + fixture 2 + model 6 + gap 3 + regression (2×5 + 1 = 11) = 23. `gap ≥ 0` and
`gap ≤ CEILING` hold on every seed.

- [ ] **Step 4: Commit**
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin"
git add "solver/tests/test_regression.py"
git commit -m "test(solver): gap>=0 invariant + documented ceiling over fixtures"
```

**🚩 Chunk 2 gate:** end-to-end works — Kotlin exports → fixtures committed → Python oracle measures →
regression guards gap≥0 + ceiling. Control suite still green (run `mvn -q test` to confirm 227 + 3 new
Kotlin tests = 230 — `OptimalityInstanceExporterTest` 2 + `OptimalityFixtureExportTest` 1).

---

## Chunk 3: Docs + final verification

### Task 8: ADR-002 update + research note

**Files:**
- Modify: `docs/adr/ADR-002-sequencing-solver.md`
- Create: `research/optimality-gap.md`

- [ ] **Step 1: Update ADR-002**

Add a dated "Update (2026-06-18): CP-SAT realized as an offline oracle" section that **supersedes** the
"deferred / OR-Tools Java failed on Windows" framing: the upgrade path is realized via the Python OR-Tools
subprocess path ADR-002 itself suggested, as an **offline optimality oracle** (not a policy swap — the
heuristic stays the production policy). Note the capacity-respecting relaxation, the `gap ≥ 0` soundness,
and that the gap is a conservative upper bound. Link `solver/` and the research note.

- [ ] **Step 2: Write the research note**

> **PREREQUISITE:** Task 7 Step 1's per-seed oracle output (optimal / heuristic / gap per seed) and the
> `CEILING` must be in your context. If not, re-run `for f in fixtures/seed-*.json; do ./.venv/Scripts/python.exe -m solver.optimality_gap --fixture "$f"; done`
> from `solver/` now and record the real numbers before writing — do NOT invent placeholder values.

`research/optimality-gap.md`: record the measured per-seed gaps (from Task 7 Step 1), the honest scope
(oracle-not-replacement, capacity-respecting relaxation → conservative bound, colour-axis only, small
synthetic capacity-binding instances), the heuristic's due-date deviation per seed (the colour gap's
"price"), and the headline framing: *"on these instances the dynamic heuristic is within at most
`CEILING` colour-changes of the buffer-constrained optimum."* No overselling — explicitly NOT a claim of
general optimality or real-plant data.

- [ ] **Step 3: Verify docs reference real artifacts (content check, not just stat)**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && git --no-pager diff -- research/optimality-gap.md docs/adr/ADR-002-sequencing-solver.md`
and confirm the printed content actually contains: a row/line per seed (1, 7, 42, 99, 2024) with numeric
optimal/heuristic/gap values matching Task 7's measurements; the `CEILING` value from `test_regression.py`;
and the phrase "conservative upper bound". There must be NO placeholder/`<TODO>`/`<...>` left in either doc.

- [ ] **Step 4: Commit**
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin"
git add "docs/adr/ADR-002-sequencing-solver.md" "research/optimality-gap.md"
git commit -m "docs(cp-sat): ADR-002 offline-oracle update + optimality-gap research note"
```

---

### Task 9: Final verification

- [ ] **Step 1: All three suites green**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q test
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/solver" && ./.venv/Scripts/python.exe -m pytest
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/sim" && ./.venv/Scripts/python.exe -m pytest
```
Expected: control `BUILD SUCCESS` (230 = 227 + 3 new); solver 23 passed; sim unchanged (pre-existing
state — do not regress; the known legacy OHT `test_generator_emits_ordered_moves` failure, if present, is
unrelated to this work — note it but do not fix here).

- [ ] **Step 2: Honesty gate**

Confirm no CP-SAT-as-policy wiring leaked into `control/` production code:
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && git grep -n -i "ortools\|cp_model\|cpsat" -- "control/src/main" || echo "OK: no CP-SAT in control production code (oracle is offline/Python only)"
```
Expected: `OK` — the oracle never touches the JVM production policy path.

- [ ] **Step 3: Hand off** to **superpowers:finishing-a-development-branch** (already on `main`; no remote → commit/cleanup only). Update memory `resequence-twin-build-state` with the CP-SAT oracle result + measured gaps.

## Done criteria (from spec)
- `solver/` pure model + gap/CLI green under unit tests (OR-Tools); regression green over committed fixtures. ✅ Tasks 3/4/7.
- Kotlin exporter + committed `solver/fixtures/*.json`; exporter determinism test green; `control/` `mvn test` stays green. ✅ Tasks 5/6.
- `gap ≥ 0` proven on every seed; heuristic gap within the documented ceiling. ✅ Task 7.
- ADR-002 updated to "offline oracle"; research note records measured gaps + honest (conservative, colour-axis, small-instance) scope. ✅ Task 8.
- Existing control + sim suites remain green. ✅ Task 9.
