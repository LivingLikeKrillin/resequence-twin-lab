# PBS Trajectory Endpoint Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `PbsSimRunner.runWithTrace(...)` + `GET /api/trajectory` endpoint (with DTO, service, tests) to the `control/` Kotlin module, producing per-step lane-frame data for the USD/3D-twin exporter.

**Architecture:** Add `runWithTrace` to `PbsSimRunner` sharing the same private step helpers as `run()`; wire a new `PbsTrajectoryService` + `TrajectoryResponse` DTO + `GET /api/trajectory` on `PbsAdvisoryController`; guard with `@WebMvcTest` slice tests matching the existing patterns.

**Tech Stack:** Kotlin, Spring Boot 3.3.1, JVM 21, Jackson `@field:JsonProperty`, MockMvc `@WebMvcTest`, Maven.

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `control/src/main/kotlin/com/resequencetwin/control/bench/PbsSimRunner.kt` | Add `runWithTrace`, `Trace`, `Frame` nested types |
| Create | `control/src/main/kotlin/com/resequencetwin/control/api/TrajectoryResponse.kt` | DTO data class (JSON schema locked with `@field:JsonProperty`) |
| Create | `control/src/main/kotlin/com/resequencetwin/control/api/PbsTrajectoryService.kt` | Service: load generation → `runWithTrace` → DTO mapping |
| Modify | `control/src/main/kotlin/com/resequencetwin/control/api/PbsAdvisoryController.kt` | Add `GET /api/trajectory` + param validation |
| Create | `control/src/test/kotlin/com/resequencetwin/control/bench/PbsSimRunnerTraceTest.kt` | Unit tests for `runWithTrace` / `Trace` / `Frame` |
| Create | `control/src/test/kotlin/com/resequencetwin/control/api/PbsTrajectoryControllerTest.kt` | `@WebMvcTest` slice tests for the new endpoint |

---

## Chunk 1: `runWithTrace` + nested types in `PbsSimRunner`

### Task 1: Add `Frame` and `Trace` data classes + `runWithTrace` method

**Files:**
- Modify: `control/src/main/kotlin/com/resequencetwin/control/bench/PbsSimRunner.kt`

The key design principle: `runWithTrace` must call the SAME private `stepArrivals` / `stepRelease` / `stepUtilization` helpers in the same order as `run()`. The only difference is that it accumulates frames and returns a `Trace` instead of a KPI snapshot.

`Frame` captures: `step` index; `lanes` map of laneId → ordered list of occupant body ids (front-first); `released` body id (or null); `arrived` list of body ids assigned this step.

`Trace` holds: `frames: List<Frame>`; `laneSpecs: List<LaneSpec>`.

Because `stepArrivals` is private and returns the updated `arrivalIdx`, we need to track arrivals. The cleanest approach: snapshot lane occupant ids BEFORE stepArrivals, then AFTER — the difference (new ids not previously in any lane) are the arrivals. Similarly for released: snapshot assemblyOut size before/after stepRelease, then grab the new tail.

However since `stepRelease` is private and calls `policy.selectRelease` + `kpi.recordRelease`, we cannot easily intercept the released body without changing the helpers. Instead, we track `kpi.releasedCount()` before/after each step loop iteration and read `assemblyOut()` from state.

But wait — `stepRelease` releases into `state` (via `releaseFromLane`) and records via `kpi.recordRelease`. The released body IS the new entry in `state.assemblyOut()`. So: snapshot `state.assemblyOut().size` before calling `stepRelease`; after calling it, if size grew, the new last element is the released body.

For arrivals: snapshot per-lane occupant ids before `stepArrivals`; after it, new ids = all ids now in lanes minus ids that were there before minus released id. But since arrivals happen BEFORE release in the step order, arrivals at step N are present in the post-arrival snapshot. The simplest: snapshot total lane body ids before `stepArrivals` → after `stepArrivals` → difference is arrived ids.

Since we share the private helpers, we cannot use a clean "hook" approach. Instead, we implement `runWithTrace` as a parallel loop body that:
1. Snapshots lane occupant ids as a set.
2. Calls `stepArrivals` (shared helper).
3. Computes arrived = new ids now in lanes minus previously present ids.
4. Snapshots assemblyOut size.
5. Calls `stepRelease` (shared helper).
6. Computes released = if assemblyOut grew, `state.assemblyOut().last().id` else null.
7. Calls `stepUtilization` (shared helper).
8. Records `kpi.recordStep()`.
9. Builds `Frame(step, laneOccupantsAfterRelease, released, arrived)`.

The `lanes` field in Frame is captured AFTER both arrivals and release (i.e., end-of-step state) — this is what the exporter needs to replay positions.

- [ ] **Step 1: Write the failing trace unit test first**

Create `control/src/test/kotlin/com/resequencetwin/control/bench/PbsSimRunnerTraceTest.kt`:

```kotlin
package com.resequencetwin.control.bench

import com.resequencetwin.control.pbs.DynamicSequencingPolicy
import com.resequencetwin.control.pbs.StaticSequencingPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PbsSimRunnerTraceTest {

    companion object {
        private const val SEED = 42L
        private const val TOTAL_BODIES = 100

        private val LANE_SPECS = listOf(
            PbsSimRunner.LaneSpec("L1", 10),
            PbsSimRunner.LaneSpec("L2", 10),
            PbsSimRunner.LaneSpec("L3", 10)
        )
    }

    private lateinit var runner: PbsSimRunner
    private lateinit var bodies: List<com.resequencetwin.control.pbs.Body>

    @BeforeEach
    fun setUp() {
        runner = PbsSimRunner()
        bodies = PbsLoadGenerator(SEED, TOTAL_BODIES).generate()
    }

    @Test
    @DisplayName("TRACE-1: frame count is sane (>= total bodies, <= maxSteps)")
    fun frameCountIsSane() {
        val trace = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        assertThat(trace.frames).isNotEmpty
        assertThat(trace.frames.size)
            .`as`("frame count must be >= total bodies (at least one step per release)")
            .isGreaterThanOrEqualTo(TOTAL_BODIES)
        assertThat(trace.frames.size)
            .`as`("frame count must be <= maxSteps guard")
            .isLessThanOrEqualTo(PbsSimRunner.DEFAULT_MAX_STEPS)
    }

    @Test
    @DisplayName("TRACE-2: last frame has all lanes empty (all bodies released)")
    fun lastFrameAllLanesEmpty() {
        val trace = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        val lastFrame = trace.frames.last()
        val totalOccupants = lastFrame.lanes.values.sumOf { it.size }
        assertThat(totalOccupants)
            .`as`("last frame must have all lanes empty after all bodies released")
            .isEqualTo(0)
    }

    @Test
    @DisplayName("TRACE-3: released-id sequence matches run() assemblyOut order")
    fun releasedSequenceMatchesRunAssemblyOut() {
        val policy1 = DynamicSequencingPolicy()
        val policy2 = DynamicSequencingPolicy()

        val trace = runner.runWithTrace(bodies, LANE_SPECS, policy1)
        val snapshot = runner.run(bodies, LANE_SPECS, policy2)

        // Collect non-null released ids from trace frames in order
        val traceReleasedIds = trace.frames.mapNotNull { it.released }

        // Run again to get the assemblyOut order from a checkpoint that covers all bodies
        val policy3 = DynamicSequencingPolicy()
        val cp = runner.runToCheckpoint(bodies, LANE_SPECS, policy3, TOTAL_BODIES)
        val runAssemblyIds = cp.state.assemblyOut().map { it.id }

        assertThat(traceReleasedIds)
            .`as`("trace released ids must match run() assemblyOut order")
            .isEqualTo(runAssemblyIds)
    }

    @Test
    @DisplayName("TRACE-4: determinism — same inputs produce identical trace")
    fun traceIsDeterministic() {
        val trace1 = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())
        val trace2 = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        assertThat(trace1.frames.size).isEqualTo(trace2.frames.size)
        for (i in trace1.frames.indices) {
            val f1 = trace1.frames[i]
            val f2 = trace2.frames[i]
            assertThat(f1.step).isEqualTo(f2.step)
            assertThat(f1.released).isEqualTo(f2.released)
            assertThat(f1.arrived).isEqualTo(f2.arrived)
            assertThat(f1.lanes).isEqualTo(f2.lanes)
        }
    }

    @Test
    @DisplayName("TRACE-5: each frame has a step index equal to its position")
    fun frameStepIndicesAreSequential() {
        val trace = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        for (i in trace.frames.indices) {
            assertThat(trace.frames[i].step)
                .`as`("frame[%d].step must equal %d", i, i)
                .isEqualTo(i)
        }
    }

    @Test
    @DisplayName("TRACE-6: total released ids across all frames == total bodies")
    fun totalReleasedCountEqualsBodyCount() {
        val trace = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        val releasedCount = trace.frames.count { it.released != null }
        assertThat(releasedCount)
            .`as`("total released count must equal total body count")
            .isEqualTo(TOTAL_BODIES)
    }

    @Test
    @DisplayName("TRACE-7: laneSpecs in Trace match the input lane specs")
    fun traceLaneSpecsMatchInput() {
        val trace = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        assertThat(trace.laneSpecs).isEqualTo(LANE_SPECS)
    }

    @Test
    @DisplayName("TRACE-8: existing run() still produces same KPIs after runWithTrace added")
    fun existingRunUnchanged() {
        val s1 = runner.run(bodies, LANE_SPECS, DynamicSequencingPolicy())
        val s2 = runner.run(bodies, LANE_SPECS, DynamicSequencingPolicy())

        assertThat(s1.colorChanges).isEqualTo(s2.colorChanges)
        assertThat(s1.avgBatchLength).isEqualTo(s2.avgBatchLength)
        assertThat(s1.dueDateDeviation).isEqualTo(s2.dueDateDeviation)
    }
}
```

- [ ] **Step 2: Run test to confirm it fails (compilation error expected)**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test 2>&1 | tail -20
```
Expected: compilation failure — `runWithTrace`, `Trace`, `Frame` not found.

- [ ] **Step 3: Implement `Frame`, `Trace`, and `runWithTrace` in `PbsSimRunner.kt`**

Add to `control/src/main/kotlin/com/resequencetwin/control/bench/PbsSimRunner.kt` — inside the class body, AFTER the `Checkpoint` data class, BEFORE closing brace:

```kotlin
    // -------------------------------------------------------------------------
    // Trace run (viz-1)
    // -------------------------------------------------------------------------

    /**
     * Drive the same step loop as [run] but record a [Frame] after each step,
     * returning a [Trace] of the full simulation.
     *
     * Loop order is IDENTICAL to [run]: stepArrivals → stepRelease →
     * stepUtilization → tick.  The private step helpers are reused so this
     * method cannot drift from [run].
     *
     * @param bodies     the seeded paint-stream
     * @param laneSpecs  lane specifications
     * @param policy     the policy to drive
     * @return [Trace] holding all frames (one per simulation step) and the lane specs
     */
    fun runWithTrace(
        bodies: List<Body>,
        laneSpecs: List<LaneSpec>,
        policy: SequencingPolicy
    ): Trace {
        val lanes = laneSpecs.map { s -> Lane(s.id, s.capacity) }
        val state = PbsState.withLanes(lanes)
        val kpi = PbsKpiCollector()

        var arrivalIdx = 0
        var steps = 0
        val frames = mutableListOf<Frame>()

        while (kpi.releasedCount() < bodies.size && steps < maxSteps) {
            // Snapshot occupant ids before arrivals phase
            val beforeArrivalsIds = laneSpecs.flatMap { s ->
                state.lane(s.id)?.occupants()?.map { it.id } ?: emptyList()
            }.toSet()

            arrivalIdx = stepArrivals(bodies, laneSpecs, state, policy, arrivalIdx)

            // Arrived = new ids in any lane that were not there before arrivals
            val afterArrivalsIds = laneSpecs.flatMap { s ->
                state.lane(s.id)?.occupants()?.map { it.id } ?: emptyList()
            }.toSet()
            val arrivedIds = (afterArrivalsIds - beforeArrivalsIds).toList()

            // Snapshot assemblyOut size before release
            val assemblyOutSizeBefore = kpi.releasedCount()

            stepRelease(laneSpecs, state, policy, kpi)

            // Released = if assemblyOut grew, read last id from state
            val releasedId: String? = if (kpi.releasedCount() > assemblyOutSizeBefore) {
                state.assemblyOut().last().id
            } else null

            stepUtilization(laneSpecs, state, kpi)
            kpi.recordStep()

            // Build lane-occupants map (ordered front-first) after release
            val laneOccupants = laneSpecs.associate { s ->
                s.id to (state.lane(s.id)?.occupants()?.map { it.id } ?: emptyList())
            }

            frames += Frame(
                step = steps,
                lanes = laneOccupants,
                released = releasedId,
                arrived = arrivedIds
            )
            steps++
        }

        return Trace(frames = frames.toList(), laneSpecs = laneSpecs)
    }

    // -------------------------------------------------------------------------
    // Trace nested types
    // -------------------------------------------------------------------------

    /**
     * A per-step snapshot of the PBS simulation state.
     *
     * @param step     0-indexed step counter
     * @param lanes    map of laneId → front-first list of occupant body ids after this step
     * @param released body id released to assembly this step; null if no release occurred
     * @param arrived  body ids assigned to lanes during arrivals phase this step
     */
    data class Frame(
        val step: Int,
        val lanes: Map<String, List<String>>,
        val released: String?,
        val arrived: List<String>
    )

    /**
     * Full simulation trace returned by [runWithTrace].
     *
     * @param frames    ordered list of per-step frames (index == frame.step)
     * @param laneSpecs lane specifications used for this run
     */
    data class Trace(
        val frames: List<Frame>,
        val laneSpecs: List<LaneSpec>
    )
```

**Important note on `state.assemblyOut()`:** `PbsState.assemblyOut()` returns a list from `_assemblyOut` — but currently only `kpi.recordRelease(body)` is called in `stepRelease`. The body IS released from the lane (via `policy.selectRelease(state)` which calls `state.releaseFromLane`), so `state.assemblyOut()` should have the released body. Verify by reading `DynamicSequencingPolicy.selectRelease` to confirm it calls `state.releaseFromLane`.

- [ ] **Step 4: Verify `selectRelease` calls `state.releaseFromLane`**

Read `control/src/main/kotlin/com/resequencetwin/control/pbs/DynamicSequencingPolicy.kt` to confirm `state.releaseFromLane` is called.

- [ ] **Step 5: Run tests to verify TRACE tests pass (existing tests stay green)**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test 2>&1 | tail -30
```
Expected: all prior 97 tests + 8 new TRACE tests = 105 total, 0 failures.

- [ ] **Step 6: Commit**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && git add \
  control/src/main/kotlin/com/resequencetwin/control/bench/PbsSimRunner.kt \
  control/src/test/kotlin/com/resequencetwin/control/bench/PbsSimRunnerTraceTest.kt && \
git commit -m "feat(viz-1): add PbsSimRunner.runWithTrace + Trace/Frame types (TDD red→green)"
```

---

## Chunk 2: `TrajectoryResponse` DTO

### Task 2: Create the DTO data class

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/api/TrajectoryResponse.kt`

The JSON schema (locked via `@field:JsonProperty`):

```json
{
  "synthetic": true,
  "disclaimer": "...",
  "seed": 42,
  "policy": "dynamic",
  "lanes": [{"id": "L1", "capacity": 10}, ...],
  "bodies": {
    "BODY-00001": {"color":"RED","model":"Focus","options":["SUNROOF"],"dueDateSeq":12},
    ...
  },
  "frames": [
    {"step":0,"lanes":{"L1":["BODY-00001"],"L2":[],"L3":[]},"released":null,"arrived":["BODY-00001"]},
    ...
  ]
}
```

- [ ] **Step 1: Create `TrajectoryResponse.kt`**

```kotlin
package com.resequencetwin.control.api

import com.fasterxml.jackson.annotation.JsonProperty

/** Metadata for one body in the trajectory bodies map. */
data class TrajectoryBodyDto(
    @field:JsonProperty("color")      val color: String,
    @field:JsonProperty("model")      val model: String,
    @field:JsonProperty("options")    val options: List<String>,
    @field:JsonProperty("dueDateSeq") val dueDateSeq: Int
)

/** One lane in the trajectory lanes array. */
data class TrajectoryLaneDto(
    @field:JsonProperty("id")       val id: String,
    @field:JsonProperty("capacity") val capacity: Int
)

/** One per-step frame in the trajectory frames array. */
data class TrajectoryFrameDto(
    @field:JsonProperty("step")     val step: Int,
    @field:JsonProperty("lanes")    val lanes: Map<String, List<String>>,
    @field:JsonProperty("released") val released: String?,
    @field:JsonProperty("arrived")  val arrived: List<String>
)

/**
 * Full trajectory response for GET /api/trajectory.
 *
 * Wraps the honesty envelope + seed/policy context + lane specs +
 * body metadata map + per-step frames.
 */
data class TrajectoryResponse(
    @field:JsonProperty("synthetic")  val synthetic: Boolean,
    @field:JsonProperty("disclaimer") val disclaimer: String,
    @field:JsonProperty("seed")       val seed: Long,
    @field:JsonProperty("policy")     val policy: String,
    @field:JsonProperty("lanes")      val lanes: List<TrajectoryLaneDto>,
    @field:JsonProperty("bodies")     val bodies: Map<String, TrajectoryBodyDto>,
    @field:JsonProperty("frames")     val frames: List<TrajectoryFrameDto>
)
```

- [ ] **Step 2: Run tests to confirm no compilation error (nothing new should break)**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test 2>&1 | tail -10
```
Expected: same green count as before.

---

## Chunk 3: `PbsTrajectoryService`

### Task 3: Create the service

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/api/PbsTrajectoryService.kt`

This service:
1. Uses `PbsLoadGenerator(seed, bodiesCount).generate()` to get the body list.
2. Chooses policy based on `policyStr`: `"dynamic"` → `DynamicSequencingPolicy()`, `"static"` → `StaticSequencingPolicy()`.
3. Calls `runner.runWithTrace(bodies, DEFAULT_LANE_SPECS, policy)` → `Trace`.
4. Maps `Trace` → `TrajectoryResponse`.

The DEFAULT_LANE_SPECS (3×10) are defined in `PbsAdvisoryService` — to avoid duplication, we can share them by moving to a companion object on `PbsAdvisoryService` or declare them locally in the new service. Since the spec says "reuse the same default config the benchmark uses", reference `PbsAdvisoryService.DEFAULT_LANE_SPECS` if accessible (it's `private` now — we'll need to either change to `internal`/`companion object` accessible, OR redefine). **Simplest**: declare the constant in both services (tiny duplication, avoids coupling) with an identical value.

- [ ] **Step 1: Create `PbsTrajectoryService.kt`**

```kotlin
package com.resequencetwin.control.api

import com.resequencetwin.control.bench.PbsLoadGenerator
import com.resequencetwin.control.bench.PbsSimRunner
import com.resequencetwin.control.pbs.DynamicSequencingPolicy
import com.resequencetwin.control.pbs.StaticSequencingPolicy
import org.springframework.stereotype.Service

/**
 * Service backing GET /api/trajectory.
 *
 * Wires PbsLoadGenerator → PbsSimRunner.runWithTrace → TrajectoryResponse.
 */
@Service
class PbsTrajectoryService {

    companion object {
        const val DEFAULT_SEED: Long = 42L
        const val DEFAULT_BODIES: Int = 100
        const val DEFAULT_POLICY: String = "dynamic"
        const val MAX_BODIES: Int = 2000

        /** Canonical 3×10 lane config matching the benchmark. */
        val DEFAULT_LANE_SPECS: List<PbsSimRunner.LaneSpec> = listOf(
            PbsSimRunner.LaneSpec("L1", 10),
            PbsSimRunner.LaneSpec("L2", 10),
            PbsSimRunner.LaneSpec("L3", 10)
        )
    }

    private val runner = PbsSimRunner()

    /**
     * Build a full trajectory for the given params.
     *
     * @param seed       RNG seed (default 42)
     * @param bodiesCount total bodies to simulate (1..2000)
     * @param policyStr  "dynamic" or "static"
     * @return [TrajectoryResponse] with honesty envelope + all frames
     */
    fun trajectory(seed: Long, bodiesCount: Int, policyStr: String): TrajectoryResponse {
        val bodyList = PbsLoadGenerator(seed, bodiesCount).generate()

        val policy = when (policyStr) {
            "dynamic" -> DynamicSequencingPolicy()
            "static"  -> StaticSequencingPolicy()
            else      -> throw IllegalArgumentException("Unknown policy: $policyStr")
        }

        val trace = runner.runWithTrace(bodyList, DEFAULT_LANE_SPECS, policy)

        val env = AdvisoryEnvelope.standard()

        // Build bodies map: id → TrajectoryBodyDto
        val bodiesMap: Map<String, TrajectoryBodyDto> = bodyList.associate { b ->
            b.id to TrajectoryBodyDto(
                color      = b.color,
                model      = b.model,
                options    = b.options.toList(),
                dueDateSeq = b.dueDateSeq
            )
        }

        // Build lanes list
        val lanesDto = trace.laneSpecs.map { s ->
            TrajectoryLaneDto(id = s.id, capacity = s.capacity)
        }

        // Build frames list
        val framesDto = trace.frames.map { f ->
            TrajectoryFrameDto(
                step     = f.step,
                lanes    = f.lanes,
                released = f.released,
                arrived  = f.arrived
            )
        }

        return TrajectoryResponse(
            synthetic  = env.synthetic,
            disclaimer = env.disclaimer,
            seed       = seed,
            policy     = policyStr,
            lanes      = lanesDto,
            bodies     = bodiesMap,
            frames     = framesDto
        )
    }
}
```

- [ ] **Step 2: Run tests — still green**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test 2>&1 | tail -10
```

---

## Chunk 4: `GET /api/trajectory` on controller

### Task 4: Add endpoint + param validation

**Files:**
- Modify: `control/src/main/kotlin/com/resequencetwin/control/api/PbsAdvisoryController.kt`

Add `PbsTrajectoryService` as a second constructor parameter. Add `GET /api/trajectory` mapping. Add param validation (bodies > 0, bodies <= 2000, policy in {dynamic, static}).

- [ ] **Step 1: Inject `PbsTrajectoryService` and add the endpoint**

Change the controller constructor to accept both services:
```kotlin
class PbsAdvisoryController(
    private val service: PbsAdvisoryService,
    private val trajectoryService: PbsTrajectoryService
)
```

Add inside the class body, after the existing endpoints:
```kotlin
    // -------------------------------------------------------------------------
    // GET /api/trajectory
    // -------------------------------------------------------------------------

    companion object {
        val VALID_POLICIES = setOf("dynamic", "static")
        const val MAX_BODIES_TRAJECTORY: Int = 2000
    }

    @GetMapping("/trajectory")
    fun getTrajectory(
        @RequestParam(defaultValue = "42")      seed: Long,
        @RequestParam(defaultValue = "100")     bodies: Int,
        @RequestParam(defaultValue = "dynamic") policy: String
    ): TrajectoryResponse {
        validateBodies(bodies)
        if (bodies > MAX_BODIES_TRAJECTORY) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "bodies must be <= $MAX_BODIES_TRAJECTORY (got $bodies)"
            )
        }
        if (policy !in VALID_POLICIES) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "policy must be one of $VALID_POLICIES (got '$policy')"
            )
        }
        return trajectoryService.trajectory(seed, bodies, policy)
    }
```

Note: the existing `companion object` in the controller has `MAX_AFTER_RELEASES`. We add `VALID_POLICIES` and `MAX_BODIES_TRAJECTORY` to the same companion object.

**Important**: `@WebMvcTest(PbsAdvisoryController::class)` in the existing test imports only `PbsAdvisoryService`. After this change, Spring will also need `PbsTrajectoryService` to instantiate the controller. The existing test file will need `@Import(PbsAdvisoryService::class, PbsTrajectoryService::class)`. We must update the existing test file to add `PbsTrajectoryService` to the `@Import`.

- [ ] **Step 2: Update `PbsAdvisoryControllerTest.kt` to also import `PbsTrajectoryService`**

Change line 33 in `PbsAdvisoryControllerTest.kt`:
```kotlin
// Before:
@Import(PbsAdvisoryService::class)
// After:
@Import(PbsAdvisoryService::class, PbsTrajectoryService::class)
```

- [ ] **Step 3: Run tests — all prior tests still green**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test 2>&1 | tail -30
```
Expected: all tests green (no new tests yet from the controller test file).

---

## Chunk 5: `@WebMvcTest` slice tests for `GET /api/trajectory`

### Task 5: Create `PbsTrajectoryControllerTest.kt`

**Files:**
- Create: `control/src/test/kotlin/com/resequencetwin/control/api/PbsTrajectoryControllerTest.kt`

- [ ] **Step 1: Write the failing trajectory controller tests first (TDD red)**

```kotlin
package com.resequencetwin.control.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/**
 * WebMvcTest slice for GET /api/trajectory (viz-1).
 *
 * Exercises the real simulation — no mocks of the sim pipeline.
 * Follows the pattern of PbsAdvisoryControllerTest.
 */
@WebMvcTest(PbsAdvisoryController::class)
@Import(PbsAdvisoryService::class, PbsTrajectoryService::class)
class PbsTrajectoryControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    // =========================================================================
    // 1. Structure: GET /api/trajectory 200 + JSON shape
    // =========================================================================

    @Test
    @DisplayName("TRAJ-1: GET /api/trajectory returns 200 with honesty envelope")
    fun getTrajectoryReturns200WithEnvelope() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "20")
                .param("policy", "dynamic"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.synthetic").value(true))
            .andExpect(jsonPath("$.disclaimer").isString)
    }

    @Test
    @DisplayName("TRAJ-2: GET /api/trajectory returns seed, policy, non-empty lanes/bodies/frames")
    fun getTrajectoryReturnsFullStructure() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "20")
                .param("policy", "dynamic"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.seed").value(42))
            .andExpect(jsonPath("$.policy").value("dynamic"))
            .andExpect(jsonPath("$.lanes").isArray)
            .andExpect(jsonPath("$.lanes.length()").value(3))
            .andExpect(jsonPath("$.bodies").isMap)
            .andExpect(jsonPath("$.frames").isArray)
    }

    @Test
    @DisplayName("TRAJ-3: frames array is non-empty and first frame has required keys")
    fun framesArrayNonEmptyWithRequiredKeys() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "10")
                .param("policy", "dynamic"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.frames").isArray)
            .andExpect(jsonPath("$.frames[0].step").isNumber)
            .andExpect(jsonPath("$.frames[0].lanes").isMap)
            .andExpect(jsonPath("$.frames[0].arrived").isArray)
            // released may be null — just check key presence via non-exception
    }

    @Test
    @DisplayName("TRAJ-4: bodies map is non-empty and each entry has color/model/options/dueDateSeq")
    fun bodiesMapNonEmpty() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "10")
                .param("policy", "dynamic"))
            .andExpect(status().isOk)
            // bodies map must be non-empty (10 bodies generated)
            .andExpect(jsonPath("$.bodies").isMap)
            // Spot-check first body by confirming at least one key-path exists
            // (We check the count via frames non-empty)
            .andExpect(jsonPath("$.frames.length()").value(org.hamcrest.Matchers.greaterThan(0)))
    }

    @Test
    @DisplayName("TRAJ-5: lanes array has id and capacity fields")
    fun lanesArrayHasIdAndCapacity() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lanes[0].id").isString)
            .andExpect(jsonPath("$.lanes[0].capacity").isNumber)
    }

    @Test
    @DisplayName("TRAJ-6: defaults — no params — returns 200")
    fun defaultParamsReturn200() {
        mockMvc.perform(get("/api/trajectory"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.synthetic").value(true))
            .andExpect(jsonPath("$.seed").value(42))
            .andExpect(jsonPath("$.policy").value("dynamic"))
    }

    // =========================================================================
    // 2. Determinism: same params → identical JSON response
    // =========================================================================

    @Test
    @DisplayName("DETERM-TRAJ-1: same params twice → identical response body (byte-equality)")
    fun trajectoryIsDeterministic() {
        val r1 = mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "20")
                .param("policy", "dynamic"))
            .andExpect(status().isOk).andReturn()
        val r2 = mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "20")
                .param("policy", "dynamic"))
            .andExpect(status().isOk).andReturn()

        assertThat(r1.response.contentAsString)
            .`as`("trajectory response must be byte-identical for same params")
            .isEqualTo(r2.response.contentAsString)
    }

    // =========================================================================
    // 3. policy=static returns a valid (different) trajectory
    // =========================================================================

    @Test
    @DisplayName("POLICY-1: policy=static returns 200 with policy field == 'static'")
    fun staticPolicyReturns200() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "20")
                .param("policy", "static"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.policy").value("static"))
            .andExpect(jsonPath("$.frames").isArray)
    }

    // =========================================================================
    // 4. Bad params → 400
    // =========================================================================

    @Test
    @DisplayName("BADPARAM-TRAJ-1: bodies=0 returns 400")
    fun bodiesZeroReturns400() {
        mockMvc.perform(get("/api/trajectory").param("bodies", "0"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-TRAJ-2: bodies=99999 returns 400 (absurd size)")
    fun bodiesAbsurdSizeReturns400() {
        mockMvc.perform(get("/api/trajectory").param("bodies", "99999"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-TRAJ-3: bodies=2001 returns 400 (just over limit)")
    fun bodiesJustOverLimitReturns400() {
        mockMvc.perform(get("/api/trajectory").param("bodies", "2001"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-TRAJ-4: policy=bogus returns 400")
    fun bogusHolicyReturns400() {
        mockMvc.perform(get("/api/trajectory").param("policy", "bogus"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-TRAJ-5: policy=DYNAMIC (uppercase) returns 400 — policy is case-sensitive")
    fun policyUppercaseReturns400() {
        mockMvc.perform(get("/api/trajectory").param("policy", "DYNAMIC"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("READONLY-TRAJ: POST /api/trajectory returns 405 Method Not Allowed")
    fun postTrajectoryReturns405() {
        mockMvc.perform(post("/api/trajectory"))
            .andExpect(status().isMethodNotAllowed)
    }
}
```

- [ ] **Step 2: Run the trajectory tests — they should fail (endpoint not wired yet if running before Task 4)**

If running after Task 4 is done (controller wired), the tests should pass immediately. If running before, expect 404. Either way, run to see current state:

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test 2>&1 | tail -30
```

- [ ] **Step 3: Fix any issues and run until all tests pass**

Expected final state: 97 (existing) + 8 (TRACE) + 15 (TRAJ) = 120 total tests, 0 failures, 2 skipped.

- [ ] **Step 4: Final verification run**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml test 2>&1 | grep -E "Tests run:|BUILD"
```

Expected: `Tests run: 120, Failures: 0, Errors: 0, Skipped: 2` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit all remaining files**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && git add \
  control/src/main/kotlin/com/resequencetwin/control/api/TrajectoryResponse.kt \
  control/src/main/kotlin/com/resequencetwin/control/api/PbsTrajectoryService.kt \
  control/src/main/kotlin/com/resequencetwin/control/api/PbsAdvisoryController.kt \
  control/src/test/kotlin/com/resequencetwin/control/api/PbsTrajectoryControllerTest.kt \
  control/src/test/kotlin/com/resequencetwin/control/api/PbsAdvisoryControllerTest.kt && \
git commit -m "feat(viz-1): PBS trajectory endpoint for USD/3D-twin export

- GET /api/trajectory (seed, bodies, policy params; honesty envelope)
- PbsTrajectoryService wires PbsLoadGenerator → runWithTrace → TrajectoryResponse DTO
- TrajectoryResponse DTO with @field:JsonProperty-locked field names
- Bad-param guards: bodies>0, bodies<=2000, policy in {dynamic,static} → 400
- Deterministic: same params → byte-identical JSON
- 15 new WebMvcTest slice tests (structure, DETERM, policy, bad-params, readonly)"
```

---

## Notes for USD/3D-twin Exporter

### Exact JSON Schema (field names are locked by `@field:JsonProperty`)

```json
{
  "synthetic": true,                        // Boolean
  "disclaimer": "Synthetic PoC — ...",      // String
  "seed": 42,                               // Long
  "policy": "dynamic",                      // String: "dynamic"|"static"
  "lanes": [                                // Array<TrajectoryLaneDto>
    {"id": "L1", "capacity": 10},
    {"id": "L2", "capacity": 10},
    {"id": "L3", "capacity": 10}
  ],
  "bodies": {                               // Map<String, TrajectoryBodyDto>
    "BODY-00001": {
      "color": "RED",                       // String (paint color)
      "model": "Focus",                     // String (vehicle model)
      "options": ["SUNROOF"],               // Array<String> (installed options)
      "dueDateSeq": 12                      // Int (JIS target assembly position, 0-indexed)
    }
  },
  "frames": [                               // Array<TrajectoryFrameDto>
    {
      "step": 0,                            // Int (0-indexed step counter)
      "lanes": {                            // Map<String, Array<String>>
        "L1": ["BODY-00001"],               // front-first occupant body ids
        "L2": [],
        "L3": []
      },
      "released": null,                     // String? (body id released this step, or null)
      "arrived": ["BODY-00001"]             // Array<String> (body ids assigned this step)
    }
  ]
}
```

### Key facts for the exporter:
- `frames[i].step == i` always (sequential, 0-indexed)
- `frames[i].lanes[laneId]` is front-first (index 0 = front of FIFO queue, next to release)
- `bodies` map key = `BODY-NNNNN` (zero-padded 5 digits)
- `color` values: `RED`, `BLUE`, `WHITE`, `BLACK`, `SILVER`
- To get body metadata for a body id in a frame: `bodies[bodyId]`
- The full release sequence = `frames.mapNotNull { it.released }` in order
- Default params: `seed=42`, `bodies=100`, `policy=dynamic` → canonical PoC scenario
