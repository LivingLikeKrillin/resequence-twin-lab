"""
resequence-twin sim — synthetic event generator.

Chunk 1 implementation:
  - Seeded SimPy simulation over the declarative layout graph (sim/layout/auto.yaml)
  - Emits normalised Move events (canonical event set)
  - Produces to Kafka (key = Resource id)

Public API:
    run_simulation(seed, layout_path) → list[dict]
    produce_to_kafka(events, bootstrap_servers, topic) → None
    create_generator(seed) → dict   # backward-compat stub kept
"""
from __future__ import annotations

import json
import os
import random
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import simpy
import yaml

# ---------------------------------------------------------------------------
# Canonical event types
# ---------------------------------------------------------------------------
EVT_MOVE_ASSIGNED = "MoveAssigned"
EVT_MOVE_STARTED = "MoveStarted"
EVT_UNIT_MOVED = "UnitMoved"
EVT_UNIT_ARRIVED = "UnitArrived"
EVT_UNIT_DEPARTED = "UnitDeparted"
EVT_RESOURCE_OCCUPIED = "ResourceOccupied"
EVT_RESOURCE_RELEASED = "ResourceReleased"
EVT_MOVE_BLOCKED = "MoveBlocked"
EVT_MOVE_COMPLETED = "MoveCompleted"
EVT_ERROR_RAISED = "ErrorRaised"

_DEFAULT_LAYOUT = Path(__file__).parent / "layout" / "auto.yaml"


# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------

@dataclass
class ResourceDef:
    id: str
    type: str
    capacity: int
    graph_coord: list


@dataclass
class EdgeDef:
    from_id: str
    to_id: str
    weight: float
    transport: Optional[str] = None


@dataclass
class Layout:
    resources: list[ResourceDef]
    edges: list[EdgeDef]
    unit_count: int
    unit_id_prefix: str
    unit_type: str
    process_time: float
    transit_time: float
    total_time: float

    def get_resource(self, res_id: str) -> ResourceDef:
        for r in self.resources:
            if r.id == res_id:
                return r
        raise KeyError(f"Resource {res_id} not found")

    def successors(self, res_id: str) -> list[EdgeDef]:
        return [e for e in self.edges if e.from_id == res_id]

    def find_path(self, rng: random.Random, from_id: str, to_id: str) -> list[str]:
        """BFS shortest path; tie-break randomly for simulation variety."""
        from collections import deque
        q: deque[list[str]] = deque([[from_id]])
        visited = {from_id}
        paths: list[list[str]] = []
        best_len: Optional[int] = None
        while q:
            path = q.popleft()
            cur = path[-1]
            if cur == to_id:
                if best_len is None:
                    best_len = len(path)
                if len(path) == best_len:
                    paths.append(path)
                continue
            if best_len and len(path) >= best_len:
                continue
            edges = self.successors(cur)
            rng.shuffle(edges)  # seeded shuffle for determinism
            for e in edges:
                if e.to_id not in visited:
                    visited.add(e.to_id)
                    q.append(path + [e.to_id])
        if not paths:
            return [from_id, to_id]  # fallback direct
        return rng.choice(paths)


# ---------------------------------------------------------------------------
# Layout loader
# ---------------------------------------------------------------------------

def _load_layout(path: Path = _DEFAULT_LAYOUT) -> Layout:
    with open(path, "r", encoding="utf-8") as f:
        raw = yaml.safe_load(f)
    resources = [
        ResourceDef(
            id=r["id"],
            type=r["type"],
            capacity=r["capacity"],
            graph_coord=r["graph_coord"],
        )
        for r in raw["resources"]
    ]
    edges = [
        EdgeDef(
            from_id=e["from"],
            to_id=e["to"],
            weight=float(e.get("weight", 1.0)),
            transport=e.get("transport"),
        )
        for e in raw["edges"]
    ]
    units_cfg = raw["units"]
    sim_cfg = raw["simulation"]
    return Layout(
        resources=resources,
        edges=edges,
        unit_count=int(units_cfg["count"]),
        unit_id_prefix=units_cfg["id_prefix"],
        unit_type=units_cfg["type"],
        process_time=float(sim_cfg["process_time_per_resource"]),
        transit_time=float(sim_cfg["transit_time_per_edge"]),
        total_time=float(sim_cfg["total_time"]),
    )


# ---------------------------------------------------------------------------
# Simulation
# ---------------------------------------------------------------------------

class Simulation:
    """SimPy-based simulation; all state in this object."""

    def __init__(self, seed: int, layout: Layout):
        self._rng = random.Random(seed)
        self._layout = layout
        self._env = simpy.Environment()
        self._events: list[dict] = []
        # partition-local sequence per resource
        self._seq_counter: dict[str, int] = defaultdict(int)
        # SimPy resources (capacity-guarded)
        self._simpy_resources: dict[str, simpy.Resource] = {
            r.id: simpy.Resource(self._env, capacity=r.capacity)
            for r in layout.resources
        }

    # --- Event factory ---------------------------------------------------

    def _next_seq(self, resource_id: str) -> int:
        n = self._seq_counter[resource_id]
        self._seq_counter[resource_id] = n + 1
        return n

    def _emit(
        self,
        event_type: str,
        unit_id: str,
        resource_id: str,
        move_id: str,
        causation_id: Optional[str] = None,
    ) -> str:
        event_id = str(self._rng.getrandbits(64))
        ev = {
            "eventId": event_id,
            "eventType": event_type,
            "simTs": float(self._env.now),  # simulation-relative seconds (env.now), NOT epoch ms
            "seq": self._next_seq(resource_id),
            "unitId": unit_id,
            "resourceId": resource_id,
            "moveId": move_id,
            "causationId": causation_id or event_id,
        }
        self._events.append(ev)
        return event_id

    # --- Unit process ----------------------------------------------------

    def _unit_process(self, unit_id: str):
        layout = self._layout
        rng = self._rng

        # Determine route: LOAD → … → UNLOAD (exclude AGV-type resources from the station path)
        station_ids = [r.id for r in layout.resources if r.type != "agv"]
        first = station_ids[0]
        last = station_ids[-1]
        path = layout.find_path(rng, first, last)

        # Walk path station by station
        prev_event_id: Optional[str] = None
        move_id = str(rng.getrandbits(64))

        # Assign move
        eid = self._emit(EVT_MOVE_ASSIGNED, unit_id, path[0], move_id, prev_event_id)
        prev_event_id = eid

        for i, res_id in enumerate(path):
            # Acquire resource
            req = self._simpy_resources[res_id].request()
            blocked_emitted = False
            # If resource busy, emit blocked
            if len(self._simpy_resources[res_id].queue) > 0:
                eid = self._emit(EVT_MOVE_BLOCKED, unit_id, res_id, move_id, prev_event_id)
                prev_event_id = eid
                blocked_emitted = True

            yield req

            # Resource acquired
            if i == 0:
                # Starting the move
                eid = self._emit(EVT_MOVE_STARTED, unit_id, res_id, move_id, prev_event_id)
                prev_event_id = eid

            # Occupy resource
            eid = self._emit(EVT_RESOURCE_OCCUPIED, unit_id, res_id, move_id, prev_event_id)
            prev_event_id = eid

            # Transit to this resource (if not first hop)
            if i > 0:
                yield self._env.timeout(layout.transit_time)
                eid = self._emit(EVT_UNIT_MOVED, unit_id, res_id, move_id, prev_event_id)
                prev_event_id = eid

            eid = self._emit(EVT_UNIT_ARRIVED, unit_id, res_id, move_id, prev_event_id)
            prev_event_id = eid

            # Process at resource
            yield self._env.timeout(layout.process_time)

            eid = self._emit(EVT_UNIT_DEPARTED, unit_id, res_id, move_id, prev_event_id)
            prev_event_id = eid

            # Release resource
            self._simpy_resources[res_id].release(req)
            eid = self._emit(EVT_RESOURCE_RELEASED, unit_id, res_id, move_id, prev_event_id)
            prev_event_id = eid

        # Move complete
        self._emit(EVT_MOVE_COMPLETED, unit_id, last, move_id, prev_event_id)

    def run(self) -> list[dict]:
        layout = self._layout
        # Stagger unit arrivals slightly (seeded)
        for i in range(layout.unit_count):
            unit_id = f"{layout.unit_id_prefix}{i+1:04d}"
            delay = i * (layout.process_time / 2)
            self._env.process(self._staggered(unit_id, delay))
        self._env.run(until=layout.total_time)
        return list(self._events)

    def _staggered(self, unit_id: str, delay: float):
        yield self._env.timeout(delay)
        yield from self._unit_process(unit_id)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def run_simulation(seed: int = 42, layout_path: Optional[Path] = None) -> list[dict]:
    """Run the seeded SimPy simulation and return normalized events.

    Args:
        seed: RNG seed — same seed → same sequence.
        layout_path: Override layout YAML path (defaults to layout/auto.yaml).

    Returns:
        List of normalized event dicts.
    """
    path = Path(layout_path) if layout_path else _DEFAULT_LAYOUT
    layout = _load_layout(path)
    sim = Simulation(seed=seed, layout=layout)
    return sim.run()


def produce_to_kafka(
    events: list[dict],
    bootstrap_servers: str = "localhost:19092",
    topic: str = "rtw.events",
) -> None:
    """Produce normalized events to Kafka. Key = resourceId (bytes).

    Args:
        events: Normalized event dicts from run_simulation().
        bootstrap_servers: Kafka bootstrap address.
        topic: Target topic name.
    """
    from kafka import KafkaProducer  # kafka-python

    producer = KafkaProducer(
        bootstrap_servers=bootstrap_servers,
        key_serializer=lambda k: k.encode("utf-8"),
        value_serializer=lambda v: json.dumps(v).encode("utf-8"),
        acks="all",
    )
    for ev in events:
        producer.send(topic, key=ev["resourceId"], value=ev)
    producer.flush()
    producer.close()


# ---------------------------------------------------------------------------
# Backward-compat stub (Chunk 0 contract)
# ---------------------------------------------------------------------------

def create_generator(seed: int = 42):
    """Return a configured (but not yet started) generator.

    Args:
        seed: RNG seed for deterministic replay.  Same seed → same event sequence.

    Returns:
        A generator object (stub kept for Chunk 0 backward compatibility).
    """
    return {"seed": seed, "running": False}
