package com.resequencetwin.control.routing;

import com.resequencetwin.control.graph.CongestionDetector.ResourceUtilization;

import java.util.List;

/**
 * Strategy interface for routing policies.
 *
 * <p>Both the static baseline (fixed link weights) and the congestion-aware dynamic
 * policy (live-weight update from twin state) implement this interface. The benchmark
 * (Chunk 2c) and the dispatcher use this interface exclusively — the solver choice
 * (JGraphT Dijkstra PoC, OR-Tools, or cuOpt) is hidden behind each implementation.
 *
 * <p>See ADR-001 for the PoC solver choice rationale.
 *
 * @see StaticPolicy
 * @see CongestionAwarePolicy
 */
public interface RoutingPolicy {

    /**
     * Compute a route from {@code from} to {@code to} given the current twin state.
     *
     * @param utilizations  current resource utilization snapshots from the digital twin;
     *                      ignored by the static policy, used by the dynamic policy to
     *                      adjust link weights before solving
     * @param from          source resource ID
     * @param to            destination resource ID
     * @return              ordered list of resource IDs forming the route (inclusive of
     *                      {@code from} and {@code to}); <b>empty list</b> in either of
     *                      two distinct failure cases:
     *                      <ol>
     *                        <li><b>Unknown vertex</b> — {@code from} or {@code to} is
     *                            not present in the graph (guard against
     *                            {@link IllegalArgumentException} from the solver).</li>
     *                        <li><b>Unreachable target</b> — both vertices exist but no
     *                            directed path connects them (solver returns {@code null}).</li>
     *                      </ol>
     *                      Callers that need to distinguish the two cases should
     *                      pre-check vertex membership before calling, or inspect the
     *                      graph directly; the return value alone does not encode the reason.
     */
    List<String> route(List<ResourceUtilization> utilizations, String from, String to);
}
