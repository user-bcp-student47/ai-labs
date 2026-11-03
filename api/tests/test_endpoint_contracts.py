from __future__ import annotations

from pathlib import Path
from typing import Iterator

import app.main as main_module
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.main import create_app
from test_api import headers


@pytest.fixture
def api_client(tmp_path: Path) -> Iterator[tuple[TestClient, FastAPI]]:
    app = create_app(str(tmp_path / "workshop.db"), str(tmp_path / "uploads"))
    with TestClient(app, raise_server_exceptions=False) as client:
        yield client, app


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("get", "/api/tasks"),
        ("post", "/api/tasks"),
        ("get", "/api/summary"),
        ("post", "/api/requirements"),
        ("get", "/api/backend-health"),
    ],
)
def test_each_endpoint_rejects_missing_authentication(
    api_client: tuple[TestClient, FastAPI],
    method: str,
    path: str,
) -> None:
    client, _ = api_client
    response = getattr(client, method)(path)

    assert response.status_code == 401
    assert response.json() == {"detail": "Invalid token: invalid token"}


def test_list_tasks_empty_owner_falls_back_to_identity(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client

    response = client.get(
        "/api/tasks",
        params={"owner": ""},
        headers=headers(),
    )

    assert response.status_code == 200
    assert {task["owner"] for task in response.json()} == {"student"}


def test_list_tasks_reports_query_failure(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, app = api_client
    app.state.connection.execute("DROP TABLE tasks")
    response = client.get("/api/tasks", headers=headers())

    assert response.status_code == 500
    assert response.text == "Internal Server Error"


def test_list_tasks_treats_quoted_owner_as_data(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client
    response = client.get(
        "/api/tasks",
        params={"owner": "student'"},
        headers=headers(),
    )

    assert response.status_code == 200
    assert response.json() == []


def test_list_tasks_denies_another_owner(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client

    response = client.get(
        "/api/tasks",
        params={"owner": "trainer"},
        headers=headers(),
    )

    assert response.status_code == 403


def test_summary_denies_another_owner(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client

    response = client.get(
        "/api/summary",
        params={"owner": "trainer"},
        headers=headers(),
    )

    assert response.status_code == 403


def test_create_task_empty_payload_uses_documented_defaults(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client

    response = client.post(
        "/api/tasks",
        headers=headers(),
        json={},
    )

    assert response.status_code == 201
    assert response.json()["title"] == ""
    assert response.json()["description"] == ""
    assert response.json()["status"] == "TODO"
    assert response.json()["priority"] == "MEDIUM"
    assert response.json()["owner"] == "student"


def test_create_task_missing_body_returns_validation_error(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client

    response = client.post("/api/tasks", headers=headers())

    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body"]


def test_create_task_reports_insert_failure(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, app = api_client
    app.state.connection.execute("DROP TABLE tasks")
    response = client.post(
        "/api/tasks",
        headers=headers(),
        json={"title": "Valid title"},
    )

    assert response.status_code == 500
    assert response.text == "Internal Server Error"


def test_create_task_stores_quoted_text_as_data(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client
    response = client.post(
        "/api/tasks",
        headers=headers(),
        json={
            "title": "O'Brien",
            "description": "It's ordinary text",
        },
    )

    assert response.status_code == 201
    assert response.json()["title"] == "O'Brien"
    assert response.json()["description"] == "It's ordinary text"


def test_create_task_rejects_owner_mismatch(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client

    response = client.post(
        "/api/tasks",
        headers=headers(),
        json={
            "title": "Other owner",
            "description": "Should not cross identity",
            "owner": "trainer",
        },
    )

    assert response.status_code == 403


def test_summary_empty_owner_and_empty_task_set(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, app = api_client
    app.state.connection.execute("DELETE FROM tasks")
    app.state.connection.commit()

    response = client.get(
        "/api/summary",
        params={"owner": ""},
        headers=headers(),
    )

    assert response.status_code == 200
    assert response.json() == {
        "owner": "student",
        "total": 0,
        "byStatus": {},
        "highPriorityOpen": 0,
    }


def test_summary_reports_query_failure(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, app = api_client
    app.state.connection.execute("DROP TABLE tasks")
    response = client.get("/api/summary", headers=headers())

    assert response.status_code == 500
    assert response.text == "Internal Server Error"


def test_upload_without_extension_and_empty_content(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client

    response = client.post(
        "/api/requirements",
        headers=headers(),
        files={"file": ("README", b"", "application/octet-stream")},
    )

    assert response.status_code == 201
    assert response.json()["fileName"] == "README"
    assert response.json()["size"] == 0


def test_upload_one_megabyte_without_configured_limit(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client
    content = b"x" * (1024 * 1024)

    response = client.post(
        "/api/requirements",
        headers=headers(),
        files={"file": ("largefile", content, "application/octet-stream")},
    )

    assert response.status_code == 201
    assert response.json()["size"] == len(content)


def test_upload_malformed_multipart_returns_bad_request(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client

    response = client.post(
        "/api/requirements",
        headers={
            **headers(),
            "Content-Type": "multipart/form-data; boundary=abc",
        },
        content=b"not a multipart body",
    )

    assert response.status_code == 400
    assert response.json() == {"detail": "There was an error parsing the body"}


def test_upload_missing_file_returns_validation_error(
    api_client: tuple[TestClient, FastAPI],
) -> None:
    client, _ = api_client

    response = client.post("/api/requirements", headers=headers())

    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body", "file"]


def test_upload_write_failure_returns_internal_server_error(
    api_client: tuple[TestClient, FastAPI],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client, _ = api_client

    def fail_write(self: Path, content: bytes) -> int:
        raise OSError("simulated write failure")

    monkeypatch.setattr(Path, "write_bytes", fail_write)
    response = client.post(
        "/api/requirements",
        headers=headers(),
        files={"file": ("requirements.txt", b"sample", "text/plain")},
    )

    assert response.status_code == 500
    assert response.text == "Internal Server Error"


def test_backend_health_handles_empty_success_body(
    api_client: tuple[TestClient, FastAPI],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client, _ = api_client

    class EmptyHealthResponse:
        status = 204
        data = b""

    class SuccessfulPoolManager:
        def request(self, *args: object, **kwargs: object) -> EmptyHealthResponse:
            return EmptyHealthResponse()

    monkeypatch.setattr(
        main_module.urllib3,
        "PoolManager",
        lambda: SuccessfulPoolManager(),
    )
    response = client.get("/api/backend-health", headers=headers())

    assert response.status_code == 200
    assert response.json() == {
        "requestedBy": "student",
        "status": 204,
        "body": "",
    }


def test_backend_health_converts_upstream_exception_to_status_zero(
    api_client: tuple[TestClient, FastAPI],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client, _ = api_client

    class FailedPoolManager:
        def request(self, *args: object, **kwargs: object) -> None:
            raise RuntimeError("simulated backend failure")

    monkeypatch.setattr(
        main_module.urllib3,
        "PoolManager",
        lambda: FailedPoolManager(),
    )
    response = client.get("/api/backend-health", headers=headers())

    assert response.status_code == 200
    assert response.json() == {
        "requestedBy": "student",
        "status": 0,
        "body": "simulated backend failure",
    }
