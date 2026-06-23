# ADR-002: Sequencing Solver Choice — OR-Tools CP-SAT vs. Greedy Lookahead Heuristic

- **Date:** 2026-06-17 (updated 2026-06-19)
- **Status:** Accepted — see the [2026-06-19 update](#update-2026-06-19-cp-sat-realized-as-an-offline-optimality-oracle): CP-SAT is now realized as an **offline optimality oracle** (not a policy swap; the heuristic remains the runtime policy)
- **Context:** resequence-twin PBS resequencing PoC, rev3 R2

---

## Context

The `DynamicSequencingPolicy` must balance three objectives on each release decision:

1. **Colour-batch extension** — keep same-colour bodies together to minimise paint colour changes (dominant objective in Ford Saarlouis PBS literature).
2. **Option leveling** — avoid clustering high-work-content option bodies in the assembly input stream.
3. **Due-date / sequence adherence** — respect JIS sequence numbers; no body may be starved beyond its window.

Two candidate solver approaches were evaluated.

---

## Decision

**Use a greedy lookahead heuristic** for the PoC. The OR-Tools CP-SAT path is documented and preserved as the production upgrade path.

---

## Option A — OR-Tools CP-SAT (production / stack-fit)

**Why it fits:**
- CP-SAT handles multi-objective combinatorial sequencing natively (weighted objectives, hard constraints, windowed time-horizon lookahead).
- Architecturally correct for PBS: the problem is a variant of sequence-dependent scheduling, which is a known CP-SAT application domain.
- Aligns with the spec's tech stack mention of OR-Tools as the intended solver.
- Would produce provably optimal or near-optimal sequences for moderate-size lookahead windows.

**Why it was not used here:**
- OR-Tools Java native libraries (`com.google.ortools:ortools-java`) ship platform-specific `.dll`/`.so` JNI bindings. These required platform-matching native artifacts that caused build failures on this Windows development environment in earlier project phases (native lib resolution blocked `mvn test`).
- Integrating OR-Tools would require either: (a) Maven `classifier`-specific native JARs and careful PATH/library resolution, or (b) switching the solver to a Python subprocess call, adding inter-process complexity.
- The PoC's differentiator is the **multi-objective resequencing decision logic and the static-vs-dynamic contrast** — not the solver engine. The heuristic captures this correctly and allows R3 benchmark validation to proceed.

**Upgrade path:** Replace `DynamicSequencingPolicy.score()` with a CP-SAT model call (e.g., via OR-Tools Java or a Python subprocess). The `SequencingPolicy` interface and all callers remain unchanged. The R3 benchmark harness will automatically pick up the improved policy.

---

## Option B — Greedy Lookahead Heuristic (PoC / chosen)

**Design:**
Each candidate lane-front body receives a composite score:

```
score(b) = W_COLOR  * colorBatchBonus(b)
         + W_OPTION * optionLevelingBonus(b)
         + W_DUE    * dueDateBonus(b)
```

| Weight | Value | Objective |
|--------|-------|-----------|
| `W_COLOR` | 4.0 | Colour-batch extension (dominant per Ford literature) |
| `W_DUE`   | 3.0 | Due-date urgency proximity |
| `W_OPTION`| 2.0 | Option leveling (workload spread) |

**Hard constraint (anti-starvation):** Any body with `dueDateSeq ≤ assemblyOutSize` (overdue) is released immediately before scoring, regardless of colour. This prevents the colour objective from indefinitely blocking a due body.

**Determinism:** No `Date.now()` or `Math.random()`. Tie-breaking by lane insertion order. Fully reproducible given the same `PbsState` and policy instance state — required for R3 seeded benchmark regression.

**Why it is genuinely multi-objective:**
- Not pure colour-greedy: the hard due-date override prevents starvation even when it breaks a colour batch.
- Not pure due-date FIFO (that's the static baseline): colour and option weights actively influence release order within the due-date window.
- Option leveling actively penalises releasing option-heavy bodies when the recent window is saturated.

---

## Update (2026-06-19): CP-SAT realized as an offline optimality oracle

**Status change: the "deferred / Java failed" framing is superseded.** The OR-Tools CP-SAT path ADR-002 itself anticipated — *"OR-Tools Java or a Python subprocess"* — has been realized, not as a policy swap but as an **offline optimality oracle** in a new top-level `solver/` package (Python, OR-Tools).

### What changed

The `DynamicSequencingPolicy` heuristic **remains the production policy.** Exact CP-SAT is exponential in the number of bodies; it cannot serve as a real-time release policy, exactly as this ADR stated. The upgrade is to a separate, offline measurement tool: the oracle runs CP-SAT on small committed fixtures and reports the heuristic's **gap to the proven colour optimum**, directly answering the "how far from optimal is your heuristic?" question.

### Oracle design

The CP-SAT oracle models the same K-FIFO resequencing buffer: bodies arrive in order, each is routed to a FIFO lane (capacity `cap_l`), and released to an output sequence. The oracle jointly chooses routing and pull/release schedule to **minimise colour transitions** in the output, subject to:

- **Capacity-over-time:** at every event step, no lane exceeds its physical capacity. Pull timing is a *free* decision (not forced to match the simulator's greedy ASAP discipline) — this is a **relaxation** of the simulator's greedy admission, making the oracle a stronger (hence more conservative, never flattering) lower bound.
- **Due-date hard constraint:** the output's total due-date deviation must be ≤ the heuristic's achieved deviation on the same instance (`Σ|pos[i] − dueDateSeq[i]| ≤ round(N · heuristic.dueDateDeviation)`). Without this constraint, the colour optimum would be trivially `#colours − 1` (because `PbsLoadGenerator` emits colour-batched streams). The due-date constraint forces the buffer to reorder for JIS adherence, breaking colour batches, making the optimum a genuine CP-SAT result and the gap a **fair, Pareto-style comparison**: colour the heuristic wastes *at its own due-date level*.

Because the heuristic's own run is a feasible point of "minimise colour s.t. deviation ≤ B", `gap = heuristicColorChanges − optimal ≥ 0` is **sound by construction**.

The gap is also a **conservative upper bound** on the heuristic's distance from a same-discipline (greedy-admission) optimum: `optimal_relaxed ≤ optimal_greedy ≤ heuristic`, so the claim "heuristic is within G colour-changes of this optimum" understates the heuristic's quality.

### Measured results (N=12 bodies, 3 lanes, cap=1 each, seeds {1,7,42,99,2024}, proven within 10 s)

| seed | unconstrained colour-opt | due-date-constrained opt | heuristic colorChanges | gap (heuristic − constrained opt) | proven |
|------|:---:|:---:|:---:|:---:|:---:|
| 1    | 2 | 3 | 3 | 0 | yes |
| 7    | 1 | 1 | 3 | 2 | yes |
| 42   | 2 | 3 | 3 | 0 | yes |
| 99   | 2 | 3 | 3 | 0 | yes |
| 2024 | 2 | 3 | 5 | 2 | yes |

**Headline:** the heuristic is within 0–2 colour-changes of the proven due-date-constrained optimum. Exactly optimal (gap = 0) on 3/5 seeds — i.e. Pareto-efficient at its own due-date level on those instances. The due-date constraint binds on 4/5 seeds (constrained-opt 3 > unconstrained-opt 2), confirming the optimum is non-trivial. Regression ceiling = 3 (max gap 2 + 1 slack).

### Honest scope and caveats

- **Small synthetic instances only.** Exact CP-SAT is exponential; the study is scoped to instances where optimality is provable within a 10 s budget. This is not a claim about large or real-plant instances.
- **Colour-axis with a single due-date hard constraint, not a full Pareto sweep.** The oracle minimises `colorChanges` subject to one hard due-date bound. A full multi-objective Pareto front (sweeping the entire colour-vs-due-date trade-off frontier) is out of scope and recorded as future work.
- **The gap is a conservative upper bound.** The oracle uses a capacity-respecting *relaxation* of the simulator's greedy admission discipline. The relaxed optimum is ≤ the same-discipline greedy optimum, so the reported gap cannot understate the heuristic's quality.
- **Synthetic data.** Fixtures come from the seeded `PbsLoadGenerator`; no real plant data.
- **The heuristic stays the policy.** The oracle is a measurement tool, not a replacement. `DynamicSequencingPolicy` remains the production policy.

### Artefacts

- `solver/` — Python OR-Tools CP-SAT oracle (`solver/solver/model.py`, `solver/solver/optimality_gap.py`) + committed fixtures (`solver/fixtures/*.json`) + regression tests.
- `research/optimality-gap.md` — detailed research note with per-seed table, method, and scope.

---

## Consequences

- **Static-vs-dynamic contrast** is fully supported: both policies share the `SequencingPolicy` interface, making the R3 benchmark swap trivial.
- **R3 regression invariants (honest classification):**
  - `dynamic.colorChanges ≤ static.colorChanges` — **HARD invariant**: holds on ALL seeds across the robustness sweep {1, 7, 42, 99, 100, 1234, 2024} with 3×10 lanes.
  - `dynamic.batchLength ≥ static.batchLength` — **HARD invariant**: holds on ALL seeds.
  - `dynamic.dueDateDeviation ≤ static.dueDateDeviation` — **DOCUMENTED OBSERVATION only**: the heuristic improves due-date deviation on the majority of seeds but NOT universally. For some seed/config combinations (e.g., seed=99 with 3×10 lanes), the color-batch objective (`W_COLOR=4.0`) outweighs the due-date objective (`W_DUE=3.0`), producing more JIS deviation than the static baseline. This is an inherent trade-off of the weighted scalar approach: it is not possible to simultaneously dominate all three objectives with a single-pass greedy heuristic in all cases. This is asserted honestly as "majority" in `PbsBenchRegressionTest.OBS-3`, not as an unconditional pass.
- **Due-date/color trade-off note:** The tension between `W_COLOR` and `W_DUE` is fundamental. A production-grade solution should use CP-SAT with explicit Pareto-front multi-objective formulation (minimize color changes AND minimize JIS deviation simultaneously, with hard due-date constraints). The greedy scalar heuristic is a PoC approximation that cannot guarantee Pareto optimality.
- **Honesty:** This is a PoC heuristic, not a CP-SAT optimal solver. README and dashboard labels reflect this. Ford Saarlouis KPI numbers (+30% batch / -23% colour changes) are public references for the real-world problem, not measurements from this PoC.
- **Upgrade cost:** Replacing the heuristic with CP-SAT requires only implementing `score()` differently; all tests, benchmarks, and the interface contract remain stable.
