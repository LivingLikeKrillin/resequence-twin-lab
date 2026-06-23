package com.resequencetwin.control.routing;

import com.resequencetwin.control.graph.CongestionDetector.ResourceUtilization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chunk 2b — static routing policy (fixed link weights).
 *
 * <p>Graph layout used in all tests:
 * <pre>
 *   A --1-- segA --1-- B
 *    \                /
 *     --1-- segHot --1
 * </pre>
 * Two paths from A to B:
 *   Path 1 (direct):  A → segA → B  (cost 2)
 *   Path 2 (via hot): A → segHot → B (cost 2, same static weight)
 *
 * With identical static weights the solver picks any minimal path.
 * We verify: (a) a valid shortest path is returned, (b) it contains exactly
 * the expected intermediate hop (we seed the graph so the tie-break is deterministic).
 *
 * <p>TDD: test written before StaticPolicy.java exists.
 */
class StaticPolicyTest {

    /*
     * Graph:
     *   A --[w=1]--> segDirect --[w=1]--> B
     *   A --[w=5]--> segHot    --[w=5]--> B
     *
     * Static policy uses fixed weights → shortest path goes through segDirect (cost 2)
     * rather than segHot (cost 10). This gives a deterministic expected result.
     */
    private StaticPolicy staticPolicy;

    @BeforeEach
    void setUp() {
        // Build a graph where segDirect is the cheap path and segHot is expensive.
        // nodes: A, B, segDirect, segHot
        // edges: A→segDirect(1), segDirect→B(1), A→segHot(5), segHot→B(5)
        staticPolicy = StaticPolicy.builder()
            .nodes(List.of("A", "B", "segDirect", "segHot"))
            .weightedEdges(List.of(
                Map.entry(Map.entry("A", "segDirect"), 1.0),
                Map.entry(Map.entry("segDirect", "B"), 1.0),
                Map.entry(Map.entry("A", "segHot"), 5.0),
                Map.entry(Map.entry("segHot", "B"), 5.0)
            ))
            .build();
    }

    /** Static policy returns a non-empty route for a reachable A→B pair. */
    @Test
    void returnsRouteForReachablePair() {
        var route = staticPolicy.route(List.of(), "A", "B");
        assertThat(route).isNotEmpty();
        assertThat(route.get(0)).isEqualTo("A");
        assertThat(route.get(route.size() - 1)).isEqualTo("B");
    }

    /** Static policy picks the lowest-cost path (segDirect, cost 2 vs segHot cost 10). */
    @Test
    void picksShortestPathByFixedWeight() {
        var route = staticPolicy.route(List.of(), "A", "B");
        // Cheapest path: A → segDirect → B
        assertThat(route).containsExactly("A", "segDirect", "B");
    }

    /**
     * Static policy is oblivious to congestion:
     * even when segDirect is saturated, it still routes through the lowest-weight edge.
     * (The congestion-aware policy is what routes around it — tested in CongestionAwarePolicyTest.)
     */
    @Test
    void ignoresCongestionInState() {
        // segDirect fully saturated — static policy ignores this
        var utilizations = List.of(
            new ResourceUtilization("segDirect", 10, 10)
        );
        var route = staticPolicy.route(utilizations, "A", "B");
        assertThat(route).containsExactly("A", "segDirect", "B");
    }

    /** No path between disconnected nodes → empty list (no exception). */
    @Test
    void returnsEmptyForUnreachablePair() {
        var route = staticPolicy.route(List.of(), "A", "DISCONNECTED");
        assertThat(route).isEmpty();
    }
}
