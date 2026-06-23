import json
import subprocess
import sys
from solver.optimality_gap import gap, GapResult
from solver.fixture import load_fixture


def _write(tmp_path, colors, caps, heuristic_cc, dd=0.0):
    bodies = [{"id": f"BODY-{i:05d}", "color": c, "dueDateSeq": i} for i, c in enumerate(colors)]
    lanes = [{"id": f"L{j+1}", "cap": caps[j]} for j in range(len(caps))]
    p = tmp_path / "f.json"
    p.write_text(json.dumps({"seed": 1, "lanes": lanes, "bodies": bodies,
                             "heuristic": {"colorChanges": heuristic_cc, "dueDateDeviation": dd}}))
    return str(p)


def test_gap_computes_heuristic_minus_optimal(tmp_path):
    # [R,R,B,B] K2 cap2: optimal=1. Pretend heuristic achieved 3 -> gap 2.
    f = _write(tmp_path, ["RED", "RED", "BLUE", "BLUE"], [2, 2], heuristic_cc=3, dd=1.5)
    r: GapResult = gap(load_fixture(f))
    assert r.optimal_color_changes == 1
    assert r.heuristic_color_changes == 3
    assert r.gap == 2
    assert r.proven is True
    assert r.due_date_deviation == 1.5


def test_gap_invariant_holds_when_heuristic_equals_optimal(tmp_path):
    f = _write(tmp_path, ["RED", "RED", "BLUE", "BLUE"], [2, 2], heuristic_cc=1)
    r = gap(load_fixture(f))
    assert r.gap == 0  # gap >= 0 always


def test_cli_json_output(tmp_path):
    f = _write(tmp_path, ["RED", "BLUE"], [2, 2], heuristic_cc=1)
    out = subprocess.run(
        [sys.executable, "-m", "solver.optimality_gap", "--fixture", f, "--json"],
        capture_output=True, text=True, cwd=_solver_dir())
    assert out.returncode == 0
    payload = json.loads(out.stdout)
    assert payload["seed"] == 1 and payload["gap"] >= 0


def _solver_dir():
    import os
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
