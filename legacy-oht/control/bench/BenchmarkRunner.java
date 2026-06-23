package com.resequencetwin.control.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Standalone benchmark runner: runs static-vs-dynamic comparison and outputs report.
 *
 * <p>Outputs:
 * <ol>
 *   <li>Console: formatted comparison table (relative deltas)</li>
 *   <li>JSON file (optional): machine-readable KPIs with honesty metadata</li>
 * </ol>
 *
 * <p>Invoked by tests and optionally as a {@code main()} entry point.
 *
 * <p><b>Honesty note (spec §6, §12):</b> all outputs are labeled
 * "synthetic, relative-to-static" — never absolute throughput claims.
 */
public final class BenchmarkRunner {

    /** Standard scenario: diamond graph with bottleneck, SATURATED load. */
    public static BenchmarkScenario defaultStaticScenario() {
        return BenchmarkScenario.builder()
            .addNode("SOURCE",  3)
            .addNode("segHot",  1)   // bottleneck: capacity=1
            .addNode("segHot2", 1)   // bottleneck continuation
            .addNode("segAlt1", 3)
            .addNode("segAlt2", 3)
            .addNode("SINK",    5)
            .addEdge("SOURCE",  "segHot",  1.0)
            .addEdge("segHot",  "segHot2", 1.0)
            .addEdge("segHot2", "SINK",    1.0)
            .addEdge("SOURCE",  "segAlt1", 3.0)
            .addEdge("segAlt1", "segAlt2", 3.0)
            .addEdge("segAlt2", "SINK",    3.0)
            .from("SOURCE")
            .to("SINK")
            .congestionPenaltyEnabled(false)  // static: no penalty
            .build();
    }

    /** Dynamic scenario — same topology, penalty enabled. */
    public static BenchmarkScenario defaultDynamicScenario() {
        return BenchmarkScenario.builder()
            .addNode("SOURCE",  3)
            .addNode("segHot",  1)
            .addNode("segHot2", 1)
            .addNode("segAlt1", 3)
            .addNode("segAlt2", 3)
            .addNode("SINK",    5)
            .addEdge("SOURCE",  "segHot",  1.0)
            .addEdge("segHot",  "segHot2", 1.0)
            .addEdge("segHot2", "SINK",    1.0)
            .addEdge("SOURCE",  "segAlt1", 3.0)
            .addEdge("segAlt1", "segAlt2", 3.0)
            .addEdge("segAlt2", "SINK",    3.0)
            .from("SOURCE")
            .to("SINK")
            .congestionPenaltyEnabled(true)   // dynamic: congestion penalty active
            .build();
    }

    /**
     * Run with default parameters and print report to stdout.
     *
     * @param seed       RNG seed (42 = standard regression seed)
     * @param totalTicks simulation length in ticks
     */
    public static BenchmarkComparison runDefault(long seed, int totalTicks) {
        BenchmarkComparison cmp = BenchmarkComparison.run(
            seed, totalTicks,
            defaultStaticScenario(),
            defaultDynamicScenario()
        );
        cmp.printReport();
        return cmp;
    }

    /**
     * Serialize a comparison report to JSON.
     *
     * <p>JSON includes honesty metadata (synthetic, relative-to-static) per spec §12.
     *
     * @param report     the comparison to serialize
     * @param outputPath optional file path; if null, prints JSON to stdout only
     */
    public static String toJson(BenchmarkComparison.Report report, Path outputPath) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        // Honesty metadata
        root.put("disclaimer", "synthetic simulation, relative-to-static — not real equipment data");
        root.put("generatedAt", Instant.now().toString());
        root.put("seed", report.seed());

        // Deadlock honesty note (I1): the benchmark graph is a unidirectional DAG, so
        // deadlock is structurally impossible. Both static and dynamic deadlock rates are
        // always 0. The invariant dynamic_deadlock <= static is vacuously satisfied (0<=0)
        // and is NOT a demonstration of dynamic superiority. Deadlock detection itself is
        // proven in WaitForGraphTest, not here.
        root.put("deadlock_rate_note",
            "unidirectional DAG; deadlock structurally impossible; invariant vacuously satisfied (0<=0); "
            + "deadlock detection itself is proven in WaitForGraphTest, not here");

        // Static KPIs
        ObjectNode staticNode = root.putObject("static");
        staticNode.put("throughput_moves_per_tick", report.staticKpis().throughput());
        staticNode.put("mean_flow_lead_time_ticks", report.staticKpis().meanFlowLeadTime());
        staticNode.put("deadlock_rate", report.staticKpis().deadlockRate());
        staticNode.put("flow_time_variance", report.staticKpis().flowTimeVariance());
        staticNode.put("mean_utilization", report.staticKpis().meanUtilization());

        // Dynamic KPIs
        ObjectNode dynamicNode = root.putObject("dynamic");
        dynamicNode.put("throughput_moves_per_tick", report.dynamicKpis().throughput());
        dynamicNode.put("mean_flow_lead_time_ticks", report.dynamicKpis().meanFlowLeadTime());
        dynamicNode.put("deadlock_rate", report.dynamicKpis().deadlockRate());
        dynamicNode.put("flow_time_variance", report.dynamicKpis().flowTimeVariance());
        dynamicNode.put("mean_utilization", report.dynamicKpis().meanUtilization());

        // Relative deltas (the primary output — spec §6)
        ObjectNode deltaNode = root.putObject("relative_deltas_dynamic_minus_static");
        deltaNode.put("throughput", report.throughputDelta());
        deltaNode.put("flow_lead_time", report.flowLeadTimeDelta());
        deltaNode.put("deadlock_rate", report.deadlockRateDelta());
        deltaNode.put("flow_time_variance", report.flowTimeVarianceDelta());
        deltaNode.put("mean_utilization", report.utilizationDelta());

        // Invariant evaluation (spec §6)
        ObjectNode invNode = root.putObject("invariants_spec_section6");
        invNode.put("dynamic_deadlock_rate_le_static", report.dynamicKpis().deadlockRate() <= report.staticKpis().deadlockRate());
        invNode.put("dynamic_flow_variance_le_static", report.dynamicKpis().flowTimeVariance() <= report.staticKpis().flowTimeVariance());
        invNode.put("dynamic_throughput_ge_static_saturated", report.dynamicKpis().throughput() >= report.staticKpis().throughput());

        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            if (outputPath != null) {
                Files.writeString(outputPath, json);
            }
            return json;
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize benchmark report", e);
        }
    }

    /** Entry point for standalone execution. */
    public static void main(String[] args) {
        long seed = 42L;
        int totalTicks = 200;
        BenchmarkComparison cmp = runDefault(seed, totalTicks);
        String json = toJson(cmp.report(), null);
        System.out.println("\n--- JSON Report ---");
        System.out.println(json);
    }
}
