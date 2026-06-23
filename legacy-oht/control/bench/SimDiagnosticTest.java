package com.resequencetwin.control.bench;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Diagnostic test to understand simulation behavior.
 * NOT a regression test — temporary diagnostic, will be removed or kept as doc.
 */
@Disabled("diagnostic — run manually")
class SimDiagnosticTest {

    @Test
    void printSimulationDiagnostics() {
        long seed = 42L;
        int totalTicks = 50;

        BenchmarkScenario staticScen = BenchmarkScenario.builder()
            .addNode("SOURCE",  3)
            .addNode("segHot",  1)
            .addNode("segHot2", 1)
            .addNode("segAlt1", 3)
            .addNode("segAlt2", 3)
            .addNode("SINK",    5)
            .addEdge("SOURCE", "segHot",  1.0)
            .addEdge("segHot", "segHot2", 1.0)
            .addEdge("segHot2","SINK",    1.0)
            .addEdge("SOURCE", "segAlt1", 3.0)
            .addEdge("segAlt1","segAlt2", 3.0)
            .addEdge("segAlt2","SINK",    3.0)
            .from("SOURCE").to("SINK").congestionPenaltyEnabled(false).build();

        LoadGenerator gen = new LoadGenerator(seed, totalTicks, LoadGenerator.Intensity.SATURATED, "SOURCE", "SINK");
        List<LoadGenerator.MoveArrival> arrivals = gen.generate();

        System.out.println("Arrivals generated: " + arrivals.size());
        arrivals.stream().limit(15).forEach(a ->
            System.out.println("  tick=" + a.tick() + " unit=" + a.unitId()));

        // Run static
        SimRunner staticRunner = new SimRunner(staticScen);
        KpiCollector.Snapshot snap = staticRunner.run(arrivals, totalTicks);

        System.out.println("\nStatic KPIs over " + totalTicks + " ticks:");
        System.out.println("  throughput: " + snap.throughput());
        System.out.println("  meanFlowLeadTime: " + snap.meanFlowLeadTime());
        System.out.println("  deadlockRate: " + snap.deadlockRate());
        System.out.println("  flowTimeVariance: " + snap.flowTimeVariance());
        System.out.println("  meanUtilization: " + snap.meanUtilization());
    }
}
