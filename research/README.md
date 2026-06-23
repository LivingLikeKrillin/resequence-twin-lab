# Research & Grounding — resequence-twin (PBS resequencing PoC)

> **Honesty contract.** Every external number on this page is a **published reference** for the
> *real-world* problem. **None of it is a measurement from this PoC.** This PoC produces only
> **relative deltas** (dynamic vs. static, same seeded synthetic stream); see the root `README.md`
> §Honesty.

This file records the falsifiable real-world template the PoC is modelled on, and the public
evidence that the scenario (automotive Painted-Body-Store resequencing) is a real, current
industrial problem.

---

## 1. The falsifiable template — Ford Saarlouis car resequencing

**Karrenbauer, A., Kuhn, B., Mehlhorn, K., Rinaldi, P. L. (2025).**
*Optimizing Car Resequencing on Mixed-Model Assembly Lines: Algorithm Development and Deployment.*
arXiv:2507.17422 (submitted 2025-07-23). https://arxiv.org/abs/2507.17422

Why this paper anchors the PoC:

- **Same problem.** A multi-objective resequencing algorithm for a mixed-model assembly line that
  simultaneously minimises **paint-shop colour changeovers**, balances **option/workload**, and
  respects **delivery (JIS) deadlines** — exactly the three objectives the
  `DynamicSequencingPolicy` scores (colour 4.0 / due-date 3.0 / option 2.0; see ADR-002).
- **Real deployment, not a toy.** Empirical results were obtained from **recorded event data over
  4 weeks following deployment at the Saarlouis plant of Ford-Werke GmbH** — i.e. the problem
  framing is falsifiable against an actual production line.
- **Reference KPIs (real plant, NOT this PoC):** ≈ **+30 % average batch size**, **−23 % colour
  changeovers**, **−10 % delivery-date spread**. The PoC's benchmark KPIs (colour changes, batch
  length, due-date deviation) are the *same metric family*, reported only as relative deltas on a
  synthetic 100-body stream — structurally analogous, **not** claimed to match Ford's scale.

How the PoC stays honest against this template:

| KPI | Ford Saarlouis (published) | This PoC (synthetic, relative) |
|---|---|---|
| Colour changes | −23 % | HARD invariant: dynamic ≤ static on all 7 robustness seeds |
| Batch length | +30 % | HARD invariant: dynamic ≥ static on all 7 seeds |
| Due-date deviation | −10 % | DOCUMENTED OBSERVATION: improves on majority of seeds, not universal (W_COLOR>W_DUE can override JIS on some seeds; see ADR-002 OBS-3) |

---

## 2. PBS (Painted Body Store) — domain context

The PBS is a buffer of FIFO lanes between the **paint shop** (which prefers long same-colour
batches to cut colour-change cost) and **assembly** (which needs a JIS / option-levelled order).
These two objectives conflict; the PBS exists to **resequence** bodies between them. This is an
automotive-specific decoupling problem (paint↔assembly takt mismatch) and is unrelated to the
semiconductor OHT routing scenario the project was originally scaffolded around (now legacy; see
ADR-001 superseded banner and `legacy-oht/`).

Key properties the PoC models:
- Paint-optimal (colour-batched) input order vs. shuffled JIS due positions (the resequencing
  challenge) — see `bench/PbsLoadGenerator` and `sim/paint_stream.py`.
- Anti-starvation hard override so colour batching can never indefinitely delay an overdue body.
- Relative, fix-toggle benchmark to prove the gain is *caused* by the optimisation, not the harness.

---

## 3. Tools considered — honest scope (not used, or used only in a limited role)

- **NVIDIA Omniverse / OpenUSD** — appeared in an earlier spec revision only as an *optional /
  stretch* 3D-twin visualisation idea (rationale: adopted by Samsung/SK/TSMC → stack-fit signal).
  **It was never built or used.** Actual visualisation is Grafana; actual simulation is SimPy +
  the Kotlin seeded generator. Do not represent Omniverse as used — at most "evaluated, not adopted".
- **OR-Tools CP-SAT** — architecturally the right sequencing solver. **Not used as the runtime
  policy** (exact CP-SAT is exponential and can't make a real-time release decision; the production
  policy is the greedy-lookahead heuristic). It **is** realized in a limited role as an **offline
  optimality-gap oracle** (`solver/`, Python OR-Tools) that measures the heuristic's distance to the
  proven optimum on small synthetic instances — see ADR-002 (2026-06-19 update) and
  `research/optimality-gap.md`. The scramble forecast similarly uses a transparent heuristic, not a
  trained model (ADR-003).

---

## Sources

- [arXiv:2507.17422 — Optimizing Car Resequencing on Mixed-Model Assembly Lines](https://arxiv.org/abs/2507.17422)
