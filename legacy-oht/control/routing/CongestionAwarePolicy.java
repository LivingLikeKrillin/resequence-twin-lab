package com.resequencetwin.control.routing;

import com.resequencetwin.control.graph.CongestionDetector;
import com.resequencetwin.control.graph.CongestionDetector.ResourceUtilization;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

import java.util.List;
import java.util.Map;

/**
 * Congestion-aware dynamic link-weight routing policy — ★ the project's differentiator.
 *
 * <h2>How it works (DLWC-style heuristic)</h2>
 * <ol>
 *   <li>Start from the same base graph as {@link StaticPolicy}.</li>
 *   <li>For each solve, <em>clone</em> the graph so the base weights are unchanged.</li>
 *   <li>Inspect live twin state ({@link ResourceUtilization}):
 *     <ul>
 *       <li>Any resource whose utilization ratio exceeds the threshold
 *           ({@code DEFAULT_UTILIZATION_THRESHOLD = 0.80}) is a hotspot.</li>
 *       <li>All edges <em>entering</em> that hotspot resource get their weight
 *           multiplied by {@code congestionPenaltyMultiplier} (default 20×).</li>
 *     </ul>
 *   </li>
 *   <li>Run Dijkstra on the re-weighted graph → routes away from congested segments.</li>
 * </ol>
 *
 * <p>This is a Dynamic Link-Weight Control (DLWC) heuristic — not RL.
 * See spec §5, §9 ("RL = simulation-only, claim clearly") and ADR-001.
 *
 * <p><b>Solver:</b> JGraphT {@link DijkstraShortestPath} (PoC). OR-Tools / cuOpt is
 * the production solver; see ADR-001. The interface contract is identical.
 *
 * <p>Thread safety: each {@link #route} call works on a per-call graph copy;
 * the base graph (built once at construction and stored as {@code baseGraph}) is
 * read-only after construction. Concurrent calls to {@link #route} are safe.
 */
public final class CongestionAwarePolicy implements RoutingPolicy {

    /** Utilization fraction above which a resource is penalised. */
    static final double DEFAULT_UTILIZATION_THRESHOLD = 0.80;

    /**
     * Factor by which incoming edge weights are multiplied for congested resources.
     *
     * <p><b>Soft penalty, not hard exclusion.</b> This multiplier is applied to the
     * incoming edges of any hotspot resource to make that path more expensive in
     * Dijkstra's cost model. Rerouting occurs only when an alternative path exists
     * whose total cost is lower than the penalised path's cost. If no cheaper
     * alternative exists — e.g. the penalised link is the only path — the policy
     * still routes through the hotspot (graceful degradation; this is expected and
     * correct behaviour).
     *
     * <p>See ADR-001 §"Solver" for the production path: hard and soft capacity
     * constraints are enforced by OR-Tools / cuOpt, not by this heuristic multiplier.
     */
    static final double DEFAULT_PENALTY_MULTIPLIER = 20.0;

    /** Pre-built base graph; cloned per-call so base weights are never mutated. */
    private final DefaultDirectedWeightedGraph<String, DefaultWeightedEdge> baseGraph;
    private final double congestionPenaltyMultiplier;

    private CongestionAwarePolicy(
        List<String> baseNodes,
        List<Map.Entry<Map.Entry<String, String>, Double>> baseEdges,
        double congestionPenaltyMultiplier
    ) {
        // Build the base graph once at construction time; per-call work is clone + reweight + solve
        this.baseGraph = StaticPolicy.buildGraph(List.copyOf(baseNodes), List.copyOf(baseEdges));
        this.congestionPenaltyMultiplier = congestionPenaltyMultiplier;
    }

    // -------------------------------------------------------------------------
    // RoutingPolicy
    // -------------------------------------------------------------------------

    /**
     * Route from {@code from} to {@code to} with live-weight adjustment.
     *
     * <p>The graph is re-weighted on every call (per-call clone) so concurrent
     * calls do not interfere. The base graph is never mutated.
     *
     * @return ordered resource IDs (inclusive), or empty list if no path exists
     */
    @Override
    public List<String> route(List<ResourceUtilization> utilizations, String from, String to) {
        // 1. Clone the pre-built base graph (same per-call cost as StaticPolicy: clone + reweight + solve)
        var graph = StaticPolicy.copyGraph(baseGraph);

        // 2. Detect hotspots from live utilization state (hand-built threshold logic)
        List<CongestionDetector.Hotspot> hotspots =
            CongestionDetector.utilizationHotspots(utilizations, DEFAULT_UTILIZATION_THRESHOLD);

        // 3. Penalise all edges entering a hotspot resource (DLWC-style)
        for (CongestionDetector.Hotspot hotspot : hotspots) {
            String congested = hotspot.resourceId();
            if (!graph.containsVertex(congested)) continue;

            // Penalise every incoming edge to the congested resource
            for (DefaultWeightedEdge edge : graph.incomingEdgesOf(congested)) {
                double current = graph.getEdgeWeight(edge);
                graph.setEdgeWeight(edge, current * congestionPenaltyMultiplier);
            }
        }

        // 4. Solve shortest path on the re-weighted graph
        // Guard: Dijkstra throws IllegalArgumentException if either endpoint is not in the graph
        if (!graph.containsVertex(from) || !graph.containsVertex(to)) {
            return List.of(); // failure case 1: unknown vertex
        }
        var path = new DijkstraShortestPath<>(graph).getPath(from, to);
        if (path == null) {
            return List.of(); // failure case 2: unreachable target (vertices exist, no directed path)
        }
        return List.copyOf(path.getVertexList());
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link CongestionAwarePolicy}.
     *
     * <p>Usage:
     * <pre>{@code
     * CongestionAwarePolicy policy = CongestionAwarePolicy.builder()
     *     .nodes(List.of("A", "B", "segX", "segAlt"))
     *     .weightedEdges(List.of(
     *         Map.entry(Map.entry("A", "segX"),   1.0),
     *         Map.entry(Map.entry("segX",   "B"), 1.0),
     *         Map.entry(Map.entry("A", "segAlt"), 3.0),
     *         Map.entry(Map.entry("segAlt", "B"), 3.0)
     *     ))
     *     .congestionPenaltyMultiplier(20.0)
     *     .build();
     * }</pre>
     */
    public static final class Builder {

        private List<String> nodes = List.of();
        private List<Map.Entry<Map.Entry<String, String>, Double>> weightedEdges = List.of();
        private double penaltyMultiplier = DEFAULT_PENALTY_MULTIPLIER;

        private Builder() {}

        public Builder nodes(List<String> nodes) {
            this.nodes = List.copyOf(nodes);
            return this;
        }

        public Builder weightedEdges(
            List<Map.Entry<Map.Entry<String, String>, Double>> weightedEdges
        ) {
            this.weightedEdges = List.copyOf(weightedEdges);
            return this;
        }

        public Builder congestionPenaltyMultiplier(double multiplier) {
            this.penaltyMultiplier = multiplier;
            return this;
        }

        public CongestionAwarePolicy build() {
            return new CongestionAwarePolicy(nodes, weightedEdges, penaltyMultiplier);
        }
    }
}
