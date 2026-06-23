from solver.model import optimal_color_changes, OracleResult


def opt(colors, caps, t=10.0):
    return optimal_color_changes(colors, caps, time_limit_s=t)


def test_n1_is_zero_regardless_of_capacity():
    r = opt(["RED"], [1, 1])
    assert r.optimal_color_changes == 0
    assert r.proven is True


def test_single_colour_is_zero():
    r = opt(["RED", "RED", "RED", "RED"], [2, 2])
    assert r.optimal_color_changes == 0
    assert r.proven is True


def test_two_colours_separable_within_capacity_is_one():
    r = opt(["RED", "RED", "BLUE", "BLUE"], [2, 2])
    assert r.optimal_color_changes == 1
    assert r.proven is True


def test_single_lane_forces_input_order_fifo_fidelity():
    r = opt(["RED", "BLUE", "RED"], [3])
    assert r.optimal_color_changes == 2
    assert r.proven is True


def test_capacity_binds_above_free_permutation_bound():
    # [R,B,R,B,R,B], K=2 cap=1: cannot fully separate 3R/3B with total buffer 2.
    # Free-permutation bound = (#distinct - 1) = 1; capacity forces strictly more.
    r = opt(["RED", "BLUE", "RED", "BLUE", "RED", "BLUE"], [1, 1])
    assert r.optimal_color_changes > 1
    assert r.proven is True


def test_returns_oracle_result_type():
    r = opt(["RED"], [1])
    assert isinstance(r, OracleResult)
    assert hasattr(r, "optimal_color_changes") and hasattr(r, "proven")


def test_due_date_constraint_binds():
    # K=4 cap=1 -> any output permutation is feasible (isolate the due-date effect, not capacity).
    colors = ["RED", "BLUE", "RED", "BLUE"]
    caps = [1, 1, 1, 1]
    due = [0, 1, 2, 3]  # identity JIS
    unconstrained = optimal_color_changes(colors, caps).optimal_color_changes
    assert unconstrained == 1  # free reorder groups colours -> RRBB -> 1 transition
    # budget 0 forces pos == due (identity output = arrival order R,B,R,B -> 3 transitions)
    tight = optimal_color_changes(colors, caps, due_dates=due, max_due_dev_sum=0).optimal_color_changes
    assert tight == 3
    assert tight > unconstrained
