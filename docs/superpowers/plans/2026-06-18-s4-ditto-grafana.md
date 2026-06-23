# S4 — Ditto Live Twin Projection + Grafana Dashboard

**Branch:** `build/scaffold` (continue). **Baseline:** `38f0adb` (S3 done).
**Goal:** Project the live PBS state (from the S2 `LivePbsProcessor`) into Eclipse Ditto as a digital-twin
Thing, and surface the `pbs.*` Prometheus metrics in a provisioned Grafana dashboard. Together with S2
(consumer+metrics) and S3 (producer), this completes the live streaming twin: **producer → Kafka →
consumer/state-machine → (metrics → Prometheus → Grafana) and (state → Ditto twin).**

## Honesty / verification boundary (agreed with user)
I build the integration CODE + config; the **Docker stack run + Grafana visual + Ditto round-trip are the
USER's verification** (the dev PC has no GPU/Docker-render guarantees and these need the live stack).
Everything that can be unit-tested WITHOUT Docker must be (pure mapping fns, mocked HTTP). Live round-trips
go in `@SpringBootTest`/integration tests that require Docker — consistent with the existing
`TwinStateQueryTest`/`IngestProjectionTest` which already need Ditto.

## Context (read before any task)

- `control/` is 100% Kotlin / Spring Boot / Maven. The shared `@Bean livePbsProcessor()` (in
  `stream/PbsStreamConfig.kt`) is the live state. `LivePbsProcessor.snapshot(): LiveSnapshot` exposes:
  `laneOccupants: Map<String,List<String>>`, `assemblyOutSize`, `releases`, `colourChanges`, `rejected`,
  `duplicates`, `outOfOrder`, `buffered`, `blockedLanes: Set<String>`. (`@Synchronized`, cheap to call.)
- `ingest/DittoProjector.kt` already exists (OHT-era): a `@Component` with a working Ditto HTTP client —
  `bootstrapPolicy()`, `dittoSend(method,url,body)`, `dittoRequest(...)` with header
  `x-ditto-pre-authenticated: nginx:ditto`, base url `ditto.base-url` (default `http://localhost:18080`),
  policy id `rtw:default-policy`, `thingsUrl(id) = "$baseUrl/api/2/things/$id"`. **Reuse this HTTP/auth/
  policy approach** (extract/share a small helper or mirror the pattern — do NOT duplicate the whole class;
  prefer a thin shared client or calling its public helpers). Its `@PostConstruct` bootstrap CATCHES
  connection failure and only logs — so the app boots even with Ditto down.
- `application.properties`: `ditto.base-url=http://localhost:18080`, `ditto.auth-header=nginx:ditto`,
  `server.port=8081`, actuator exposes `health,info,prometheus`, `pbs.lanes=L1:10,L2:10,L3:10`.
- `PbsEventListener` (S2) is currently `processor.process(event)` only — KEEP it that way. Do NOT add
  blocking Ditto calls on the Kafka consumer thread (would couple twin latency to ingestion + risk
  consumer lag). Twin projection must run on a SEPARATE schedule (decoupled — this is the sound design
  and a useful demo signal).
- **Infra (`docker-compose.yml` + `prometheus/prometheus.yml`):** Ditto (host 18080), Kafka (19092),
  Prometheus (9090, scrapes prometheus/ditto/kafka — **NO control-app job yet**), Grafana (host 3001,
  anonymous Viewer enabled, **NO datasource/dashboard provisioning yet**). **The `control` app runs on the
  HOST at :8081, NOT in a container** — so Prometheus (in Docker) must scrape `host.docker.internal:8081`.
- Lane ids come from the topology (`L1,L2,L3`). `PbsState.laneIds()` exists; from a snapshot use
  `snapshot().laneOccupants.keys`.
- **Honesty constraints (keep):** synthetic PoC; advisory/observability only; the twin reflects a
  synthetic stream, not a real plant.

## TDD: RED → GREEN → refactor. `mvn test` from `control/`. Commit per task.

---

## Task 1 — PBS → Ditto live twin projection (scheduled, decoupled)

**Spec:** A scheduled component that periodically reads `LivePbsProcessor.snapshot()` and projects it to a
Ditto twin Thing, decoupled from the Kafka consumer thread, graceful when Ditto is unreachable.

- New `ingest/PbsTwinProjector.kt` (`@Component`):
  - A **pure mapping function** `buildTwinPayload(snapshot: LiveSnapshot): Map<String, Any>` (or a typed
    DTO → JSON) that turns a snapshot into a Ditto Thing body: a single Thing `rtw:pbs-line` with
    `policyId = rtw:default-policy` and `features`:
    - `lanes`: properties = per-lane occupancy (`{ "L1": <count>, "L2": ..., "L3": ... }`) — derive counts
      from `laneOccupants[id].size`; also expose `blocked` = list of blocked lane ids.
    - `kpi`: properties = `releases`, `colourChanges`, `assemblyOut` (=assemblyOutSize), `buffered`,
      `rejected`, `duplicates`, `outOfOrder`.
    - `status`: properties = `synthetic: true`, `updatedAt` (a passed-in counter/epoch — do NOT call
      wall-clock inside the pure fn; accept it as a param so the fn stays deterministic/testable).
    This pure fn is the unit-test target.
  - `project(snapshot, updatedAt)`: builds the payload and PUTs it to Ditto (`PUT
    $baseUrl/api/2/things/rtw:pbs-line`). Reuse the existing `DittoProjector` HTTP/auth/policy machinery
    (inject `DittoProjector` and add a small public method there, OR extract a shared `DittoClient` helper —
    pick the cleaner one with the least duplication; ensure the shared policy is bootstrapped). PUT is
    idempotent. Catch and log any IOException/connection error — a Ditto outage must NOT crash the
    scheduler or the app.
  - A `@Scheduled(fixedDelayString = "\${pbs.twin.project-interval-ms:1000}")` method `tick()` that, when
    enabled, calls `project(processor.snapshot(), <monotonic counter>)`. Guard with property
    `pbs.twin.enabled` (default `true`): if disabled, `tick()` early-returns. Enable scheduling app-wide
    via `@EnableScheduling` (on the projector with `@Configuration`, or a small config class) — ensure
    this does NOT break `contextLoads` or other tests (set `pbs.twin.enabled=false` in test properties so
    the scheduler does not hit a non-existent Ditto during unit/CI runs).
- Wire the lane id ordering deterministically (sorted, or topology order) so the twin payload is stable.

**Tests:**
- `ingest/PbsTwinProjectorTest.kt` (PURE, no Spring/Ditto): drive a `LivePbsProcessor` through events,
  call `buildTwinPayload(snapshot, updatedAt=...)`, assert the payload structure: `policyId`, the three
  features, per-lane occupancy counts match the snapshot, `kpi` values match, `synthetic=true`, blocked
  lanes reflected. Assert determinism (same snapshot+updatedAt → identical payload).
- Confirm `mvn test` green and `contextLoads` still passes WITHOUT Ditto (scheduler disabled in tests, or
  the catch path proven). Do NOT add a test that requires a live Ditto to the default green suite; if you
  add a round-trip test, gate it like the existing `@SpringBootTest` Ditto tests (which already fail/skip
  without Docker) and document it.

**Commit:** `feat(s4): scheduled PBS→Ditto live twin projection (decoupled, graceful)`

---

## Task 2 — Observability infra: Prometheus control scrape + Grafana provisioning + dashboard + runbook

**Spec:** Make the `pbs.*` metrics visible end-to-end through the Docker stack, with provisioned Grafana,
and document the manual run/verify steps. (All config files; structural correctness is automatable, full
behavior is the user's Docker verify.)

- `prometheus/prometheus.yml`: add a scrape job for the host-run control app:
  ```yaml
  - job_name: 'resequence-twin-control'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8081']
  ```
  (Document that `host.docker.internal` lets the Dockerized Prometheus reach the control app running on the
  host. On Linux this needs `extra_hosts` — see compose below.)
- `docker-compose.yml`:
  - Prometheus service: add `extra_hosts: ["host.docker.internal:host-gateway"]` so the host alias resolves
    on Linux too (harmless on Docker Desktop Win/Mac).
  - Grafana service: mount provisioning + dashboards (read-only):
    `- ./grafana/provisioning:/etc/grafana/provisioning:ro`
    `- ./grafana/dashboards:/var/lib/grafana/dashboards:ro`
- Grafana provisioning:
  - `grafana/provisioning/datasources/prometheus.yml`: a Prometheus datasource, `url:
    http://prometheus:9090`, `isDefault: true`, `access: proxy`.
  - `grafana/provisioning/dashboards/dashboards.yml`: a file provider pointing at
    `/var/lib/grafana/dashboards`.
  - `grafana/dashboards/pbs-line.json`: a dashboard (schema valid for Grafana 11) titled "PBS Live Twin"
    with panels using the S2 meter names (Prometheus-translated: dots→underscores):
    - Release throughput: `rate(pbs_releases_total[1m])`
    - Colour-change rate: `rate(pbs_colour_changes_total[1m])` (the KPI the whole PoC optimizes)
    - Assembly-out cumulative / rate: `pbs_assembly_out_total` / `rate(pbs_assembly_out_total[1m])`
    - Per-lane occupancy (time series, by `lane` label): `pbs_lane_occupancy`
    - Blocked lanes: `pbs_blocked_lanes`
    - Buffered (deferred) bodies: `pbs_buffered`
    - Resilience counters: `pbs_duplicates_total`, `pbs_out_of_order_total`, `pbs_rejected_total`
    Include the Prometheus datasource reference (templated `${DS_PROMETHEUS}` or the provisioned uid) so it
    loads under the provisioned datasource.
- README + a runbook: add a "Live streaming twin (S2–S4)" section documenting the END-TO-END manual run:
  1. `docker compose up -d` (wait for Ditto/Kafka/Prometheus/Grafana healthy).
  2. Run control on host: `mvn -f control spring-boot:run` (or the jar) → :8081.
  3. Run the producer: from `sim/`, `python -m pbs_stream_producer --bootstrap localhost:19092 --topic
     pbs-events --bodies 100 --rate 5 [--inject lane-block ...]`.
  4. Observe: Grafana http://localhost:3001 "PBS Live Twin" dashboard (`pbs_*` advancing); Ditto twin
     `curl -H "x-ditto-pre-authenticated: nginx:ditto" http://localhost:18080/api/2/things/rtw:pbs-line`.
  Clearly LABEL this as the user-run manual verification (synthetic PoC).

**Tests / validation (automatable parts):**
- Validate the JSON/YAML files PARSE (e.g. a tiny test or a `python -c "import json; json.load(...)"` /
  `yaml.safe_load` check; or just ensure they are well-formed — the implementer should actually parse them
  to prove validity, since a broken dashboard JSON silently fails to load in Grafana).
- Confirm the dashboard's PromQL metric names EXACTLY match the S2 meter names (cross-check against
  `LiveMetrics.kt`): `pbs_releases_total`, `pbs_colour_changes_total`, `pbs_assembly_out_total`,
  `pbs_rejected_total`, `pbs_duplicates_total`, `pbs_out_of_order_total`, `pbs_buffered`,
  `pbs_blocked_lanes`, `pbs_lane_occupancy`. A wrong name = empty panel.

**Commit:** `feat(s4): Prometheus control scrape + provisioned Grafana PBS dashboard + runbook`

---

## Done criteria
- `mvn test` from `control/` green (existing 184 + new T1 tests); `contextLoads` green without Docker.
- All S4 config files parse (JSON/YAML valid); dashboard PromQL names match `LiveMetrics` exactly.
- README has the end-to-end manual runbook, clearly labeled as user-run / synthetic.
- Update memory `resequence-twin-build-state.md` §C: mark S4 ✅ (code+config built; live verify = user/Docker),
  and note the streaming arc (S1–S4) complete.
- This is the last streaming slice → afterward, `superpowers:finishing-a-development-branch` (git
  integration decision is the user's: single `build/scaffold`, no remote).
