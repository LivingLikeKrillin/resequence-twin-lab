package com.resequencetwin.control.drift

/**
 * Deterministic, **mutable** in-process config source for offline TDD and the offline demo.
 *
 * The mutators ([setCapacity], [block], [unblock], [removeLane]) let a test or demo inject drift
 * (capacity 10 -> 8, disable a station/lane) that the next [DriftMonitor] tick will surface. This is
 * synthetic perturbation — it demonstrates the detection mechanism, not real plant data.
 */
class SimulatedConfigSource(initial: ObservedConfig) : LiveConfigSource {
    private var lanes: MutableMap<String, Int> = initial.lanes.toMutableMap()
    private var blocked: MutableSet<String> = initial.blocked.toMutableSet()

    @Synchronized
    override fun readConfig(): ObservedConfig = ObservedConfig(lanes.toMap(), blocked.toSet())

    @Synchronized fun setCapacity(laneId: String, capacity: Int) { lanes[laneId] = capacity }
    @Synchronized fun block(laneId: String) { blocked.add(laneId) }
    @Synchronized fun unblock(laneId: String) { blocked.remove(laneId) }
    @Synchronized fun removeLane(laneId: String) { lanes.remove(laneId) }
}
