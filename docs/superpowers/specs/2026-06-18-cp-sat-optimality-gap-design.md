# CP-SAT Optimality-Gap Oracle — Design

**Date:** 2026-06-18 · **Status:** design (approved, pre-plan)
**Modules:** new top-level `solver/` (Python, OR-Tools) + `control/` Kotlin exporter (bench) + docs (ADR-002 update, research note).
**Branch base:** `main` (post S5 drift-seam).

## Why (motivation)

The project's own objective self-assessment named **weakness #2: a weak baseline** — the dynamic
sequencing heuristic is only ever compared against round-robin + FIFO (`StaticSequencingPolicy`). A
skeptical reviewer asks: *"beating a weak baseline proves little — how far is your heuristic from
optimal?"* The codebase already anticipates this: `PbsBenchRegressionTest` literally comments
*"CP-SAT multi-objective optimization would dominate the Pareto frontier (see ADR-002),"* and ADR-002
records CP-SAT as the intended-but-deferred solver (the OR-Tools **Java** binding failed on Windows
JNI `.dll` resolution; ADR-002 suggests *"OR-Tools Java or a Python subprocess"* as the upgrade path).

This slice realizes that upgrade path **as an offline optimality oracle** that measures the heuristic's
distance to the provable optimum, directly neutralizing the weak-baseline critique with a falsifiable,
honest number.

## Honesty / framing invariants (non-negotiable)

- **Oracle, not replacement.** The `DynamicSequencingPolicy` heuristic **remains the production
  policy** (exact CP-SAT is exponential → too slow for real-time, per ADR-002). CP-SAT is used
  **offline only**, to compute the best colour-batching any policy could achieve in the *same* K-FIFO
  buffer, and to report the heuristic's gap to it. We do **not** wire CP-SAT in as a `SequencingPolicy`.
- **Colour-axis only.** The oracle minimizes `colorChanges` (the robust hard-invariant KPI). The
  heuristic also deliberately spends effort on due-date adherence and option leveling, so part of any
  measured gap is the heuristic's *intentional* due-date trade-off — the report quantifies the due-date
  the heuristic "bought" alongside the colour gap so the gap is not misread as pure suboptimality.
- **Small instances only.** Exact CP-SAT is exponential; the study is scoped to small instances where
  optimality is provable in seconds. This is stated everywhere the gap is reported.
- **Synthetic instances.** Bodies come from the seeded `PbsLoadGenerator`; not real plant data.
- **Bound-honest under time limit.** If the solver hits its time limit without proving optimality, the
  result is reported as a *bound* (`proven=false`) and the gap as "≤ measured", never as a proven gap.

## Architecture

Single source of truth = **Kotlin** (generates the instance and runs the heuristic). The CP-SAT solver
lives in an **isolated Python tool**. The JVM test suite stays Python-free (no `mvn test` ↔ OR-Tools
coupling), consistent with the existing control=JVM / sim=Python split.

```
Kotlin exporter ──JSON fixture──> Python CP-SAT oracle ──> gap report
 (PbsLoadGenerator +              (solver/optimality_gap.py,    (per seed:
  DynamicSequencingPolicy via      ortools cp_model)             optimal, heuristic,
  PbsSimRunner) emits              ──────────────────────>       gap, proven)
  {seed, lanes, bodies,                                     ──> pytest regression test
   heuristic:{colorChanges,                                     (gap>=0 invariant + ceiling)
   releaseColorSequence}}                                   ──> ADR-002 update + research note
```

### A. Kotlin instance exporter (`control/`, bench)
- A small bench utility (e.g. `bench/OptimalityInstanceExporter`) + a thin entry point (CLI `main` or a
  test-driven generator) that, for a `(seed, bodies, laneSpecs)` config:
  1. generates the body stream via `PbsLoadGenerator(seed, bodies)`,
  2. runs `DynamicSequencingPolicy` through `PbsSimRunner.run(...)` (and/or `runWithTrace` to capture the
     release colour sequence) to obtain the heuristic's `colorChanges`,
  3. emits a JSON fixture:
     ```json
     {
       "seed": 42,
       "lanes": [{"id": "L1", "cap": 2}, {"id": "L2", "cap": 2}, {"id": "L3", "cap": 2}],
       "bodies": [{"id": "BODY-00000", "color": "RED", "dueDateSeq": 5}, ...],   // arrival order
       "heuristic": {"colorChanges": 7, "dueDateDeviation": 2.4}
     }
     ```
- **Fixture schema (required fields, types):** `seed: int`; `lanes: [{id: string, cap: int>0}]`;
  `bodies: [{id: string, color: string, dueDateSeq: int≥0}]` in arrival order (length N) — `dueDateSeq` is
  the body's JIS target assembly position (`Body.dueDateSeq`), needed for the due-date constraint;
  `heuristic.colorChanges: int≥0` (the heuristic's actual colour changes) and
  `heuristic.dueDateDeviation: number≥0` (the heuristic's `PbsKpiCollector.dueDateDeviation` — both the
  honesty "price" AND the due-date budget `B = round(N·dueDateDeviation)`). The Python core consumes
  `lanes` + `bodies` (colours **and** dueDateSeq) + the budget; the gap/report layer also uses
  `heuristic.colorChanges`. (No `releaseColorSequence` — dropped, the oracle solves from the instance.)
- Fixtures are **committed** under `solver/fixtures/` so the Python test is pure-offline and the gaps are
  reproducible. The exporter is committed too, so fixtures can be regenerated. **Staleness handling:** if
  the heuristic's weights/logic change, `heuristic.colorChanges` in the committed fixtures goes stale; the
  ceiling regression test (Section "Testing") is the explicit detector (it will fail), and fixture
  regeneration is a done-criterion of any heuristic change.
- Honesty: the fixture's `heuristic.*` values are the *actual* numbers from running the Kotlin policy —
  not recomputed in Python (avoids divergence and RNG-matching across languages).

### B. Python CP-SAT oracle (`solver/optimality_gap.py`, OR-Tools)
- New top-level `solver/` package mirroring the `sim/` producer's structure: a **pure-function core**
  (no I/O) + a **thin CLI**.
- Pure core `optimal_color_changes(bodies, lanes, time_limit_s) -> OracleResult` builds the
  buffer-constrained colour-minimization CP-SAT model (Section C) and solves it. On `INFEASIBLE`/`UNKNOWN`
  the core **raises** (a faithful model is always feasible — the heuristic's own run is a witness); the
  CLI maps that exception to a clean stderr message + nonzero exit.
- `OracleResult = {optimalColorChanges: int, proven: bool}`.
- A `gap(fixture) -> {seed, optimalColorChanges, heuristicColorChanges, dueDateDeviation, gap, proven}`
  wrapper reads a fixture, calls the core, computes `gap = heuristicColorChanges - optimalColorChanges`,
  and passes through `heuristic.dueDateDeviation` for the honesty report.
- CLI `python -m solver.optimality_gap --fixture <path> [--time-limit 10] [--json]`: prints the per-seed
  report; `--json` for machine-readable stdout. Errors → clean stderr + nonzero exit.
- **Packaging (resolved):** `solver/` is its **own top-level package**, separate from `sim/`, with its
  own `solver/pyproject.toml` declaring `ortools` (and `pytest` for tests) and its **own venv**
  `solver/.venv` — keeping the heavy OR-Tools dependency out of the `sim/` venv. (py3.13 wheel
  `ortools-9.15.x` confirmed installable.) Run via `solver/.venv/Scripts/python.exe -m solver.optimality_gap`
  and `... -m pytest`. This mirrors how `control/` and `sim/` are independent modules.

### C. The CP-SAT model (buffer-constrained colour minimization)

The buffer is a **K-lane FIFO resequencing buffer** (the simulator's structure, verified against
`PbsSimRunner.stepArrivals`/`stepRelease`): bodies arrive on an in-order conveyor; each is *pulled* into a
routed lane (FIFO, capacity `cap_l`) and later *released* from a lane head to the output. The oracle
minimizes `colorChanges` over the output, jointly choosing **routing + the pull/release schedule**.

**Modelling fidelity decision (resolved during planning — capacity-respecting relaxation).** The simulator
pulls **greedily** (admit ASAP whenever a lane has room). Encoding that exact greedy timing as CP-SAT
constraints is complex and error-prone (admission timing is a deterministic function of the routing +
release choices — it requires encoding the forward simulation as constraints). The oracle instead models
the **same buffer structure but treats pull *timing* as a free decision** (pulls remain strictly
**in-arrival-order** and **capacity-limited** — you can never pull past buffer capacity, which is what makes
a small capacity genuinely bite — but the model may pull lazily rather than ASAP). This is a clean,
well-defined **relaxation** of the simulator's greedy discipline.

- **Decision variables:** routing `lane[i] ∈ {0..K-1}` per body + a pull/release schedule (a sequence of
  `2N` events: `N` PULLs in input order, each routing its body to a lane; `N` RELEASEs, each popping a
  chosen non-empty lane's head). Equivalently, output positions `pos[i]` (a permutation).
- **Per-lane FIFO:** same-lane bodies are released in arrival order (`lane[i]==lane[j] ∧ i<j ⇒
  pos[i]<pos[j]`).
- **Capacity-over-time (the binding constraint):** at every event, each lane holds ≤ `cap_l` pulled-but-
  -unreleased bodies; pulls are prefix-in-order (to pull body `i`, bodies `0..i-1` are already pulled), so
  the buffer cannot run arbitrarily far ahead of releases. (An output-position-only capacity check is
  explicitly **rejected** — it fails to bound simultaneous occupancy and would let a lane overflow.)
- **Objective:** minimize colour transitions in the release sequence
  `Σ_t [color(release_t) ≠ color(release_{t-1})]`.
- **Due-date hard constraint (added — makes the optimum non-trivial & domain-faithful):** the output's
  due-date deviation must be **no worse than the heuristic's** on the same instance:
  `Σ_i |pos[i] − dueDateSeq[i]| ≤ B`, where `B = round(N · heuristic.dueDateDeviation)` (the heuristic's
  achieved total deviation, an integer sum). **Why:** `PbsLoadGenerator` emits *colour-batched* streams,
  so the *unconstrained* colour optimum is trivially `#distinct colours − 1` (the buffer never has to
  re-batch colours that arrive batched). The due-date constraint is what makes the buffer genuinely work
  — to honour due-date it must reorder bodies, breaking colour batches — so the optimum becomes a real
  CP-SAT computation. The resulting gap = `heuristic.colorChanges − optimal` is the **colour the heuristic
  wastes *at its own due-date level*** (a fair, Pareto-style comparison), not a trivial difference.
  - **gap ≥ 0 still sound:** the heuristic's own run achieves `heuristic.colorChanges` at exactly
    deviation `B`, so it is a feasible point of "minimise colour s.t. deviation ≤ B" → `optimal ≤
    heuristic.colorChanges`. (If the heuristic is already colour-optimal at its due-date level, gap = 0 —
    an honest "heuristic is Pareto-efficient here" result.)
  - This is a **single due-date hard constraint**, not a full multi-objective Pareto sweep (that remains
    future work). The optimum is still over the capacity-respecting *relaxed* buffer, so the gap remains a
    conservative bound.
- **Solver:** `cp_model.CpSolver` with `max_time_in_seconds`; map `OPTIMAL → proven=true`,
  `FEASIBLE → proven=false` (gap reported as "≤ value"), and treat `INFEASIBLE`/`UNKNOWN` as a hard error
  (the model is always feasible on a real fixture — the heuristic's own run is a witness).

**Soundness & honest framing of the gap:**
- **gap ≥ 0 is sound by construction.** The simulator's greedy pull is *one* feasible pull schedule in this
  relaxed model, so the `DynamicSequencingPolicy` run is a feasible point → `optimal ≤ heuristic`
  necessarily holds. The gap≥0 regression test (Section "Testing") therefore doubles as the
  **model-soundness net**: an accidentally *over*-constrained model would produce `optimal > heuristic` and
  fail loudly.
- **The reported gap is a conservative *upper bound*** on the heuristic's distance from a same-discipline
  (greedy) optimum, since `optimal_relaxed ≤ optimal_greedy ≤ heuristic`. The honest claim is therefore
  *"the heuristic is within at most G colour-changes of even this (admission-relaxed, hence stronger)
  optimum"* — conservative, never flattering the heuristic. On the small capacity-binding instances
  (Section D) the relaxed and greedy optima typically coincide, so the conservatism usually costs nothing.
  This framing is stated wherever the gap is reported (research note, ADR-002).
- Strict greedy-admission-faithful optimisation is recorded as **future work** (Out of scope), not built now.

> The exact CpModel variable encoding is a plan-level detail; the spec fixes the *semantics above*
> (routing + per-lane FIFO + in-order, capacity-limited pulls + free pull timing, minimise colour
> transitions). An event-step encoding (`2N` pull/release events with per-lane occupancy counters) or an
> equivalent position/permutation encoding with the capacity-over-time constraint are both acceptable, as
> long as the capacity-binding unit test (Section "Testing") passes.

### D. Instances & seeds
- **Small deterministic instances:** N ≈ 12–16 bodies, K = 3 lanes, **reduced capacity** (e.g. cap 2–3)
  so the buffer genuinely binds (large capacity makes the optimum trivial). Concretely, total buffer
  `K × cap` should be well below N so the buffer fills and forces real deferral events — e.g. K=3, cap=2
  (total 6) with N=12 guarantees multiple "all lanes full, next arrival waits" events per seed. The
  exporter/plan should sanity-check chosen instances are genuinely capacity-binding (not trivially solved
  by any policy). Colour palette = the existing `PbsLoadGenerator` default.
- **Multiple seeds:** a small-N subset of the existing robustness seed set (e.g. 3–5 seeds drawn from
  {1, 7, 42, 99, 2024}). One committed fixture per seed.
- **Time limit:** e.g. 10 s per seed. On non-proof, `proven=false` and the gap is reported as "≤ value".

### E. Reporting & docs
- A per-seed gap report (CLI output + summarized in the research note): seed, optimalColorChanges,
  heuristicColorChanges, gap, proven, and the heuristic's due-date deviation on that instance (the
  "price" of the colour gap).
- **ADR-002 update:** **supersede** (not append to) the current "deferred / OR-Tools Java failed on
  Windows" framing — record that the upgrade path is now realized as an **offline oracle** (not a policy
  swap; the heuristic stays the policy), via the Python OR-Tools subprocess path ADR-002 itself
  suggested, with the measured gaps and the honest scope.
- **Research note** (e.g. `research/` or the README's honesty section): the measured gaps, the
  small-instance / colour-axis scope, and the oracle-not-replacement framing.

## Testing strategy & honesty boundary

- **Pure model unit tests (Python, offline):** hand-built tiny instances with a hand-computable optimum:
  - `N=1` (degenerate) → 0 transitions regardless of capacity.
  - single colour → 0 transitions; two colours fully separable within capacity (e.g. `[R,R,B,B]`, K=2,
    cap=2) → 1 transition.
  - **single-lane FIFO fidelity:** K=1 → the output is forced to equal the input order, so the optimum
    equals the arrival's colour transitions exactly (e.g. `[R,B,R]`, K=1 → 2). Pins that the model cannot
    reorder within a lane.
  - **capacity-binding case:** capacity so small the buffer genuinely constrains reordering — assert the
    optimum is **strictly greater than the free-permutation lower bound** (`#distinct_colours − 1`),
    proving capacity-over-time actually binds and the model is NOT the loose free-merge (an
    output-position-only encoding would wrongly hit the free bound). This pins the Section C capacity fix.
  - **due-date constraint binds:** an instance where keeping colours batched would push due-date deviation
    above the budget — assert the optimum under a *tight* budget is **strictly greater than** the optimum
    under an *infinite* budget (the due-date hard constraint forces extra colour changes). This pins that
    the due-date constraint is wired and actually binds. Also: an unconstrained call (`due_dates=None` /
    no budget) reproduces the pure-colour optimum (backward-compatible with the colour-only tests above).
  - verify the `proven` flag maps correctly (`OPTIMAL`→true, time-limited `FEASIBLE`→false).
- **Regression test (Python, committed fixtures):** over every committed fixture, assert
  (1) `optimalColorChanges <= heuristicColorChanges` on every seed — the **gap ≥ 0 invariant**, which is
  *also the model-soundness net*: the oracle is a true lower bound (the heuristic is a feasible policy),
  so `optimal > heuristic` would mean an over-constrained/buggy model and fails loudly; and
  (2) `heuristicColorChanges - optimalColorChanges <= CEILING` — a documented regression ceiling
  (the heuristic must not silently regress far from optimal, and stale fixtures after a heuristic change
  surface here). The ceiling value is set from the measured gaps at build time and documented.
- **Kotlin exporter test:** determinism — same `(seed, bodies, laneSpecs)` → identical fixture JSON
  (matches the existing bench determinism discipline). Existing `mvn test` stays green.
- **Boundary restated:** exact CP-SAT is exponential → small instances only; gap is colour-axis only;
  synthetic instances; the heuristic remains the production policy (the oracle is measurement only).

## Components summary

| Unit | Responsibility | Depends on |
|------|----------------|-----------|
| `bench/OptimalityInstanceExporter` (Kotlin) | generate instance + run heuristic → emit JSON fixture | PbsLoadGenerator, PbsSimRunner, DynamicSequencingPolicy |
| `solver/optimality_gap.py` core (Python) | buffer-constrained colour-min CP-SAT → OracleResult | ortools |
| `solver/optimality_gap.py` CLI/wrapper | read fixture, compute gap, print report | core |
| `solver/fixtures/*.json` | committed small instances + heuristic colorChanges + dueDateDeviation | exporter (regen) |
| Python unit tests | solver correctness on hand-built instances | core |
| Python regression test | gap≥0 invariant + ceiling over fixtures | fixtures, core |
| ADR-002 update + research note | honest record of the oracle + measured gaps | — |

## Out of scope (explicit YAGNI)
- Real-time CP-SAT as a `SequencingPolicy` (ADR-002: too slow); **full multi-objective Pareto sweep**
  (we include only the *single* due-date hard constraint at the heuristic's level — sweeping the whole
  colour-vs-due-date frontier is future work); large instances; wiring the JVM test suite to OR-Tools;
  a Grafana panel for the gap.
- **Strict greedy-admission-faithful optimum** (encoding the simulator's ASAP pull timing exactly): the
  oracle uses the capacity-respecting *relaxation* (Section C) which is a sound conservative bound;
  tightening to the exact greedy discipline is recorded as future work, not built now.

## Done criteria
- `solver/optimality_gap.py` (pure core + CLI) green under its unit + regression tests (OR-Tools).
- Kotlin exporter + committed `solver/fixtures/*.json` (one per seed); exporter determinism test green;
  existing `control/` `mvn test` stays green.
- `gap ≥ 0` proven on every seed; heuristic gap within the documented ceiling.
- ADR-002 updated to "offline oracle" framing; research note records measured gaps + honest scope.
- Existing control + sim suites remain green (no regression).
