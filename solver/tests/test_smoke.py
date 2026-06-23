def test_ortools_cp_model_imports():
    from ortools.sat.python import cp_model
    model = cp_model.CpModel()
    x = model.NewIntVar(0, 1, "x")
    model.Add(x == 1)
    solver = cp_model.CpSolver()
    assert solver.Solve(model) == cp_model.OPTIMAL
    assert solver.Value(x) == 1
