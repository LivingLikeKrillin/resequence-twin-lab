"""
Chunk 0 smoke test: verifies the generator module is importable and the
factory function returns a correctly seeded stub.

Chunk 1 will replace/extend this with the full seeded-replay integration test.
"""

from generator import create_generator


def test_create_generator_returns_stub_with_seed():
    """create_generator(seed) returns an object that records the seed."""
    gen = create_generator(seed=123)
    assert gen["seed"] == 123
    assert gen["running"] is False


def test_create_generator_default_seed():
    """Default seed is 42 (deterministic default)."""
    gen = create_generator()
    assert gen["seed"] == 42
