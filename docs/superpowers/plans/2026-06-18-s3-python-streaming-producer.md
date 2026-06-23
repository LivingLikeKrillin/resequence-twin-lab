# S3 — Python Streaming Producer (paced emit · deterministic eventId · `--inject` faults)

**Module:** `sim/` (Python 3.11+, the synthetic-event side). **Branch:** `build/scaffold` (continue).
**Goal:** A Python producer that emits PBS events to Kafka topic `pbs-events` matching the S2 wire
schema, paced over time, with deterministic event ids (so a re-run demonstrates S2's replay/idempotency
end-to-end) and `--inject` fault injection (lane-block, duplicate, malformed, out-of-order). This is the
live counterpart to the S2 consumer — together they form a real cross-language event pipeline.

## Context (read before any task)

- `sim/` is a Python package (`pyproject.toml`, name `resequence-twin-sim`). Deps already present: `simpy`,
  `kafka-python>=2.0.2`, `pyyaml`; dev: `pytest`, `pytest-cov`. A `.venv` exists under `sim/.venv`.
- `sim/paint_stream.py` exposes `generate_paint_stream(seed=42, total_bodies=30, colors=None,
  models=None, options_pool=None, max_options_per_body=2, batch_size_range=(3,7)) -> list[Body]`.
  `Body` is a frozen dataclass: `id: str` (e.g. `"BODY-00000"`), `color: str`, `model: str`,
  `options: frozenset[str]`, `due_date_seq: int`. Same seed → identical list (deterministic, colour-batched).
- `sim/generator.py` (OHT legacy) shows the `kafka-python` producer pattern to reuse:
  `KafkaProducer(bootstrap_servers="localhost:19092", key_serializer=..., value_serializer=lambda v:
  json.dumps(v).encode("utf-8"), acks="all")`, then `.send(topic, key=, value=)`, `.flush()`, `.close()`.
- Tests live in `sim/tests/`, import modules via `sys.path.insert(0, ...)` then `from paint_stream import ...`.
  pytest config: `testpaths=["tests"]`, `addopts="-m 'not integration'"`, marker `integration` =
  "requires external services (Kafka, Ditto) — skipped by default". So broker-touching tests MUST be
  marked `@pytest.mark.integration`; everything else runs offline by default.
- **S2 WIRE SCHEMA (the consumer's contract — match EXACTLY, camelCase, discriminated by `type`):**
  - `BodyArrived`: `{"type":"BodyArrived","eventId":<str>,"seq":<int>,"bodyId":<str>,"color":<str>,
    "model":<str>,"options":[<str>...],"dueDateSeq":<int>}`
  - `ReleaseTick`: `{"type":"ReleaseTick","eventId":<str>,"seq":<int>}`
  - `LaneBlocked` / `LaneUnblocked`: `{"type":"LaneBlocked","eventId":<str>,"seq":<int>,"laneId":<str>}`
  Note the snake_case→camelCase mapping: Body.`id`→`bodyId`, Body.`due_date_seq`→`dueDateSeq`,
  `options` frozenset → JSON array (sort it for determinism). Default lane ids `L1,L2,L3` (match S2 topology).
  The S2 consumer uses `StringDeserializer` + `JsonDeserializer(useTypeHeaders=false)` — so producing
  plain JSON strings (NO Kafka type headers) is correct; the `type` field drives subtype resolution.
- **Honesty constraints (keep):** synthetic PoC; deterministic/seeded; the producer simulates a plant
  event source, it is not a real plant feed.

## Design principle (mirrors S2): separate PURE sequence-building from Kafka emission
The event sequence (and fault injection) must be a **pure, deterministic function** with NO Kafka
dependency, so it is fully unit-testable offline. Kafka emission (paced send) is a thin separate layer.
A `--dry-run` flag prints records to stdout instead of producing — lets a human/CI see output with no broker.

## TDD: RED → GREEN → refactor. Run `python -m pytest` from `sim/`. Commit per task.

---

## Task 1 — Pure deterministic event-stream builder + fault injection (offline)

**Spec:** A new `sim/pbs_stream_producer.py` with a pure function that turns a seed + parameters into an
ordered list of PBS event records (wire-schema dicts), plus a serializer, with deterministic event ids
and `--inject` fault support. NO Kafka import in the pure path.

- `build_event_stream(seed: int, bodies: int, release_every: int = 3, injects: set[str] = frozenset()) ->
  list[dict]`:
  - Call `generate_paint_stream(seed=seed, total_bodies=bodies)` to get the deterministic body list.
  - Walk the bodies assigning a monotonic `seq` (start 1). Emit a `BodyArrived` dict per body (mapped to
    the camelCase wire schema; `options` = `sorted(list(body.options))`). After every `release_every`
    body arrivals, interleave a `ReleaseTick` dict (own seq). After all bodies, append trailing
    `ReleaseTick`s so every arrived body can drain (e.g. ceil(bodies/?) — enough ticks; simplest: one
    trailing tick per remaining un-drained body, or `bodies` trailing ticks — pick a clear rule and
    document it).
  - **Deterministic event ids:** `eventId = f"s{seed}-e{seq}"` (or equivalent keyed on seed+seq) so a
    re-run with the same seed produces IDENTICAL ids — this is what lets S2's idempotency dedup trigger
    on replay. Document this explicitly.
  - **Fault injection** (`injects` is a set of strings; support these, each independently + combinable):
    - `"lane-block"`: insert a `LaneBlocked{laneId:"L1"}` event partway through, and a matching
      `LaneUnblocked{laneId:"L1"}` later (both with their own seq). (This exercises S2 graceful degradation.)
    - `"duplicate"`: re-emit a previously-emitted event verbatim (SAME eventId) somewhere in the stream
      (exercises S2 idempotency within a single run).
    - `"out-of-order"`: emit an event whose `seq` is lower than the preceding one (exercises S2
      process-anyway + outOfOrder counter). Keep its eventId unique.
  - Returns a `list[dict]` (the `"malformed"` inject is handled at the serialization layer — see below —
    so the pure dict list stays valid JSON-able except where a raw-broken sentinel is used).
- `serialize_record(record: dict) -> str`: `json.dumps`. To support the `"malformed"` inject, represent
  a deliberately-broken record with a sentinel `{"__malformed__": "<the broken string>"}` and have
  `serialize_record` return the raw broken string for it. `build_event_stream` appends one such sentinel
  when `"malformed"` in injects (followed by at least one VALID event, so a test can prove the stream
  continues past the poison record — matching S2's poison-skip test). Document this.
- `to_records(...)` convenience that maps the dict list through `serialize_record` → `list[str]` (the
  exact strings that will be put on the wire) is optional but handy for tests.

**Tests (`sim/tests/test_pbs_stream_producer.py`, OFFLINE — no Kafka, default-run):**
- Determinism: same seed → identical `build_event_stream` output; different seed → different.
- Schema: every `BodyArrived` dict has exactly the wire keys with correct types; `type` ∈ the 4 names;
  `options` is a sorted list; `bodyId`/`dueDateSeq` correctly mapped from `Body.id`/`due_date_seq`.
- `seq` is assigned and event ids follow the `s{seed}-e{seq}` rule (assert two runs share ids).
- `release_every` cadence: a `ReleaseTick` appears after every N arrivals; trailing ticks present.
- Each inject works: `lane-block` produces a matched LaneBlocked+LaneUnblocked on `L1`;
  `duplicate` yields two records with the same eventId; `out-of-order` yields a seq that decreases;
  `malformed` yields a record whose serialized form is NOT valid JSON, followed by ≥1 valid record.
- Combined injects (`{"lane-block","malformed"}`) coexist without breaking the valid records.

**Commit:** `feat(s3): pure deterministic PBS event-stream builder + fault injection`

---

## Task 2 — Kafka producer + paced CLI + dry-run + integration test

**Spec:** Wrap Task 1's pure builder with a `kafka-python` producer that emits records paced over time,
plus an argparse CLI, a `--dry-run` mode (stdout, no broker), and one broker-backed integration test
(marked `integration`, skipped by default).

- In `sim/pbs_stream_producer.py` add:
  - `produce(records: list[str], bootstrap_servers: str, topic: str, rate: float, dry_run: bool=False,
    out=<stdout>) -> int`: if `dry_run`, write each record (one per line) to `out` and return the count;
    else create a `KafkaProducer` (value as raw string → `value_serializer=lambda s: s.encode("utf-8")`,
    key=None or a constant — single partition; `acks="all"`), and `.send(topic, value=record)` paced by
    `rate` events/sec (`time.sleep(1.0/rate)` between sends when rate>0; rate<=0 = as fast as possible),
    then `.flush()`/`.close()`. Import `KafkaProducer` lazily inside the function (as `generator.py` does)
    so the module imports without a broker/kafka at test-collection time.
  - `main(argv=None)`: argparse with `--seed` (int, default 42), `--bodies` (int, default 30),
    `--bootstrap` (default `localhost:19092`), `--topic` (default `pbs-events`), `--rate` (float, default
    5.0, events/sec), `--release-every` (int, default 3), `--inject` (repeatable or comma-separated;
    choices: `lane-block,duplicate,out-of-order,malformed`), `--dry-run` (flag). Build the stream, serialize,
    call `produce`. Print a short honesty/summary line (synthetic, N records, seed). Make `main` callable
    with an argv list (for testability) and also via `if __name__ == "__main__"`.
- Keep `produce` thin: all sequence/fault logic stays in the Task 1 pure layer.

**Tests (`sim/tests/test_pbs_stream_producer_cli.py`):**
- OFFLINE (default-run): `--dry-run` path — call `main(["--dry-run","--seed","7","--bodies","10"])`
  capturing stdout (or `produce(..., dry_run=True, out=StringIO())`), assert N lines emitted, each valid
  per schema (parse the JSON lines), deterministic for the same seed. Assert `--inject malformed` makes
  exactly one stdout line that is not valid JSON. Assert CLI arg parsing (choices, defaults).
- INTEGRATION (`@pytest.mark.integration`, skipped by default): produce to a real broker at
  `localhost:19092` topic `pbs-events` (requires the Docker stack OR the running S2 app). Keep it small;
  it is a manual/Docker check, NOT part of the default green run. Document that it needs the broker.

**Commit:** `feat(s3): kafka producer + paced CLI + dry-run + integration test`

---

## Done criteria
- `python -m pytest` from `sim/` green by default (offline; integration tests skipped per pyproject).
- `python -m pbs_stream_producer --dry-run --seed 42 --bodies 20` (run from `sim/`) prints valid
  wire-schema JSON lines; `--inject` variants visibly alter the stream.
- The deterministic eventId scheme makes a re-run reproduce identical ids (enables S2 replay/idempotency
  to trigger when the same stream is produced twice against a live consumer).
- Update memory `resequence-twin-build-state.md` §C: mark S3 ✅ with commit SHAs + how it pairs with S2.
- (Optional human/Docker check, NOT required for task completion): run S2 app + this producer against the
  Docker Kafka, observe `/actuator/prometheus` `pbs.*` advance — flag this as the manual end-to-end verify.
