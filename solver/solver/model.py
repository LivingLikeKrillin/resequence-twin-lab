"""Capacity-respecting K-FIFO colour-minimisation oracle (spec §C).

Models the PBS resequencing buffer: bodies arrive in order, each is routed to a FIFO
lane (capacity cap_l), and released from a lane head to the output. Minimises colour
transitions in the output. Pull *timing* is a free (but in-arrival-order, capacity-
limited) decision — a relaxation of the simulator's greedy admission. The simulator's
run is a feasible point, so the optimum is a true lower bound on the heuristic
(gap >= 0 sound) and a conservative bound on the same-discipline optimum.
"""
from __future__ import annotations
from dataclasses import dataclass
from ortools.sat.python import cp_model


@dataclass(frozen=True)
class OracleResult:
    optimal_color_changes: int
    proven: bool  # True iff CP-SAT proved optimality within the time limit


def optimal_color_changes(colors: list[str], lane_caps: list[int],
                          due_dates: list[int] | None = None,
                          max_due_dev_sum: int | None = None,
                          time_limit_s: float = 10.0) -> OracleResult:
    n = len(colors)
    k = len(lane_caps)
    if n <= 1:
        return OracleResult(0, True)

    palette = sorted(set(colors))
    cidx = {c: i for i, c in enumerate(palette)}
    col = [cidx[c] for c in colors]
    n_col = len(palette)

    m = cp_model.CpModel()

    lane = [m.NewIntVar(0, k - 1, f"lane_{i}") for i in range(n)]
    pos = [m.NewIntVar(0, n - 1, f"pos_{i}") for i in range(n)]       # output position of body i
    pull = [m.NewIntVar(0, n - 1, f"pull_{i}") for i in range(n)]     # output-step index it is pulled at
    m.AddAllDifferent(pos)

    for i in range(n - 1):
        m.Add(pull[i] <= pull[i + 1])
    for i in range(n):
        m.Add(pull[i] <= pos[i])

    for i in range(n):
        for j in range(i + 1, n):
            s = m.NewBoolVar(f"same_{i}_{j}")
            m.Add(lane[i] == lane[j]).OnlyEnforceIf(s)
            m.Add(lane[i] != lane[j]).OnlyEnforceIf(s.Not())
            m.Add(pos[i] < pos[j]).OnlyEnforceIf(s)

    for t in range(n):
        for li in range(k):
            terms = []
            for i in range(n):
                pulled_by_t = m.NewBoolVar(f"pb_{i}_{t}")     # pull[i] <= t
                m.Add(pull[i] <= t).OnlyEnforceIf(pulled_by_t)
                m.Add(pull[i] > t).OnlyEnforceIf(pulled_by_t.Not())
                not_yet_out = m.NewBoolVar(f"ny_{i}_{t}")      # pos[i] >= t
                m.Add(pos[i] >= t).OnlyEnforceIf(not_yet_out)
                m.Add(pos[i] < t).OnlyEnforceIf(not_yet_out.Not())
                in_lane = m.NewBoolVar(f"il_{i}_{li}_{t}")     # lane[i] == li
                m.Add(lane[i] == li).OnlyEnforceIf(in_lane)
                m.Add(lane[i] != li).OnlyEnforceIf(in_lane.Not())
                present = m.NewBoolVar(f"pr_{i}_{li}_{t}")
                m.AddBoolAnd([pulled_by_t, not_yet_out, in_lane]).OnlyEnforceIf(present)
                m.AddBoolOr([pulled_by_t.Not(), not_yet_out.Not(), in_lane.Not()]).OnlyEnforceIf(present.Not())
                terms.append(present)
            m.Add(sum(terms) <= lane_caps[li])

    # Optional due-date hard constraint: output deviation <= budget.
    # Sum of |pos[i] - dueDateSeq[i]| <= max_due_dev_sum. Makes the optimum non-trivial on
    # colour-batched input (the buffer must reorder for due-date, breaking colour batches).
    if due_dates is not None and max_due_dev_sum is not None:
        dev_terms = []
        for i in range(n):
            diff = m.NewIntVar(-(n - 1), n - 1, f"diff_{i}")
            m.Add(diff == pos[i] - due_dates[i])
            absdev = m.NewIntVar(0, n - 1, f"absdev_{i}")
            m.AddAbsEquality(absdev, diff)
            dev_terms.append(absdev)
        m.Add(sum(dev_terms) <= max_due_dev_sum)

    out_col = [m.NewIntVar(0, n_col - 1, f"outc_{t}") for t in range(n)]
    for i in range(n):
        m.AddElement(pos[i], out_col, col[i])  # out_col[pos[i]] == col[i]
    trans = []
    for t in range(n - 1):
        d = m.NewBoolVar(f"trans_{t}")
        m.Add(out_col[t] != out_col[t + 1]).OnlyEnforceIf(d)
        m.Add(out_col[t] == out_col[t + 1]).OnlyEnforceIf(d.Not())
        trans.append(d)
    m.Minimize(sum(trans))

    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = time_limit_s
    status = solver.Solve(m)
    if status == cp_model.OPTIMAL:
        return OracleResult(int(solver.ObjectiveValue()), True)
    if status == cp_model.FEASIBLE:
        return OracleResult(int(solver.ObjectiveValue()), False)
    raise RuntimeError(f"CP-SAT returned non-feasible status {status} "
                       f"(model should always be feasible — the heuristic is a witness)")
