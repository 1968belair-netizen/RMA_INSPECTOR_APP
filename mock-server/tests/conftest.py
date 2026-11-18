import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mock_server.main import app, store  # noqa: E402

REPO = Path(__file__).resolve().parents[2]


@pytest.fixture
def anon():
    """Not signed in."""
    store.reset()
    return TestClient(app)


@pytest.fixture
def client(anon):
    """Signed in as the fake demo user (docs/api.md §3)."""
    r = anon.post("/api/v1/auth/login", json={"username": "demo", "password": "demo", "device_id": "test"})
    assert r.status_code == 200, r.text
    anon.headers["Authorization"] = "Bearer " + r.json()["token"]
    return anon


@pytest.fixture
def H():
    """Extra headers for writes — none needed now that identity comes from the token."""
    return {}
