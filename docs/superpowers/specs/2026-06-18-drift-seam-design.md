# S5 — Drift Seam: Semi-Automatic Config + Behavioral Drift Detection

**Date:** 2026-06-18 · **Module:** `control/` (Kotlin) · **Status:** design (approved, pre-plan)
**Branch base:** `main` (post S1–S4).

## Why (motivation)

Deep research (memory `virtual-commissioning-gap`) verified two industry-wide pains: (1) no turnkey
that stitches the virtual-commissioning layers together; (2) virtual environments **diverge from the
real plant** (sim-real / model-reality drift) and become abandoned double-labor. The unmet, vendor-
neutral angle — **treat the real plant as the source of truth and detect drift semi-automatically** —
is an immature frontier no major vendor occupies (TRL 3-4 for full automation). This slice builds a
**narrow, honest PoC** of that angle, scoped to the layers that are genuinely feasible engineering:

- **① Configuration drift** — real config (lane capacities, blocked lanes) vs the twin's expected
  baseline, read via a live source (OPC-UA in the real demo). Structured diff. **Feasible.**
- **② Behavioral drift** — the twin's *predicted* KPI (resequence-twin `LivePbsProcessor` shadow) vs the
  *observed* KPI, residual-monitored (EWMA/SPC). **Feasible at signal level.**

Out of scope (would be overselling per the research): portable semantic ladder-logic diff, full
as-built auto model regeneration, automatic write-back/correction to a live controller.

## Honesty / safety invariants (non-negotiable)

- **detect → propose → human approve.** NO write-back to any controller; reconciliation outputs are
  **advisory only** (consistent with resequence-twin's existing read-only advisory stance).
- The PoC's "real observed" config & KPI are **synthetic (perturbed simulation)** — we demonstrate the
  **detection mechanism**, not a real plant. Every API/report carries the synthetic disclaimer envelope.
- Baseline is **captured from the live source**, not hand-authored (this is the "real = source of
  truth" idea applied narrowly to config; it also inverts the double-labor pain).
- TRL framing: this is a **research-grade PoC of the detection seam**, not a solved capability.

## Architecture — new package `com.resequencetwin.control.drift`

Three layers: pure domain (offline-testable) + ports (live-source abstraction) + decoupled monitor.
Reuses resequence-twin patterns: the `PbsTwinProjector` decoupled-`@Scheduled` pattern; the read-only advisory
REST + honesty-envelope pattern; the EmbeddedKafka-style "real-stack-is-user-run" boundary.

### A. Expected baseline (pure types)
- `ExpectedConfig` (data class): `lanes: Map<String, Int>` (laneId → capacity), `expectedBlocked:
  Set<String>`. KDoc notes the real-deployment mapping to an AAS submodel (documented, **not built**).
- `ObservedConfig` (data class): same shape, representing what the live source currently reports.
- Baseline is produced by `DriftMonitor.captureBaseline()` = one snapshot of the **live config source**
  at startup. **The existing `LivePbsProcessor` singleton is NOT re-seeded or re-instantiated** — its
  lane topology stays fixed from `pbs.lanes` (= the twin's standing assumption). Config drift =
  `diff(captured baseline, later observed)`. The "real = source of truth" property is honored by
  *capturing* the baseline from the live source (not hand-authoring it); we deliberately do NOT mutate
  the running twin to match reality (that would be the forbidden write-back direction).

### B. Live-source ports (2 adapters each)
- `interface LiveConfigSource { fun readConfig(): ObservedConfig }`
  - `SimulatedConfigSource` — in-process, deterministic, **mutable** (demo injects: capacity 10→8,
    disable a station/lane, etc.). Offline TDD + offline demo.
  - `OpcUaConfigSource` — Eclipse **milo** OPC-UA client reading nodes → `ObservedConfig`. Real demo
    (user-run against an external OPC-UA simulation server, or the in-JVM embedded milo test server).
- `interface LiveTelemetrySource { fun readKpi(): ObservedKpi }` — same two-adapter shape
  (`SimulatedTelemetrySource`, `OpcUaTelemetrySource`).
  - **`ObservedKpi` is an exact mirror of the comparable cumulative fields on `LiveSnapshot`** (NOT a
    new vocabulary — avoids unit mismatch): `releases: Int`, `colourChanges: Int`, `assemblyOut: Int`
    (← `LiveSnapshot.assemblyOutSize`). These are cumulative monotonic counts. **There is no
    `throughput` field** — a rate is derived inside `ResidualDriftDetector` as the per-tick *delta* of a
    cumulative metric, not stored on `ObservedKpi`. The twin's predicted side comes from the same three
    `LiveSnapshot` fields, so prediction and observation are dimensionally identical.

### C. Detectors (pure — the unit-test core)
- `ConfigDriftDetector.detect(expected: ExpectedConfig, observed: ObservedConfig): List<ConfigDriftFinding>`
  - `ConfigDriftFinding { laneId: String, kind: DriftKind, expected: ..., observed: ... }`
    where `DriftKind ∈ {CAPACITY_CHANGED, UNEXPECTED_BLOCK, EXPECTED_LANE_MISSING, ...}`.
  - Each finding carries a `ReconciliationProposal` (the twin-side change that would re-align to reality)
    — **advisory, never auto-applied**.
- `ResidualDriftDetector` — stateful but deterministic, **one instance per compared metric** (e.g.
  `releases`, `colourChanges`, `assemblyOut`). `update(predicted: Double, observed: Double):
  BehavioralDriftFinding`. The caller (`DriftMonitor`) passes the *current cumulative value* of the
  twin-predicted and observed metric each tick; the detector internally tracks the previous values to
  compute the per-tick **delta (rate)** of each side and then the residual between the two deltas, so a
  cumulative-vs-cumulative or rate-vs-rate comparison is always dimensionally consistent. Maintains an
  EWMA of the residual; flags `breached = true` when |EWMA| (or the raw residual) exceeds a configured
  threshold/SPC limit. `BehavioralDriftFinding { metric, predicted, observed, residual, ewma, breached }`.
  EWMA alpha + per-metric threshold are config (`pbs.drift.*`).

### D. Orchestration (decoupled monitor)
- `DriftMonitor` (`@Component`):
  - `captureBaseline()` at startup: snapshot the live config source → `ExpectedConfig`. (Does NOT
    re-seed the `LivePbsProcessor` — see Section A.)
  - Reads the existing Spring-wired `LivePbsProcessor` **singleton** (constructor-injected) for the twin
    prediction; does not own or re-instantiate it.
  - `@Scheduled(fixedDelayString = "\${pbs.drift.interval-ms:1000}") tick()` — gated by an
    `enabled: Boolean` **field** (`@Value("\${pbs.drift.enabled:true}")`, read once at construction, NOT
    re-read per tick — identical to `PbsTwinProjector`; **test sets false**). Per tick: read observed
    config + telemetry, pull twin predicted KPI from `LivePbsProcessor.snapshot()`, feed the per-metric
    `ResidualDriftDetector`s, run `ConfigDriftDetector`, aggregate into a `DriftReport`, expose via
    metrics + REST, log only newly-appeared findings. Live-source/telemetry read errors (e.g. OPC-UA
    unreachable) are caught and logged — a source outage must NOT crash the scheduler (same graceful
    pattern as `PbsTwinProjector`); the report flags the source as unavailable for that tick.
  - Runs on the scheduler thread — **does NOT touch the Kafka consumer thread** (decoupling, same
    rationale as `PbsTwinProjector`).
- Surface:
  - Micrometer gauges: `pbs.drift.config.findings` (count), `pbs.drift.behavioral.breaches` (count).
  - Read-only REST `GET /api/drift` → `DriftReport` { synthetic, disclaimer, baseline, observed,
    configFindings[], behavioralFindings[], reconciliationProposals[] }.

## Data flow

1. **Startup:** `captureBaseline()` snapshots the live config source → `ExpectedConfig`. (Twin topology
   stays as `pbs.lanes`; no re-seed — config drift is measured against the *captured* baseline.)
2. **Steady tick:** read `ObservedConfig` → `ConfigDriftDetector` → config findings; read `ObservedKpi`
   + twin `snapshot()` → `ResidualDriftDetector` → behavioral findings; aggregate → `DriftReport` →
   metrics + `/api/drift`; log new findings.
3. **Demo:** mutate `SimulatedConfigSource` (capacity 10→8 / disable lane / inject throughput slowdown)
   → next tick surfaces config drift + reconciliation proposal and/or a behavioral residual breach in
   `/api/drift`.

Subtlety (documented honestly): the twin's prediction and the "real" observation derive from the same
synthetic event stream; the demo perturbs the "real" side to create the residual. We show the
**mechanism**, on synthetic perturbation.

## Testing strategy & honesty boundary

- **Pure detector unit tests** (offline, no Spring): `ConfigDriftDetector` (capacity change, unexpected
  block, expected-lane-missing, no-drift baseline → empty) ; `ResidualDriftDetector` (EWMA crosses
  threshold → breached; within tolerance → not breached; determinism).
- **Monitor integration (offline):** `SimulatedConfigSource` + simulated telemetry → inject drift →
  assert findings surface in `DriftReport` / gauges / REST. `/api/drift` via `@WebMvcTest` slice
  (avoids Kafka/Ditto), matching existing `api/` test pattern.
- **OPC-UA adapter test:** prefer an **in-JVM embedded milo OPC-UA server** → `OpcUaConfigSource` reads
  from it = real OPC-UA round-trip with **no external Docker** (analogous to EmbeddedKafka). If the
  embedded server proves heavyweight, fall back to marking it `@Tag`/user-run integration like the
  existing Ditto `@SpringBootTest` tests, and document it.
- **`contextLoads` stays green without Docker** (`pbs.drift.enabled=false` in test properties; scheduler
  no-ops).
- **Real demo = user-run:** `OpcUaConfigSource` against an external OPC-UA simulation server
  (node-opcua / Prosys) — documented in the README runbook. Honesty: the data is synthetic.

## Components summary (single-responsibility units)

| Unit | Responsibility | Depends on |
|------|----------------|-----------|
| `ExpectedConfig` / `ObservedConfig` / `ObservedKpi` | immutable value types | — |
| `LiveConfigSource` / `LiveTelemetrySource` (ports) | abstract the live source | value types |
| `SimulatedConfigSource` / `SimulatedTelemetrySource` | deterministic, mutable test/demo source | ports |
| `OpcUaConfigSource` | milo OPC-UA client: read config nodes (lane caps, blocked) → `ObservedConfig` | port, milo |
| `OpcUaTelemetrySource` | milo OPC-UA client: read KPI nodes (releases/colourChanges/assemblyOut) → `ObservedKpi` | port, milo |
| `ConfigDriftDetector` | structured config diff → findings + proposals | value types |
| `ResidualDriftDetector` | EWMA residual → behavioral findings | — |
| `DriftMonitor` | schedule, baseline capture, orchestrate, expose | all of the above, `LivePbsProcessor` |
| `DriftController` / `DriftService` | read-only `/api/drift` | `DriftMonitor` |

## Configuration keys
`pbs.drift.enabled` (default true; test false), `pbs.drift.interval-ms` (default 1000),
`pbs.drift.ewma-alpha` (default e.g. 0.3), `pbs.drift.residual-threshold` (default tuned per metric),
`pbs.drift.source` (sim | opcua; default sim), OPC-UA endpoint key when `opcua`.

## Dependencies
- Eclipse **milo** (`org.eclipse.milo:sdk-client` for `OpcUaConfigSource`/`OpcUaTelemetrySource`; and
  `org.eclipse.milo:sdk-server` only if the in-JVM embedded-server test path is taken) is **NOT yet in
  `control/pom.xml`** — the plan must add it. The simulated sources + pure detectors need NO new
  dependency, so the offline core can be built and fully tested before milo is introduced (sequence the
  OPC-UA adapter + its dependency as a later, isolated plan step).

## Out of scope (explicit YAGNI)
Semantic ladder/ST logic diff; program-change (③) detection; full as-built auto model generation;
automatic write-back/correction to a controller; multi-vendor adapters beyond one OPC-UA adapter;
AAS/BaSyx build (mapping documented only); Grafana panels for drift (could be a later add).

## Done criteria
- `mvn test` from `control/` green (existing 195 + new); `contextLoads` green without Docker.
- Pure detectors + monitor integration prove config drift and behavioral residual detection on injected
  synthetic perturbation; `/api/drift` returns findings + advisory reconciliation proposals.
- OPC-UA adapter exercised by embedded-milo test (or documented user-run) — no write-back anywhere.
- README runbook: how to run the real OPC-UA demo (user-run, synthetic-labeled).
- Memory `virtual-commissioning-gap` updated: PoC seam built (which layers, honest scope).
