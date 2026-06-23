"""Shared fixtures for viz tests."""
import pytest


FIXTURE_TRAJECTORY = {
    "synthetic": True,
    "disclaimer": "test fixture",
    "seed": 0,
    "policy": "dynamic",
    "lanes": [
        {"id": "L1", "capacity": 3},
        {"id": "L2", "capacity": 3},
        {"id": "L3", "capacity": 3},
    ],
    "bodies": {
        "BODY-00000": {"color": "RED",    "model": "Focus",   "options": [], "dueDateSeq": 1},
        "BODY-00001": {"color": "BLUE",   "model": "Focus",   "options": [], "dueDateSeq": 2},
        "BODY-00002": {"color": "WHITE",  "model": "Focus",   "options": [], "dueDateSeq": 3},
        "BODY-00003": {"color": "BLACK",  "model": "Focus",   "options": [], "dueDateSeq": 4},
        "BODY-00004": {"color": "SILVER", "model": "Focus",   "options": [], "dueDateSeq": 5},
    },
    "frames": [
        {
            "step": 0,
            "lanes": {"L1": ["BODY-00000"], "L2": [], "L3": []},
            "released": None,
            "arrived": ["BODY-00000"],
        },
        {
            "step": 1,
            "lanes": {"L1": ["BODY-00000", "BODY-00001"], "L2": [], "L3": []},
            "released": None,
            "arrived": ["BODY-00001"],
        },
        {
            "step": 2,
            "lanes": {"L1": ["BODY-00000", "BODY-00001"], "L2": ["BODY-00002"], "L3": []},
            "released": None,
            "arrived": ["BODY-00002"],
        },
        {
            "step": 3,
            "lanes": {"L1": ["BODY-00001", "BODY-00003"], "L2": ["BODY-00002"], "L3": []},
            "released": "BODY-00000",
            "arrived": ["BODY-00003"],
        },
        {
            "step": 4,
            "lanes": {"L1": ["BODY-00001", "BODY-00003"], "L2": ["BODY-00002", "BODY-00004"], "L3": []},
            "released": None,
            "arrived": ["BODY-00004"],
        },
        {
            "step": 5,
            "lanes": {"L1": ["BODY-00003"], "L2": ["BODY-00002", "BODY-00004"], "L3": []},
            "released": "BODY-00001",
            "arrived": [],
        },
        {
            "step": 6,
            "lanes": {"L1": ["BODY-00003"], "L2": ["BODY-00004"], "L3": []},
            "released": "BODY-00002",
            "arrived": [],
        },
        {
            "step": 7,
            "lanes": {"L1": ["BODY-00003"], "L2": [], "L3": []},
            "released": "BODY-00004",
            "arrived": [],
        },
    ],
}


@pytest.fixture()
def trajectory():
    """Return the shared fixture trajectory dict."""
    return FIXTURE_TRAJECTORY
