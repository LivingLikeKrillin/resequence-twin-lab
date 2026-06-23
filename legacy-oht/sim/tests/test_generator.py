"""
Chunk 1 generator tests.

Step 1: test_generator_emits_ordered_moves — seeded generator produces deterministic
        normalized events.  Run → FAIL before impl, PASS after.

Step 3: test_generator_to_kafka — integration test against localhost:19092.
        Requires running Kafka (Docker compose up).
"""
from __future__ import annotations

import uuid

import pytest


# ---------------------------------------------------------------------------
# Step 1 — deterministic seeded replay
# ---------------------------------------------------------------------------

def test_generator_emits_ordered_moves():
    """
    Same seed → identical normalized-event sequence.
    Each event must carry: eventId, ts, seq, unitId, resourceId, moveId,
    causationId, and eventType from the canonical set.
    Events must be ordered by (ts, seq) for the same resourceId.
    """
    from generator import run_simulation

    CANONICAL_EVENT_TYPES = {
        "MoveAssigned", "MoveStarted", "UnitMoved", "UnitArrived",
        "UnitDeparted", "ResourceOccupied", "ResourceReleased",
        "MoveBlocked", "MoveCompleted", "ErrorRaised",
    }

    events_a = run_simulation(seed=42)
    events_b = run_simulation(seed=42)

    # Must produce events
    assert len(events_a) > 0, "simulation produced no events"

    # Deterministic: same seed → same sequence
    assert len(events_a) == len(events_b), "same seed produced different lengths"
    for i, (a, b) in enumerate(zip(events_a, events_b)):
        assert a["eventType"] == b["eventType"], f"event[{i}] type mismatch"
        assert a["unitId"] == b["unitId"], f"event[{i}] unitId mismatch"
        assert a["resourceId"] == b["resourceId"], f"event[{i}] resourceId mismatch"
        assert a["seq"] == b["seq"], f"event[{i}] seq mismatch"

    # Schema check on first run
    for ev in events_a:
        assert "eventId" in ev, f"missing eventId: {ev}"
        assert "simTs" in ev, f"missing simTs: {ev}"
        assert "seq" in ev, f"missing seq: {ev}"
        assert "unitId" in ev, f"missing unitId: {ev}"
        assert "resourceId" in ev, f"missing resourceId: {ev}"
        assert "moveId" in ev, f"missing moveId: {ev}"
        assert "causationId" in ev, f"missing causationId: {ev}"
        assert ev["eventType"] in CANONICAL_EVENT_TYPES, \
            f"unknown eventType: {ev['eventType']}"

    # Per-resource ordering: for each resourceId, events must be non-decreasing by ts
    from collections import defaultdict
    by_resource: dict[str, list] = defaultdict(list)
    for ev in events_a:
        by_resource[ev["resourceId"]].append(ev)
    for res_id, evs in by_resource.items():
        for prev, nxt in zip(evs, evs[1:]):
            assert prev["simTs"] <= nxt["simTs"], \
                f"out-of-order simTs for resource {res_id}: {prev['simTs']} > {nxt['simTs']}"

    # Different seed → different eventIds / moveIds (eventType+unitId may coincide on
    # simple linear layouts, but the random IDs must differ)
    events_c = run_simulation(seed=99)
    event_ids_a = {ev["eventId"] for ev in events_a}
    event_ids_c = {ev["eventId"] for ev in events_c}
    assert event_ids_a != event_ids_c, \
        "different seeds produced identical eventId sets — seeding likely broken"


# ---------------------------------------------------------------------------
# Step 3 — Kafka integration
# ---------------------------------------------------------------------------

@pytest.mark.integration
def test_generator_to_kafka():
    """
    Produces events to Kafka (topic rtw.events, key=resourceId) and reads them
    back.  Requires localhost:19092 (Docker compose kafka).
    """
    from generator import run_simulation, produce_to_kafka
    from kafka import KafkaConsumer
    import json

    BOOTSTRAP = "localhost:19092"
    TOPIC = "rtw.events"

    events = run_simulation(seed=7)
    assert len(events) > 0

    # Produce
    produce_to_kafka(events, bootstrap_servers=BOOTSTRAP, topic=TOPIC)

    # Consume from beginning — use a unique group_id to read from start
    group_id = f"test-{uuid.uuid4().hex}"
    consumer = KafkaConsumer(
        TOPIC,
        bootstrap_servers=BOOTSTRAP,
        group_id=group_id,
        auto_offset_reset="earliest",
        enable_auto_commit=False,
        value_deserializer=lambda v: json.loads(v.decode("utf-8")),
        key_deserializer=lambda k: k.decode("utf-8") if k else None,
        consumer_timeout_ms=5000,
    )

    consumed: list[dict] = []
    try:
        for msg in consumer:
            consumed.append({"key": msg.key, "value": msg.value})
            if len(consumed) >= len(events):
                break
    finally:
        consumer.close()

    assert len(consumed) == len(events), \
        f"produced {len(events)} but consumed {len(consumed)}"

    # Key must equal resourceId (Kafka topic key = Resource id)
    for msg in consumed:
        assert msg["key"] is not None, "message key is None; expected resourceId"
        assert msg["key"] == msg["value"]["resourceId"], \
            f"key {msg['key']!r} != resourceId {msg['value']['resourceId']!r}"
