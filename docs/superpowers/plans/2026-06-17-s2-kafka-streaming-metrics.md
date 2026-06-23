# S2 — Kafka Consumer + Micrometer Prometheus + EmbeddedKafka/Replay Tests

**Branch:** `build/scaffold` (continue) · **Baseline:** `b6d40fa` (S1 done)
**Goal:** Wire the pure S1 `LivePbsProcessor` to a live Kafka stream, expose live KPIs via
`/actuator/prometheus`, and prove idempotent replay-safety with EmbeddedKafka — no Docker
required for the automated tests. (Ditto live projection + Grafana are deferred to S4.)

## Context (read before any task)

- `control/` is **100% Kotlin**, Spring Boot, Maven (`mvn` from `control/`). Java 25 / release 21.
- S1 produced `stream/LivePbsProcessor` (pure, `@Synchronized`, idempotent dedup via
  `processedIds.putIfAbsent`, fault-tolerant lane-block degradation, out-of-order = process-anyway).
  Its public API: `process(event: PbsEvent): ProcessResult` and `snapshot(): LiveSnapshot`.
  **Do NOT change the processor's logic** — S2 only *drives* it and *observes* it.
- `stream/PbsEvent` is a **sealed interface** (`BodyArrived`, `ReleaseTick`, `LaneBlocked`,
  `LaneUnblocked`), each with `eventId: String`, `seq: Long`. **No Jackson annotations yet.**
- `stream/ProcessResult` sealed: `Duplicate`, `Rejected(reason)`, `Deferred(bodyId)`,
  `Assigned(bodyId,laneId)`, `Released(body,colourChanged)`, `Idle`, `LaneStatusChanged(laneId,blocked)`.
- `stream/LiveSnapshot` fields: `laneOccupants: Map<String,List<String>>`, `assemblyOutSize: Int`,
  `releases`, `colourChanges`, `rejected`, `duplicates`, `outOfOrder` (all Int), `buffered: Int`,
  `blockedLanes: Set<String>`.
- **Canonical lane topology (must match bench + API for honesty):** 3 lanes `L1,L2,L3`, capacity 10
  each. See `api/PbsAdvisoryService.kt:30`, `bench/PbsBenchRegressionTest.kt:26`.
- `application.properties`: `spring.kafka.bootstrap-servers=localhost:19092`,
  `consumer.group-id=resequence-twin-control`, `auto-offset-reset=earliest`,
  `management.endpoints.web.exposure.include=health,info,prometheus`, `server.port=8081`.
- `pom.xml` HAS: actuator, spring-kafka, spring-kafka-test, jackson-module-kotlin, kotlin-test-junit5.
  **MISSING:** `micrometer-registry-prometheus` (Task 3 adds it).
- Existing `@SpringBootTest` tests (`TwinStateQueryTest`, `IngestProjectionTest`) require a running
  Ditto and are the "2 skip / fail-without-Docker" ones. `ResequenceTwinControlApplicationTest.contextLoads`
  must keep passing **without a broker** — Kafka listener auto-startup connection failures must not
  fail context load (they don't, by default; just don't introduce a bean that requires a live broker
  at startup).
- **Event JSON wire schema (memory §C):**
  `{"type":"BodyArrived|ReleaseTick|LaneBlocked|LaneUnblocked","eventId":"...","seq":<long>,
    "bodyId":"...","color":"...","model":"...","options":["..."],"dueDateSeq":<int>,"laneId":"..."}`
  discriminated by `type`. Only the fields relevant to each subtype are present.
- **Honesty constraints (keep):** synthetic PoC, no real plant/data; KPIs relative-only; advisory
  read-only. Metrics are observability of the synthetic stream, not a marketing dashboard.

## TDD: every task is RED → GREEN → refactor. Commit per task. Run `mvn test` (from `control/`).

---

## Task 1 — Polymorphic JSON for `PbsEvent`

**Spec:** Make `PbsEvent` deserializable from the wire schema above using Jackson polymorphic typing,
without changing its runtime shape or S1 behavior.

- Add to the sealed interface `PbsEvent`:
  - `@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")`
  - `@JsonSubTypes` mapping names `"BodyArrived"`, `"ReleaseTick"`, `"LaneBlocked"`, `"LaneUnblocked"`
    to the four data classes.
- The discriminator property is `type` (NOT serialized into the data-class bodies — use
  `visible = false` default). `jackson-module-kotlin` is already on the classpath for ctor binding.
- Subtypes must round-trip with the canonical `ObjectMapper` configured with the Kotlin module.
  `options` defaults to empty set, `dueDateSeq` defaults to 0 if absent (already defaulted in data class).

**Tests (`stream/PbsEventJsonTest.kt`, pure unit, no Spring/Kafka):**
- Each of the 4 subtypes deserializes from a representative JSON string into the correct subtype with
  correct fields.
- A `BodyArrived` JSON omitting `options`/`dueDateSeq` uses the defaults.
- Round-trip (serialize → deserialize) preserves equality for one `BodyArrived`.
- Unknown `type` value → throws (documents fail-fast).

**Commit:** `feat(s2): polymorphic JSON (@JsonTypeInfo) for PbsEvent + deser tests`

---

## Task 2 — Kafka consumer config + listener → `LivePbsProcessor`

**Spec:** A Spring `@KafkaListener` that consumes `PbsEvent` from a topic and feeds each event to a
single shared `LivePbsProcessor` configured with the canonical 3×10 topology. Single processor
instance for the PoC (one partition assumed; document the per-partition note from S1's threading model).

- New `stream/PbsStreamConfig` (`@Configuration`):
  - `@Bean fun livePbsProcessor(): LivePbsProcessor` built from topology read from properties
    `pbs.lanes` (default `L1:10,L2:10,L3:10`) — parse `"id:cap,..."`. Keep it simple & validated.
  - `@Bean` `ConsumerFactory<String, PbsEvent>` / `ConcurrentKafkaListenerContainerFactory` using a
    spring-kafka `JsonDeserializer(PbsEvent::class)` with trusted package
    `com.resequencetwin.control.stream` and the polymorphic mapper (delegate to `ErrorHandlingDeserializer`
    so a malformed record does not poison the partition — log + skip).
  - Topic name property `pbs.topic` default `pbs-events`.
- New `stream/PbsEventListener` (`@Component`):
  - `@KafkaListener(topics = "\${pbs.topic:pbs-events}", containerFactory = ...)`
    `fun onEvent(event: PbsEvent)` → `val result = processor.process(event)`; log at debug.
  - Holds a reference to the shared `LivePbsProcessor` bean (constructor injection).
  - Update Micrometer happens in Task 3 — for now just process. (If sequencing is awkward, Task 2 may
    introduce the metrics seam as a no-op collaborator; but do NOT add the registry dep here.)

**Tests:** Defer the broker-backed integration test to Task 4. Here add a **pure** listener unit test
(`stream/PbsEventListenerTest.kt`) that constructs the listener with a real `LivePbsProcessor` and
calls `onEvent(...)` directly, asserting the processor state advanced (e.g. a `BodyArrived` then
`snapshot().laneOccupants` reflects assignment). Also a unit test for the `pbs.lanes` topology parser.
Verify `mvn test` still has `contextLoads` green (no live broker needed at startup).

**Commit:** `feat(s2): Kafka consumer config + PbsEventListener bridging to LivePbsProcessor`

---

## Task 3 — Micrometer Prometheus live KPIs

**Spec:** Expose `LivePbsProcessor` state as Prometheus metrics on `/actuator/prometheus`, with the
processor remaining the single source of truth (no duplicate counting).

- Add dependency `io.micrometer:micrometer-registry-prometheus` (runtime) to `control/pom.xml`.
- New `stream/LiveMetrics` (`@Component`, implements `MeterBinder` OR registers in a
  `@PostConstruct` given the `MeterRegistry` + `LivePbsProcessor` + topology):
  - **FunctionCounters** (monotonic, read from snapshot — no double bookkeeping):
    `pbs.releases.total`, `pbs.colour.changes.total`, `pbs.rejected.total`, `pbs.duplicates.total`,
    `pbs.out.of.order.total`.
  - **Gauges** (current state): `pbs.buffered`, `pbs.assembly.out`, `pbs.blocked.lanes`
    (= `snapshot().blockedLanes.size`), and per-lane `pbs.lane.occupancy{lane="L1"}` for each lane id
    in the topology.
  - Each meter's value lambda reads `processor.snapshot()` (it is `@Synchronized`, cheap). Read the
    snapshot once per meter call; do not cache across scrapes.
- Document in KDoc: cumulative counts surfaced as FunctionCounters (Prometheus `_total` convention),
  current state as gauges; values are observability of a **synthetic** stream.

**Tests (`stream/LiveMetricsTest.kt`, pure — use `SimpleMeterRegistry`):**
- Bind metrics to a `LivePbsProcessor`, drive a few events through the processor, then assert the
  registered meters report the expected values (e.g. after 2 arrivals + 1 tick: `pbs.releases.total`
  == 1, a lane occupancy gauge reflects the remaining body).
- Assert per-lane occupancy gauges exist for L1,L2,L3.
- Assert `pbs.blocked.lanes` reflects a `LaneBlocked` event.

**Commit:** `feat(s2): Micrometer Prometheus live PBS KPIs (FunctionCounters + gauges)`

---

## Task 4 — EmbeddedKafka end-to-end + replay/idempotency integration test

**Spec:** Prove the full path produce→consume→process→metrics works on an in-memory broker, and that
replaying the same event log is idempotent (state + counters identical, duplicates counted).

- New `stream/PbsStreamIntegrationTest.kt`:
  - `@SpringBootTest` + `@EmbeddedKafka(partitions = 1, topics = ["pbs-events"])`, with
    `@TestPropertySource`/`@DirtiesContext` wiring `spring.kafka.bootstrap-servers` to
    `\${spring.embedded.kafka.brokers}` and a unique consumer group. **Must NOT require Ditto/Docker.**
    If the Ditto `DittoProjector` `@PostConstruct` bootstrap interferes, isolate via a test slice or
    a mock/`@MockBean` so this test runs broker-only (document the choice).
  - Produce a deterministic event sequence (a handful of `BodyArrived` of varying colours + some
    `ReleaseTick` + one `LaneBlocked`/`LaneUnblocked`) to the topic using a `KafkaTemplate<String,
    PbsEvent>` (JsonSerializer with the polymorphic mapper) OR raw JSON strings matching the wire
    schema (prefer raw JSON to also exercise Task 1's deserialization on the wire).
  - Await consumption (Awaitility or poll the processor `snapshot()` with a timeout) then assert the
    snapshot/metrics match expected (releases, colourChanges, assignment counts).
  - **Replay test:** send the exact same events (same `eventId`s) a second time; assert
    `snapshot().duplicates` increased by the replayed count and the substantive state (laneOccupants,
    releases, colourChanges) is **unchanged** from after the first pass — proving replay-safety.
  - **Malformed record:** produce one malformed JSON; assert the partition keeps flowing (subsequent
    valid events still processed) — i.e. ErrorHandlingDeserializer skips it, no poison.

**Commit:** `test(s2): EmbeddedKafka end-to-end + replay/idempotency + poison-record integration test`

---

## Done criteria
- `mvn test` from `control/`: all green (existing 142 + new), excluding the pre-existing
  Ditto-requires-Docker tests which remain as-is.
- `/actuator/prometheus` (when run with a broker) exposes the `pbs.*` meters.
- Replay test demonstrably proves idempotency.
- Update memory `resequence-twin-build-state.md` §C: mark S2 ✅ with commit SHAs + test count.
