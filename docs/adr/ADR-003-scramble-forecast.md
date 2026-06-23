# ADR-003: Scramble-Forecast Predictor — Transparent Heuristic vs. Learned Model

- **Date:** 2026-06-17
- **Status:** Accepted
- **Context:** resequence-twin PBS resequencing PoC, rev3 R4 (advisory agent)

---

## Context

R4 adds an **advisory** layer over the PBS resequencing core: a Python MCP agent exposes
read-only tools that an MCP host (Claude) calls to reason about the control service's
decisions. One of those tools, `predict_scramble`, answers the question:

> *"If the current release policy continues, how badly is the release sequence likely to
> scramble — lose colour batching and/or JIS adherence — and what should we do about it?"*

This requires a forecast over the **current PBS buffer state**, surfaced as:
- a `riskScore` in `[0, 1]`,
- a coarse `level` (LOW / MEDIUM / HIGH),
- the contributing factors with the specific lane/body driving each, and
- a `stabilizationHint`.

The design tension is between two honest pulls:

1. A production deployment would value **ML/MLOps** capability, which argues for a learned model.
2. The PoC's **honesty spine** forbids claiming a trained model where none exists, or
   fabricating predictive accuracy on synthetic data.

Two candidate approaches were evaluated.

---

## Decision

**Use a transparent, deterministic weighted-average heuristic** for the PoC, explicitly
labelled as *not a trained model*. The learned-predictor path is documented and preserved
as the production upgrade path — exactly mirroring how ADR-002 frames the
greedy-heuristic-vs-CP-SAT choice for the sequencing solver.

This keeps the ML-adjacent *thinking* visible (interpretable features, a scoring model, a
calibration discussion, and a concrete upgrade path) **without** dishonestly asserting a
model was trained or validated on data the PoC does not have.

---

## Option A — Learned classifier (production path)

**Design:** generate `(buffer-state features → realized-scramble label)` pairs by running
many seeded simulations forward to completion, label each mid-run snapshot with the
scramble actually realized downstream, and train a classifier/regressor (e.g. logistic
regression or gradient-boosted trees) to predict `riskScore`.

**Why it fits:**
- Produces the genuine ML/MLOps artifact a production setting values (feature pipeline, training job,
  model registry, inference service).
- Could capture non-linear feature interactions a hand-weighted average cannot.

**Why it was not used here:**
- The only available data is **synthetic** (seeded SimPy paint streams). A model trained on
  synthetic labels and then presented as predictive would violate the honesty spine: its
  "accuracy" would be an artifact of the generator, not evidence about real plants.
- The PoC's differentiator is the **multi-objective resequencing decision logic + the
  falsifiable static-vs-dynamic benchmark + the advisory integration architecture** — not a
  model's headline accuracy number.
- A black-box score would also be *worse* for the advisory use case, where the value is an
  **explainable** "why" a Claude host can cite, not an opaque probability.

**Upgrade path:** replace `ScramblePredictor.forecast()`'s scoring body with a trained-model
inference call. The `ScrambleForecast` output contract (riskScore, level, factors,
topContributor, stabilizationHint) and all callers — including the `predict_scramble` MCP
tool — remain unchanged. The interpretable features below become the model's input
features, so the feature-engineering work carries forward directly.

---

## Option B — Transparent weighted-average heuristic (PoC / chosen)

**Model:**

```
riskScore = W_FRAGMENTATION * colorFragmentation
          + W_OVERDUE       * overdueRatio
          + W_IMBALANCE      * laneImbalance
```

| Weight | Value | Feature it scores |
|--------|-------|-------------------|
| `W_FRAGMENTATION` | 0.45 | Colour fragmentation across lane fronts (dominant — colour batching is the primary PBS objective, per ADR-002 / Ford literature) |
| `W_OVERDUE` | 0.35 | Ratio of lane fronts already overdue vs. JIS due position |
| `W_IMBALANCE` | 0.20 | Lane-occupancy imbalance (uneven buffer use limits future choice) |

The weights **sum to 1.0**, so `riskScore` is guaranteed to stay in `[0, 1]` (every feature
is normalised to `[0, 1]`). Each weight is a named constant carrying a Javadoc rationale.

**Interpretable features (all read from the current `PbsState`):**

- **`colorFragmentation`** = `distinctFrontColors / nonEmptyLanes`. More distinct colours
  among the lane fronts ⇒ the next releases cannot extend a single colour run ⇒ higher
  scramble risk. *Acknowledged characteristic:* a perfectly batched same-colour buffer with
  N non-empty lanes still scores `1/N` (e.g. 0.5 for 2 lanes), so `riskScore` is `0` only
  when all lanes are empty. This floor is documented on `ScrambleForecast.riskScore` and
  keeps a batched buffer solidly in the LOW band rather than at exactly zero.
- **`overdueRatio`** = `overdueFronts / nonEmptyLanes`, where a front is overdue when
  `dueDateSeq <= assemblyOut.size()`. This mirrors the `DynamicSequencingPolicy`
  anti-starvation hard override, so the forecast is consistent with the policy it advises.
- **`laneImbalance`** = coefficient of variation (population stddev / mean) of lane
  occupancy, clamped to `[0, 1]`.

**Risk bands (`ScrambleLevel`, single source of truth for both Java and the agent glossary):**

```
LOW    : riskScore < 0.35
MEDIUM : 0.35 <= riskScore < 0.65
HIGH   : riskScore >= 0.65
```

**Driving lane/body + stabilization hint:** the `topContributor` is the factor with the
largest weighted contribution. For the overdue factor, the structured `drivingLaneId` /
`drivingBodyId` fields name the specific overdue lane front (computed once, shared with the
human-readable `stabilizationHint` so the two cannot diverge). The hint is an actionable
suggestion derived from the top contributor (e.g. *"Lane L2 front is overdue — release it
next to protect JIS order"*).

**Determinism:** no `Date.now()` or `Math.random()`. Same `PbsState` ⇒ identical forecast.
This matches the determinism guarantee of the rest of the control service and makes the
advisory tool reproducible.

---

## Consequences

- **Honest by construction.** The class-level Javadoc states plainly that this is a
  transparent heuristic, *not* a trained model, with no fitted parameters and no inference
  engine. The agent's KPI glossary (consumed by the RAG tool) repeats the same framing and
  the same thresholds — verified to match `ScrambleLevel` exactly (a prior cross-language
  threshold drift, 0.33/0.67 vs 0.35/0.65, was caught and corrected to the Java values).
- **Explainable for the advisory use case.** Because every contribution is a named feature ×
  named weight, the Claude host can cite *why* a buffer is risky and *which* lane/body drives
  it — strictly better grounding than an opaque score.
- **Read-only & advisory.** The predictor never mutates `PbsState` or policy internals; it is
  surfaced only through the read-only `predict_scramble` MCP tool over a GET REST endpoint.
- **Calibration is heuristic, not learned.** The weights (0.45 / 0.35 / 0.20) and band
  thresholds (0.35 / 0.65) are PoC cut-points calibrated to the benchmark buffer scale
  (3 lanes × capacity 10), not derived from real plant data. This is stated wherever the
  numbers appear.
- **Upgrade cost is low.** Swapping in a learned model touches only `forecast()`'s scoring
  body; the output contract, the MCP tool, and the tests' structural expectations are stable.

---

## Honesty notes

- This is a synthetic-simulation PoC. The scramble forecast is an advisory heuristic, not a
  validated predictor; no accuracy claim is made.
- Ford Saarlouis KPI numbers (arXiv 2507.17422) are a published reference for the real-world
  resequencing problem, **not** measurements from this PoC. See `research/`.
- The "ML/MLOps" capability this tool signals is the *engineering of an explainable,
  feature-based, upgrade-ready forecasting component* — not a trained model presented as such.
