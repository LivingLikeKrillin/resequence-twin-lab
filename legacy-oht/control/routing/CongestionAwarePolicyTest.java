package com.resequencetwin.control.routing;

import com.resequencetwin.control.graph.CongestionDetector.ResourceUtilization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chunk 2b — congestion-aware dynamic link-weight routing policy.
 *
 * <p>Key test: {@link #reroutesAwayFromHotspot()} — the core differentiator.
 * When a link is saturated, the dynamic policy penalises that link heavily and
 * reroutes around it; the static policy keeps using the nominally cheaper (but
 * congested) path.
 *
 * <p>Graph used in all tests (same topology as StaticPolicyTest):
 * <pre>
 *   A --[w=1]--> segDirect --[w=1]--> B   (cheap but will be saturated)
 *   A --[w=3]--> segAlt    --[w=3]--> B   (more expensive alternative)
 * </pre>
 * Static shortest path: A → segDirect → B (cost 2).
 * When segDirect is saturated, dynamic policy reroutes to: A → segAlt → B.
 *
 * <p>TDD: test written before CongestionAwarePolicy.java exists.
 */
class CongestionAwarePolicyTest {

    // "segHot" is the saturated segment alias used throughout to match the spec snippet
    private static final String SEG_HOT = "segDirect"; // the cheap path that gets congested

    private StaticPolicy staticPolicy;
    private CongestionAwarePolicy congestionAwarePolicy;

    @BeforeEach
    void setUp() {
        // Graph:
        //   A → segDirect (w=1) → B   cheap; will be saturated
        //   A → segAlt    (w=3) → B   more expensive but uncongested
        var nodes = List.of("A", "B", "segDirect", "segAlt");
        var edges = List.of(
            Map.entry(Map.entry("A", "segDirect"), 1.0),
            Map.entry(Map.entry("segDirect", "B"), 1.0),
            Map.entry(Map.entry("A", "segAlt"), 3.0),
            Map.entry(Map.entry("segAlt", "B"), 3.0)
        );

        staticPolicy = StaticPolicy.builder()
            .nodes(nodes)
            .weightedEdges(edges)
            .build();

        // CongestionAwarePolicy uses the same base graph but dynamically adjusts weights
        congestionAwarePolicy = CongestionAwarePolicy.builder()
            .nodes(nodes)
            .weightedEdges(edges)
            .congestionPenaltyMultiplier(20.0) // saturated link gets weight * 20
            .build();
    }

    /** Helper: produce utilization list where the given resourceId is saturated (100%). */
    private static List<ResourceUtilization> stateWithSaturatedLink(String resourceId) {
        return List.of(new ResourceUtilization(resourceId, 10, 10)); // occupancy == capacity
    }

    /**
     * ★ Core differentiator test.
     *
     * <p>With segDirect saturated:
     * <ul>
     *   <li>Static policy ignores state → still routes through segDirect (static weight wins).
     *   <li>Dynamic policy penalises saturated link → reroutes via segAlt.
     * </ul>
     */
    @Test
    void reroutesAwayFromHotspot() {
        var state = stateWithSaturatedLink("segDirect");

        var staticRoute  = staticPolicy.route(state, "A", "B");
        var dynamicRoute = congestionAwarePolicy.route(state, "A", "B");

        // Static ignores congestion: still uses the nominally cheapest edge
        assertThat(staticRoute).contains("segDirect");

        // Dynamic avoids the congested link
        assertThat(dynamicRoute).doesNotContain("segDirect");
        assertThat(dynamicRoute).contains("segAlt");
    }

    /** When nothing is congested, dynamic policy behaves like static (picks cheapest path). */
    @Test
    void withNoCongestionPicksShortestPath() {
        var route = congestionAwarePolicy.route(List.of(), "A", "B");
        // No congestion → segDirect is still cheapest
        assertThat(route).containsExactly("A", "segDirect", "B");
    }

    /** Dynamic policy returns a valid route (correct start/end) under congestion. */
    @Test
    void reroutedPathIsValid() {
        var state = stateWithSaturatedLink("segDirect");
        var route = congestionAwarePolicy.route(state, "A", "B");

        assertThat(route).isNotEmpty();
        assertThat(route.get(0)).isEqualTo("A");
        assertThat(route.get(route.size() - 1)).isEqualTo("B");
    }

    /** Unreachable destination → empty list, no exception. */
    @Test
    void returnsEmptyForUnreachablePair() {
        var route = congestionAwarePolicy.route(List.of(), "A", "DISCONNECTED");
        assertThat(route).isEmpty();
    }

    /**
     * Partial saturation below the penalty threshold does not trigger rerouting.
     * The dynamic policy only penalises resources above 80% utilization.
     */
    @Test
    void partialCongestionBelowThresholdDoesNotReroute() {
        // 70% utilization — below default 0.80 threshold
        var state = List.of(new ResourceUtilization("segDirect", 7, 10));
        var route = congestionAwarePolicy.route(state, "A", "B");
        // segDirect is still cheaper after partial penalty
        assertThat(route).contains("segDirect");
    }

    /**
     * Soft-penalty contract: when the congested link is the ONLY path, the policy
     * still routes through it — graceful degradation, no failure.
     *
     * <p>Graph: A → segDirect → B (single path, no alternative).
     * segDirect is saturated (100%). The ×20 penalty makes the edge expensive, but
     * Dijkstra has no cheaper alternative and still returns [A, segDirect, B].
     *
     * <p>This confirms DEFAULT_PENALTY_MULTIPLIER is a soft-ranking heuristic, not
     * hard exclusion — see ADR-001.
     */
    @Test
    void routesThroughCongestedLinkWhenNoAlternativeExists() {
        // Single-path graph: A → segDirect → B only (no segAlt)
        var nodes = List.of("A", "segDirect", "B");
        var edges = List.of(
            Map.entry(Map.entry("A", "segDirect"), 1.0),
            Map.entry(Map.entry("segDirect", "B"), 1.0)
        );
        var singlePathPolicy = CongestionAwarePolicy.builder()
            .nodes(nodes)
            .weightedEdges(edges)
            .congestionPenaltyMultiplier(20.0)
            .build();

        // segDirect is fully saturated — penalty applies
        var state = stateWithSaturatedLink("segDirect");
        var route = singlePathPolicy.route(state, "A", "B");

        // Soft penalty: no alternative → still routes through the hotspot
        assertThat(route).containsExactly("A", "segDirect", "B");
    }
}
