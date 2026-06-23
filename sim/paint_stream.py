"""
sim/paint_stream.py — PBS paint-stream generator (rev3 R1).

Emits car bodies in PAINT-OPTIMAL order: colour-batched output from the paint shop,
interleaved with model/options/dueDateSeq drawn from a seeded RNG.

Automotive context: the paint shop minimises colour-change cost by grouping bodies
of the same colour into consecutive batches.  This is the input sequence that the
PBS buffer must RE-SEQUENCE into mixed-model (JIS) assembly order.

Public API
----------
    generate_paint_stream(
        seed: int,
        total_bodies: int,
        colors: list[str],
        models: list[str],
        options_pool: list[str],
        max_options_per_body: int,
        batch_size_range: tuple[int, int],
    ) -> list[Body]

Returned list is colour-batched (same seed → identical list).
"""
from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Optional

# ---------------------------------------------------------------------------
# Domain dataclass (mirrors Java Body for Python-side sim)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class Body:
    """Immutable car body as seen by the Python simulation."""
    id: str
    color: str
    model: str
    options: frozenset[str]
    due_date_seq: int

    def __repr__(self) -> str:
        return (
            f"Body(id={self.id!r}, color={self.color!r}, model={self.model!r}, "
            f"options={sorted(self.options)}, due_date_seq={self.due_date_seq})"
        )


# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

_DEFAULT_COLORS  = ["RED", "BLUE", "WHITE", "BLACK", "SILVER"]
_DEFAULT_MODELS  = ["Fiesta", "Focus", "Puma", "Kuga", "Mustang"]
_DEFAULT_OPTIONS = ["SUNROOF", "LEATHER", "SPORT_PKG", "TOWING_PKG", "AMBIENT_LIGHT"]
_DEFAULT_BATCH   = (3, 7)


# ---------------------------------------------------------------------------
# Generator
# ---------------------------------------------------------------------------

def generate_paint_stream(
    seed: int = 42,
    total_bodies: int = 30,
    colors: Optional[list[str]] = None,
    models: Optional[list[str]] = None,
    options_pool: Optional[list[str]] = None,
    max_options_per_body: int = 2,
    batch_size_range: tuple[int, int] = _DEFAULT_BATCH,
) -> list[Body]:
    """Generate a colour-batched paint-stream.

    Colours are emitted in consecutive batches (paint-optimal order).
    Model, options, and due_date_seq are drawn pseudo-randomly from the seeded RNG.

    Parameters
    ----------
    seed:
        RNG seed — same seed produces an identical stream (deterministic).
    total_bodies:
        Total number of car bodies to emit.
    colors:
        Available paint colours (default: RED/BLUE/WHITE/BLACK/SILVER).
    models:
        Available model/variant names (default: Fiesta/Focus/Puma/Kuga/Mustang).
    options_pool:
        Pool of option codes to sample from (default: 5 common options).
    max_options_per_body:
        Maximum number of options per body (0 allowed; sampled uniformly in [0, max]).
    batch_size_range:
        (min, max) inclusive batch size for each colour run.

    Returns
    -------
    list[Body]
        Bodies in paint-optimal (colour-batched) order.  Same seed → same list.
    """
    if total_bodies <= 0:
        return []

    rng = random.Random(seed)

    colors       = list(colors or _DEFAULT_COLORS)
    models       = list(models or _DEFAULT_MODELS)
    options_pool = list(options_pool or _DEFAULT_OPTIONS)
    min_batch, max_batch = batch_size_range

    bodies: list[Body] = []
    body_counter = 0
    due_seq      = 0  # monotonically increasing due-date sequence (paint arrival order)

    while len(bodies) < total_bodies:
        # Pick the next colour (avoid repeating same colour back-to-back when possible)
        available_colors = colors if len(colors) == 1 else [
            c for c in colors if not bodies or bodies[-1].color != c
        ]
        color = rng.choice(available_colors)

        # Batch size for this colour run
        batch_size = rng.randint(min_batch, max_batch)
        # Clamp to remaining capacity
        batch_size = min(batch_size, total_bodies - len(bodies))

        for _ in range(batch_size):
            model   = rng.choice(models)
            n_opts  = rng.randint(0, max_options_per_body)
            options = frozenset(rng.sample(options_pool, n_opts))

            body = Body(
                id           = f"BODY-{body_counter:05d}",
                color        = color,
                model        = model,
                options      = options,
                due_date_seq = due_seq,
            )
            bodies.append(body)
            body_counter += 1
            due_seq      += 1

    return bodies
