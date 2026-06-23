# S5 Drift Seam Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a narrow, honest PoC of semi-automatic drift detection for the resequence-twin PBS line — config drift (live lane topology vs a captured baseline) and behavioral drift (twin-predicted KPI vs observed KPI, EWMA residual) — surfaced as advisory, read-only findings with reconciliation proposals and **no write-back anywhere**.

**Architecture:** A new Kotlin package `com.resequencetwin.control.drift` with three layers: (1) pure immutable value types + pure detectors (offline-unit-testable, no Spring/Docker/new deps); (2) live-source **ports** (`LiveConfigSource`/`LiveTelemetrySource`) with deterministic `Simulated*` adapters first and an isolated `OpcUa*` (Eclipse milo) adapter added last behind a new dependency; (3) a decoupled `@Scheduled DriftMonitor` that captures the baseline, orchestrates the detectors against the existing Spring-wired `LivePbsProcessor` singleton, and exposes a read-only `GET /api/drift` + two Micrometer gauges. The monitor reuses the exact resilience/decoupling pattern of `PbsTwinProjector` (own scheduler thread, field-gated `enabled`, source outage is non-fatal).

**Tech Stack:** Kotlin (Spring Boot 3.3.1, Java 21 release / Java 25 runtime), Maven, Micrometer/Prometheus, Spring MVC `@WebMvcTest`, JUnit5 + AssertJ; Eclipse milo (`org.eclipse.milo:sdk-client`/`sdk-server`) added only in the final OPC-UA chunk.

**Spec (source of truth):** `docs/superpowers/specs/2026-06-18-drift-seam-design.md`

---

## Honesty / safety invariants (carry into every task — non-negotiable)

- **detect → propose → human approve.** NO write-back to any controller or to the running twin. Reconciliation outputs are **advisory text only**.
- The existing `LivePbsProcessor` singleton is **read-only** to this feature: never re-seeded, re-instantiated, or mutated. Its lane topology stays fixed from `pbs.lanes`.
- Every REST response and the `DriftReport` carry the **synthetic disclaimer envelope** (reuse `AdvisoryEnvelope`). The "real observed" config & KPI are **synthetic (perturbed simulation)** — we demonstrate the detection *mechanism*, not a real plant.
- The baseline is **captured from the live source** (not hand-authored); we do NOT mutate the twin to match reality (that would be the forbidden write-back direction).

## Baseline confirmation (do this BEFORE Task 1)

The Done criteria reference "existing tests + new, all green". The exact existing count is **~195** per project memory but **was not re-run at plan time**. Before writing any code:

- [ ] **Step 0a: Capture the green baseline**

Run (from the repo root; path has spaces + brackets — quote it):
```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q test
```
Expected: `BUILD SUCCESS`. Record the exact `Tests run:` total (e.g. 195) — this is `BASELINE_N`. Every later "all green" assertion means `BASELINE_N + <new tests this task> ` pass, 0 failures, 0 errors (the 2 pre-existing Docker/Ditto skips remain skipped).

> If the baseline is NOT green, STOP and surface to the human — do not build on a red tree.

## File Structure (decomposition map)

All new production code under `control/src/main/kotlin/com/resequencetwin/control/drift/`, tests under `control/src/test/kotlin/com/resequencetwin/control/drift/`.

| File | Responsibility | Chunk |
|------|----------------|-------|
| `DriftTypes.kt` | immutable value types: `ExpectedConfig`, `ObservedConfig`, `ObservedKpi`, `DriftKind`, `ConfigDriftFinding`, `ReconciliationProposal`, `BehavioralDriftFinding` | 1 |
| `ConfigDriftDetector.kt` | pure structural config diff → findings + proposals | 1 |
| `ResidualDriftDetector.kt` | pure stateful EWMA residual on per-tick deltas → behavioral finding | 1 |
| `LiveConfigSource.kt` | port interface: `readConfig(): ObservedConfig` | 2 |
| `LiveTelemetrySource.kt` | port interface: `readKpi(): ObservedKpi` | 2 |
| `SimulatedConfigSource.kt` | deterministic, **mutable** demo/test config source | 2 |
| `SimulatedTelemetrySource.kt` | deterministic, **mutable** demo/test telemetry source | 2 |
| `DriftReport.kt` | REST/aggregate response value type (envelope + baseline + observed + findings + proposals) | 2 |
| `DriftMonitor.kt` | `@Component` + `@Scheduled`: baseline capture, orchestrate detectors against `LivePbsProcessor`, hold latest report, source-outage-safe | 2 |
| `DriftConfig.kt` | `@Configuration`: wire `LiveConfigSource`/`LiveTelemetrySource` beans by `pbs.drift.source` (sim branch in Chunk 2; opcua branch in Chunk 3) | 2 / 3 |
| `DriftMetrics.kt` | `MeterBinder`: `pbs.drift.config.findings` + `pbs.drift.behavioral.breaches` gauges from `DriftMonitor` | 2 |
| `DriftService.kt` | thin read-only relay of `DriftMonitor.currentReport()` | 2 |
| `DriftController.kt` | `GET /api/drift` | 2 |
| `OpcUaConfigSource.kt` | milo OPC-UA client: read lane-cap/blocked nodes → `ObservedConfig` | 3 |
| `OpcUaTelemetrySource.kt` | milo OPC-UA client: read KPI nodes → `ObservedKpi` | 3 |

Modified files: `control/pom.xml` (Chunk 3, add milo), `control/src/main/resources/application.properties` (drift keys), `control/src/test/resources/application.properties` (`pbs.drift.enabled=false`), `README.md` (Chunk 3 runbook), memory `virtual-commissioning-gap.md` (Chunk 3 close-out).

## Design decisions locked here (resolving spec plan-time questions)

1. **`ResidualDriftDetector` count:** `DriftMonitor` constructs **exactly 3** detectors in `init`, keyed by metric name: `"releases"`, `"colourChanges"`, `"assemblyOut"`. These mirror `LiveSnapshot.{releases, colourChanges, assemblyOutSize}` and `ObservedKpi.{releases, colourChanges, assemblyOut}`.
2. **Threshold key shape:** a **single** `pbs.drift.residual-threshold` (Double) applied to all three metrics for this MVP. KDoc notes per-metric thresholds are a future refinement (YAGNI now). `pbs.drift.ewma-alpha` is likewise single.
3. **`ObservedConfig` field naming:** "same shape" = same structural shape, not identical field names. `ExpectedConfig(lanes, expectedBlocked)` vs `ObservedConfig(lanes, blocked)` — clearer than reusing `expectedBlocked` on an observed type. The detector compares `expected.expectedBlocked` against `observed.blocked`.
4. **Baseline capture timing:** captured **lazily on the first successful config read inside `tick()`** (resilient to a source that is down at startup — same spirit as `PbsTwinProjector`'s guarded one-time bootstrap), exposed as a public `captureBaseline()` for direct unit testing.
5. **milo isolation:** Chunks 1–2 add **no new dependency** and must be fully green before Chunk 3 introduces milo. `pbs.drift.source` defaults to `sim`.

---

## Chunk 1: Pure value types + detectors (offline core, no new deps)

### Task 1: Drift value types

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/DriftTypes.kt`
- Test: `control/src/test/kotlin/com/resequencetwin/control/drift/DriftTypesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DriftTypesTest {

    @Test
    fun `ExpectedConfig and ObservedConfig hold lanes and blocked sets`() {
        val expected = ExpectedConfig(lanes = mapOf("L1" to 10, "L2" to 10), expectedBlocked = emptySet())
        val observed = ObservedConfig(lanes = mapOf("L1" to 8, "L2" to 10), blocked = setOf("L2"))

        assertThat(expected.lanes["L1"]).isEqualTo(10)
        assertThat(observed.lanes["L1"]).isEqualTo(8)
        assertThat(observed.blocked).containsExactly("L2")
    }

    @Test
    fun `ObservedKpi mirrors the three comparable cumulative LiveSnapshot fields`() {
        val kpi = ObservedKpi(releases = 5, colourChanges = 2, assemblyOut = 5)
        assertThat(kpi.releases).isEqualTo(5)
        assertThat(kpi.colourChanges).isEqualTo(2)
        assertThat(kpi.assemblyOut).isEqualTo(5)
    }

    @Test
    fun `ConfigDriftFinding carries a ReconciliationProposal`() {
        val f = ConfigDriftFinding(
            laneId = "L1", kind = DriftKind.CAPACITY_CHANGED,
            expected = "10", observed = "8",
            proposal = ReconciliationProposal(action = "ADJUST_CAPACITY", description = "twin L1 10 -> 8"),
        )
        assertThat(f.kind).isEqualTo(DriftKind.CAPACITY_CHANGED)
        assertThat(f.proposal.action).isEqualTo("ADJUST_CAPACITY")
    }

    @Test
    fun `BehavioralDriftFinding exposes residual, ewma and breached`() {
        val b = BehavioralDriftFinding(metric = "releases", predicted = 3.0, observed = 1.0,
            residual = 2.0, ewma = 1.4, breached = true)
        assertThat(b.breached).isTrue()
        assertThat(b.metric).isEqualTo("releases")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftTypesTest test`
Expected: FAIL — compilation error, types unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.resequencetwin.control.drift

/**
 * The twin's expected lane topology baseline, captured once from the live config source at startup.
 *
 * Real-deployment note (documented, NOT built): in a production AAS deployment this would map to an
 * Asset Administration Shell submodel describing nominal station/lane configuration.
 *
 * @property lanes laneId -> capacity
 * @property expectedBlocked lane ids expected to be blocked in the nominal baseline (usually empty)
 */
data class ExpectedConfig(
    val lanes: Map<String, Int>,
    val expectedBlocked: Set<String>,
)

/**
 * What the live config source currently reports. Same structural shape as [ExpectedConfig]; the
 * blocked set is named [blocked] (not "expected") because it is an *observation*, not a nominal value.
 *
 * @property lanes laneId -> capacity as currently reported
 * @property blocked lane ids currently reported blocked
 */
data class ObservedConfig(
    val lanes: Map<String, Int>,
    val blocked: Set<String>,
)

/**
 * Observed KPI from the live telemetry source. An **exact mirror** of the comparable cumulative
 * fields on [com.resequencetwin.control.stream.LiveSnapshot] (`releases`, `colourChanges`, `assemblyOutSize`)
 * so the twin-predicted and observed sides are dimensionally identical. There is intentionally **no
 * throughput field** — a rate is derived inside [ResidualDriftDetector] as a per-tick delta.
 *
 * All three are cumulative, monotonically non-decreasing counts.
 */
data class ObservedKpi(
    val releases: Int,
    val colourChanges: Int,
    val assemblyOut: Int,
)

/** Kinds of structural config drift the [ConfigDriftDetector] can report. Open for future kinds. */
enum class DriftKind {
    /** A lane present in both baseline and observed has a different capacity. */
    CAPACITY_CHANGED,
    /** A lane is blocked in the observed config but not in the baseline's expectedBlocked set. */
    UNEXPECTED_BLOCK,
    /** A lane present in the baseline is absent from the observed config (e.g. station disabled). */
    EXPECTED_LANE_MISSING,
}

/**
 * The advisory twin-side change that would re-align the twin to observed reality.
 * **Advisory only — never auto-applied** (no write-back). [action] is a machine tag, [description]
 * is human-readable.
 */
data class ReconciliationProposal(
    val action: String,
    val description: String,
)

/**
 * One structural config-drift finding. [expected] / [observed] are rendered as strings for a uniform
 * advisory surface regardless of the underlying type (capacity int vs blocked flag).
 */
data class ConfigDriftFinding(
    val laneId: String,
    val kind: DriftKind,
    val expected: String,
    val observed: String,
    val proposal: ReconciliationProposal,
)

/**
 * One behavioral-drift finding for a single metric on a single tick.
 *
 * @property predicted current twin-predicted cumulative value
 * @property observed current observed cumulative value
 * @property residual (predictedDelta - observedDelta) for this tick
 * @property ewma exponentially-weighted moving average of the residual
 * @property breached true when |ewma| exceeds the configured threshold
 */
data class BehavioralDriftFinding(
    val metric: String,
    val predicted: Double,
    val observed: Double,
    val residual: Double,
    val ewma: Double,
    val breached: Boolean,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftTypesTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add "control/src/main/kotlin/com/resequencetwin/control/drift/DriftTypes.kt" "control/src/test/kotlin/com/resequencetwin/control/drift/DriftTypesTest.kt"
git commit -m "feat(drift): pure drift value types"
```

---

### Task 2: ConfigDriftDetector (pure structural diff)

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/ConfigDriftDetector.kt`
- Test: `control/src/test/kotlin/com/resequencetwin/control/drift/ConfigDriftDetectorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigDriftDetectorTest {

    private val detector = ConfigDriftDetector()
    private val baseline = ExpectedConfig(lanes = mapOf("L1" to 10, "L2" to 10, "L3" to 10), expectedBlocked = emptySet())

    @Test
    fun `no drift when observed equals baseline returns empty`() {
        val observed = ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10, "L3" to 10), blocked = emptySet())
        assertThat(detector.detect(baseline, observed)).isEmpty()
    }

    @Test
    fun `capacity change is reported with expected and observed values and a proposal`() {
        val observed = ObservedConfig(lanes = mapOf("L1" to 8, "L2" to 10, "L3" to 10), blocked = emptySet())
        val findings = detector.detect(baseline, observed)

        assertThat(findings).hasSize(1)
        val f = findings.single()
        assertThat(f.laneId).isEqualTo("L1")
        assertThat(f.kind).isEqualTo(DriftKind.CAPACITY_CHANGED)
        assertThat(f.expected).isEqualTo("10")
        assertThat(f.observed).isEqualTo("8")
        assertThat(f.proposal.action).isEqualTo("ADJUST_CAPACITY")
    }

    @Test
    fun `unexpected block is reported`() {
        val observed = ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10, "L3" to 10), blocked = setOf("L2"))
        val findings = detector.detect(baseline, observed)
        assertThat(findings).extracting<DriftKind> { it.kind }.containsExactly(DriftKind.UNEXPECTED_BLOCK)
        assertThat(findings.single().laneId).isEqualTo("L2")
    }

    @Test
    fun `missing expected lane is reported`() {
        val observed = ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10), blocked = emptySet())
        val findings = detector.detect(baseline, observed)
        assertThat(findings).extracting<DriftKind> { it.kind }.containsExactly(DriftKind.EXPECTED_LANE_MISSING)
        assertThat(findings.single().laneId).isEqualTo("L3")
    }

    @Test
    fun `findings are ordered deterministically by laneId then kind`() {
        val observed = ObservedConfig(lanes = mapOf("L1" to 8, "L2" to 10), blocked = setOf("L1"))
        val findings = detector.detect(baseline, observed)
        // L1 capacity + L1 block + L3 missing — stable order
        assertThat(findings.map { it.laneId }).isEqualTo(listOf("L1", "L1", "L3"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=ConfigDriftDetectorTest test`
Expected: FAIL — `ConfigDriftDetector` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.resequencetwin.control.drift

/**
 * Pure, stateless structural diff between an [ExpectedConfig] baseline and a current [ObservedConfig].
 *
 * Emits one [ConfigDriftFinding] per divergence with an advisory [ReconciliationProposal]. Output is
 * deterministically ordered (by laneId, then a fixed kind order) so reports and tests are stable.
 *
 * **Advisory only** — proposals describe the twin-side change that would re-align to observed reality;
 * nothing is applied automatically and there is no write-back.
 */
class ConfigDriftDetector {

    fun detect(expected: ExpectedConfig, observed: ObservedConfig): List<ConfigDriftFinding> {
        val findings = mutableListOf<ConfigDriftFinding>()

        for ((laneId, expectedCap) in expected.lanes) {
            val observedCap = observed.lanes[laneId]
            if (observedCap == null) {
                findings += ConfigDriftFinding(
                    laneId = laneId, kind = DriftKind.EXPECTED_LANE_MISSING,
                    expected = "present(cap=$expectedCap)", observed = "absent",
                    proposal = ReconciliationProposal(
                        action = "REVIEW_LANE_REMOVAL",
                        description = "Lane $laneId in baseline is absent from observed config; " +
                            "review whether the twin topology should drop $laneId (advisory).",
                    ),
                )
            } else if (observedCap != expectedCap) {
                findings += ConfigDriftFinding(
                    laneId = laneId, kind = DriftKind.CAPACITY_CHANGED,
                    expected = expectedCap.toString(), observed = observedCap.toString(),
                    proposal = ReconciliationProposal(
                        action = "ADJUST_CAPACITY",
                        description = "Twin lane $laneId capacity $expectedCap -> $observedCap (advisory).",
                    ),
                )
            }
        }

        for (laneId in observed.blocked) {
            if (laneId !in expected.expectedBlocked) {
                findings += ConfigDriftFinding(
                    laneId = laneId, kind = DriftKind.UNEXPECTED_BLOCK,
                    expected = "unblocked", observed = "blocked",
                    proposal = ReconciliationProposal(
                        action = "REVIEW_BLOCK",
                        description = "Lane $laneId is blocked in observed config but not in baseline; " +
                            "review the blockage cause (advisory).",
                    ),
                )
            }
        }

        return findings.sortedWith(compareBy({ it.laneId }, { it.kind.ordinal }))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=ConfigDriftDetectorTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add "control/src/main/kotlin/com/resequencetwin/control/drift/ConfigDriftDetector.kt" "control/src/test/kotlin/com/resequencetwin/control/drift/ConfigDriftDetectorTest.kt"
git commit -m "feat(drift): pure config drift detector with advisory proposals"
```

---

### Task 3: ResidualDriftDetector (pure stateful EWMA on deltas)

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/ResidualDriftDetector.kt`
- Test: `control/src/test/kotlin/com/resequencetwin/control/drift/ResidualDriftDetectorTest.kt`

**Algorithm contract:** the caller passes the *current cumulative* predicted & observed value each tick.
- First `update`: seed previous values, return finding with `residual=0.0, ewma=0.0, breached=false` (no delta computable yet).
- Subsequent: `dPred = predicted - prevPred`, `dObs = observed - prevObs`, `residual = dPred - dObs`, `ewma = alpha*residual + (1-alpha)*prevEwma`, `breached = abs(ewma) > threshold`. Then store current as previous.
- Deterministic: identical input sequence → identical outputs.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ResidualDriftDetectorTest {

    @Test
    fun `first update has zero residual and is not breached`() {
        val d = ResidualDriftDetector(metric = "releases", alpha = 0.3, threshold = 1.0)
        val f = d.update(predicted = 5.0, observed = 5.0)
        assertThat(f.residual).isEqualTo(0.0)
        assertThat(f.ewma).isEqualTo(0.0)
        assertThat(f.breached).isFalse()
    }

    @Test
    fun `matched rates keep residual zero and not breached`() {
        val d = ResidualDriftDetector(metric = "releases", alpha = 0.3, threshold = 1.0)
        d.update(0.0, 0.0)
        val f1 = d.update(2.0, 2.0)   // dPred=2, dObs=2 -> residual 0
        val f2 = d.update(4.0, 4.0)
        assertThat(f1.residual).isEqualTo(0.0)
        assertThat(f2.breached).isFalse()
    }

    @Test
    fun `sustained divergence eventually breaches the threshold`() {
        val d = ResidualDriftDetector(metric = "releases", alpha = 0.5, threshold = 1.0)
        d.update(0.0, 0.0)
        // twin predicts +3 per tick, reality only +1 -> residual +2 each tick; EWMA climbs past 1.0
        val findings = (1..5).map { i -> d.update(predicted = 3.0 * i, observed = 1.0 * i) }
        assertThat(findings.last().residual).isEqualTo(2.0)
        assertThat(findings.last().ewma).isGreaterThan(1.0)
        assertThat(findings.last().breached).isTrue()
    }

    @Test
    fun `ewma uses the configured alpha`() {
        val d = ResidualDriftDetector(metric = "m", alpha = 0.3, threshold = 100.0)
        d.update(0.0, 0.0)
        val f = d.update(predicted = 10.0, observed = 0.0)  // residual 10, ewma = 0.3*10 + 0.7*0 = 3.0
        assertThat(f.residual).isEqualTo(10.0)
        assertThat(f.ewma).isCloseTo(3.0, within(1e-9))
        assertThat(f.metric).isEqualTo("m")
    }

    @Test
    fun `determinism — identical input sequences produce identical outputs`() {
        fun run(): List<BehavioralDriftFinding> {
            val d = ResidualDriftDetector("m", alpha = 0.4, threshold = 1.5)
            return listOf(d.update(0.0, 0.0), d.update(3.0, 1.0), d.update(6.0, 2.0), d.update(7.0, 5.0))
        }
        assertThat(run()).isEqualTo(run())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=ResidualDriftDetectorTest test`
Expected: FAIL — `ResidualDriftDetector` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.resequencetwin.control.drift

import kotlin.math.abs

/**
 * Stateful-but-deterministic behavioral-drift detector for a **single** metric. One instance per
 * compared metric (the twin-predicted vs observed cumulative value of e.g. releases).
 *
 * Each [update] receives the *current cumulative* predicted & observed values. The detector tracks
 * the previous values to compute the per-tick **delta (rate)** of each side, then the residual
 * between the two deltas, so a cumulative-vs-cumulative comparison is always dimensionally consistent.
 * It maintains an EWMA of the residual and flags [BehavioralDriftFinding.breached] when |EWMA|
 * exceeds [threshold].
 *
 * Not thread-safe; called only from the single [DriftMonitor] scheduler thread.
 *
 * @param alpha EWMA smoothing factor in (0,1]; higher = more reactive.
 * @param threshold absolute EWMA breach limit (single value per this MVP; per-metric tuning is future work).
 */
class ResidualDriftDetector(
    private val metric: String,
    private val alpha: Double,
    private val threshold: Double,
) {
    private var prevPredicted: Double? = null
    private var prevObserved: Double? = null
    private var ewma: Double = 0.0

    fun update(predicted: Double, observed: Double): BehavioralDriftFinding {
        val prevP = prevPredicted
        val prevO = prevObserved
        prevPredicted = predicted
        prevObserved = observed

        if (prevP == null || prevO == null) {
            // First observation: no delta computable yet. Seed and report no drift.
            ewma = 0.0
            return BehavioralDriftFinding(metric, predicted, observed, residual = 0.0, ewma = 0.0, breached = false)
        }

        val residual = (predicted - prevP) - (observed - prevO)
        ewma = alpha * residual + (1 - alpha) * ewma
        return BehavioralDriftFinding(
            metric = metric, predicted = predicted, observed = observed,
            residual = residual, ewma = ewma, breached = abs(ewma) > threshold,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=ResidualDriftDetectorTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add "control/src/main/kotlin/com/resequencetwin/control/drift/ResidualDriftDetector.kt" "control/src/test/kotlin/com/resequencetwin/control/drift/ResidualDriftDetectorTest.kt"
git commit -m "feat(drift): pure EWMA residual behavioral drift detector"
```

---

## Chunk 2: Ports, Simulated adapters, decoupled monitor, REST + metrics (offline, no new deps)

### Task 4: Live-source ports + Simulated adapters

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/LiveConfigSource.kt`
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/LiveTelemetrySource.kt`
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/SimulatedConfigSource.kt`
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/SimulatedTelemetrySource.kt`
- Test: `control/src/test/kotlin/com/resequencetwin/control/drift/SimulatedSourcesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SimulatedSourcesTest {

    @Test
    fun `simulated config source returns its seeded config and reflects mutations`() {
        val src = SimulatedConfigSource(
            initial = ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10), blocked = emptySet())
        )
        assertThat(src.readConfig().lanes["L1"]).isEqualTo(10)

        src.setCapacity("L1", 8)
        assertThat(src.readConfig().lanes["L1"]).isEqualTo(8)

        src.block("L2")
        assertThat(src.readConfig().blocked).containsExactly("L2")

        src.removeLane("L1")
        assertThat(src.readConfig().lanes).doesNotContainKey("L1")
    }

    @Test
    fun `simulated telemetry source returns its seeded kpi and reflects mutations`() {
        val src = SimulatedTelemetrySource(initial = ObservedKpi(0, 0, 0))
        assertThat(src.readKpi().releases).isEqualTo(0)
        src.set(ObservedKpi(releases = 5, colourChanges = 2, assemblyOut = 5))
        assertThat(src.readKpi()).isEqualTo(ObservedKpi(5, 2, 5))
    }

    @Test
    fun `ports are implemented by the simulated adapters`() {
        val cfg: LiveConfigSource = SimulatedConfigSource(ObservedConfig(emptyMap(), emptySet()))
        val tel: LiveTelemetrySource = SimulatedTelemetrySource(ObservedKpi(0, 0, 0))
        assertThat(cfg.readConfig()).isNotNull()
        assertThat(tel.readKpi()).isNotNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=SimulatedSourcesTest test`
Expected: FAIL — ports/adapters unresolved.

- [ ] **Step 3: Write minimal implementation**

`LiveConfigSource.kt`:
```kotlin
package com.resequencetwin.control.drift

/** Port: a live source of the plant's current lane configuration. Implementations may do I/O. */
interface LiveConfigSource {
    fun readConfig(): ObservedConfig
}
```

`LiveTelemetrySource.kt`:
```kotlin
package com.resequencetwin.control.drift

/** Port: a live source of the plant's currently observed cumulative KPI. Implementations may do I/O. */
interface LiveTelemetrySource {
    fun readKpi(): ObservedKpi
}
```

`SimulatedConfigSource.kt`:
```kotlin
package com.resequencetwin.control.drift

/**
 * Deterministic, **mutable** in-process config source for offline TDD and the offline demo.
 *
 * The mutators ([setCapacity], [block], [unblock], [removeLane]) let a test or demo inject drift
 * (capacity 10 -> 8, disable a station/lane) that the next [DriftMonitor] tick will surface. This is
 * synthetic perturbation — it demonstrates the detection mechanism, not real plant data.
 */
class SimulatedConfigSource(initial: ObservedConfig) : LiveConfigSource {
    private var lanes: MutableMap<String, Int> = initial.lanes.toMutableMap()
    private var blocked: MutableSet<String> = initial.blocked.toMutableSet()

    @Synchronized
    override fun readConfig(): ObservedConfig = ObservedConfig(lanes.toMap(), blocked.toSet())

    @Synchronized fun setCapacity(laneId: String, capacity: Int) { lanes[laneId] = capacity }
    @Synchronized fun block(laneId: String) { blocked.add(laneId) }
    @Synchronized fun unblock(laneId: String) { blocked.remove(laneId) }
    @Synchronized fun removeLane(laneId: String) { lanes.remove(laneId) }
}
```

`SimulatedTelemetrySource.kt`:
```kotlin
package com.resequencetwin.control.drift

import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic, **mutable** in-process telemetry source for offline TDD and the offline demo.
 * [set] replaces the current observed KPI so a test/demo can drive a residual against the twin
 * prediction. Synthetic — demonstrates the mechanism, not real telemetry.
 */
class SimulatedTelemetrySource(initial: ObservedKpi) : LiveTelemetrySource {
    private val current = AtomicReference(initial)
    override fun readKpi(): ObservedKpi = current.get()
    fun set(kpi: ObservedKpi) { current.set(kpi) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=SimulatedSourcesTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add "control/src/main/kotlin/com/resequencetwin/control/drift/LiveConfigSource.kt" "control/src/main/kotlin/com/resequencetwin/control/drift/LiveTelemetrySource.kt" "control/src/main/kotlin/com/resequencetwin/control/drift/SimulatedConfigSource.kt" "control/src/main/kotlin/com/resequencetwin/control/drift/SimulatedTelemetrySource.kt" "control/src/test/kotlin/com/resequencetwin/control/drift/SimulatedSourcesTest.kt"
git commit -m "feat(drift): live-source ports + deterministic simulated adapters"
```

---

### Task 5: DriftReport value type

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/DriftReport.kt`
- Test: `control/src/test/kotlin/com/resequencetwin/control/drift/DriftReportTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.resequencetwin.control.drift

import com.resequencetwin.control.api.AdvisoryEnvelope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DriftReportTest {

    @Test
    fun `empty report has the synthetic envelope, no findings, source unavailable`() {
        val r = DriftReport.empty()
        assertThat(r.synthetic).isTrue()
        assertThat(r.disclaimer).isEqualTo(AdvisoryEnvelope.DISCLAIMER_TEXT)
        assertThat(r.sourceAvailable).isFalse()
        assertThat(r.configFindings).isEmpty()
        assertThat(r.behavioralFindings).isEmpty()
        assertThat(r.reconciliationProposals).isEmpty()
        assertThat(r.baseline).isNull()
        assertThat(r.observed).isNull()
    }

    @Test
    fun `of() flattens reconciliation proposals from config findings and carries the envelope`() {
        val baseline = ExpectedConfig(mapOf("L1" to 10), emptySet())
        val observed = ObservedConfig(mapOf("L1" to 8), emptySet())
        val cf = ConfigDriftFinding("L1", DriftKind.CAPACITY_CHANGED, "10", "8",
            ReconciliationProposal("ADJUST_CAPACITY", "twin L1 10 -> 8"))
        val bf = BehavioralDriftFinding("releases", 3.0, 1.0, 2.0, 1.4, true)

        val r = DriftReport.of(sourceAvailable = true, baseline = baseline, observed = observed,
            configFindings = listOf(cf), behavioralFindings = listOf(bf))

        assertThat(r.synthetic).isTrue()
        assertThat(r.sourceAvailable).isTrue()
        assertThat(r.reconciliationProposals).containsExactly(cf.proposal)
        assertThat(r.behavioralFindings).containsExactly(bf)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftReportTest test`
Expected: FAIL — `DriftReport` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.resequencetwin.control.drift

import com.fasterxml.jackson.annotation.JsonProperty
import com.resequencetwin.control.api.AdvisoryEnvelope

/**
 * Read-only aggregate exposed at `GET /api/drift`. Carries the synthetic honesty envelope plus the
 * captured baseline, the latest observed config, and config + behavioral findings. The advisory
 * [reconciliationProposals] are a flattened convenience view of the per-finding proposals.
 *
 * @property sourceAvailable false when the live source could not be read on the latest tick.
 */
data class DriftReport(
    @field:JsonProperty("synthetic")              val synthetic: Boolean,
    @field:JsonProperty("disclaimer")             val disclaimer: String,
    @field:JsonProperty("sourceAvailable")        val sourceAvailable: Boolean,
    @field:JsonProperty("baseline")               val baseline: ExpectedConfig?,
    @field:JsonProperty("observed")               val observed: ObservedConfig?,
    @field:JsonProperty("configFindings")         val configFindings: List<ConfigDriftFinding>,
    @field:JsonProperty("behavioralFindings")     val behavioralFindings: List<BehavioralDriftFinding>,
    @field:JsonProperty("reconciliationProposals") val reconciliationProposals: List<ReconciliationProposal>,
) {
    companion object {
        /** Initial report before the first tick (or while the scheduler is disabled). */
        fun empty(): DriftReport {
            val env = AdvisoryEnvelope.standard()
            return DriftReport(env.synthetic, env.disclaimer, sourceAvailable = false,
                baseline = null, observed = null,
                configFindings = emptyList(), behavioralFindings = emptyList(),
                reconciliationProposals = emptyList())
        }

        fun of(
            sourceAvailable: Boolean,
            baseline: ExpectedConfig?,
            observed: ObservedConfig?,
            configFindings: List<ConfigDriftFinding>,
            behavioralFindings: List<BehavioralDriftFinding>,
        ): DriftReport {
            val env = AdvisoryEnvelope.standard()
            return DriftReport(env.synthetic, env.disclaimer, sourceAvailable, baseline, observed,
                configFindings, behavioralFindings, configFindings.map { it.proposal })
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftReportTest test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add "control/src/main/kotlin/com/resequencetwin/control/drift/DriftReport.kt" "control/src/test/kotlin/com/resequencetwin/control/drift/DriftReportTest.kt"
git commit -m "feat(drift): DriftReport aggregate with honesty envelope"
```

---

### Task 6: DriftMonitor (decoupled scheduled orchestrator)

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/DriftMonitor.kt`
- Test: `control/src/test/kotlin/com/resequencetwin/control/drift/DriftMonitorTest.kt`

**Design notes (mirror `PbsTwinProjector`):**
- `@Component`, constructor-injects the shared `LivePbsProcessor` singleton (**read-only**), a `LiveConfigSource`, a `LiveTelemetrySource`, and `@Value` config (`enabled` read once at construction, `ewmaAlpha`, `residualThreshold`).
- `init`: build exactly 3 `ResidualDriftDetector`s keyed `"releases" / "colourChanges" / "assemblyOut"`.
- `latestReport: AtomicReference<DriftReport>` initialized to `DriftReport.empty()`; `currentReport()` returns it.
- `captureBaseline()`: read the config source once → convert `ObservedConfig` → `ExpectedConfig` (lanes copy; `blocked` → `expectedBlocked`). Public for unit test. Called lazily inside `tick()` when baseline is null (resilient to a startup-down source).
- `@Scheduled(fixedDelayString = "\${pbs.drift.interval-ms:1000}") tick()`: early-return if `!enabled`. Wrap source reads in try/catch — a source outage marks `sourceAvailable=false`, stores a report preserving the baseline, and does NOT crash. On success: read observed config → `ConfigDriftDetector`; read observed KPI + `processor.snapshot()` → feed the 3 residual detectors; aggregate into `DriftReport.of(...)`; store.
- Runs on the scheduler thread only — never touches the Kafka consumer thread.

- [ ] **Step 1: Write the failing test** (offline, no Spring — drives a real processor + simulated sources)

```kotlin
package com.resequencetwin.control.drift

import com.resequencetwin.control.pbs.Lane
import com.resequencetwin.control.stream.BodyArrived
import com.resequencetwin.control.stream.LivePbsProcessor
import com.resequencetwin.control.stream.ReleaseTick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DriftMonitorTest {

    private val lanes = listOf(Lane("L1", 10), Lane("L2", 10), Lane("L3", 10))

    private fun monitor(
        processor: LivePbsProcessor,
        cfg: SimulatedConfigSource,
        tel: SimulatedTelemetrySource,
    ) = DriftMonitor(processor, cfg, tel, enabled = true, ewmaAlpha = 0.5, residualThreshold = 1.0)

    private fun baselineConfig() =
        ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10, "L3" to 10), blocked = emptySet())

    @Test
    fun `disabled monitor never mutates the report on tick`() {
        val proc = LivePbsProcessor(lanes)
        val m = DriftMonitor(proc, SimulatedConfigSource(baselineConfig()),
            SimulatedTelemetrySource(ObservedKpi(0, 0, 0)),
            enabled = false, ewmaAlpha = 0.5, residualThreshold = 1.0)
        m.tick()
        assertThat(m.currentReport()).isEqualTo(DriftReport.empty())
    }

    @Test
    fun `captureBaseline snapshots the live config source`() {
        val cfg = SimulatedConfigSource(baselineConfig())
        val m = monitor(LivePbsProcessor(lanes), cfg, SimulatedTelemetrySource(ObservedKpi(0, 0, 0)))
        val baseline = m.captureBaseline()
        assertThat(baseline.lanes).isEqualTo(mapOf("L1" to 10, "L2" to 10, "L3" to 10))
        assertThat(baseline.expectedBlocked).isEmpty()
    }

    @Test
    fun `no config mutation yields no config findings`() {
        val cfg = SimulatedConfigSource(baselineConfig())
        val m = monitor(LivePbsProcessor(lanes), cfg, SimulatedTelemetrySource(ObservedKpi(0, 0, 0)))
        m.tick()  // captures baseline + first observation
        m.tick()
        assertThat(m.currentReport().sourceAvailable).isTrue()
        assertThat(m.currentReport().configFindings).isEmpty()
    }

    @Test
    fun `injected capacity drift surfaces as a config finding with a proposal`() {
        val cfg = SimulatedConfigSource(baselineConfig())
        val m = monitor(LivePbsProcessor(lanes), cfg, SimulatedTelemetrySource(ObservedKpi(0, 0, 0)))
        m.tick()                 // baseline captured at 10
        cfg.setCapacity("L1", 8) // inject drift
        m.tick()

        val findings = m.currentReport().configFindings
        assertThat(findings).hasSize(1)
        assertThat(findings.single().kind).isEqualTo(DriftKind.CAPACITY_CHANGED)
        assertThat(m.currentReport().reconciliationProposals).hasSize(1)
    }

    @Test
    fun `behavioral residual breaches when observed KPI lags twin prediction`() {
        val proc = LivePbsProcessor(lanes)
        val cfg = SimulatedConfigSource(baselineConfig())
        val tel = SimulatedTelemetrySource(ObservedKpi(0, 0, 0))
        val m = monitor(proc, cfg, tel)  // alpha=0.5, threshold=1.0

        m.tick()  // seed: both sides at 0, no delta yet

        // The residual is a per-tick DELTA of cumulative values, so a one-time jump decays away.
        // To create a SUSTAINED residual we advance the twin by +3 releases between EVERY monitor
        // tick while observed telemetry stays at 0. Each tick then sees dPred=+3, dObs=0,
        // residual=+3; with alpha=0.5 the EWMA climbs to 1.5 on the first such tick (> threshold 1.0)
        // and stays breached. All bodies are RED, so colourChanges stays matched (0 vs 0).
        var idx = 0
        var seq = 1L
        repeat(3) {
            repeat(3) {
                proc.process(BodyArrived(eventId = "e$seq", seq = seq++, bodyId = "B${idx++}",
                    color = "RED", model = "M1", dueDateSeq = 0))
            }
            repeat(3) { proc.process(ReleaseTick(eventId = "t$seq", seq = seq++)) }
            m.tick()  // observed telemetry still 0 -> residual = +3 on the "releases" metric this tick
        }

        val breaches = m.currentReport().behavioralFindings.filter { it.breached }
        assertThat(breaches).isNotEmpty()
        assertThat(breaches.map { it.metric }).contains("releases")
    }

    @Test
    fun `source outage is non-fatal and preserves the baseline`() {
        val throwing = object : LiveConfigSource {
            var fail = false
            override fun readConfig(): ObservedConfig =
                if (fail) throw RuntimeException("source down") else baselineConfig()
        }
        val m = DriftMonitor(LivePbsProcessor(lanes), throwing,
            SimulatedTelemetrySource(ObservedKpi(0, 0, 0)),
            enabled = true, ewmaAlpha = 0.5, residualThreshold = 1.0)
        m.tick()                       // baseline captured
        throwing.fail = true
        m.tick()                       // must not throw
        assertThat(m.currentReport().sourceAvailable).isFalse()
        assertThat(m.currentReport().baseline).isNotNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftMonitorTest test`
Expected: FAIL — `DriftMonitor` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.resequencetwin.control.drift

import com.resequencetwin.control.stream.LivePbsProcessor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Scheduled, decoupled drift monitor.
 *
 * ## Separation of concerns
 * Runs on a dedicated scheduler thread (enabled via [com.resequencetwin.control.ingest.SchedulingConfig]),
 * **never** on the Kafka consumer thread — identical rationale to
 * [com.resequencetwin.control.ingest.PbsTwinProjector]. A live-source outage cannot stall or crash ingestion.
 *
 * ## Read-only twin
 * The injected [processor] is the shared [LivePbsProcessor] singleton. This monitor only calls
 * [LivePbsProcessor.snapshot] — it never re-seeds, re-instantiates, or mutates the twin (no write-back).
 *
 * ## Baseline = real source of truth
 * [captureBaseline] takes one snapshot of the live config source and treats it as the expected
 * baseline (captured, not hand-authored). Config drift is `diff(baseline, later observed)`.
 *
 * @param enabled read once at construction (test sets false); when false [tick] is a no-op.
 */
@Component
class DriftMonitor(
    private val processor: LivePbsProcessor,
    private val configSource: LiveConfigSource,
    private val telemetrySource: LiveTelemetrySource,
    @Value("\${pbs.drift.enabled:true}") private val enabled: Boolean,
    @Value("\${pbs.drift.ewma-alpha:0.3}") ewmaAlpha: Double,
    @Value("\${pbs.drift.residual-threshold:2.0}") residualThreshold: Double,
) {
    private val configDetector = ConfigDriftDetector()

    // Exactly three residual detectors, one per comparable cumulative metric (decision #1).
    private val residualDetectors: Map<String, ResidualDriftDetector> = linkedMapOf(
        "releases"      to ResidualDriftDetector("releases", ewmaAlpha, residualThreshold),
        "colourChanges" to ResidualDriftDetector("colourChanges", ewmaAlpha, residualThreshold),
        "assemblyOut"   to ResidualDriftDetector("assemblyOut", ewmaAlpha, residualThreshold),
    )

    private val latestReport = AtomicReference(DriftReport.empty())
    @Volatile private var baseline: ExpectedConfig? = null

    fun currentReport(): DriftReport = latestReport.get()

    /** One snapshot of the live config source as the expected baseline (captured, not authored). */
    fun captureBaseline(): ExpectedConfig {
        val observed = configSource.readConfig()
        val captured = ExpectedConfig(lanes = observed.lanes.toMap(), expectedBlocked = observed.blocked.toSet())
        baseline = captured
        return captured
    }

    @Scheduled(fixedDelayString = "\${pbs.drift.interval-ms:1000}")
    fun tick() {
        if (!enabled) return
        val base = baseline ?: try {
            captureBaseline()
        } catch (e: Exception) {
            log.warn("Drift baseline capture failed (source unavailable?): {}", e.message)
            latestReport.set(DriftReport.of(sourceAvailable = false, baseline = null, observed = null,
                configFindings = emptyList(), behavioralFindings = emptyList()))
            return
        }

        val observedConfig = try {
            configSource.readConfig()
        } catch (e: Exception) {
            log.warn("Drift config read failed (source unavailable?): {}", e.message)
            latestReport.set(DriftReport.of(sourceAvailable = false, baseline = base, observed = null,
                configFindings = emptyList(), behavioralFindings = emptyList()))
            return
        }

        val observedKpi = try {
            telemetrySource.readKpi()
        } catch (e: Exception) {
            log.warn("Drift telemetry read failed (source unavailable?): {}", e.message)
            latestReport.set(DriftReport.of(sourceAvailable = false, baseline = base, observed = observedConfig,
                configFindings = configDetector.detect(base, observedConfig), behavioralFindings = emptyList()))
            return
        }

        val configFindings = configDetector.detect(base, observedConfig)

        val predicted = processor.snapshot()
        val behavioral = listOf(
            residualDetectors.getValue("releases").update(predicted.releases.toDouble(), observedKpi.releases.toDouble()),
            residualDetectors.getValue("colourChanges").update(predicted.colourChanges.toDouble(), observedKpi.colourChanges.toDouble()),
            residualDetectors.getValue("assemblyOut").update(predicted.assemblyOutSize.toDouble(), observedKpi.assemblyOut.toDouble()),
        )

        latestReport.set(DriftReport.of(sourceAvailable = true, baseline = base, observed = observedConfig,
            configFindings = configFindings, behavioralFindings = behavioral))
    }

    companion object {
        private val log = LoggerFactory.getLogger(DriftMonitor::class.java)
    }
}
```

> **Note for the implementer:** `DriftMonitor` is a `@Component` with non-`@Value` constructor params (`processor`, `configSource`, `telemetrySource`) that must be satisfied by beans — those beans are added in Task 8 (`DriftConfig`). Until Task 8, the offline `DriftMonitorTest` constructs `DriftMonitor` directly (no Spring), so this task is green on its own; the full Spring context is only exercised by `contextLoads` after Task 8.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftMonitorTest test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add "control/src/main/kotlin/com/resequencetwin/control/drift/DriftMonitor.kt" "control/src/test/kotlin/com/resequencetwin/control/drift/DriftMonitorTest.kt"
git commit -m "feat(drift): decoupled scheduled DriftMonitor orchestrator"
```

---

### Task 7: DriftMetrics (Micrometer gauges)

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/DriftMetrics.kt`
- Test: `control/src/test/kotlin/com/resequencetwin/control/drift/DriftMetricsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.resequencetwin.control.drift

import com.resequencetwin.control.pbs.Lane
import com.resequencetwin.control.stream.LivePbsProcessor
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DriftMetricsTest {

    private val lanes = listOf(Lane("L1", 10), Lane("L2", 10), Lane("L3", 10))
    private fun baselineConfig() = ObservedConfig(mapOf("L1" to 10, "L2" to 10, "L3" to 10), emptySet())

    @Test
    fun `gauges expose config finding and behavioral breach counts from the monitor`() {
        val cfg = SimulatedConfigSource(baselineConfig())
        val monitor = DriftMonitor(LivePbsProcessor(lanes), cfg,
            SimulatedTelemetrySource(ObservedKpi(0, 0, 0)),
            enabled = true, ewmaAlpha = 0.5, residualThreshold = 1.0)

        val registry = SimpleMeterRegistry()
        DriftMetrics(monitor).bindTo(registry)

        monitor.tick()
        cfg.setCapacity("L1", 8)
        monitor.tick()

        val findings = registry.get("pbs.drift.config.findings").gauge().value()
        val breaches = registry.get("pbs.drift.behavioral.breaches").gauge().value()
        assertThat(findings).isEqualTo(1.0)
        assertThat(breaches).isGreaterThanOrEqualTo(0.0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftMetricsTest test`
Expected: FAIL — `DriftMetrics` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.resequencetwin.control.drift

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * Binds drift-monitor state to Micrometer gauges at `/actuator/prometheus`.
 *
 * [DriftMonitor] is the single source of truth: each gauge reads [DriftMonitor.currentReport] freshly
 * on scrape. Gauges (not counters) because both counts can go up or down as drift appears and clears.
 *
 * Honesty: these observe a SYNTHETIC perturbation, demonstrating the detection mechanism.
 */
@Component
class DriftMetrics(private val monitor: DriftMonitor) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Gauge.builder("pbs.drift.config.findings", monitor) { it.currentReport().configFindings.size.toDouble() }
            .description("Current number of structural config-drift findings")
            .baseUnit("findings")
            .register(registry)

        Gauge.builder("pbs.drift.behavioral.breaches", monitor) { m ->
            m.currentReport().behavioralFindings.count { it.breached }.toDouble()
        }
            .description("Current number of behavioral-drift metrics in breach")
            .baseUnit("breaches")
            .register(registry)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftMetricsTest test`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add "control/src/main/kotlin/com/resequencetwin/control/drift/DriftMetrics.kt" "control/src/test/kotlin/com/resequencetwin/control/drift/DriftMetricsTest.kt"
git commit -m "feat(drift): Micrometer gauges for config findings and behavioral breaches"
```

---

### Task 8: DriftConfig wiring + DriftService + DriftController + config keys + contextLoads

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/DriftConfig.kt`
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/DriftService.kt`
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/DriftController.kt`
- Modify: `control/src/main/resources/application.properties` (add drift keys)
- Modify: `control/src/test/resources/application.properties` (add `pbs.drift.enabled=false`)
- Test: `control/src/test/kotlin/com/resequencetwin/control/drift/DriftControllerTest.kt`

> **Important ordering:** edit `control/src/test/resources/application.properties` to add `pbs.drift.enabled=false` **in Step 1 of this task, before running the full suite**, so that once `DriftMonitor`/`DriftConfig` become Spring beans the existing `contextLoads`/`@SpringBootTest` tests stay green without Docker and the scheduler never fires.

- [ ] **Step 1a: Add the test-properties gate**

Append to `control/src/test/resources/application.properties`:
```properties
# Drift monitor disabled in tests: scheduler no-ops, no live source polling (S5).
pbs.drift.enabled=false
```

- [ ] **Step 1b: Write the failing controller test**

```kotlin
package com.resequencetwin.control.drift

import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/**
 * WebMvcTest slice for [DriftController]. [DriftService] is mocked so the slice does not need
 * [DriftMonitor]'s scheduler or the LivePbsProcessor/Kafka/Ditto beans — matching the existing
 * `api/` slice pattern.
 */
@WebMvcTest(DriftController::class)
class DriftControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @MockBean private lateinit var service: DriftService

    private fun sampleReport() = DriftReport.of(
        sourceAvailable = true,
        baseline = ExpectedConfig(mapOf("L1" to 10), emptySet()),
        observed = ObservedConfig(mapOf("L1" to 8), emptySet()),
        configFindings = listOf(ConfigDriftFinding("L1", DriftKind.CAPACITY_CHANGED, "10", "8",
            ReconciliationProposal("ADJUST_CAPACITY", "twin L1 10 -> 8"))),
        behavioralFindings = listOf(BehavioralDriftFinding("releases", 3.0, 1.0, 2.0, 1.4, true)),
    )

    @Test
    fun `GET api drift returns 200 with envelope and findings`() {
        `when`(service.report()).thenReturn(sampleReport())
        mockMvc.perform(get("/api/drift"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.synthetic").value(true))
            .andExpect(jsonPath("$.disclaimer").isString)
            .andExpect(jsonPath("$.sourceAvailable").value(true))
            .andExpect(jsonPath("$.configFindings[0].kind").value("CAPACITY_CHANGED"))
            .andExpect(jsonPath("$.configFindings[0].proposal.action").value("ADJUST_CAPACITY"))
            .andExpect(jsonPath("$.reconciliationProposals[0].action").value("ADJUST_CAPACITY"))
            .andExpect(jsonPath("$.behavioralFindings[0].metric").value("releases"))
            .andExpect(jsonPath("$.behavioralFindings[0].breached").value(true))
    }

    @Test
    fun `POST api drift returns 405 (read-only surface)`() {
        mockMvc.perform(post("/api/drift")).andExpect(status().isMethodNotAllowed)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftControllerTest test`
Expected: FAIL — `DriftController` / `DriftService` unresolved.

- [ ] **Step 3: Write minimal implementation**

`DriftService.kt`:
```kotlin
package com.resequencetwin.control.drift

import org.springframework.stereotype.Service

/** Thin read-only relay of the latest drift report held by [DriftMonitor]. */
@Service
class DriftService(private val monitor: DriftMonitor) {
    fun report(): DriftReport = monitor.currentReport()
}
```

`DriftController.kt`:
```kotlin
package com.resequencetwin.control.drift

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Read-only REST surface for drift detection (S5). Advisory only; no write-back. */
@RestController
@RequestMapping("/api")
class DriftController(private val service: DriftService) {
    @GetMapping("/drift")
    fun getDrift(): DriftReport = service.report()
}
```

`DriftConfig.kt` (sim branch only for now; opcua branch added in Chunk 3):
```kotlin
package com.resequencetwin.control.drift

import com.resequencetwin.control.stream.parseLaneTopology
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the live-source beans selected by `pbs.drift.source` (default `sim`).
 *
 * The simulated sources are seeded from the same `pbs.lanes` topology the twin uses, so a clean
 * startup shows zero drift until a demo mutates the source. (OPC-UA branch added in the milo chunk.)
 */
@Configuration
class DriftConfig(
    @Value("\${pbs.lanes:L1:10,L2:10,L3:10}") private val lanesSpec: String,
    @Value("\${pbs.drift.source:sim}") private val source: String,
) {
    private fun seedConfig(): ObservedConfig {
        val lanes = parseLaneTopology(lanesSpec).associate { it.id to it.capacity }
        return ObservedConfig(lanes = lanes, blocked = emptySet())
    }

    @Bean
    fun liveConfigSource(): LiveConfigSource = when (source) {
        "sim" -> SimulatedConfigSource(seedConfig())
        else -> throw IllegalStateException("Unsupported pbs.drift.source='$source' (opcua wired in the milo chunk)")
    }

    @Bean
    fun liveTelemetrySource(): LiveTelemetrySource = when (source) {
        "sim" -> SimulatedTelemetrySource(ObservedKpi(0, 0, 0))
        else -> throw IllegalStateException("Unsupported pbs.drift.source='$source' (opcua wired in the milo chunk)")
    }
}
```

> **Implementer check:** confirm `Lane`'s property names are `id` and `capacity` (used in `seedConfig`). Read `control/src/main/kotlin/com/resequencetwin/control/pbs/Lane.kt`; if the accessors differ, adjust `it.id to it.capacity` accordingly.

Append to `control/src/main/resources/application.properties`:
```properties

# PBS drift seam (S5) — detect config + behavioral drift; advisory only, no write-back
pbs.drift.enabled=true
pbs.drift.interval-ms=1000
pbs.drift.ewma-alpha=0.3
pbs.drift.residual-threshold=2.0
# Live source: sim (offline deterministic) | opcua (milo, user-run real demo)
pbs.drift.source=sim
```

- [ ] **Step 4: Run the controller test, then the FULL suite**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftControllerTest test`
Expected: PASS (2 tests).

Then run the full suite to prove `contextLoads` is green with the new Spring beans and the scheduler disabled:
Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q test`
Expected: `BUILD SUCCESS`; `Tests run: BASELINE_N + 28` (Tasks 1–8 add 4+5+5+3+2+6+1+2 = 28), 0 failures, 0 errors (pre-existing 2 skips remain).

- [ ] **Step 5: Commit**

```bash
git add "control/src/main/kotlin/com/resequencetwin/control/drift/DriftConfig.kt" "control/src/main/kotlin/com/resequencetwin/control/drift/DriftService.kt" "control/src/main/kotlin/com/resequencetwin/control/drift/DriftController.kt" "control/src/main/resources/application.properties" "control/src/test/resources/application.properties" "control/src/test/kotlin/com/resequencetwin/control/drift/DriftControllerTest.kt"
git commit -m "feat(drift): wire sim sources, read-only GET /api/drift, config keys"
```

**🚩 Chunk 2 gate:** the entire offline drift seam is now built and the full suite is green with **no new dependency**. This is a clean, shippable checkpoint. Chunk 3 (milo / OPC-UA) is optional and isolated — if time-boxed, the PoC is already demonstrable end-to-end with the simulated source.

---

## Chunk 3: OPC-UA adapter (isolated — introduces the milo dependency)

> Build this only after Chunk 2 is fully green. It is the only chunk that touches `pom.xml`. Keep the offline core untouched: the OPC-UA adapters are additive, selected by `pbs.drift.source=opcua`.

### Task 9: Add milo dependency

**Files:**
- Modify: `control/pom.xml`

- [ ] **Step 1: Add the milo client dependency**

In `control/pom.xml` `<dependencies>`, add (verify the latest stable 0.6.x coordinate at implementation time; milo `sdk-client` pulls the stack/transport transitively):
```xml
    <!-- Eclipse milo OPC-UA client — S5 drift OPC-UA adapter (pbs.drift.source=opcua) -->
    <dependency>
      <groupId>org.eclipse.milo</groupId>
      <artifactId>sdk-client</artifactId>
      <version>0.6.12</version>
    </dependency>
    <!-- Embedded OPC-UA server for the in-JVM adapter test (test scope only) -->
    <dependency>
      <groupId>org.eclipse.milo</groupId>
      <artifactId>sdk-server</artifactId>
      <version>0.6.12</version>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Verify the dependency resolves and the build still compiles**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -DskipTests compile`
Expected: `BUILD SUCCESS` (milo downloaded, nothing else changed).

- [ ] **Step 3: Commit**

```bash
git add "control/pom.xml"
git commit -m "build(drift): add Eclipse milo OPC-UA client + test server"
```

### Task 10: OPC-UA adapters + embedded-server test

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/OpcUaConfigSource.kt`
- Create: `control/src/main/kotlin/com/resequencetwin/control/drift/OpcUaTelemetrySource.kt`
- Test: `control/src/test/kotlin/com/resequencetwin/control/drift/OpcUaSourcesTest.kt`

**Node layout (document in KDoc):** the OPC-UA server exposes, under a `PBS` folder, string-identifier nodes:
- config: `ns=2;s=PBS/lanes/<laneId>/capacity` (Int32), `ns=2;s=PBS/lanes/<laneId>/blocked` (Boolean), and a `ns=2;s=PBS/lanes` list node enumerating lane ids (or a configured lane-id list property).
- telemetry: `ns=2;s=PBS/kpi/releases`, `.../colourChanges`, `.../assemblyOut` (Int32).

**Adapter contract:** constructor takes an endpoint URL + the lane-id list (from `pbs.lanes`); `readConfig()`/`readKpi()` connect (or reuse a cached client), read the nodes, map to `ObservedConfig`/`ObservedKpi`. A read failure throws — `DriftMonitor` already treats that as a non-fatal source outage.

- [ ] **Step 1: Write the failing test (in-JVM embedded milo server → adapter round-trip)**

```kotlin
package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Real OPC-UA round-trip with NO external Docker: an in-JVM embedded milo server exposes PBS nodes,
 * and [OpcUaConfigSource]/[OpcUaTelemetrySource] read them back (analogous to EmbeddedKafka).
 *
 * If the embedded server proves too heavy/flaky in CI, fall back to @Tag("opcua") + user-run per the
 * spec, and document the manual run in the README. Do NOT block the offline suite on this test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpcUaSourcesTest {

    private lateinit var server: EmbeddedPbsOpcUaServer   // small test helper that boots milo sdk-server
    private val endpoint get() = server.endpointUrl

    @BeforeAll fun start() {
        server = EmbeddedPbsOpcUaServer()
        server.start()
        server.setConfig(lanes = mapOf("L1" to 10, "L2" to 8), blocked = setOf("L2"))
        server.setKpi(releases = 5, colourChanges = 2, assemblyOut = 5)
    }

    @AfterAll fun stop() { server.stop() }

    @Test
    fun `OpcUaConfigSource reads lane capacities and blocked set`() {
        val src = OpcUaConfigSource(endpoint, laneIds = listOf("L1", "L2"))
        val cfg = src.readConfig()
        assertThat(cfg.lanes).isEqualTo(mapOf("L1" to 10, "L2" to 8))
        assertThat(cfg.blocked).containsExactly("L2")
        src.close()
    }

    @Test
    fun `OpcUaTelemetrySource reads KPI nodes`() {
        val src = OpcUaTelemetrySource(endpoint)
        assertThat(src.readKpi()).isEqualTo(ObservedKpi(releases = 5, colourChanges = 2, assemblyOut = 5))
        src.close()
    }
}
```

> The `EmbeddedPbsOpcUaServer` test helper (under `src/test/kotlin/.../drift/`) wraps milo `sdk-server`: boots an `OpcUaServer` on an ephemeral port with a small namespace exposing the PBS nodes above and setters to mutate values. Implement it as part of this step. **If milo's embedded-server bootstrap (certificates/endpoints) proves heavyweight or flaky**, switch both tests to `@org.junit.jupiter.api.Tag("opcua")` + `@org.junit.jupiter.api.Disabled("user-run; see README OPC-UA runbook")` and rely on the documented user-run demo — the offline suite must stay green regardless.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=OpcUaSourcesTest test`
Expected: FAIL — adapters + embedded server helper unresolved.

- [ ] **Step 3: Write minimal implementation**

Implement `OpcUaConfigSource` and `OpcUaTelemetrySource` using milo's `OpcUaClient` (connect to `endpoint`, read the node ids above via `client.readValues(...)`, map `DataValue` → Int/Boolean, build `ObservedConfig`/`ObservedKpi`). Both implement their port, expose `close()`, and surface read/connect failures as a thrown exception (so `DriftMonitor` records a non-fatal outage). Keep a single cached `OpcUaClient` per adapter instance. Add the `EmbeddedPbsOpcUaServer` test helper.

> Keep the milo specifics (security policy = `None` for the PoC, anonymous identity) documented in KDoc and honest: this is a synthetic OPC-UA server, not a real PLC.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=OpcUaSourcesTest test`
Expected: PASS (2 tests) — or, if the embedded server was deferred to user-run, the tests are `@Disabled` and reported as skipped (document the decision in the commit message).

- [ ] **Step 5: Commit**

```bash
git add "control/src/main/kotlin/com/resequencetwin/control/drift/OpcUaConfigSource.kt" "control/src/main/kotlin/com/resequencetwin/control/drift/OpcUaTelemetrySource.kt" "control/src/test/kotlin/com/resequencetwin/control/drift/OpcUaSourcesTest.kt"
git commit -m "feat(drift): OPC-UA (milo) live-source adapters + embedded-server test"
```

### Task 11: Wire the opcua branch into DriftConfig

**Files:**
- Modify: `control/src/main/kotlin/com/resequencetwin/control/drift/DriftConfig.kt`
- Test: extend `control/src/test/kotlin/com/resequencetwin/control/drift/DriftControllerTest.kt` is NOT the right place — add a tiny `DriftConfigTest` instead.
- Test: `control/src/test/kotlin/com/resequencetwin/control/drift/DriftConfigTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DriftConfigTest {

    @Test
    fun `sim source selection yields simulated adapters`() {
        val cfg = DriftConfig(lanesSpec = "L1:10,L2:10", source = "sim",
            opcuaEndpoint = "opc.tcp://unused")
        assertThat(cfg.liveConfigSource()).isInstanceOf(SimulatedConfigSource::class.java)
        assertThat(cfg.liveTelemetrySource()).isInstanceOf(SimulatedTelemetrySource::class.java)
    }

    @Test
    fun `opcua source selection yields OPC-UA adapters`() {
        val cfg = DriftConfig(lanesSpec = "L1:10,L2:10", source = "opcua",
            opcuaEndpoint = "opc.tcp://localhost:4840")
        assertThat(cfg.liveConfigSource()).isInstanceOf(OpcUaConfigSource::class.java)
        assertThat(cfg.liveTelemetrySource()).isInstanceOf(OpcUaTelemetrySource::class.java)
    }
}
```

> Note: constructing `OpcUaConfigSource` must NOT connect eagerly (connection is lazy on first `readConfig()`), so this test does not need a live server.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftConfigTest test`
Expected: FAIL — `DriftConfig` has no `opcuaEndpoint` param / no opcua branch.

- [ ] **Step 3: Add the opcua branch**

Extend `DriftConfig` with `@Value("\${pbs.drift.opcua.endpoint:opc.tcp://localhost:4840}") private val opcuaEndpoint: String` and the `"opcua"` `when` branches returning `OpcUaConfigSource(opcuaEndpoint, parseLaneTopology(lanesSpec).map { it.id })` and `OpcUaTelemetrySource(opcuaEndpoint)`. Add the key to `application.properties`:
```properties
pbs.drift.opcua.endpoint=opc.tcp://localhost:4840
```

- [ ] **Step 4: Run the test, then the full suite**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q -Dtest=DriftConfigTest test` → PASS (2 tests).
Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q test` → `BUILD SUCCESS`, all green (default `pbs.drift.source=sim`, opcua adapters constructed lazily and never connected in the suite).

- [ ] **Step 5: Commit**

```bash
git add "control/src/main/kotlin/com/resequencetwin/control/drift/DriftConfig.kt" "control/src/main/resources/application.properties" "control/src/test/kotlin/com/resequencetwin/control/drift/DriftConfigTest.kt"
git commit -m "feat(drift): select OPC-UA live sources via pbs.drift.source=opcua"
```

### Task 12: README runbook + memory close-out

**Files:**
- Modify: `README.md`
- Modify: memory `C:\Users\Eisen\.claude\projects\C--Users-Eisen-Desktop-Labs\memory\virtual-commissioning-gap.md` (+ index line if needed)

- [ ] **Step 1: Add the drift seam to the README**

Add a "Drift seam (S5)" subsection: what it detects (config + behavioral), the honesty stance (advisory, no write-back, synthetic), how to see it offline (`GET /api/drift` after a `SimulatedConfigSource` mutation in a demo), and the **OPC-UA user-run runbook**: start an external OPC-UA simulation server (node-opcua / Prosys) exposing the PBS nodes, run control with `--pbs.drift.source=opcua --pbs.drift.opcua.endpoint=...`, observe `/api/drift` + `pbs.drift.*` gauges. Label the data synthetic. Update the test-count line if the README tracks per-module totals.

- [ ] **Step 2: Verify the README renders / no broken references**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && git diff --stat` and eyeball the README diff.

- [ ] **Step 3: Update the memory**

Edit `virtual-commissioning-gap.md` to record: PoC drift seam built (layers ① config + ② behavioral, ③ excluded), honest scope (advisory/no-write-back/synthetic), the `com.resequencetwin.control.drift` location, and link `[[resequence-twin-build-state]]`. Update the `resequence-twin-build-state.md` body §D to mark S5 complete with the final commit + test count.

- [ ] **Step 4: Commit**

> The memory files edited in Step 3 live under `~/.claude/...`, **outside** this git repo — do NOT `git add` them; they persist via the memory system, not the commit.

```bash
git add "README.md"
git commit -m "docs(drift): README drift-seam runbook (offline + OPC-UA user-run)"
```

---

## Final verification (after the last task)

- [ ] **Full suite green:** `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin/control" && mvn -q test` → `BUILD SUCCESS`, `Tests run: BASELINE_N + 32` (Chunk 1–2: 28, Chunk 3: 4 — or 28 + 2 with 2 skipped if OPC-UA deferred to user-run), 0 failures / 0 errors, only the 2 pre-existing Docker/Ditto skips.
- [ ] **`contextLoads` without Docker:** confirmed by the full suite passing with `pbs.drift.enabled=false`.
- [ ] **No write-back anywhere:** grep the package for any `set`/`put`/`write` against `LivePbsProcessor` or a controller — there must be none. `git grep -n "processor\." control/src/main/kotlin/com/resequencetwin/control/drift` should show only `.snapshot()`.
- [ ] **Honesty envelope present:** `/api/drift` response carries `synthetic=true` + disclaimer (covered by `DriftControllerTest`).
- [ ] Hand off to **superpowers:finishing-a-development-branch** (already on `main`, no remote → commit/cleanup only).

## Done criteria (from spec)
- `mvn test` from `control/` green (existing + new); `contextLoads` green without Docker. ✅ gated above.
- Pure detectors + monitor integration prove config drift and behavioral residual detection on injected synthetic perturbation; `/api/drift` returns findings + advisory reconciliation proposals. ✅ Tasks 2/3/6/8.
- OPC-UA adapter exercised by embedded-milo test (or documented user-run) — no write-back anywhere. ✅ Tasks 10–11.
- README runbook for the real OPC-UA demo (user-run, synthetic-labeled). ✅ Task 12.
- Memory `virtual-commissioning-gap` updated. ✅ Task 12.
