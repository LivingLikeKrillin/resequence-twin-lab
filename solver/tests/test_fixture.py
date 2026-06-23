from solver.fixture import load_fixture, Instance


def test_load_fixture_parses_bodies_lanes_and_heuristic(tmp_path):
    p = tmp_path / "seed-42.json"
    p.write_text(
        '{"seed":42,'
        '"lanes":[{"id":"L1","cap":2},{"id":"L2","cap":2},{"id":"L3","cap":2}],'
        '"bodies":[{"id":"BODY-00000","color":"RED","dueDateSeq":1},{"id":"BODY-00001","color":"BLUE","dueDateSeq":0}],'
        '"heuristic":{"colorChanges":1,"dueDateDeviation":2.4}}'
    )
    inst: Instance = load_fixture(str(p))
    assert inst.seed == 42
    assert inst.lane_caps == [2, 2, 2]
    assert inst.colors == ["RED", "BLUE"]
    assert inst.due_dates == [1, 0]
    assert inst.heuristic_color_changes == 1
    assert inst.heuristic_due_date_deviation == 2.4


def test_load_fixture_rejects_missing_fields(tmp_path):
    p = tmp_path / "bad.json"
    p.write_text('{"seed":1,"lanes":[],"bodies":[]}')  # missing heuristic
    import pytest
    with pytest.raises(KeyError):
        load_fixture(str(p))
