package com.resequencetwin.control.graph;

import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.GabowStrongConnectivityInspector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import java.util.List;

/**
 * Wait-for graph model for deadlock detection.
 *
 * <p>Vertices = unit IDs (strings). A directed edge u → holder means
 * "unit u is blocked waiting for a resource currently held by holder."
 *
 * <p>Graph construction (modeling/extraction) is hand-built here.
 * SCC computation is delegated to JGraphT {@link GabowStrongConnectivityInspector}
 * — hand-rolling SCC is explicitly prohibited (spec §0, §4).
 *
 * <p>NOTE: single-threaded assumption — the graph and inspector are constructed
 * in a single call; no concurrent mutation protection is needed.
 */
public class WaitForGraph {

    /**
     * Represents a blocked unit waiting for a resource held by another unit.
     *
     * @param unit            the blocked unit
     * @param wantedResource  the resource this unit is waiting for
     * @param holder          the unit currently holding (occupying) that resource
     */
    public record BlockedRelation(String unit, String wantedResource, String holder) {}

    /** The underlying JGraphT directed graph: vertices = unit IDs, edges = wait-for. */
    private final Graph<String, DefaultEdge> graph;

    /**
     * Constructs the wait-for graph from a list of blocked relations.
     *
     * <p>Each {@link BlockedRelation} contributes a directed edge {@code unit → holder}.
     * Multiple relations may share a holder (fan-in) or a unit (fan-out).
     */
    public WaitForGraph(List<BlockedRelation> relations) {
        this.graph = buildGraph(relations);
    }

    // -------------------------------------------------------------------------
    // Static factory helpers (for concise test construction)
    // -------------------------------------------------------------------------

    /**
     * Factory: construct a wait-for graph directly from a list of relations.
     * Package-private — only for intra-package test use.
     */
    static Graph<String, DefaultEdge> waitForGraph(List<BlockedRelation> relations) {
        return buildGraph(relations);
    }

    /**
     * Factory: create a {@link BlockedRelation} record.
     * Intended for test use: {@code blocked("u1", "segB", "u2")}.
     */
    public static BlockedRelation blocked(String unit, String wantedResource, String holder) {
        return new BlockedRelation(unit, wantedResource, holder);
    }

    // -------------------------------------------------------------------------
    // Detection API (domain objects for downstream consumers)
    // -------------------------------------------------------------------------

    /**
     * Detect deadlocks: returns each strongly-connected component with more than
     * one vertex (a trivial SCC is a single vertex with no self-loop).
     *
     * <p>Uses JGraphT {@link GabowStrongConnectivityInspector} — O(V+E).
     *
     * @return list of {@link Deadlock} domain objects; empty if no cycles
     */
    public List<Deadlock> detectDeadlocks() {
        // NOTE: single-threaded assumption — GabowStrongConnectivityInspector is not thread-safe.
        return new GabowStrongConnectivityInspector<>(graph)
            .getStronglyConnectedComponents().stream()
            .filter(scc -> scc.vertexSet().size() > 1)
            .map(scc -> new Deadlock(scc.vertexSet().stream().sorted().toList()))
            .toList();
    }

    /**
     * Expose the raw graph for use in tests (e.g., direct SCC inspection).
     */
    public Graph<String, DefaultEdge> graph() {
        return graph;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static Graph<String, DefaultEdge> buildGraph(List<BlockedRelation> relations) {
        Graph<String, DefaultEdge> g = new DefaultDirectedGraph<>(DefaultEdge.class);
        for (BlockedRelation r : relations) {
            g.addVertex(r.unit());
            g.addVertex(r.holder());
            g.addEdge(r.unit(), r.holder());
        }
        return g;
    }

    // -------------------------------------------------------------------------
    // Domain output type
    // -------------------------------------------------------------------------

    /**
     * A detected deadlock: a set of unit IDs that form a wait-for cycle.
     *
     * <p>Fed to Chunk 2b (routing) and Chunk 3 (agent triage) for resolution.
     */
    public record Deadlock(List<String> involvedUnits) {}
}
