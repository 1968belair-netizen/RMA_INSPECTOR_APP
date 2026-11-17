import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mock_server.main import app, store  # noqa: E402

REPO = Path(__file__).resolve().parents[2]


@pytest.fixture
def client():
    store.reset()
    return TestClient(app)


@pytest.fixture
def H():
    return {"X-Inspector": "jdoe"}
