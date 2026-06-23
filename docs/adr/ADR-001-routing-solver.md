# ADR-001: Routing Solver Choice — JGraphT Dijkstra (PoC) vs OR-Tools / cuOpt (Production)

> **⚠️ SUPERSEDED / LEGACY (2026-06-17).** This ADR belongs to the original **semiconductor-OHT
> routing** scenario, which was deliberately re-anchored to the automotive **PBS resequencing**
> scenario in a later spec revision. PBS uses FIFO lane
> buffers, not a routing mesh — there is no shortest-path solver in the current build, and the
> OHT routing/deadlock code is quarantined under `legacy-oht/`. This ADR is retained for history
> only. The active solver decision for the PBS scenario is **ADR-002** (sequencing); the advisory
> forecast is **ADR-003**. The agent's RAG corpus intentionally excludes this ADR.

**Date:** 2026-06-16
**Status:** Superseded (legacy OHT scenario — see rev3 re-anchor)
**Context:** Chunk 2b requires a shortest-path solver for the static and congestion-aware dynamic routing policies.

## Decision

The PoC implementation uses **JGraphT `DijkstraShortestPath`** (already a project dependency, no native
libs, zero friction on Windows/CI) rather than Google OR-Tools Java.

## Rationale

OR-Tools Java (`com.google.ortools:ortools-java`) ships platform-specific native libraries bundled inside
the JAR. On Windows the setup requires either a matching pre-built artifact or a local CMake build of
OR-Tools — both add non-trivial friction and risk CI breakage. The *differentiator of this project is the
congestion-aware dynamic link-weight control policy*, not the underlying shortest-path algorithm. Both
static and dynamic policies use **the same solver**; only the weight function differs. Shortest-path is
solver-agnostic here — swapping Dijkstra for OR-Tools changes performance characteristics at scale but
not the correctness of the benchmark.

## Production / Stack-Fit Intent

Production / scale deployment SHOULD use:

- **Google OR-Tools** (`com.google.ortools:ortools-java`, Apache-2.0) — CPU-based VRP/shortest-path
  solver; the standard choice for the Java control service.
- **NVIDIA cuOpt** (Apache-2.0, requires GPU) — fleet routing solver noted in SK hynix fab-asset routing
  publications; a toggle behind the `RoutingPolicy` interface enables cuOpt when a GPU is present
  (Chunk 4 stretch goal).

The `RoutingPolicy` interface (`route(state, from, to) -> List<String>`) abstracts the solver choice
so the benchmark (Chunk 2c) and the agent (Chunk 3) are agnostic to which solver is active.

## Consequences

- No native-lib setup pain in the PoC; `mvn test` runs cleanly on any JVM 21+ host.
- Adding OR-Tools in the future is a one-line pom.xml change + swap `DijkstraShortestPath` call inside
  `StaticPolicy` / `CongestionAwarePolicy` — the interface and tests do not change.
- The benchmark result (static vs dynamic KPI delta) is valid regardless of solver; the weight-function
  difference is what drives the separation.
