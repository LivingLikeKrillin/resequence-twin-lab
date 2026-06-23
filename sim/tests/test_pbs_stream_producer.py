"""
Tests for sim/pbs_stream_producer.py — pure deterministic PBS event-stream builder.

Wire schema tested here must match exactly the S2 Kotlin Kafka consumer contract.
All tests are offline (no Kafka). Runs in the default pytest suite (not integration-marked).
"""
from __future__ import annotations

import json
import os
import sys

import pytest

# Follow the pattern established in the sim/tests/ directory: add sim/ to path.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pbs_stream_producer import (  # noqa: E402
    _DEFAULT_LANES,
    build_event_stream,
    serialize_record,
    to_records,
)

# ---------------------------------------------------------------------------
# Constants matching the wire schema
# ---------------------------------------------------------------------------
WIRE_TYPES = {"BodyArrived", "ReleaseTick", "LaneBlocked", "LaneUnblocked"}
BODY_ARRIVED_KEYS = {"type", "eventId", "seq", "bodyId", "color", "model", "options", "dueDateSeq"}
RELEASE_TICK_KEYS = {"type", "eventId", "seq"}
LANE_EVENT_KEYS   = {"type", "eventId", "seq", "laneId"}


# ===========================================================================
# 1. Determinism
# ===========================================================================

class TestDeterminism:
    def test_same_seed_produces_identical_stream(self):
        """Two calls with the same seed must return byte-for-byte identical lists."""
        s1 = build_event_stream(seed=42, bodies=10)
        s2 = build_event_stream(seed=42, bodies=10)
        assert s1 == s2

    def test_different_seed_produces_different_stream(self):
        """Different seeds must produce at least one differing event."""
        s1 = build_event_stream(seed=1, bodies=10)
        s2 = build_event_stream(seed=2, bodies=10)
        assert s1 != s2

    def test_event_ids_are_identical_across_same_seed_runs(self):
        """eventId = f's{seed}-e{seq}' — same seed → same ids at same position."""
        s1 = build_event_stream(seed=7, bodies=5)
        s2 = build_event_stream(seed=7, bodies=5)
        ids1 = [e["eventId"] for e in s1]
        ids2 = [e["eventId"] for e in s2]
        assert ids1 == ids2


# ===========================================================================
# 2. Wire schema — field names, types, values
# ===========================================================================

class TestWireSchema:
    def _stream(self, bodies=10, seed=42, **kwargs):
        return build_event_stream(seed=seed, bodies=bodies, **kwargs)

    def test_all_event_types_are_valid(self):
        """`type` field on every event must be one of the four wire types."""
        stream = self._stream()
        for evt in stream:
            assert evt["type"] in WIRE_TYPES, f"Unknown type: {evt['type']}"

    def test_body_arrived_has_exact_keys(self):
        """BodyArrived events must have exactly the required wire keys — no more, no less."""
        stream = self._stream()
        for evt in stream:
            if evt["type"] == "BodyArrived":
                assert set(evt.keys()) == BODY_ARRIVED_KEYS, (
                    f"BodyArrived key mismatch: {set(evt.keys())} != {BODY_ARRIVED_KEYS}"
                )

    def test_release_tick_has_exact_keys(self):
        """ReleaseTick events must have exactly the three wire keys."""
        stream = self._stream()
        for evt in stream:
            if evt["type"] == "ReleaseTick":
                assert set(evt.keys()) == RELEASE_TICK_KEYS

    def test_lane_events_have_exact_keys(self):
        """LaneBlocked/LaneUnblocked events must include laneId."""
        stream = self._stream(injects={"lane-block"})
        lane_events = [e for e in stream if e["type"] in ("LaneBlocked", "LaneUnblocked")]
        assert len(lane_events) >= 2, "Expected at least one LaneBlocked + LaneUnblocked"
        for evt in lane_events:
            assert set(evt.keys()) == LANE_EVENT_KEYS

    def test_body_arrived_field_types(self):
        """BodyArrived field values must have correct Python types for JSON serialization."""
        stream = self._stream()
        for evt in stream:
            if evt["type"] == "BodyArrived":
                assert isinstance(evt["eventId"], str)
                assert isinstance(evt["seq"], int)
                assert isinstance(evt["bodyId"], str)
                assert isinstance(evt["color"], str)
                assert isinstance(evt["model"], str)
                assert isinstance(evt["options"], list)
                assert isinstance(evt["dueDateSeq"], int)

    def test_options_is_sorted_list(self):
        """`options` must be a sorted list of strings (not a frozenset)."""
        stream = self._stream(bodies=30, seed=42)
        for evt in stream:
            if evt["type"] == "BodyArrived":
                opts = evt["options"]
                assert isinstance(opts, list)
                assert opts == sorted(opts), f"options not sorted: {opts}"
                assert all(isinstance(o, str) for o in opts)

    def test_body_id_mapped_from_body_id(self):
        """`bodyId` must match the Body.id value from the paint stream (e.g. BODY-00000)."""
        stream = self._stream(bodies=5, seed=0)
        arrivals = [e for e in stream if e["type"] == "BodyArrived"]
        # The first body should be BODY-00000
        assert arrivals[0]["bodyId"].startswith("BODY-")

    def test_due_date_seq_mapped_from_body_due_date_seq(self):
        """`dueDateSeq` must be an int derived from Body.due_date_seq."""
        stream = self._stream(bodies=5, seed=0)
        for evt in stream:
            if evt["type"] == "BodyArrived":
                assert isinstance(evt["dueDateSeq"], int)
                assert evt["dueDateSeq"] >= 0

    def test_body_count_matches_bodies_param(self):
        """Number of BodyArrived events must equal the `bodies` parameter."""
        for n in (1, 5, 15, 30):
            stream = build_event_stream(seed=42, bodies=n)
            arrivals = [e for e in stream if e["type"] == "BodyArrived"]
            assert len(arrivals) == n, f"Expected {n} arrivals, got {len(arrivals)}"


# ===========================================================================
# 3. seq assignment and eventId format
# ===========================================================================

class TestSeqAndEventId:
    def test_seq_starts_at_1(self):
        """The first event must have seq=1."""
        stream = build_event_stream(seed=42, bodies=5)
        assert stream[0]["seq"] == 1

    def test_seq_is_monotonically_increasing_without_inject(self):
        """Without fault injection, seq must strictly increase by 1 each step."""
        stream = build_event_stream(seed=42, bodies=10)
        seqs = [e["seq"] for e in stream]
        for i in range(1, len(seqs)):
            assert seqs[i] == seqs[i - 1] + 1, (
                f"seq gap at index {i}: {seqs[i-1]} -> {seqs[i]}"
            )

    def test_event_id_format_matches_seed_seq(self):
        """eventId must follow f's{seed}-e{seq}' for deterministic dedup replay."""
        seed = 42
        stream = build_event_stream(seed=seed, bodies=5)
        for evt in stream:
            expected_id = f"s{seed}-e{evt['seq']}"
            assert evt["eventId"] == expected_id, (
                f"eventId mismatch: {evt['eventId']} != {expected_id}"
            )

    def test_event_ids_are_unique_without_inject(self):
        """Without the duplicate inject, all eventIds must be unique."""
        stream = build_event_stream(seed=42, bodies=10)
        ids = [e["eventId"] for e in stream]
        assert len(ids) == len(set(ids)), "Duplicate eventIds found without inject"


# ===========================================================================
# 4. ReleaseTick cadence and trailing ticks
# ===========================================================================

class TestReleaseTickCadence:
    def test_release_tick_appears_after_every_n_arrivals(self):
        """After every `release_every` BodyArrivals there must be a ReleaseTick."""
        release_every = 3
        stream = build_event_stream(seed=42, bodies=9, release_every=release_every)
        # Walk the stream; count arrivals between ticks
        arrival_count = 0
        ticks_at = []
        for evt in stream:
            if evt["type"] == "BodyArrived":
                arrival_count += 1
            elif evt["type"] == "ReleaseTick":
                ticks_at.append(arrival_count)
        # Check the in-stream ticks (before trailing) are at multiples of release_every
        in_stream_ticks = ticks_at[: 9 // release_every]  # 3 in-stream ticks for 9 bodies
        for i, tick_pos in enumerate(in_stream_ticks, 1):
            assert tick_pos == i * release_every, (
                f"ReleaseTick {i} at arrival #{tick_pos}, expected {i * release_every}"
            )

    def test_trailing_ticks_present(self):
        """After all bodies, exactly `bodies` trailing ReleaseTicks must be appended (drain rule)."""
        bodies = 5
        stream = build_event_stream(seed=42, bodies=bodies)
        # Find the last BodyArrived index
        last_arrival_idx = max(
            i for i, e in enumerate(stream) if e["type"] == "BodyArrived"
        )
        trailing = stream[last_arrival_idx + 1:]
        ticks = [e for e in trailing if e["type"] == "ReleaseTick"]
        assert len(ticks) == bodies, (
            f"Expected exactly {bodies} trailing ReleaseTicks (drain rule), got {len(ticks)}"
        )

    def test_release_every_default_is_3(self):
        """Default release_every=3: ReleaseTick after every 3 arrivals."""
        stream = build_event_stream(seed=42, bodies=6)
        # With 6 bodies and release_every=3, we expect at least 2 in-stream ticks
        arrival_count = 0
        tick_positions = []
        for evt in stream:
            if evt["type"] == "BodyArrived":
                arrival_count += 1
            elif evt["type"] == "ReleaseTick" and arrival_count <= 6:
                tick_positions.append(arrival_count)
        # First tick after 3rd arrival, second tick after 6th
        assert 3 in tick_positions
        assert 6 in tick_positions

    def test_release_every_1_gives_tick_after_every_body(self):
        """release_every=1 means a ReleaseTick after each BodyArrived (bodies in-stream ticks)."""
        bodies = 4
        stream = build_event_stream(seed=42, bodies=bodies, release_every=1)
        # Count in-stream ticks: walk until we've passed all bodies
        arrival_count = 0
        in_stream_tick_count = 0
        for evt in stream:
            if evt["type"] == "BodyArrived":
                arrival_count += 1
            elif evt["type"] == "ReleaseTick" and arrival_count <= bodies and arrival_count > 0:
                # Only count ticks that appear while bodies are still arriving (in-stream)
                if in_stream_tick_count < bodies:
                    in_stream_tick_count += 1
        # With release_every=1, every body triggers one in-stream tick → exactly `bodies` in-stream ticks
        assert in_stream_tick_count == bodies, (
            f"Expected {bodies} in-stream ticks with release_every=1, got {in_stream_tick_count}"
        )


# ===========================================================================
# 5. Fault injection — individual faults
# ===========================================================================

class TestFaultInjectionLaneBlock:
    def test_lane_block_injects_blocked_then_unblocked_on_L1(self):
        """lane-block inject: LaneBlocked{laneId:L1} followed eventually by LaneUnblocked{laneId:L1}."""
        stream = build_event_stream(seed=42, bodies=10, injects={"lane-block"})
        blocked = [e for e in stream if e["type"] == "LaneBlocked"]
        unblocked = [e for e in stream if e["type"] == "LaneUnblocked"]
        assert len(blocked) >= 1
        assert len(unblocked) >= 1
        assert all(e["laneId"] == "L1" for e in blocked)
        assert all(e["laneId"] == "L1" for e in unblocked)

    def test_lane_block_comes_before_unblock(self):
        """LaneBlocked must appear before LaneUnblocked in stream order."""
        stream = build_event_stream(seed=42, bodies=10, injects={"lane-block"})
        blocked_idx = next(i for i, e in enumerate(stream) if e["type"] == "LaneBlocked")
        unblocked_idx = next(i for i, e in enumerate(stream) if e["type"] == "LaneUnblocked")
        assert blocked_idx < unblocked_idx

    def test_no_lane_events_without_inject(self):
        """Without lane-block inject, no LaneBlocked/LaneUnblocked events."""
        stream = build_event_stream(seed=42, bodies=10)
        lane_events = [e for e in stream if e["type"] in ("LaneBlocked", "LaneUnblocked")]
        assert len(lane_events) == 0


class TestFaultInjectionDuplicate:
    def test_duplicate_inject_produces_two_events_with_same_event_id(self):
        """duplicate inject: at least two events share the same eventId."""
        stream = build_event_stream(seed=42, bodies=10, injects={"duplicate"})
        ids = [e["eventId"] for e in stream]
        id_counts = {}
        for eid in ids:
            id_counts[eid] = id_counts.get(eid, 0) + 1
        duplicated = {k: v for k, v in id_counts.items() if v > 1}
        assert len(duplicated) >= 1, "Expected at least one duplicated eventId"

    def test_duplicate_event_is_verbatim_copy(self):
        """The duplicate must be byte-identical to the original (same dict)."""
        stream = build_event_stream(seed=42, bodies=10, injects={"duplicate"})
        seen = {}
        for evt in stream:
            eid = evt["eventId"]
            if eid in seen:
                assert evt == seen[eid], f"Duplicate {eid} differs from original"
            else:
                seen[eid] = evt


class TestFaultInjectionOutOfOrder:
    def test_out_of_order_inject_produces_decreasing_seq(self):
        """out-of-order inject: at least one event has seq lower than its predecessor."""
        stream = build_event_stream(seed=42, bodies=10, injects={"out-of-order"})
        seqs = [e["seq"] for e in stream]
        decreases = [
            i for i in range(1, len(seqs))
            if seqs[i] < seqs[i - 1]
        ]
        assert len(decreases) >= 1, "Expected at least one seq decrease with out-of-order inject"

    def test_out_of_order_event_has_unique_event_id(self):
        """The out-of-order injected event must have a unique eventId (not a duplicate)."""
        stream = build_event_stream(seed=42, bodies=10, injects={"out-of-order"})
        ids = [e["eventId"] for e in stream]
        id_counts = {}
        for eid in ids:
            id_counts[eid] = id_counts.get(eid, 0) + 1
        # Find the out-of-order event(s)
        seqs = [e["seq"] for e in stream]
        oor_events = [
            stream[i] for i in range(1, len(stream))
            if stream[i]["seq"] < stream[i - 1]["seq"]
        ]
        for evt in oor_events:
            assert id_counts[evt["eventId"]] == 1, (
                f"Out-of-order event {evt['eventId']} is a duplicate — should be unique"
            )


class TestFaultInjectionMalformed:
    def test_malformed_inject_appends_sentinel_dict(self):
        """malformed inject: stream contains a dict with __malformed__ key."""
        stream = build_event_stream(seed=42, bodies=10, injects={"malformed"})
        malformed = [e for e in stream if "__malformed__" in e]
        assert len(malformed) >= 1

    def test_malformed_sentinel_serializes_to_invalid_json(self):
        """serialize_record of the malformed sentinel must NOT be valid JSON."""
        stream = build_event_stream(seed=42, bodies=10, injects={"malformed"})
        sentinel = next(e for e in stream if "__malformed__" in e)
        raw = serialize_record(sentinel)
        try:
            json.loads(raw)
            raise AssertionError(f"Expected invalid JSON but json.loads succeeded on: {raw!r}")
        except (json.JSONDecodeError, ValueError):
            pass  # expected

    def test_valid_event_follows_malformed_sentinel(self):
        """At least one valid event must appear AFTER the malformed sentinel."""
        stream = build_event_stream(seed=42, bodies=10, injects={"malformed"})
        sentinel_idx = next(
            i for i, e in enumerate(stream) if "__malformed__" in e
        )
        events_after = stream[sentinel_idx + 1:]
        valid_after = [e for e in events_after if "__malformed__" not in e]
        assert len(valid_after) >= 1, "No valid events after the malformed sentinel"

    def test_valid_events_are_parseable_json(self):
        """All non-sentinel events must produce valid JSON via serialize_record."""
        stream = build_event_stream(seed=42, bodies=10, injects={"malformed"})
        for evt in stream:
            if "__malformed__" not in evt:
                serialized = serialize_record(evt)
                parsed = json.loads(serialized)  # must not raise
                assert parsed["type"] in WIRE_TYPES


# ===========================================================================
# 6. Combined fault injection
# ===========================================================================

class TestCombinedInjection:
    def test_lane_block_and_malformed_coexist(self):
        """lane-block + malformed together: both effects present in the stream."""
        stream = build_event_stream(seed=42, bodies=15, injects={"lane-block", "malformed"})
        has_blocked = any(e["type"] == "LaneBlocked" for e in stream)
        has_unblocked = any(e["type"] == "LaneUnblocked" for e in stream)
        has_malformed = any("__malformed__" in e for e in stream)
        assert has_blocked, "LaneBlocked missing with lane-block inject"
        assert has_unblocked, "LaneUnblocked missing with lane-block inject"
        assert has_malformed, "__malformed__ sentinel missing with malformed inject"

    def test_valid_records_in_combined_stream_parse_correctly(self):
        """Valid events in a combined-inject stream must still be JSON-serializable."""
        stream = build_event_stream(seed=42, bodies=15, injects={"lane-block", "malformed"})
        for evt in stream:
            if "__malformed__" not in evt:
                serialized = serialize_record(evt)
                parsed = json.loads(serialized)
                assert parsed["type"] in WIRE_TYPES

    def test_all_four_injects_together(self):
        """All four injects at once must not crash and must produce a non-empty stream."""
        injects = {"lane-block", "duplicate", "out-of-order", "malformed"}
        stream = build_event_stream(seed=42, bodies=20, injects=injects)
        assert len(stream) > 20  # base arrivals + ticks + injected events


# ===========================================================================
# 7. serialize_record and to_records
# ===========================================================================

class TestSerializeRecord:
    def test_serialize_normal_event_produces_valid_json(self):
        """serialize_record on a normal event dict returns valid JSON."""
        evt = {"type": "ReleaseTick", "eventId": "s42-e1", "seq": 1}
        result = serialize_record(evt)
        parsed = json.loads(result)
        assert parsed == evt

    def test_serialize_malformed_sentinel_returns_raw_string_unchanged(self):
        """serialize_record on the sentinel returns the __malformed__ value as-is."""
        broken = '{"type": "BodyArrived", "seq": NOTANUMBER'
        sentinel = {"__malformed__": broken}
        result = serialize_record(sentinel)
        assert result == broken

    def test_to_records_maps_through_serialize_record(self):
        """to_records returns a list[str] with one entry per stream event."""
        stream = build_event_stream(seed=42, bodies=5)
        records = to_records(stream)
        assert len(records) == len(stream)
        assert all(isinstance(r, str) for r in records)

    def test_to_records_with_malformed_includes_broken_string(self):
        """to_records with malformed inject contains the raw broken string."""
        stream = build_event_stream(seed=42, bodies=10, injects={"malformed"})
        records = to_records(stream)
        # One record must fail json.loads
        invalid_count = 0
        for r in records:
            try:
                json.loads(r)
            except (json.JSONDecodeError, ValueError):
                invalid_count += 1
        assert invalid_count >= 1, "Expected at least one invalid JSON record from malformed inject"


# ===========================================================================
# 8. Guard: unsatisfiable inject raises ValueError
# ===========================================================================

class TestInjectGuards:
    """Verify that under-specified parameters raise clear ValueErrors rather than
    silently omitting the requested inject."""

    # lane-block requires bodies >= 3 (lane_unblock_after_body = lane_block_after_body + 1 = 3)
    # so that BOTH LaneBlocked (after body #2) AND LaneUnblocked (after body #3) are emitted.
    def test_lane_block_raises_when_bodies_too_small(self):
        """lane-block with bodies < 3 must raise ValueError naming the inject and param."""
        with pytest.raises(ValueError, match="inject 'lane-block'"):
            build_event_stream(seed=42, bodies=1, injects={"lane-block"})

    def test_lane_block_raises_at_bodies_2(self):
        """lane-block with bodies=2 must raise: only LaneBlocked would fire, never LaneUnblocked."""
        with pytest.raises(ValueError, match="inject 'lane-block'"):
            build_event_stream(seed=42, bodies=2, injects={"lane-block"})

    def test_lane_block_works_at_minimum_bodies(self):
        """lane-block at bodies==3 (documented minimum) emits BOTH LaneBlocked AND LaneUnblocked on L1."""
        stream = build_event_stream(seed=42, bodies=3, injects={"lane-block"})
        blocked = [e for e in stream if e["type"] == "LaneBlocked"]
        unblocked = [e for e in stream if e["type"] == "LaneUnblocked"]
        assert len(blocked) >= 1, "LaneBlocked missing at minimum bodies=3"
        assert len(unblocked) >= 1, "LaneUnblocked missing at minimum bodies=3 (block-without-unblock)"
        assert blocked[0]["laneId"] == unblocked[0]["laneId"], (
            "LaneBlocked and LaneUnblocked must reference the same laneId"
        )

    # out-of-order requires bodies >= 3 (oor_after_body)
    def test_out_of_order_raises_when_bodies_too_small(self):
        """out-of-order with bodies < 3 must raise ValueError naming the inject and param."""
        with pytest.raises(ValueError, match="inject 'out-of-order'"):
            build_event_stream(seed=42, bodies=2, injects={"out-of-order"})

    def test_out_of_order_works_at_minimum_bodies(self):
        """out-of-order must not raise when bodies == 3 (the documented minimum)."""
        stream = build_event_stream(seed=42, bodies=3, injects={"out-of-order"})
        seqs = [e["seq"] for e in stream]
        decreases = [i for i in range(1, len(seqs)) if seqs[i] < seqs[i - 1]]
        assert len(decreases) >= 1

    # duplicate requires bodies >= duplicate_after_tick * release_every == 2 * 3 == 6 (default)
    def test_duplicate_raises_when_bodies_too_small(self):
        """duplicate with bodies < 6 (default release_every=3) must raise ValueError."""
        with pytest.raises(ValueError, match="inject 'duplicate'"):
            build_event_stream(seed=42, bodies=5, injects={"duplicate"})

    def test_duplicate_works_at_minimum_bodies(self):
        """duplicate must not raise when bodies == 6 (2 × release_every=3)."""
        stream = build_event_stream(seed=42, bodies=6, injects={"duplicate"})
        ids = [e["eventId"] for e in stream]
        id_counts: dict[str, int] = {}
        for eid in ids:
            id_counts[eid] = id_counts.get(eid, 0) + 1
        assert any(v > 1 for v in id_counts.values()), "Expected duplicate eventId at minimum bodies"

    def test_duplicate_raises_respects_release_every(self):
        """duplicate with release_every=5 requires bodies >= 10; bodies=9 must raise."""
        with pytest.raises(ValueError, match="inject 'duplicate'"):
            build_event_stream(seed=42, bodies=9, release_every=5, injects={"duplicate"})

    def test_duplicate_works_at_minimum_with_custom_release_every(self):
        """duplicate with release_every=2 requires bodies >= 4; bodies=4 must work."""
        stream = build_event_stream(seed=42, bodies=4, release_every=2, injects={"duplicate"})
        ids = [e["eventId"] for e in stream]
        id_counts: dict[str, int] = {}
        for eid in ids:
            id_counts[eid] = id_counts.get(eid, 0) + 1
        assert any(v > 1 for v in id_counts.values()), "Expected duplicate eventId"


# ===========================================================================
# 9. OOR eventId encodes real counter position (documents the intended behaviour)
# ===========================================================================

class TestOutOfOrderEventId:
    def test_oor_event_id_uses_real_counter_not_spoofed_seq(self):
        """The OOR injected event has seq=1 (spoofed) but eventId encodes the real counter value.

        Per the module docstring: OOR is the single exception where eventId != f's{seed}-e{seq}'.
        eventId must be f's{seed}-e{real_counter}' where real_counter > 1.
        """
        seed = 42
        stream = build_event_stream(seed=seed, bodies=5, injects={"out-of-order"})
        # Identify the OOR event: seq < previous seq
        oor_events = [
            stream[i] for i in range(1, len(stream))
            if stream[i]["seq"] < stream[i - 1]["seq"]
        ]
        assert len(oor_events) == 1, "Expected exactly one OOR event"
        oor = oor_events[0]
        # Its seq is the spoofed low value (1)
        assert oor["seq"] == 1, f"OOR spoofed seq should be 1, got {oor['seq']}"
        # Its eventId must NOT encode the spoofed seq
        spoofed_id = f"s{seed}-e{oor['seq']}"  # s42-e1
        assert oor["eventId"] != spoofed_id, (
            f"OOR eventId should encode the real counter, not spoofed seq; got {oor['eventId']!r}"
        )
        # Its eventId must follow the f's{seed}-e{{real_counter}}' pattern with counter > 1
        prefix = f"s{seed}-e"
        assert oor["eventId"].startswith(prefix), (
            f"OOR eventId {oor['eventId']!r} doesn't follow s{{seed}}-e{{n}} pattern"
        )
        real_counter = int(oor["eventId"][len(prefix):])
        assert real_counter > 1, (
            f"OOR eventId counter must be > 1 (real position), got {real_counter}"
        )

    def test_lane_event_uses_default_lanes_constant(self):
        """LaneBlocked/LaneUnblocked laneId must equal _DEFAULT_LANES[0], not a hardcoded string."""
        stream = build_event_stream(seed=42, bodies=10, injects={"lane-block"})
        lane_events = [e for e in stream if e["type"] in ("LaneBlocked", "LaneUnblocked")]
        for evt in lane_events:
            assert evt["laneId"] == _DEFAULT_LANES[0], (
                f"laneId {evt['laneId']!r} != _DEFAULT_LANES[0] {_DEFAULT_LANES[0]!r}"
            )
