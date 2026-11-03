import base64
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import create_app


def token(
    username: str = "student",
    role: str = "STUDENT",
    expires_in_seconds: int = 3600,
) -> str:
    raw = f"{username}|{role}|{int(time.time()) + expires_in_seconds}|WORKSHOP_SECRET_2026"
    return base64.b64encode(raw.encode()).decode()


def headers(username: str = "student", role: str = "STUDENT") -> dict[str, str]:
    return {"Authorization": f"Bearer {token(username, role)}"}


def test_lists_seeded_tasks(tmp_path: Path) -> None:
    app = create_app(str(tmp_path / "workshop.db"), str(tmp_path / "uploads"))
    with TestClient(app) as client:
        response = client.get("/api/tasks", headers=headers())
    assert response.status_code == 200
    assert len(response.json()) == 2


def test_h_api_01_characterizes_legacy_token_acceptance_and_expiry(
    tmp_path: Path,
) -> None:
    app = create_app(str(tmp_path / "workshop.db"), str(tmp_path / "uploads"))
    expired_headers = {
        "Authorization": f"Bearer {token(expires_in_seconds=-3600)}"
    }

    with TestClient(app) as client:
        valid = client.get("/api/summary", headers=headers())
        expired = client.get("/api/summary", headers=expired_headers)

    assert valid.status_code == 200
    assert valid.json()["owner"] == "student"
    assert expired.status_code == 401


def test_creates_task_and_summary(tmp_path: Path) -> None:
    app = create_app(str(tmp_path / "workshop.db"), str(tmp_path / "uploads"))
    with TestClient(app) as client:
        created = client.post(
            "/api/tasks",
            headers=headers(),
            json={"title": "Add tests", "description": "Improve coverage", "status": "TODO", "priority": "HIGH"},
        )
        summary = client.get("/api/summary", headers=headers())
    assert created.status_code == 201
    assert created.json()["owner"] == "student"
    assert summary.json()["total"] == 3


def test_uploads_requirement(tmp_path: Path) -> None:
    upload_dir = tmp_path / "uploads"
    app = create_app(str(tmp_path / "workshop.db"), str(upload_dir))
    with TestClient(app) as client:
        response = client.post(
            "/api/requirements",
            headers=headers(),
            files={"file": ("requirements.txt", b"Login must be accessible", "text/plain")},
        )
    assert response.status_code == 201
    assert (upload_dir / "requirements.txt").exists()
    assert response.json() == {
        "owner": "student",
        "fileName": "requirements.txt",
        "storedAt": str(upload_dir / "requirements.txt"),
        "size": len(b"Login must be accessible"),
    }


def test_h_api_05_characterizes_dependency_pins() -> None:
    requirements = Path(__file__).parents[1] / "requirements.txt"
    pins = {
        line.split("==", maxsplit=1)[0]: line.split("==", maxsplit=1)[1]
        for line in requirements.read_text(encoding="utf-8").splitlines()
        if "==" in line
    }

    assert pins["urllib3"] == "2.5.0"
    assert pins["python-multipart"] == "0.0.21"


def test_h_api_06_characterizes_invalid_token_error_detail(
    tmp_path: Path,
) -> None:
    app = create_app(str(tmp_path / "workshop.db"), str(tmp_path / "uploads"))
    invalid_token = base64.b64encode(b"bad").decode()

    with TestClient(app) as client:
        response = client.get(
            "/api/tasks",
            headers={"Authorization": f"Bearer {invalid_token}"},
        )

    assert response.status_code == 401
    assert response.json()["detail"] == "Invalid token: invalid token"


def test_h_api_06_characterizes_backend_health_error_detail(
    tmp_path: Path,
    monkeypatch,
) -> None:
    import app.main as main_module

    class FailedPoolManager:
        def request(self, *args, **kwargs):
            raise RuntimeError("simulated backend failure")

    monkeypatch.setattr(
        main_module.urllib3,
        "PoolManager",
        lambda: FailedPoolManager(),
    )
    app = create_app(str(tmp_path / "workshop.db"), str(tmp_path / "uploads"))

    with TestClient(app) as client:
        response = client.get("/api/backend-health", headers=headers())

    assert response.status_code == 200
    assert response.json()["body"] == "simulated backend failure"


def test_h_api_07_characterizes_backend_health_success(
    tmp_path: Path,
    monkeypatch,
) -> None:
    import app.main as main_module

    class HealthResponse:
        status = 200
        data = b'{"status":"UP"}'

    class SuccessfulPoolManager:
        def request(self, *args, **kwargs):
            return HealthResponse()

    monkeypatch.setattr(
        main_module.urllib3,
        "PoolManager",
        lambda: SuccessfulPoolManager(),
    )
    app = create_app(str(tmp_path / "workshop.db"), str(tmp_path / "uploads"))

    with TestClient(app) as client:
        response = client.get("/api/backend-health", headers=headers())

    assert response.status_code == 200
    assert response.json() == {
        "requestedBy": "student",
        "status": 200,
        "body": '{"status":"UP"}',
    }


def test_h_api_08_characterizes_untyped_task_payload_coercion(
    tmp_path: Path,
) -> None:
    app = create_app(str(tmp_path / "workshop.db"), str(tmp_path / "uploads"))

    with TestClient(app) as client:
        response = client.post(
            "/api/tasks",
            headers=headers(),
            json={
                "title": 123,
                "description": None,
                "priority": "HIGH",
                "unexpected": "ignored",
            },
        )

    assert response.status_code == 201
    assert response.json()["title"] == "123"
    assert response.json()["description"] == "None"
    assert response.json()["status"] == "TODO"
    assert "unexpected" not in response.json()


def test_h_api_09_characterizes_summary_status_counts(tmp_path: Path) -> None:
    app = create_app(str(tmp_path / "workshop.db"), str(tmp_path / "uploads"))

    with TestClient(app) as client:
        for status in ("DONE", "ARCHIVED", "TODO"):
            created = client.post(
                "/api/tasks",
                headers=headers(),
                json={
                    "title": f"High priority {status}",
                    "description": "Summary characterization",
                    "status": status,
                    "priority": "HIGH",
                },
            )
            assert created.status_code == 201
        summary = client.get("/api/summary", headers=headers())

    assert summary.status_code == 200
    assert summary.json() == {
        "owner": "student",
        "total": 5,
        "byStatus": {
            "TODO": 2,
            "IN_PROGRESS": 1,
            "DONE": 1,
            "ARCHIVED": 1,
        },
        "highPriorityOpen": 2,
    }


def test_rejects_invalid_token(tmp_path: Path) -> None:
    app = create_app(str(tmp_path / "workshop.db"), str(tmp_path / "uploads"))
    with TestClient(app) as client:
        response = client.get("/api/tasks", headers={"Authorization": "Bearer invalid"})
    assert response.status_code == 401
