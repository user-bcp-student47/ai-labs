# Contratos de endpoints

## API (`api/`)

### Estado del contrato

- **Contrato observado:** lo descrito abajo se deriva de las rutas actuales en
  `api/app/main.py`. No se encontró un contrato previamente acordado.
- **Contrato acordado:** pendiente de confirmación por el responsable de la
  API y del servicio que emite las credenciales.
- **Nota de implementación:** en el código consultado, las cinco rutas y la
  inicialización siguen dentro de `api/app/main.py`; no hay modelos Pydantic
  para estos contratos.
- **Riesgos y preguntas pendientes:** el token se valida mediante un formato
  Base64 legado sin firma; no hay una especificación acordada del emisor,
  issuer, audience ni algoritmo JWT. Los endpoints de tareas aceptan el
  `owner` enviado por el cliente. La carga no define límites de tamaño ni
  extensiones permitidas. Estas diferencias se indican en la seguridad de
  cada endpoint; esta documentación no implica que ya estén corregidas.

### Autenticación común observada

Todas las rutas de esta sección dependen de `legacy_identity`. El cliente debe
enviar el header:

```http
Authorization: Bearer <token>
```

El código elimina el texto `Bearer ` y decodifica el resto como Base64. El
contenido decodificado esperado tiene cuatro segmentos separados por `|`:
`username|role|expiry|secret`; `expiry` es un timestamp Unix en segundos. La
firma criptográfica, issuer y audience no se verifican. La API no documenta
un emisor acordado ni un mecanismo seguro para obtener el token.

**Ejemplo de autenticación:** reemplazar `<token vigente>` por un valor
generado por el emisor configurado para el entorno local. No se publica aquí
un bearer token funcional: el código contiene un secreto estático de
laboratorio y no debe reutilizarse como credencial de integración.

Cuando la autenticación falla, el código devuelve HTTP 401 con un campo
`detail` que incluye el texto de la excepción. Su valor exacto puede variar
según cómo esté malformado el token.

---

## `GET /api/tasks`

1. **Qué hace:** lista las tareas asociadas al owner solicitado.
2. **Autenticación:** requerida por dependencia. Enviar el header común
   `Authorization: Bearer <token>`.
3. **Request**

   | campo/parametro | tipo | obligatorio | descripcion |
   |---|---|---|---|
   | `owner` (query) | string | No | Owner que se usa para filtrar. Si se omite o está vacío, el código usa el `username` del token. |
   | `Authorization` (header) | string | Sí para obtener acceso | Header Bearer leído por la dependencia común. |

   **Ejemplo completo (con un token vigente del emisor local):**

   ```http
   GET /api/tasks?owner=trainer HTTP/1.1
   Host: localhost:8000
   Authorization: Bearer <token vigente>
   ```

4. **Response exitosa:** HTTP `200`.

   | campo | tipo | descripcion |
   |---|---|---|
   | response | array de objetos | Lista ordenada por `id`. Puede ser vacía. |
   | `id` | integer | Identificador de la tarea. |
   | `title` | string | Título. |
   | `description` | string | Descripción. |
   | `status` | string | Estado almacenado. |
   | `owner` | string | Owner almacenado. |
   | `priority` | string | Prioridad almacenada. |
   | `createdAt` | string | Valor de `created_at` de SQLite. |

   **Ejemplo:**

   ```json
   [
     {
       "id": 3,
       "title": "Trainer notes",
       "description": "Preparar retrospectiva",
       "status": "TODO",
       "owner": "trainer",
       "priority": "LOW",
       "createdAt": "2026-11-02 12:00:00"
     }
   ]
   ```

   El `createdAt` mostrado es ilustrativo; el valor real lo genera SQLite.

5. **Respuestas de error observables**

   | codigo | cuando ocurre | ejemplo de cuerpo |
   |---|---|---|
   | 401 | Token ausente, inválido o expirado. | `{"detail":"Invalid token: invalid token"}` |
   | 500 | Error no manejado al ejecutar la consulta o convertir la respuesta. | `Internal Server Error` (cuerpo de texto plano) |

6. **Notas de seguridad:** `owner` es controlado por el cliente y el código
   permite solicitar tareas de otro owner. No existe autorización por rol
   verificado. La consulta SQL concatena el valor de `owner`; no está
   parametrizada.

---

## `POST /api/tasks`

1. **Qué hace:** crea una tarea y devuelve la fila creada.
2. **Autenticación:** requerida por dependencia. Enviar el header común
   `Authorization: Bearer <token>`.
3. **Request**

   | campo/parametro | tipo | obligatorio | descripcion |
   |---|---|---|---|
   | `Authorization` (header) | string | Sí para obtener acceso | Header Bearer leído por la dependencia común. |
   | body | objeto JSON | Sí | El código lee campos del objeto como valores y los convierte a string. No hay un modelo de entrada que imponga tipos. |
   | `title` | cualquier valor JSON | No | Se convierte a string; default `""`. |
   | `description` | cualquier valor JSON | No | Se convierte a string; default `""`. |
   | `status` | cualquier valor JSON | No | Se convierte a string; default `"TODO"`. |
   | `priority` | cualquier valor JSON | No | Se convierte a string; default `"MEDIUM"`. |
   | `owner` | cualquier valor JSON | No | Se convierte a string; default al `username` del token. El valor del cliente puede seleccionar otro owner. |

   **Ejemplo completo:**

   ```http
   POST /api/tasks HTTP/1.1
   Host: localhost:8000
   Authorization: Bearer <token vigente>
   Content-Type: application/json

   {
     "title": "Add tests",
     "description": "Improve API coverage",
     "status": "TODO",
     "priority": "HIGH",
     "owner": "student"
   }
   ```

4. **Response exitosa:** HTTP `201`.

   | campo | tipo | descripcion |
   |---|---|---|
   | `id` | integer | Identificador generado por SQLite. |
   | `title` | string | Título almacenado. |
   | `description` | string | Descripción almacenada. |
   | `status` | string | Estado almacenado. |
   | `owner` | string | Owner almacenado. |
   | `priority` | string | Prioridad almacenada. |
   | `createdAt` | string | Fecha de creación almacenada como `created_at`. |

   **Ejemplo de forma de respuesta:**

   ```json
   {
     "id": 4,
     "title": "Add tests",
     "description": "Improve API coverage",
     "status": "TODO",
     "owner": "student",
     "priority": "HIGH",
     "createdAt": "2026-11-02 12:00:00"
   }
   ```

   `id` y `createdAt` son generados en ejecución; los valores del ejemplo no
   identifican una fila garantizada.

5. **Respuestas de error observables**

   | codigo | cuando ocurre | ejemplo de cuerpo |
   |---|---|---|
   | 401 | Token ausente, inválido o expirado. | `{"detail":"Invalid token: invalid token"}` |
   | 422 | Falta el body o el JSON no puede parsearse como body requerido. | `{"detail":[{"type":"missing","loc":["body"],"msg":"Field required","input":null}]}` |
   | 500 | Error no manejado al acceder a campos del body, escribir SQL o leer la fila. | `Internal Server Error` (cuerpo de texto plano) |

6. **Notas de seguridad:** el campo `owner` del cliente puede asignar una tarea
   a otra identidad. Los campos se concatenan en el `INSERT` SQL. Un body que
   no sea un objeto con método `get` puede provocar un error no manejado.

---

## `GET /api/summary`

1. **Qué hace:** resume el número de tareas y sus estados para un owner.
2. **Autenticación:** requerida por dependencia. Enviar el header común
   `Authorization: Bearer <token>`.
3. **Request**

   | campo/parametro | tipo | obligatorio | descripcion |
   |---|---|---|---|
   | `owner` (query) | string | No | Owner usado para filtrar. Si se omite o está vacío, se usa el `username` del token. |
   | `Authorization` (header) | string | Sí para obtener acceso | Header Bearer leído por la dependencia común. |

   **Ejemplo completo:**

   ```http
   GET /api/summary?owner=student HTTP/1.1
   Host: localhost:8000
   Authorization: Bearer <token vigente>
   ```

4. **Response exitosa:** HTTP `200`.

   | campo | tipo | descripcion |
   |---|---|---|
   | `owner` | string | Owner usado para filtrar. |
   | `total` | integer | Número total de tareas encontradas. |
   | `byStatus` | objeto de string a integer | Conteo de tareas por valor de `status`; puede estar vacío. |
   | `highPriorityOpen` | integer | Tareas `HIGH` cuyo estado no sea `DONE` ni `ARCHIVED`. |

   **Ejemplo:**

   ```json
   {
     "owner": "student",
     "total": 2,
     "byStatus": {
       "TODO": 1,
       "IN_PROGRESS": 1
     },
     "highPriorityOpen": 1
   }
   ```

5. **Respuestas de error observables**

   | codigo | cuando ocurre | ejemplo de cuerpo |
   |---|---|---|
   | 401 | Token ausente, inválido o expirado. | `{"detail":"Invalid token: invalid token"}` |
   | 500 | Error no manejado al ejecutar la consulta o calcular el resumen. | `Internal Server Error` (cuerpo de texto plano) |

6. **Notas de seguridad:** el `owner` de query es controlado por el cliente
   y permite consultar otro owner. La consulta SQL concatena el valor recibido
   y no está parametrizada.

---

## `POST /api/requirements`

1. **Qué hace:** guarda un archivo de requerimientos en el directorio de
   uploads configurado.
2. **Autenticación:** requerida por dependencia. Enviar el header común
   `Authorization: Bearer <token>`.
3. **Request**

   | campo/parametro | tipo | obligatorio | descripcion |
   |---|---|---|---|
   | `Authorization` (header) | string | Sí para obtener acceso | Header Bearer leído por la dependencia común. |
   | `file` (multipart) | archivo | Sí | Archivo recibido como `UploadFile`; no se observa validación de extensión o tamaño. |

   **Ejemplo completo con un archivo local `requirements.txt`:**

   ```bash
   curl -X POST "http://localhost:8000/api/requirements" \
     -H "Authorization: Bearer <token vigente>" \
     -F "file=@requirements.txt;type=text/plain"
   ```

4. **Response exitosa:** HTTP `201`.

   | campo | tipo | descripcion |
   |---|---|---|
   | `owner` | string | `username` de la identidad extraída del token. |
   | `fileName` | string o null | Nombre recibido en `file.filename`. |
   | `storedAt` | string | Ruta de destino usada por el servidor. |
   | `size` | integer | Tamaño en bytes del contenido leído. |

   **Ejemplo para un archivo de 24 bytes** (la ruta depende del directorio y
   sistema operativo):

   ```json
   {
     "owner": "student",
     "fileName": "requirements.txt",
     "storedAt": "uploads/requirements.txt",
     "size": 24
   }
   ```

5. **Respuestas de error observables**

   | codigo | cuando ocurre | ejemplo de cuerpo |
   |---|---|---|
   | 401 | Token ausente, inválido o expirado. | `{"detail":"Invalid token: invalid token"}` |
   | 400 | El cuerpo multipart está malformado y Starlette no puede parsearlo. | `{"detail":"There was an error parsing the body"}` |
   | 422 | Falta la parte multipart requerida `file`. | `{"detail":[{"type":"missing","loc":["body","file"],"msg":"Field required","input":null}]}` |
   | 500 | Error no manejado al leer o escribir el archivo, por ejemplo una ruta de destino inválida. | `Internal Server Error` (cuerpo de texto plano) |

6. **Notas de seguridad:** el nombre recibido se concatena al directorio de
   uploads sin normalización ni comprobación de que el destino permanezca
   dentro del directorio. El archivo completo se lee en memoria. No hay límite
   de tamaño ni allowlist de extensiones en el código observado.

---

## `GET /api/backend-health`

1. **Qué hace:** consulta el endpoint de salud del servicio de autenticación y
   devuelve el resultado de esa consulta.
2. **Autenticación:** requerida por dependencia. Enviar el header común
   `Authorization: Bearer <token>`.
3. **Request**

   | campo/parametro | tipo | obligatorio | descripcion |
   |---|---|---|---|
   | `Authorization` (header) | string | Sí para obtener acceso | Header Bearer leído por la dependencia común. |
   | `AUTH_HEALTH_URL` (configuración de proceso) | string | No | URL objetivo; default `http://localhost:8080/q/health`. No es un parámetro HTTP. |

   **Ejemplo completo con configuración por defecto:**

   ```http
   GET /api/backend-health HTTP/1.1
   Host: localhost:8000
   Authorization: Bearer <token vigente>
   ```

4. **Response exitosa:** HTTP `200` tanto si la consulta upstream responde como
   si se captura una excepción.

   | campo | tipo | descripcion |
   |---|---|---|
   | `requestedBy` | string | `username` de la identidad extraída del token. |
   | `status` | integer | Status HTTP del upstream; `0` cuando la llamada o decodificación lanza una excepción capturada. |
   | `body` | string | Cuerpo upstream decodificado como UTF-8; texto de la excepción si falla la llamada. |

   **Ejemplo cuando upstream responde HTTP 200:**

   ```json
   {
     "requestedBy": "student",
     "status": 200,
     "body": "{\"status\":\"UP\"}"
   }
   ```

   **Ejemplo cuando la llamada upstream falla:**

   ```json
   {
     "requestedBy": "student",
     "status": 0,
     "body": "simulated backend failure"
   }
   ```

5. **Respuestas de error observables**

   | codigo | cuando ocurre | ejemplo de cuerpo |
   |---|---|---|
   | 401 | Token ausente, inválido o expirado. | `{"detail":"Invalid token: invalid token"}` |

   Los fallos del upstream no se propagan como status HTTP de error: el código
   los captura y devuelve HTTP `200` con `status: 0`.

6. **Notas de seguridad:** el texto de excepción se devuelve en `body` cuando
   la consulta falla. `AUTH_HEALTH_URL` controla el destino de una solicitud
   HTTP saliente y proviene de configuración del proceso.

---

## Preguntas abiertas

1. ¿Cuál es el formato acordado del token, quién lo emite y cómo se obtienen y
   rotan claves? El código actual no verifica firma, issuer ni audience.
2. ¿Se debe denegar el acceso cuando `owner` difiere del usuario autenticado?
   El comportamiento observado permite el filtro cruzado.
3. ¿Qué extensiones y tamaño máximo deben aceptarse en `/api/requirements`?
   La ruta actual no define restricciones.
4. ¿Qué respuestas de error deben considerarse contrato estable? Los ejemplos
   `500` corresponden al handler por defecto de FastAPI, no a una política
   acordada para errores operacionales.
