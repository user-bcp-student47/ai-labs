# Pruebas API — componente api

## Alcance y estrategia

Pruebas de integración HTTP usando FastAPI `TestClient`, SQLite temporal por
prueba y stubs locales para el servicio upstream de health. Los casos se
derivan de la sección API de `docs/ENDPOINTS.md`. No se inicia un servidor
externo ni se consulta otro componente.

La suite verifica caminos felices, valores borde y errores documentados. Las
pruebas de denegación de acceso a otros owners se incluyen por el criterio de
aceptación solicitado, pero `docs/ENDPOINTS.md` describe actualmente acceso
cruzado como comportamiento observado y lo deja como pregunta abierta. Dichas
pruebas no alteran su expectativa para pasar: fallan hasta que se decida y
corrija el contrato.

## Matriz de casos

| endpoint | caso | tipo | resultado esperado |
|---|---|---|---|
| `GET /api/tasks` | Listar tareas del usuario autenticado | Camino feliz | 200 con tareas serializadas y ordenadas por `id`. |
| `GET /api/tasks` | `owner` vacío | Borde | 200; se usa el `username` del token. |
| `GET /api/tasks` | Sin token | Error documentado | 401 con `detail`. |
| `GET /api/tasks` | Error de consulta | Error documentado | 500, cuerpo de texto `Internal Server Error`. |
| `GET /api/tasks` | Owner con comilla como dato literal | Seguridad / SQL | 200 con lista vacía; **actualmente falla** con 500 por SQL concatenado. |
| `GET /api/tasks` | Consultar `owner=trainer` con identidad `student` | Seguridad / criterio solicitado | 403; **actualmente falla**: API responde 200 y expone la tarea de trainer. |
| `POST /api/tasks` | Crear tarea válida | Camino feliz | 201 con tarea y `createdAt`. |
| `POST /api/tasks` | Body vacío | Borde | 201 con defaults observados (`title`, `description` vacíos; `TODO`, `MEDIUM`, identidad del token). |
| `POST /api/tasks` | Sin token | Error documentado | 401 con `detail`. |
| `POST /api/tasks` | Body ausente | Error documentado | 422 con error de validación en `body`. |
| `POST /api/tasks` | Error de inserción | Error documentado | 500, cuerpo de texto `Internal Server Error`. |
| `POST /api/tasks` | Título/descripción con comillas ordinarias | Seguridad / SQL | 201 y texto preservado; **actualmente falla** con 500 por SQL concatenado. |
| `POST /api/tasks` | Crear con `owner=trainer` desde `student` | Seguridad / criterio solicitado | 403; **actualmente falla**: API responde 201 y crea la tarea para trainer. |
| `GET /api/summary` | Resumen del usuario autenticado | Camino feliz | 200 con `owner`, totales y prioridad alta abierta. |
| `GET /api/summary` | Owner vacío y sin tareas | Borde | 200 con total cero y `byStatus` vacío. |
| `GET /api/summary` | Sin token | Error documentado | 401 con `detail`. |
| `GET /api/summary` | Error de consulta | Error documentado | 500, cuerpo de texto `Internal Server Error`. |
| `GET /api/summary` | Consultar resumen de `trainer` desde `student` | Seguridad / criterio solicitado | 403; **actualmente falla**: API responde 200. |
| `POST /api/requirements` | Subir archivo `requirements.txt` | Camino feliz | 201; respuesta informa owner, nombre, ruta y bytes. |
| `POST /api/requirements` | Archivo vacío sin extensión | Borde | 201; se acepta y `size` es cero según comportamiento actual. |
| `POST /api/requirements` | Archivo de 1 MiB | Borde de tamaño | 201; no hay límite configurado observado. |
| `POST /api/requirements` | Sin token | Error documentado | 401 con `detail`. |
| `POST /api/requirements` | Multipart malformado | Error documentado | 400 con `detail: There was an error parsing the body`. |
| `POST /api/requirements` | Falta el campo `file` | Error documentado | 422; ubicación del error `body.file`. |
| `POST /api/requirements` | Fallo de escritura simulado | Error documentado | 500, cuerpo de texto `Internal Server Error`. |
| `GET /api/backend-health` | Upstream devuelve 200 | Camino feliz | 200 con `requestedBy`, status del upstream y body UTF-8. |
| `GET /api/backend-health` | Upstream devuelve 204 y body vacío | Borde | 200 con `status: 204` y `body: ""`. |
| `GET /api/backend-health` | Sin token | Error documentado | 401 con `detail`. |
| `GET /api/backend-health` | Excepción upstream simulada | Error operacional capturado | 200 con `status: 0` y texto de excepción en `body`, según contrato observado. |

### Bugs y discrepancias encontrados

- **H-API-02 — autorización por owner:** `test_list_tasks_denies_another_owner`,
  `test_summary_denies_another_owner` y
  `test_create_task_rejects_owner_mismatch` esperan 403, pero obtienen
  respectivamente 200, 200 y 201. En el código actual, `api/app/main.py:69-71`
  y `api/app/main.py:92-93` usan el owner enviado por el cliente para filtrar;
  `api/app/main.py:83-85` permite asignar el owner del body. Esto es un bug
  reportado por separado; no se modificó código de producción.
- **H-API-03 — SQL concatenado:** las pruebas
  `test_list_tasks_treats_quoted_owner_as_data` y
  `test_create_task_stores_quoted_text_as_data` esperan una búsqueda/creación
  segura, pero ambas reciben HTTP 500. La evidencia está en las consultas
  concatenadas `api/app/main.py:70-71`, `api/app/main.py:84-87` y
  `api/app/main.py:93`. Se prueba aparte de los 500 operacionales, que se
  fuerzan eliminando la tabla en la base temporal.
- **Contrato de error 500:** las pruebas mostraron que FastAPI devuelve el
  cuerpo `Internal Server Error` como texto plano. La documentación de
  `docs/ENDPOINTS.md` fue corregida para reflejarlo.
- Las pruebas de borde de carga verifican únicamente lo implementado: se
  acepta nombre sin extensión y un archivo de 1 MiB. No establecen que exista
  un límite seguro; la política de tamaño/extensiones permanece pendiente.

## Ejecuciones y cobertura

Todos los comandos se ejecutaron con cwd `api/`, usando el intérprete local
`.venv\Scripts\python.exe`.

| ejecucion | componente | IDs relacionados | comando | resultado | codigo de salida |
|---|---|---|---|---|---|
| Baseline, antes de añadir la matriz de endpoints | api | H-API-01 a H-API-09 | `.\.venv\Scripts\python.exe -m pytest --cov=app --cov-report=term` | 13 passed; cobertura global 100% | 0 |
| Suite completa posterior | api | H-API-01 a H-API-09 | `.\.venv\Scripts\python.exe -m pytest --cov=app --cov-report=term-missing` | 30 passed, 5 failed; 71 warnings de deprecación; cobertura global 100% | 1 |

**Cobertura global:** 96/96 statements ejecutados; 16/16 branches cubiertos;
100%, medida por `pytest-cov`/Coverage.py con `branch = true` y `source = ["app"]`
según `api/pyproject.toml`. Es cobertura de toda la aplicación `app/`, no una
métrica aislada del diff.

**Cobertura de código modificado:** no se modificó código de producción; las
pruebas nuevas no forman parte del `source = ["app"]` medido. No hay un conjunto
de statements de producción modificados al que aplicar separadamente el gate
del 85%. La cobertura global supera 85%, pero no sustituye una medición del
diff si posteriormente se modifica producción.

## Fallos, pendientes y pruebas propuestas

### Fallos reales

| test | resultado observado | evidencia |
|---|---|---|
| `test_list_tasks_treats_quoted_owner_as_data` | Esperado 200 con lista vacía, recibido 500 | H-API-03; `api/app/main.py:70-71` |
| `test_list_tasks_denies_another_owner` | Esperado 403, recibido 200 | H-API-02; `api/app/main.py:69-71` |
| `test_summary_denies_another_owner` | Esperado 403, recibido 200 | H-API-02; `api/app/main.py:92-93` |
| `test_create_task_stores_quoted_text_as_data` | Esperado 201 con texto literal, recibido 500 | H-API-03; `api/app/main.py:84-87` |
| `test_create_task_rejects_owner_mismatch` | Esperado 403, recibido 201 | H-API-02; `api/app/main.py:83-85` |

### Pendientes

- Resolver la política de autorización entre owners y actualizar el contrato
  de endpoints antes de declarar los tres casos como parte del contrato
  acordado.
- Definir tamaño máximo y extensiones permitidas para `/api/requirements`.
- El token sigue siendo el esquema Base64 legado descrito en
  `docs/ENDPOINTS.md`; la suite no valida un contrato JWT que no existe.

### Propuestas

- Tras el acuerdo y la corrección de H-API-02, volver a ejecutar sin excluir las
  pruebas de autorización.
- Tras definir la política de uploads, añadir pruebas de los límites exactos,
  nombres de ruta y extensiones rechazadas.
- Mantener la prueba de 1 MiB como borde solo si ese valor sigue teniendo
  significado después de definir el límite.

## Código de las pruebas nuevas

El módulo completo está en [`api/tests/test_endpoint_contracts.py`](../api/tests/test_endpoint_contracts.py).
Usa el helper `headers()` de `api/tests/test_api.py` para crear la credencial
ficticia local; no contiene credenciales de producción.

```python
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
    response = client.post("/api/tasks", headers=headers(), json={})
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
```

Los caminos felices existentes permanecen cubiertos por
`api/tests/test_api.py` (`test_lists_seeded_tasks`,
`test_creates_task_and_summary`, `test_uploads_requirement` y las pruebas de
health). La versión ejecutable es la fuente de verdad si este bloque y el
archivo difieren posteriormente.
