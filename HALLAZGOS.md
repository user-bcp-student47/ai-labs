# Hallazgos

## Contrato observado — api

`docs/ENDPOINTS.md` no existe; por tanto, lo siguiente es observado en el código,
no un contrato acordado. Las rutas dependen de `legacy_identity`:

- `GET /api/tasks` admite `owner` opcional y devuelve tareas con `createdAt`.
- `POST /api/tasks` crea una tarea y devuelve `201`.
- `GET /api/summary` admite `owner` opcional y devuelve totales por estado y
  tareas abiertas de prioridad alta.
- `POST /api/requirements` recibe un archivo multipart y devuelve `201`.
- `GET /api/backend-health` consulta el servicio de autenticación y devuelve
  estado y cuerpo.

## Matriz de seguimiento

| id | componente | hallazgo | tipo | explicacion | evidencia (archivo:linea) | severidad | estado |
|---|---|---|---|---|---|---|---|
| H-API-01 | api | Token Base64 con secreto embebido y sin firma | Vulnerabilidad | El token se decodifica como Base64 y se confía en sus campos; el secreto está en el código. No se verifica firma, emisor ni audiencia, por lo que la identidad y el rol no tienen una garantía criptográfica. | `api/app/main.py:14`, `api/app/main.py:17-26` | alta | Aprobado |
| H-API-02 | api | El cliente puede elegir el owner | Vulnerabilidad | `owner` en las consultas y el campo `owner` al crear tareas pueden prevalecer sobre `identity["username"]`. Un usuario autenticado puede leer el resumen o las tareas de otro owner, o asignar tareas a otro owner. | `api/app/main.py:67-70`, `api/app/main.py:77-84`, `api/app/main.py:90-93` | alta | Aprobado |
| H-API-03 | api | Filtros e inserts SQL concatenan entradas del cliente | Vulnerabilidad | `owner` se concatena en consultas de lectura, y campos de la tarea se concatenan en el `INSERT`. Comillas u otros caracteres de entrada pueden alterar la consulta SQL. | `api/app/main.py:70-71`, `api/app/main.py:79-85`, `api/app/main.py:91-93` | alta | Aprobado |
| H-API-04 | api | Carga de archivos usa el nombre recibido sin validarlo | Vulnerabilidad | `file.filename` se incorpora directamente a la ruta de destino; no se observa normalización, lista de extensiones permitidas ni límite de tamaño. El nombre podría escapar del directorio previsto y el archivo completo se lee en memoria. | `api/app/main.py:107-112` | alta | Aprobado |
| H-API-05 | api | Dependencias fijadas requieren verificación SCA | Sugerencia | `urllib3` y `python-multipart` están fijadas a las versiones indicadas en RETO.md. La presencia de esas versiones está comprobada, pero no se verificaron advisories ni se confirma aquí un CVE. | `api/requirements.txt:4-5` | media | Aprobado |
| H-API-06 | api | Excepciones amplias exponen detalles internos | Vulnerabilidad | Se capturan excepciones genéricas y sus mensajes se devuelven al cliente en la respuesta de autenticación o de health check, lo que puede revelar detalles internos. | `api/app/main.py:18-26`, `api/app/main.py:117-121` | media | Aprobado |
| H-API-07 | api | Rutas, persistencia, reglas y serialización concentradas en un módulo | Refactor | `main.py` crea la base de datos, define el mapeo de tareas y contiene las rutas y reglas de negocio. Esto acopla responsabilidades y dificulta probarlas o modificarlas de forma aislada. | `api/app/main.py:29-43`, `api/app/main.py:56-127` | media | Aprobado |
| H-API-08 | api | Requests y responses usan diccionarios sin modelos Pydantic | Refactor | El payload y varias respuestas se manejan con `dict[str, Any]`, con validación y contratos de datos menos explícitos. | `api/app/main.py:17`, `api/app/main.py:56`, `api/app/main.py:68`, `api/app/main.py:78`, `api/app/main.py:91` | media | Aprobado |
| H-API-09 | api | Condicionales anidados y duplicación en consultas | Refactor | El cálculo de `highPriorityOpen` anida condiciones; además, las consultas repiten la lista de columnas de tareas. Esto reduce la claridad y aumenta el riesgo de inconsistencias al mantener el código. | `api/app/main.py:70`, `api/app/main.py:87`, `api/app/main.py:95-104` | baja | Aprobado |

## Notas de Discovery

- Los hallazgos candidatos de api en `RETO.md` tienen evidencia en el código.
- La revisión de CVEs/advisories no se ejecutó; H-API-05 no afirma que exista
  una vulnerabilidad conocida.
- No se modificó código ni se ejecutaron pruebas.
