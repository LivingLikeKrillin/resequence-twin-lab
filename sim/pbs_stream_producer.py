"""
sim/pbs_stream_producer.py — Pure deterministic PBS event-stream builder with fault injection,
plus a paced Kafka producer and argparse CLI (Task 2 layer).

Produces a list[dict] representing the wire events that S2 (the Kafka consumer, Kotlin) will
deserialize. The pure build layer has NO kafka import; kafka is only imported lazily inside
``produce()`` when ``dry_run=False``.

Wire schema (camelCase, discriminated by ``type``):
  - BodyArrived  : type, eventId, seq, bodyId, color, model, options (sorted list), dueDateSeq
  - ReleaseTick  : type, eventId, seq
  - LaneBlocked  : type, eventId, seq, laneId
  - LaneUnblocked: type, eventId, seq, laneId

Public API
----------
    build_event_stream(seed, bodies, release_every, injects) -> list[dict]
    serialize_record(record) -> str
    to_records(stream) -> list[str]
    produce(records, bootstrap_servers, topic, rate, dry_run, out) -> int
    main(argv) -> int
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from collections.abc import Set as AbstractSet
from typing import TextIO

from paint_stream import generate_paint_stream

# Default lane IDs — must match the S2 consumer's ``pbs.lanes`` property
# (default: ``L1:10,L2:10,L3:10`` in control/src/main/resources/application.properties).
# A lane-block inject targeting a lane id absent from the consumer topology will be
# Rejected by the consumer (not a deserialization failure).
_DEFAULT_LANES = ["L1", "L2", "L3"]


# ---------------------------------------------------------------------------
# Primary builder
# ---------------------------------------------------------------------------

def build_event_stream(
    seed: int,
    bodies: int,
    release_every: int = 3,
    injects: AbstractSet[str] = frozenset(),
) -> list[dict]:
    """Build a deterministic, ordered PBS event-stream list.

    Parameters
    ----------
    seed:
        RNG seed forwarded to ``generate_paint_stream``.  Same seed → identical
        stream (including identical eventIds), enabling S2 idempotency dedup on
        replay.
    bodies:
        Number of car bodies to emit (BodyArrived count == bodies).
    release_every:
        Emit a ReleaseTick after every N BodyArrived events (in-stream cadence).
    injects:
        A set of fault-injection tags (combinable):
          - ``"lane-block"``   : insert LaneBlocked{laneId:"L1"} after body #2 + LaneUnblocked{laneId:"L1"}
                                 after body #3.  Requires bodies >= 3.  The laneId ("L1") must exist in
                                 the S2 consumer topology (see ``_DEFAULT_LANES`` comment).
          - ``"duplicate"``    : re-emit a previously-emitted event verbatim (same eventId) mid-stream.
          - ``"out-of-order"`` : insert one event whose seq is intentionally lower than its predecessor.
          - ``"malformed"``    : append a sentinel dict {``__malformed__``: <broken JSON string>}
                                 followed by at least one valid event (trailing ReleaseTick).

    Returns
    -------
    list[dict]
        Ordered event records.  Non-malformed dicts are JSON-serializable.

    eventId determinism rule
    ------------------------
    ``eventId = f"s{seed}-e{seq}"`` for every event whose seq was assigned in the
    normal monotonic counter.  A re-run with the same seed produces *identical* ids
    at each position, which is the property the S2 consumer exploits for idempotent
    dedup on replayed streams.

    **Exception — out-of-order inject:** the single OOR-injected event has its
    ``seq`` field spoofed to a low value (1) to simulate an out-of-order arrival,
    but its ``eventId`` encodes the *real* monotonic counter position at the time
    of creation (``f"s{seed}-e{real_counter}"``).  This makes the OOR eventId
    unique and traceable while the spoofed seq triggers consumer reordering logic.

    Trailing-tick rule
    ------------------
    After all bodies have been emitted, we append ``bodies`` trailing ReleaseTicks.
    Rationale: in the worst case the last body arrives just after the Nth in-stream
    tick, leaving up to ``release_every - 1`` bodies un-drained.  Emitting ``bodies``
    trailing ticks is a conservative upper bound that guarantees every body can drain
    regardless of ``release_every``.  These ticks continue the monotonic seq counter.
    """
    body_list = generate_paint_stream(seed=seed, total_bodies=bodies)

    stream: list[dict] = []
    seq = 0  # monotonic counter; incremented before each event assignment

    def next_seq() -> int:
        nonlocal seq
        seq += 1
        return seq

    def event_id(s: int) -> str:
        return f"s{seed}-e{s}"

    # -----------------------------------------------------------------------
    # Inject bookkeeping — decide insertion points deterministically
    # -----------------------------------------------------------------------

    # lane-block: block after the 2nd body, unblock after the 3rd body.
    # Using a fixed offset (+1) rather than bodies//2 keeps the threshold
    # non-circular and guarantees BOTH events always appear when the guard passes.
    lane_block_after_body = 2           # insert LaneBlocked after this many arrivals
    lane_unblock_after_body = lane_block_after_body + 1  # insert LaneUnblocked after this many arrivals

    # duplicate: save a copy of the 1st BodyArrived and re-emit after the 2nd tick
    first_arrival_copy: dict | None = None
    duplicate_after_tick = 2  # emit the duplicate after the 2nd ReleaseTick

    # out-of-order: inserted after the 3rd body as a low-seq interloper
    oor_after_body = 3

    # -----------------------------------------------------------------------
    # Guard: raise early if a requested inject cannot be satisfied
    # -----------------------------------------------------------------------
    if "lane-block" in injects and bodies < lane_unblock_after_body:
        raise ValueError(
            f"inject 'lane-block' requires bodies >= {lane_unblock_after_body} "
            f"(need body #{lane_block_after_body} for LaneBlocked AND body #{lane_unblock_after_body} "
            f"for LaneUnblocked), got {bodies}"
        )
    if "out-of-order" in injects and bodies < oor_after_body:
        raise ValueError(
            f"inject 'out-of-order' requires bodies >= {oor_after_body}, got {bodies}"
        )
    # duplicate fires after the 2nd in-stream ReleaseTick; at least one in-stream tick
    # requires bodies >= release_every, and 2 ticks requires bodies >= 2 * release_every.
    if "duplicate" in injects and bodies < duplicate_after_tick * release_every:
        raise ValueError(
            f"inject 'duplicate' requires bodies >= {duplicate_after_tick * release_every} "
            f"(duplicate_after_tick={duplicate_after_tick} × release_every={release_every}), "
            f"got {bodies}"
        )

    # Counters
    arrival_count = 0
    tick_count = 0
    oor_injected = False
    duplicate_injected = False

    # -----------------------------------------------------------------------
    # Main walk
    # -----------------------------------------------------------------------
    for body in body_list:
        # --- BodyArrived ---
        s = next_seq()
        record: dict = {
            "type": "BodyArrived",
            "eventId": event_id(s),
            "seq": s,
            "bodyId": body.id,
            "color": body.color,
            "model": body.model,
            "options": sorted(body.options),
            "dueDateSeq": body.due_date_seq,
        }
        stream.append(record)
        arrival_count += 1

        # Save first arrival for duplicate inject (copy so later mutations can't affect it)
        if first_arrival_copy is None:
            first_arrival_copy = record.copy()

        # lane-block: insert LaneBlocked after the designated arrival
        if "lane-block" in injects and arrival_count == lane_block_after_body:
            lb_seq = next_seq()
            stream.append({
                "type": "LaneBlocked",
                "eventId": event_id(lb_seq),
                "seq": lb_seq,
                "laneId": _DEFAULT_LANES[0],
            })

        # lane-unblock: insert LaneUnblocked after the designated arrival
        if "lane-block" in injects and arrival_count == lane_unblock_after_body:
            lu_seq = next_seq()
            stream.append({
                "type": "LaneUnblocked",
                "eventId": event_id(lu_seq),
                "seq": lu_seq,
                "laneId": _DEFAULT_LANES[0],
            })

        # out-of-order: insert one event with a seq intentionally below current
        if "out-of-order" in injects and arrival_count == oor_after_body and not oor_injected:
            # seq for this event is the next normal counter value, but we report
            # it as seq=1 so it is below the preceding seq
            oor_seq = next_seq()
            stream.append({
                "type": "ReleaseTick",
                "eventId": event_id(oor_seq),
                "seq": 1,  # deliberately lower than current position → out-of-order
            })
            oor_injected = True

        # --- ReleaseTick (in-stream cadence) ---
        if arrival_count % release_every == 0:
            t_seq = next_seq()
            tick_record: dict = {
                "type": "ReleaseTick",
                "eventId": event_id(t_seq),
                "seq": t_seq,
            }
            stream.append(tick_record)
            tick_count += 1

            # duplicate: re-emit first arrival verbatim after the 2nd tick
            if (
                "duplicate" in injects
                and tick_count == duplicate_after_tick
                and not duplicate_injected
                and first_arrival_copy is not None
            ):
                stream.append(first_arrival_copy)  # same dict, same eventId
                duplicate_injected = True

    # -----------------------------------------------------------------------
    # Trailing ReleaseTicks — drain rule: emit `bodies` trailing ticks
    # (conservative upper bound ensuring every body can drain)
    # -----------------------------------------------------------------------
    for _ in range(bodies):
        t_seq = next_seq()
        stream.append({
            "type": "ReleaseTick",
            "eventId": event_id(t_seq),
            "seq": t_seq,
        })

    # -----------------------------------------------------------------------
    # Malformed inject — sentinel + one valid trailing event
    # -----------------------------------------------------------------------
    if "malformed" in injects:
        stream.append({"__malformed__": '{"type": "BodyArrived", "seq": NOTANUMBER'})
        # Emit one valid ReleaseTick after the sentinel so consumers can prove
        # the stream continues past a poison record
        recovery_seq = next_seq()
        stream.append({
            "type": "ReleaseTick",
            "eventId": event_id(recovery_seq),
            "seq": recovery_seq,
        })

    return stream


# ---------------------------------------------------------------------------
# Serialization helpers
# ---------------------------------------------------------------------------

def serialize_record(record: dict) -> str:
    """Serialize a single event record to a string.

    If the record is a malformed sentinel (contains ``__malformed__`` key),
    the raw broken string stored in that key is returned UNCHANGED — this is
    intentionally invalid JSON so that downstream consumers can observe and
    handle a poison record.

    For all other records, ``json.dumps`` is used (compact, no extra whitespace).
    """
    if "__malformed__" in record:
        return record["__malformed__"]
    return json.dumps(record)


def to_records(stream: list[dict]) -> list[str]:
    """Map a stream list through ``serialize_record`` → list[str].

    Convenience wrapper: ``[serialize_record(e) for e in stream]``.
    One string per event; order preserved.
    """
    return [serialize_record(e) for e in stream]


# ---------------------------------------------------------------------------
# Kafka producer (Task 2) — thin, paced, lazy kafka import
# ---------------------------------------------------------------------------

_ALLOWED_INJECTS = frozenset({"lane-block", "duplicate", "out-of-order", "malformed"})


def produce(
    records: list[str],
    bootstrap_servers: str,
    topic: str,
    rate: float,
    dry_run: bool = False,
    out: TextIO | None = None,
) -> int:
    """Send ``records`` to Kafka (or stdout when dry-run).

    Parameters
    ----------
    records:
        Already-serialized event strings (output of ``to_records``).
    bootstrap_servers:
        Kafka bootstrap address, e.g. ``"localhost:19092"``.
    topic:
        Target Kafka topic name.
    rate:
        Events per second. ``rate <= 0`` means send as fast as possible
        (no ``time.sleep`` between sends).
    dry_run:
        When ``True``, write each record as a line to *out* instead of
        connecting to Kafka. No kafka import occurs on this path.
    out:
        Output file-like object for dry-run mode (default: ``sys.stdout``).

    Returns
    -------
    int
        Number of records written / sent.
    """
    if dry_run:
        _out = out if out is not None else sys.stdout
        for record in records:
            _out.write(record + "\n")
        return len(records)

    # --- Live Kafka path (lazy import so module is importable without kafka) ---
    from kafka import KafkaProducer  # noqa: PLC0415

    producer = KafkaProducer(
        bootstrap_servers=bootstrap_servers,
        value_serializer=lambda s: s.encode("utf-8"),
        acks="all",
    )
    sleep_secs = (1.0 / rate) if rate > 0 else 0.0
    try:
        for record in records:
            producer.send(topic, value=record)
            if sleep_secs > 0:
                time.sleep(sleep_secs)
        producer.flush()
    finally:
        producer.close()
    return len(records)


# ---------------------------------------------------------------------------
# CLI entry-point (Task 2)
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    """Argparse CLI for the PBS stream producer.

    Examples
    --------
    Dry-run (stdout = pure JSON lines, stderr = summary):

        python -m pbs_stream_producer --dry-run --seed 42 --bodies 20

    With fault injection (multi-flag or comma-separated):

        python -m pbs_stream_producer --dry-run --inject lane-block --inject malformed
        python -m pbs_stream_producer --dry-run --inject lane-block,malformed

    Produce to a running Kafka broker:

        python -m pbs_stream_producer --bootstrap localhost:19092 --topic pbs-events

    Returns
    -------
    int
        0 on success, non-zero on error.
    """
    parser = argparse.ArgumentParser(
        prog="pbs_stream_producer",
        description=(
            "[SYNTHETIC PoC] Build a deterministic PBS event stream and produce it "
            "to Kafka (or dry-run to stdout)."
        ),
    )
    parser.add_argument("--seed", type=int, default=42,
                        help="RNG seed for deterministic replay (default: 42)")
    parser.add_argument("--bodies", type=int, default=30,
                        help="Number of car bodies to simulate (default: 30)")
    parser.add_argument("--bootstrap", default="localhost:19092",
                        help="Kafka bootstrap servers (default: localhost:19092)")
    parser.add_argument("--topic", default="pbs-events",
                        help="Kafka topic (default: pbs-events)")
    parser.add_argument("--rate", type=float, default=5.0,
                        help="Events per second; 0 = no pacing (default: 5.0)")
    parser.add_argument("--release-every", type=int, default=3,
                        help="Emit a ReleaseTick every N BodyArrived events (default: 3)")
    parser.add_argument(
        "--inject",
        action="append",
        dest="inject",
        metavar="FAULT",
        help=(
            "Fault injection tag; repeatable or comma-separated. "
            "Choices: lane-block, duplicate, out-of-order, malformed"
        ),
    )
    parser.add_argument("--dry-run", action="store_true",
                        help="Write JSON lines to stdout instead of sending to Kafka")

    args = parser.parse_args(argv)

    # --- Collect and validate inject tags ---
    raw_injects: list[str] = []
    for token in (args.inject or []):
        # Support comma-separated values within a single --inject argument
        raw_injects.extend(tag.strip() for tag in token.split(",") if tag.strip())

    unknown = set(raw_injects) - _ALLOWED_INJECTS
    if unknown:
        print(
            f"Error: unknown --inject value(s): {', '.join(sorted(unknown))}. "
            f"Allowed: {', '.join(sorted(_ALLOWED_INJECTS))}",
            file=sys.stderr,
        )
        return 2

    injects: frozenset[str] = frozenset(raw_injects)

    # --- Build stream ---
    try:
        stream = build_event_stream(
            seed=args.seed,
            bodies=args.bodies,
            release_every=args.release_every,
            injects=injects,
        )
    except ValueError as exc:  # domain validation: inject cannot be satisfied for given bodies
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    records = to_records(stream)

    # --- Summary (always to stderr so stdout stays pure JSON lines in dry-run) ---
    mode_label = "dry-run (stdout)" if args.dry_run else f"broker {args.bootstrap} topic={args.topic}"
    injects_label = ", ".join(sorted(injects)) if injects else "none"
    print(
        f"[pbs_stream_producer] SYNTHETIC PoC | seed={args.seed} bodies={args.bodies} "
        f"records={len(records)} injects=[{injects_label}] mode={mode_label}",
        file=sys.stderr,
    )

    # --- Produce ---
    produce(
        records,
        bootstrap_servers=args.bootstrap,
        topic=args.topic,
        rate=args.rate,
        dry_run=args.dry_run,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
