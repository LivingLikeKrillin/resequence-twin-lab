import glob
import os
import pytest
from solver.optimality_gap import gap
from solver.fixture import load_fixture

CEILING = 3  # max measured gap (2) + 1 slack; documented in research note.

# glob.escape the directory so that the literal "[projects]" in the repo path is
# not misinterpreted as a glob character class by glob.glob().
_FIXTURE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_FIX = sorted(glob.glob(os.path.join(glob.escape(_FIXTURE_DIR), "fixtures", "seed-*.json")))


@pytest.mark.parametrize("path", _FIX, ids=[os.path.basename(p) for p in _FIX])
def test_gap_is_non_negative_invariant(path):
    # gap >= 0 is sound by construction (the heuristic is a feasible point of the
    # due-date-constrained relaxed model); a negative gap would mean an over-constrained /
    # buggy model. This is the model-soundness net.
    r = gap(load_fixture(path))
    assert r.optimal_color_changes <= r.heuristic_color_changes, (
        f"{os.path.basename(path)}: optimal {r.optimal_color_changes} > heuristic "
        f"{r.heuristic_color_changes} — model bug (gap<0)")
    assert r.proven, f"{os.path.basename(path)}: optimum not proven within time limit"


@pytest.mark.parametrize("path", _FIX, ids=[os.path.basename(p) for p in _FIX])
def test_heuristic_gap_within_ceiling(path):
    r = gap(load_fixture(path))
    assert r.gap <= CEILING, (
        f"{os.path.basename(path)}: gap {r.gap} exceeds ceiling {CEILING} — the heuristic "
        f"regressed, or the committed fixtures are stale (regenerate via OptimalityFixtureExportTest)")


def test_fixtures_exist():
    assert _FIX, "no committed fixtures found under solver/fixtures/"
