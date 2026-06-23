package com.resequencetwin.control.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.resequencetwin.control.graph.WaitForGraph.blocked;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chunk 2a — wait-for graph and congestion detection tests.
 *
 * <p>TDD: tests are written first; implementation follows in WaitForGraph.java
 * and CongestionDetector.java.
 *
 * <p>NOTE: single-threaded assumption — all graph construction and inspection
 * runs in a single thread; no concurrent modification protection is needed here.
 */
class WaitForGraphTest {

    // -------------------------------------------------------------------------
    // Deadlock detection (SCC)
    // -------------------------------------------------------------------------

    /**
     * Classic 2-unit deadlock: u1 waits for segB held by u2; u2 waits for segA held by u1.
     * Exactly one deadlock with the two involved units must be detected.
     */
    @Test
    void detectsCycleAsDeadlock() {
        var deadlocks = new WaitForGraph(List.of(
            blocked("u1", "segB", "u2"),
            blocked("u2", "segA", "u1")
        )).detectDeadlocks();
        assertThat(deadlocks).hasSize(1);
        assertThat(deadlocks.get(0).involvedUnits()).containsExactlyInAnyOrder("u1", "u2");
    }

    /**
     * Linear wait (u1→u2→u3) is NOT a deadlock — no cycle.
     */
    @Test
    void linearWaitIsNotADeadlock() {
        var deadlocks = new WaitForGraph(List.of(
            blocked("u1", "segA", "u2"),
            blocked("u2", "segB", "u3")
        )).detectDeadlocks();
        assertThat(deadlocks).isEmpty();
    }

    /**
     * 3-unit cycle: u1→u2→u3→u1 — one deadlock with 3 involved units.
     */
    @Test
    void threeUnitCycleIsOneDeadlock() {
        var deadlocks = new WaitForGraph(List.of(
            blocked("u1", "segA", "u2"),
            blocked("u2", "segB", "u3"),
            blocked("u3", "segC", "u1")
        )).detectDeadlocks();
        assertThat(deadlocks).hasSize(1);
        assertThat(deadlocks.get(0).involvedUnits()).hasSize(3);
    }

    /**
     * Two independent 2-unit cycles must be detected as two separate deadlocks.
     * u1↔u2 is one cycle; u3↔u4 is another independent cycle.
     */
    @Test
    void twoIndependentCyclesDetectedSeparately() {
        var deadlocks = new WaitForGraph(List.of(
            blocked("u1", "segA", "u2"),
            blocked("u2", "segB", "u1"),
            blocked("u3", "segC", "u4"),
            blocked("u4", "segD", "u3")
        )).detectDeadlocks();
        assertThat(deadlocks).hasSize(2);
    }

    /**
     * WaitForGraph.detectDeadlocks() convenience method returns the same result.
     */
    @Test
    void detectDeadlocksConvenienceMethodWorks() {
        var wfg = new WaitForGraph(List.of(
            blocked("u1", "segB", "u2"),
            blocked("u2", "segA", "u1")
        ));
        assertThat(wfg.detectDeadlocks()).hasSize(1);
    }

    /**
     * Empty relations → no deadlocks.
     */
    @Test
    void emptyRelationsNoDeadlock() {
        var wfg = new WaitForGraph(List.of());
        assertThat(wfg.detectDeadlocks()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Congestion detection (betweenness + utilization)
    // -------------------------------------------------------------------------

    /**
     * A resource node that lies on all paths in a hub-and-spoke layout has high
     * betweenness centrality and should be flagged as a structural hotspot.
     *
     * <p>Layout: 4 leaf resources (A,B,C,D) each connected to a central hub (H).
     * The hub has the highest betweenness.
     */
    @Test
    void highBetweennessNodeFlaggedAsStructuralHotspot() {
        CongestionDetector detector = CongestionDetector.forResourceGraph(
            List.of("A", "B", "C", "D", "H"),
            List.of(
                Map.entry("A", "H"),
                Map.entry("B", "H"),
                Map.entry("C", "H"),
                Map.entry("D", "H"),
                Map.entry("H", "A"),
                Map.entry("H", "B"),
                Map.entry("H", "C"),
                Map.entry("H", "D")
            )
        );

        List<CongestionDetector.Hotspot> hotspots = detector.structuralHotspots(1);
        assertThat(hotspots).isNotEmpty();
        assertThat(hotspots.get(0).resourceId()).isEqualTo("H");
    }

    /**
     * A resource exceeding the utilization threshold is flagged as a utilization hotspot.
     */
    @Test
    void highUtilizationNodeFlaggedAsUtilizationHotspot() {
        // occupancy=9, capacity=10 → utilization 0.90 > default threshold 0.80
        CongestionDetector.ResourceUtilization saturated =
            new CongestionDetector.ResourceUtilization("segHot", 9, 10);
        CongestionDetector.ResourceUtilization normal =
            new CongestionDetector.ResourceUtilization("segOk", 1, 10);

        List<CongestionDetector.Hotspot> hotspots =
            CongestionDetector.utilizationHotspots(List.of(saturated, normal), 0.80);

        assertThat(hotspots).hasSize(1);
        assertThat(hotspots.get(0).resourceId()).isEqualTo("segHot");
    }

    /**
     * A resource at exactly the threshold is NOT flagged (strictly above only).
     */
    @Test
    void exactThresholdNotFlagged() {
        // occupancy=8, capacity=10 → utilization exactly 0.80 = threshold; not flagged
        CongestionDetector.ResourceUtilization atThreshold =
            new CongestionDetector.ResourceUtilization("segEdge", 8, 10);

        List<CongestionDetector.Hotspot> hotspots =
            CongestionDetector.utilizationHotspots(List.of(atThreshold), 0.80);

        assertThat(hotspots).isEmpty();
    }

    /**
     * A resource with non-positive capacity is silently skipped (no exception thrown).
     * M-3: verifies the guard does not crash and the bad entry is excluded from results.
     */
    @Test
    void zeroCapacityResourceIsSkippedWithoutException() {
        CongestionDetector.ResourceUtilization zeroCap =
            new CongestionDetector.ResourceUtilization("segBad", 5, 0);
        CongestionDetector.ResourceUtilization normal =
            new CongestionDetector.ResourceUtilization("segOk", 9, 10);

        List<CongestionDetector.Hotspot> hotspots =
            CongestionDetector.utilizationHotspots(List.of(zeroCap, normal), 0.80);

        // zeroCap must be excluded; only normal (0.90 > 0.80) survives
        assertThat(hotspots).hasSize(1);
        assertThat(hotspots.get(0).resourceId()).isEqualTo("segOk");
    }

    /**
     * detectAll combines structural top-N hotspots with utilization hotspots.
     * I-2: verifies the single entry point returns both kinds.
     */
    @Test
    void detectAllReturnsBothStructuralAndUtilizationHotspots() {
        CongestionDetector detector = CongestionDetector.forResourceGraph(
            List.of("A", "B", "C", "D", "H"),
            List.of(
                Map.entry("A", "H"), Map.entry("B", "H"),
                Map.entry("C", "H"), Map.entry("D", "H"),
                Map.entry("H", "A"), Map.entry("H", "B"),
                Map.entry("H", "C"), Map.entry("H", "D")
            )
        );

        CongestionDetector.ResourceUtilization saturated =
            new CongestionDetector.ResourceUtilization("segHot", 9, 10);

        List<CongestionDetector.Hotspot> all = detector.detectAll(List.of(saturated), 0.80, 1);

        assertThat(all).isNotEmpty();
        // Must contain at least one STRUCTURAL and one UTILIZATION hotspot
        assertThat(all).anyMatch(h -> h.kind() == CongestionDetector.HotspotKind.STRUCTURAL);
        assertThat(all).anyMatch(h -> h.kind() == CongestionDetector.HotspotKind.UTILIZATION);
    }
}
