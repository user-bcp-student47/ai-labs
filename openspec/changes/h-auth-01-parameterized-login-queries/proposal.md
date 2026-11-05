# Proposal

## Why

Referencia: Issue [#24](https://github.com/user-bcp-student47/ai-labs/issues/24) (hallazgo `H-AUTH-01`, componente `auth`, vulnerabilidad de severidad alta).

`LegacyDatabase.findByCredentials` (`auth/src/main/java/com/bcp/workshop/auth/LegacyDatabase.java:32`) arma el SQL concatenando `username` y `password`. Un valor como `' OR '1'='1` altera la consulta y permite iniciar sesión sin conocer la contraseña, es decir, el login es evadible por cualquier cliente que alcance `POST /auth/login`. Es la puerta de entrada al token que consumen `api/` y `web/`, por lo que debe cerrarse antes de continuar con los demás hallazgos de `auth`.

## What Changes

- El login deja de interpretar el contenido de `username` y `password` como parte de la consulta: cualquier valor recibido se trata únicamente como dato.
- Un intento con valores de inyección (por ejemplo `' OR '1'='1` en usuario o contraseña) responde 401 y no emite `token`.
- El contrato observable de `POST /auth/login` para entradas legítimas no cambia.
- Se agrega una prueba de caracterización/regresión del intento de inyección en `AuthResourceTest`.

### Alcance

- Componente `auth/` únicamente; flujo `POST /auth/login` → búsqueda de credenciales.
- Comportamiento observable del login ante credenciales válidas, inválidas, vacías y con caracteres especiales.
- Pruebas del componente `auth/` (`mvn test` desde `auth/`).

### Fuera de alcance

- Código, pruebas y documentos de `api/` y `web/` (se conservan sin cambios sus secciones en documentos generales).
- Otros hallazgos de `auth` listados en `RETO.md`: SCA (Quarkus, Commons Text), contraseñas en texto plano/hash con salt, token Base64 con secreto embebido, secreto y CORS hardcodeados, separación de capas, mensajes de error con detalles internos.
- Cambios al formato del token, `expiresIn`, el campo `debug` o los mensajes de error actuales.
- Diseño técnico de la solución y desglose de tareas (se definen en artefactos posteriores).

## Comportamiento que debe preservarse

Línea base observada en `AuthResource.login` y `AuthResourceTest` (`docs/ENDPOINTS.md` documenta solo `api/` y no incluye un contrato de `POST /auth/login`; ver Supuestos):

- Credenciales válidas (`student` / `Workshop123!`): HTTP 200 con `token`, `expiresIn` y `user` (`username`, `displayName`, `role`).
- Cuerpo ausente: HTTP 400; `username` ausente o vacío: HTTP 400; `password` ausente o vacío: HTTP 400.
- Credenciales que no corresponden a un usuario: HTTP 401, sin `token`.
- El Issue declara explícitamente que el contrato de `POST /auth/login` se conserva.

## Capabilities

### New Capabilities
- `auth-login`: autenticación por usuario y contraseña en `POST /auth/login`; cubre la resolución de credenciales sin que la entrada del cliente altere la consulta y el contrato observable de respuestas (éxito, validación y rechazo).

### Modified Capabilities
<!-- Ninguna: openspec/specs/ está vacío; no existen specs previas. -->

## Impact

- Código afectado (en la fase de implementación): `auth/src/main/java/com/bcp/workshop/auth/LegacyDatabase.java` y `auth/src/test/java/com/bcp/workshop/auth/AuthResourceTest.java`.
- API: sin cambios de endpoint, método, request ni formato de respuesta para entradas legítimas. Efecto observable intencional: los intentos de inyección dejan de autenticar.
- Dependencias, `api/` y `web/`: sin impacto.
- Criterios de aceptación del Issue que este cambio debe poder verificar:
  - `findByCredentials` no concatena valores en el SQL.
  - Login con `student` / `Workshop123!` responde 200 con `user.username` y `token`.
  - Login con `' OR '1'='1` como usuario o contraseña responde 401 sin `token`.
  - `AuthResourceTest` incluye la prueba de inyección y `mvn test` pasa desde `auth/`.

## Supuestos

- `docs/ENDPOINTS.md` no contiene el contrato de `/auth/login`; se toma como línea base el comportamiento actual del código y las pruebas, más lo que fija el Issue. Si existe un contrato acordado distinto, debe confirmarse antes de implementar.
- El Issue se consultó con el MCP local de GitHub porque `github-official` no está disponible en esta sesión.
- Los mensajes de error actuales (incluido el 401 que menciona el usuario) se conservan tal cual: su revisión corresponde a otro hallazgo.
