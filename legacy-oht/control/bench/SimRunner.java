package com.resequencetwin.control.bench;

import com.resequencetwin.control.graph.CongestionDetector;
import com.resequencetwin.control.graph.WaitForGraph;
import com.resequencetwin.control.routing.CongestionAwarePolicy;
import com.resequencetwin.control.routing.RoutingPolicy;
import com.resequencetwin.control.routing.StaticPolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discrete-tick simulation runner.
 *
 * <p>Drives a sequence of {@link LoadGenerator.MoveArrival} events through a track graph
 * under a given {@link RoutingPolicy}. Each simulation tick:
 * <ol>
 *   <li>Snapshot start-of-tick occupancy (used for all routing and advance decisions)</li>
 *   <li>Process new arrivals (route them using start-of-tick state)</li>
 *   <li>Compute advances: each in-transit mover decides whether it CAN advance (based on
 *       start-of-tick occupancy), collecting proposed moves</li>
 *   <li>Apply advances atomically (movers that can advance do so simultaneously)</li>
 *   <li>Admit waiting arrivals to newly-freed SOURCE slots (end-of-tick)</li>
 *   <li>Update occupancy from applied moves</li>
 *   <li>Detect deadlocks (wait-for cycle via WaitForGraph)</li>
 *   <li>Record KPIs</li>
 * </ol>
 *
 * <h2>Occupancy model — atomic advance</h2>
 * All advance decisions are made against the START-OF-TICK occupancy snapshot.
 * A unit can advance to the next resource only if that resource has capacity at
 * the start of the tick (i.e., no other unit moved there yet this tick).
 * This prevents cascading pipeline drain in a single tick.
 *
 * <p>Capacity constraint per segment = {@link BenchmarkScenario#nodeCapacities()}.
 * When a segment is at capacity, units behind it WAIT (blocked), accumulating
 * flow time. This congestion is what the dynamic policy detects and avoids.
 *
 * <h2>Determinism</h2>
 * No randomness inside SimRunner itself. The seeded {@link LoadGenerator} controls all
 * non-determinism. Same load sequence + same policy → identical KPIs.
 */
public final class SimRunner {

    // -------------------------------------------------------------------------
    // Internal move tracking
    // -------------------------------------------------------------------------

    /** State of an in-flight move. */
    private static class InFlightMove {
        final String unitId;
        final List<String> route;      // full planned route (resource IDs)
        int routeIndex;                 // current position in route (0 = at route[0])
        final int arrivalTick;
        boolean completed;

        InFlightMove(String unitId, List<String> route, int arrivalTick) {
            this.unitId = unitId;
            this.route = route;
            this.routeIndex = 0;
            this.arrivalTick = arrivalTick;
            this.completed = false;
        }

        String currentResource() {
            return route.get(routeIndex);
        }

        boolean atDestination() {
            return routeIndex == route.size() - 1;
        }

        boolean canAdvance() {
            return routeIndex < route.size() - 1;
        }

        String nextResource() {
            return route.get(routeIndex + 1);
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final BenchmarkScenario scenario;
    private final RoutingPolicy policy;

    public SimRunner(BenchmarkScenario scenario) {
        this.scenario = scenario;
        this.policy = buildPolicy(scenario);
    }

    // -------------------------------------------------------------------------
    // Run
    // -------------------------------------------------------------------------

    /**
     * Run the simulation for all provided arrivals.
     *
     * @param arrivals   ordered arrival events from {@link LoadGenerator}
     * @param totalTicks total number of simulation ticks
     * @return collected KPIs
     */
    public KpiCollector.Snapshot run(List<LoadGenerator.MoveArrival> arrivals, int totalTicks) {
        KpiCollector kpis = new KpiCollector();

        // Occupancy map: resourceId → current occupant count
        // This is the AUTHORITATIVE occupancy, updated atomically at end of each tick.
        Map<String, Integer> occupancy = new LinkedHashMap<>();
        for (String node : scenario.nodeCapacities().keySet()) {
            occupancy.put(node, 0);
        }

        // Index arrivals by tick for O(1) lookup
        Map<Integer, List<LoadGenerator.MoveArrival>> arrivalsByTick = new HashMap<>();
        for (LoadGenerator.MoveArrival a : arrivals) {
            arrivalsByTick.computeIfAbsent(a.tick(), k -> new ArrayList<>()).add(a);
        }

        // Active moves (in-transit, occupying resources)
        List<InFlightMove> activeMovers = new ArrayList<>();

        // Pending queue: units waiting to enter SOURCE (FIFO)
        Deque<LoadGenerator.MoveArrival> waitingQueue = new ArrayDeque<>();

        for (int tick = 0; tick < totalTicks; tick++) {

            // ------------------------------------------------------------------
            // A. Accept new arrivals into waiting queue (always, no capacity check yet)
            // ------------------------------------------------------------------
            List<LoadGenerator.MoveArrival> tickArrivals = arrivalsByTick.getOrDefault(tick, List.of());
            waitingQueue.addAll(tickArrivals);

            // ------------------------------------------------------------------
            // B. Snapshot start-of-tick occupancy (used for ALL routing + advance decisions)
            // ------------------------------------------------------------------
            Map<String, Integer> tickStartOccupancy = new LinkedHashMap<>(occupancy);

            // ------------------------------------------------------------------
            // C. Route and plan admission for waiting units using start-of-tick state
            //    (We only actually admit them AFTER computing advances, to be atomic)
            // ------------------------------------------------------------------
            List<CongestionDetector.ResourceUtilization> utils = buildUtilizations(tickStartOccupancy);

            List<InFlightMove> toAdmit = new ArrayList<>();
            List<LoadGenerator.MoveArrival> stillWaiting = new ArrayList<>();

            // Track "slots claimed" for SOURCE in this admission pass to avoid over-admission
            int srcSlotsUsed = tickStartOccupancy.getOrDefault(scenario.from(), 0);
            int srcCap = scenario.nodeCapacities().getOrDefault(scenario.from(), Integer.MAX_VALUE);

            for (LoadGenerator.MoveArrival arrival : waitingQueue) {
                if (srcSlotsUsed < srcCap) {
                    List<String> route = policy.route(utils, arrival.from(), arrival.to());
                    if (route.isEmpty()) {
                        // No path available — silently drop (this move can never complete)
                        continue;
                    }
                    toAdmit.add(new InFlightMove(arrival.unitId(), route, tick));
                    srcSlotsUsed++;
                } else {
                    stillWaiting.add(arrival);
                }
            }
            waitingQueue.clear();
            waitingQueue.addAll(stillWaiting);

            // ------------------------------------------------------------------
            // D. Compute all moves (advances + completions) against START-OF-TICK snapshot
            //    Claimed slots prevent two movers from both moving into the same resource
            // ------------------------------------------------------------------

            // claimed[resource] = how many units are moving INTO this resource this tick
            // LinkedHashMap: explicit insertion-order iteration for determinism guarantee
            Map<String, Integer> incomingClaims = new LinkedHashMap<>();

            // Also add "virtual" claims for newly admitted movers at SOURCE
            // (they occupy SOURCE at start of next tick, but we need to count their
            //  src occupancy to avoid double-admission)

            // Decide advances for each active mover
            List<InFlightMove> toAdvance = new ArrayList<>();   // will move forward
            List<InFlightMove> toComplete = new ArrayList<>();  // will exit at SINK
            List<InFlightMove> blocked = new ArrayList<>();     // cannot advance

            for (InFlightMove mover : activeMovers) {
                if (mover.atDestination()) {
                    // Unit is already at destination — mark for completion
                    toComplete.add(mover);
                } else {
                    String next = mover.nextResource();
                    int nextCap = scenario.nodeCapacities().getOrDefault(next, Integer.MAX_VALUE);
                    int nextCurrentOcc = tickStartOccupancy.getOrDefault(next, 0);
                    int nextClaimedSoFar = incomingClaims.getOrDefault(next, 0);
                    if (nextCurrentOcc + nextClaimedSoFar < nextCap) {
                        // Claim the slot: this mover will advance
                        incomingClaims.merge(next, 1, Integer::sum);
                        toAdvance.add(mover);
                    } else {
                        blocked.add(mover);
                    }
                }
            }

            // ------------------------------------------------------------------
            // E. Apply completions + advances atomically; then admit new units
            // ------------------------------------------------------------------

            List<InFlightMove> completed = new ArrayList<>();
            for (InFlightMove mover : toComplete) {
                occupancy.merge(mover.currentResource(), -1, Integer::sum);
                mover.completed = true;
                kpis.recordCompletion(tick - mover.arrivalTick);
                completed.add(mover);
            }
            activeMovers.removeAll(completed);

            for (InFlightMove mover : toAdvance) {
                occupancy.merge(mover.currentResource(), -1, Integer::sum);
                mover.routeIndex++;
                occupancy.merge(mover.currentResource(), 1, Integer::sum);
            }

            // Admit newly routed units to SOURCE
            for (InFlightMove mover : toAdmit) {
                occupancy.merge(mover.currentResource(), 1, Integer::sum);
                activeMovers.add(mover);
            }

            // ------------------------------------------------------------------
            // F. Detect deadlocks (on end-of-tick state)
            // ------------------------------------------------------------------

            // Rebuild occupancy-to-unit map for deadlock detection
            // LinkedHashMap: explicit insertion-order iteration for determinism guarantee
            Map<String, String> resourceToOccupant = new LinkedHashMap<>();
            for (InFlightMove m : activeMovers) {
                resourceToOccupant.putIfAbsent(m.currentResource(), m.unitId);
            }

            List<WaitForGraph.BlockedRelation> relations = new ArrayList<>();
            for (InFlightMove mover : blocked) {
                String next = mover.nextResource();
                String holder = resourceToOccupant.get(next);
                if (holder != null && !holder.equals(mover.unitId)) {
                    relations.add(WaitForGraph.blocked(mover.unitId, next, holder));
                }
            }

            boolean hadDeadlock = false;
            if (!relations.isEmpty()) {
                WaitForGraph wfg = new WaitForGraph(relations);
                hadDeadlock = !wfg.detectDeadlocks().isEmpty();
            }

            // ------------------------------------------------------------------
            // G. Record KPIs
            // ------------------------------------------------------------------
            kpis.recordTick(hadDeadlock);
            kpis.recordUtilizationSnapshot(computeAverageUtilization(occupancy));
        }

        return kpis.snapshot();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<CongestionDetector.ResourceUtilization> buildUtilizations(
        Map<String, Integer> occupancy
    ) {
        List<CongestionDetector.ResourceUtilization> utils = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scenario.nodeCapacities().entrySet()) {
            String id = entry.getKey();
            int cap = entry.getValue();
            int occ = occupancy.getOrDefault(id, 0);
            utils.add(new CongestionDetector.ResourceUtilization(id, occ, cap));
        }
        return utils;
    }

    private double computeAverageUtilization(Map<String, Integer> occupancy) {
        if (scenario.nodeCapacities().isEmpty()) return 0.0;
        double sum = 0.0;
        for (Map.Entry<String, Integer> entry : scenario.nodeCapacities().entrySet()) {
            int cap = entry.getValue();
            int occ = occupancy.getOrDefault(entry.getKey(), 0);
            sum += cap > 0 ? (double) occ / cap : 0.0;
        }
        return sum / scenario.nodeCapacities().size();
    }

    private static RoutingPolicy buildPolicy(BenchmarkScenario scenario) {
        List<String> nodes = new ArrayList<>(scenario.nodeCapacities().keySet());
        List<Map.Entry<Map.Entry<String, String>, Double>> edges = new ArrayList<>();
        for (BenchmarkScenario.EdgeSpec e : scenario.edges()) {
            edges.add(Map.entry(Map.entry(e.src(), e.tgt()), e.weight()));
        }

        if (scenario.congestionPenaltyEnabled()) {
            return CongestionAwarePolicy.builder()
                .nodes(nodes)
                .weightedEdges(edges)
                .congestionPenaltyMultiplier(20.0)
                .build();
        } else {
            return StaticPolicy.builder()
                .nodes(nodes)
                .weightedEdges(edges)
                .build();
        }
    }
}
