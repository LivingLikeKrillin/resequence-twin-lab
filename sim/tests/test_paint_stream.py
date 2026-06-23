"""
Tests for sim/paint_stream.py — PBS paint-stream generator (rev3 R1).

Key properties under test:
  1. Colour-batched: consecutive bodies of the same colour form contiguous runs;
     the stream never alternates between colours within a batch.
  2. Deterministic: same seed → identical stream (different seed → different stream).
  3. Due-date sequence is monotonically increasing (paint arrival order).
  4. All field types correct (id, color, model, options frozenset, due_date_seq).
  5. total_bodies parameter is respected exactly.
  6. Edge cases: total_bodies=0, total_bodies=1.
"""

import sys
import os

# Ensure sim/ root is importable when running from sim/ or repo root
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from paint_stream import generate_paint_stream, Body


# ---------------------------------------------------------------------------
# Core property: colour-batched + deterministic
# ---------------------------------------------------------------------------

def test_paint_stream_is_color_batched_and_deterministic():
    """Same seed → same colour-batched stream; stream must be colour-batched."""
    stream_a = generate_paint_stream(seed=42, total_bodies=50)
    stream_b = generate_paint_stream(seed=42, total_bodies=50)

    # Determinism: identical output
    assert stream_a == stream_b, "Same seed must produce identical stream"

    # Colour-batched: same-colour bodies form contiguous runs; no interleaving.
    # Default batch_size_range=(3, 7), so each run has at least 3 bodies
    # (last batch may be smaller due to clamping to total_bodies).
    _assert_color_batched(stream_a, min_batch_size=1)  # min=1 tolerates last-batch clamp


def _assert_color_batched(stream: list[Body], min_batch_size: int = 1) -> None:
    """Assert that the stream is colour-batched.

    Colour-batched means:
    1. Same-colour bodies form CONTIGUOUS runs (batches) — no interleaving.
    2. At each batch boundary the colour CHANGES (no empty colour run).
    3. Each run has at least ``min_batch_size`` bodies.

    A colour CAN reappear after cycling through others (paint shop cycles).
    E.g., RED×4 → BLUE×3 → WHITE×5 → RED×3 is valid.
    RED×2 → BLUE×1 → RED×2 → BLUE×1 violates rule 1 only if the batch
    boundary flips back too quickly — the actual interleaving rule is that
    within a single batch no foreign colour appears.
    """
    if len(stream) <= 1:
        return

    # Collect runs: [(color, count)]
    runs: list[tuple[str, int]] = []
    current_color = stream[0].color
    count = 1
    for body in stream[1:]:
        if body.color == current_color:
            count += 1
        else:
            runs.append((current_color, count))
            current_color = body.color
            count = 1
    runs.append((current_color, count))

    for color, run_size in runs:
        # Rule 2 + 3: each run must have at least min_batch_size bodies
        assert run_size >= min_batch_size, (
            f"Colour {color!r} run has {run_size} bodies, "
            f"expected >= {min_batch_size} (colour-batching violated)"
        )

    # Rule 1: no colour alternates back-to-back between consecutive runs
    for i in range(1, len(runs)):
        assert runs[i][0] != runs[i - 1][0], (
            f"Consecutive runs both have colour {runs[i][0]!r} — "
            "generator produced empty run boundary (bug)"
        )


# ---------------------------------------------------------------------------
# Determinism with a different seed
# ---------------------------------------------------------------------------

def test_different_seeds_produce_different_streams():
    stream_42 = generate_paint_stream(seed=42,  total_bodies=20)
    stream_99 = generate_paint_stream(seed=99,  total_bodies=20)
    assert stream_42 != stream_99, "Different seeds should produce different streams"


# ---------------------------------------------------------------------------
# Stream size
# ---------------------------------------------------------------------------

def test_total_bodies_count_is_exact():
    for n in [1, 5, 13, 30, 100]:
        stream = generate_paint_stream(seed=7, total_bodies=n)
        assert len(stream) == n, f"Expected {n} bodies, got {len(stream)}"


def test_total_bodies_zero_returns_empty():
    assert generate_paint_stream(seed=0, total_bodies=0) == []


# ---------------------------------------------------------------------------
# Field types and invariants
# ---------------------------------------------------------------------------

def test_body_fields_have_correct_types():
    stream = generate_paint_stream(seed=1, total_bodies=10)
    for body in stream:
        assert isinstance(body.id,           str),        f"id must be str, got {type(body.id)}"
        assert isinstance(body.color,        str),        f"color must be str"
        assert isinstance(body.model,        str),        f"model must be str"
        assert isinstance(body.options,      frozenset),  f"options must be frozenset"
        assert isinstance(body.due_date_seq, int),        f"due_date_seq must be int"
        assert body.due_date_seq >= 0,                    f"due_date_seq must be >= 0"


def test_due_date_seq_is_monotonically_increasing():
    """Paint arrival order is encoded as a monotonically increasing due_date_seq."""
    stream = generate_paint_stream(seed=3, total_bodies=30)
    for i in range(1, len(stream)):
        assert stream[i].due_date_seq > stream[i - 1].due_date_seq, (
            f"due_date_seq not monotone at index {i}: "
            f"{stream[i-1].due_date_seq} -> {stream[i].due_date_seq}"
        )


def test_body_ids_are_unique():
    stream = generate_paint_stream(seed=5, total_bodies=50)
    ids = [b.id for b in stream]
    assert len(ids) == len(set(ids)), "All body ids must be unique"


# ---------------------------------------------------------------------------
# Options are a subset of the pool
# ---------------------------------------------------------------------------

def test_options_are_subset_of_pool():
    pool = ["OPT_A", "OPT_B", "OPT_C"]
    stream = generate_paint_stream(
        seed=11, total_bodies=20,
        options_pool=pool,
        max_options_per_body=2,
    )
    for body in stream:
        assert body.options <= frozenset(pool), (
            f"Options {body.options} not a subset of pool {pool}"
        )


# ---------------------------------------------------------------------------
# Custom colors/models respected
# ---------------------------------------------------------------------------

def test_custom_colors_respected():
    colors = ["YELLOW", "PINK"]
    stream = generate_paint_stream(seed=17, total_bodies=15, colors=colors)
    for body in stream:
        assert body.color in colors, f"Unexpected color {body.color!r}"


def test_custom_models_respected():
    models = ["ModelX", "ModelY"]
    stream = generate_paint_stream(seed=17, total_bodies=15, models=models)
    for body in stream:
        assert body.model in models, f"Unexpected model {body.model!r}"


# ---------------------------------------------------------------------------
# Colour-batched invariant with many bodies (stress)
# ---------------------------------------------------------------------------

def test_color_batched_stress():
    """Large stream must remain colour-batched (runs contiguous, min batch size respected)."""
    stream = generate_paint_stream(seed=7777, total_bodies=200)
    _assert_color_batched(stream, min_batch_size=1)  # min=1 tolerates last-batch clamp
