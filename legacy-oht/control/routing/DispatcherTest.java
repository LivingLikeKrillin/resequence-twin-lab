package com.resequencetwin.control.routing;

import com.resequencetwin.control.graph.CongestionDetector.ResourceUtilization;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chunk 2b — Dispatcher wires UnitMove to RoutingPolicy and returns route.
 */
class DispatcherTest {

    private static StaticPolicy buildSimplePolicy() {
        return StaticPolicy.builder()
            .nodes(List.of("A", "seg1", "B"))
            .weightedEdges(List.of(
                Map.entry(Map.entry("A",    "seg1"), 1.0),
                Map.entry(Map.entry("seg1", "B"),    1.0)
            ))
            .build();
    }

    @Test
    void dispatchReturnsRouteFromPolicy() {
        var dispatcher = new Dispatcher(buildSimplePolicy());
        var move = new Dispatcher.UnitMove("unit1", "A", "B");

        var route = dispatcher.dispatch(List.of(), move);

        assertThat(route).containsExactly("A", "seg1", "B");
    }

    @Test
    void dispatcherIsPolicyAgnostic() {
        // Plugging in CongestionAwarePolicy — same interface, same result when uncongested
        var policy = CongestionAwarePolicy.builder()
            .nodes(List.of("A", "seg1", "B"))
            .weightedEdges(List.of(
                Map.entry(Map.entry("A",    "seg1"), 1.0),
                Map.entry(Map.entry("seg1", "B"),    1.0)
            ))
            .build();

        var dispatcher = new Dispatcher(policy);
        var move = new Dispatcher.UnitMove("unit2", "A", "B");

        var route = dispatcher.dispatch(List.of(), move);

        assertThat(route).containsExactly("A", "seg1", "B");
    }

    @Test
    void dispatcherPassesUtilizationsToPolicy() {
        // Build a graph with two paths; congestion forces alt path
        var policy = CongestionAwarePolicy.builder()
            .nodes(List.of("A", "segCheap", "segAlt", "B"))
            .weightedEdges(List.of(
                Map.entry(Map.entry("A",       "segCheap"), 1.0),
                Map.entry(Map.entry("segCheap","B"),        1.0),
                Map.entry(Map.entry("A",       "segAlt"),   3.0),
                Map.entry(Map.entry("segAlt",  "B"),        3.0)
            ))
            .congestionPenaltyMultiplier(50.0)
            .build();

        var dispatcher = new Dispatcher(policy);
        var utilizations = List.of(new ResourceUtilization("segCheap", 10, 10)); // saturated
        var move = new Dispatcher.UnitMove("unit3", "A", "B");

        var route = dispatcher.dispatch(utilizations, move);

        assertThat(route).doesNotContain("segCheap");
        assertThat(route).contains("segAlt");
    }
}
