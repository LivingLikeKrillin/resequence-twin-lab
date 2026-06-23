package com.resequencetwin.control.routing;

import com.resequencetwin.control.graph.CongestionDetector.ResourceUtilization;
import org.jgrapht.Graph;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static routing policy — fixed link weights, shortest path via JGraphT Dijkstra.
 *
 * <p>This is the baseline for the static-vs-dynamic benchmark (Chunk 2c).
 * It ignores live twin state entirely; weights are set once at construction and
 * never updated. The congestion-aware policy ({@link CongestionAwarePolicy}) uses
 * the same solver but re-weights links dynamically before each solve.
 *
 * <p><b>Solver:</b> JGraphT {@link DijkstraShortestPath} (PoC). See ADR-001 for the
 * rationale; OR-Tools / cuOpt are the production / stack-fit solvers — swapping is a
 * one-line change behind this interface.
 *
 * <p>Thread safety: instances are stateless after construction (graph is read-only
 * after {@link Builder#build()}); concurrent calls to {@link #route} are safe.
 */
public final class StaticPolicy implements RoutingPolicy {

    /** The immutable weighted graph. */
    private final Graph<String, DefaultWeightedEdge> graph;

    private StaticPolicy(Graph<String, DefaultWeightedEdge> graph) {
        this.graph = graph;
    }

    // -------------------------------------------------------------------------
    // RoutingPolicy
    // -------------------------------------------------------------------------

    /**
     * Returns the shortest path from {@code from} to {@code to} using fixed weights.
     * The {@code utilizations} argument is accepted but intentionally ignored —
     * static policy does not react to live state.
     *
     * @return ordered resource IDs (inclusive), or empty list if no path exists
     */
    @Override
    public List<String> route(List<ResourceUtilization> utilizations, String from, String to) {
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
     * Builder for {@link StaticPolicy}.
     *
     * <p>Usage:
     * <pre>{@code
     * StaticPolicy policy = StaticPolicy.builder()
     *     .nodes(List.of("A", "B", "segX"))
     *     .weightedEdges(List.of(
     *         Map.entry(Map.entry("A", "segX"), 1.0),
     *         Map.entry(Map.entry("segX", "B"), 1.0)
     *     ))
     *     .build();
     * }</pre>
     */
    public static final class Builder {

        private List<String> nodes = List.of();
        /** Each entry: key = (source, target), value = weight. */
        private List<Map.Entry<Map.Entry<String, String>, Double>> weightedEdges = List.of();

        private Builder() {}

        public Builder nodes(List<String> nodes) {
            this.nodes = List.copyOf(nodes);
            return this;
        }

        /**
         * @param weightedEdges list of {@code Map.Entry<Map.Entry<src,tgt>, weight>}
         */
        public Builder weightedEdges(List<Map.Entry<Map.Entry<String, String>, Double>> weightedEdges) {
            this.weightedEdges = List.copyOf(weightedEdges);
            return this;
        }

        public StaticPolicy build() {
            return new StaticPolicy(buildGraph(nodes, weightedEdges));
        }
    }

    // -------------------------------------------------------------------------
    // Package-private graph factory (shared by CongestionAwarePolicy)
    // -------------------------------------------------------------------------

    /**
     * Build a mutable directed weighted graph from node and edge lists.
     * Package-private so {@link CongestionAwarePolicy} can call it.
     */
    static DefaultDirectedWeightedGraph<String, DefaultWeightedEdge> buildGraph(
        List<String> nodes,
        List<Map.Entry<Map.Entry<String, String>, Double>> weightedEdges
    ) {
        var g = new DefaultDirectedWeightedGraph<String, DefaultWeightedEdge>(DefaultWeightedEdge.class);
        for (String node : nodes) {
            g.addVertex(node);
        }
        for (var entry : weightedEdges) {
            String src = entry.getKey().getKey();
            String tgt = entry.getKey().getValue();
            double w   = entry.getValue();
            g.addVertex(src); // idempotent if already added
            g.addVertex(tgt);
            var edge = g.addEdge(src, tgt);
            if (edge != null) {
                g.setEdgeWeight(edge, w);
            }
        }
        return g;
    }

    /**
     * Build a mutable copy of the given graph so weights can be updated per-solve.
     * Package-private — used by {@link CongestionAwarePolicy}.
     */
    static DefaultDirectedWeightedGraph<String, DefaultWeightedEdge> copyGraph(
        Graph<String, DefaultWeightedEdge> source
    ) {
        var copy = new DefaultDirectedWeightedGraph<String, DefaultWeightedEdge>(DefaultWeightedEdge.class);
        for (String v : source.vertexSet()) {
            copy.addVertex(v);
        }
        for (DefaultWeightedEdge e : source.edgeSet()) {
            String src = source.getEdgeSource(e);
            String tgt = source.getEdgeTarget(e);
            var newEdge = copy.addEdge(src, tgt);
            if (newEdge != null) {
                copy.setEdgeWeight(newEdge, source.getEdgeWeight(e));
            }
        }
        return copy;
    }
}
