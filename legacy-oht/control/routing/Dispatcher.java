package com.resequencetwin.control.routing;

import com.resequencetwin.control.graph.CongestionDetector.ResourceUtilization;

import java.util.List;

/**
 * Dispatches a pending unit-move request to a {@link RoutingPolicy} and returns the route.
 *
 * <p>Greedy assignment: every move request goes to the configured policy immediately.
 * No VRP / multi-unit coordination — that is solver territory (OR-Tools, Chunk 4 stretch).
 * The dispatcher's job is purely to bridge the {@link UnitMove} domain event to the
 * routing policy interface.
 *
 * <p>YAGNI: no queuing, no priority, no batching. Chunk 2c benchmark drives the policy
 * comparison — the dispatcher just wires state + move → policy → route.
 */
public final class Dispatcher {

    private final RoutingPolicy policy;

    public Dispatcher(RoutingPolicy policy) {
        this.policy = policy;
    }

    /**
     * Assign a pending move to the policy and return the computed route.
     *
     * @param utilizations  current twin state (used by dynamic policy; ignored by static)
     * @param move          the pending unit move request
     * @return              ordered list of resource IDs from {@code move.from()} to
     *                      {@code move.to()}; empty if no path exists
     */
    public List<String> dispatch(List<ResourceUtilization> utilizations, UnitMove move) {
        return policy.route(utilizations, move.from(), move.to());
    }

    /**
     * A pending move request: a unit that needs to travel from one resource to another.
     *
     * @param unitId  identifier of the unit being moved
     * @param from    source resource ID
     * @param to      destination resource ID
     */
    public record UnitMove(String unitId, String from, String to) {}
}
