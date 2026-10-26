# Contratos de endpoints

## auth

### `POST /auth/login`

#### Contrato observado

La implementación actual recibe y devuelve JSON. Las referencias de código son relativas a la raíz del repositorio.

Solicitud:

```json
{
  "username": "<usuario>",
  "password": "<contraseña>",
  "client": "<cliente-opcional>"
}
```

- `username` y `password` son necesarios y no pueden ser nulos, vacíos ni contener solo espacios. `client` está presente en el DTO, pero no se usa para autenticar. Referencias: `auth/src/main/java/com/bcp/workshop/auth/LoginRequest.java:3,4,5`; `auth/src/main/java/com/bcp/workshop/auth/AuthResource.java:28,32,35,39`.
- Las credenciales se verifican con una consulta por `username` y una comprobación de hash de contraseña. Referencias: `auth/src/main/java/com/bcp/workshop/auth/UserRepository.java:59,60,62,63,64`; `auth/src/main/java/com/bcp/workshop/auth/AuthService.java:19,20,21`.

Respuestas observadas:

| Estado | Forma del cuerpo | Condición |
|---|---|---|
| `200` | `{"token":"<JWT>","expiresIn":3600,"user":{"username":"<usuario>","displayName":"<nombre>","role":"<rol>"},"debug":"authenticated-with-legacy-flow"}` | Credenciales válidas. |
| `400` | `{"message":"Request is null"}`, `{"message":"Username is required"}` o `{"message":"Password is required"}` | Solicitud nula o campo requerido ausente/vacío. |
| `401` | `{"message":"Invalid user <username> or password"}` | Credenciales no válidas. |
| `500` | `{"message":"Internal server error"}` | Error inesperado; los detalles se registran en el servidor y no se devuelven al cliente. |

Referencias de respuestas: `auth/src/main/java/com/bcp/workshop/auth/AuthResource.java:30,32,35,41,42,49,51,53,57,59`; DTOs: `auth/src/main/java/com/bcp/workshop/auth/LoginResponse.java:3`, `auth/src/main/java/com/bcp/workshop/auth/UserResponse.java:3` y `auth/src/main/java/com/bcp/workshop/auth/ErrorResponse.java:3`.

El token emitido usa HS256 y contiene `sub`, `role`, `iat` y `exp`; su duración observada es de 3600 segundos. `AUTH_TOKEN_SECRET` debe configurarse fuera del perfil de pruebas y debe tener al menos 32 bytes. Referencias: `auth/src/main/java/com/bcp/workshop/auth/TokenService.java:22,24,40,45,46,47,48,120`; `auth/src/main/resources/application.properties:9`.

El origen CORS se configura con `AUTH_CORS_ORIGINS`; el valor predeterminado observado es `http://localhost:4200`. Referencia: `auth/src/main/resources/application.properties:5`.

#### Contrato acordado

No se encontró un contrato previo en `docs/ENDPOINTS.md` ni una aprobación humana independiente del contrato HTTP. Por ello, lo anterior se registra como comportamiento observado y no como acuerdo formal. La forma de respuesta existente —incluidos `token`, `expiresIn`, `user` y `debug`— se conserva como referencia provisional hasta que se acuerde cualquier cambio con los responsables del cliente.

#### Riesgos pendientes

- El formato interno de `token` cambió del Base64 anterior a JWT firmado. No se inspeccionaron consumidores fuera de `auth/`; confirmar que no dependan del formato anterior.
- El origen de producción permitido debe confirmarse y configurarse mediante `AUTH_CORS_ORIGINS`.
- El perfil de producción requiere un `AUTH_TOKEN_SECRET` seguro, aleatorio y de al menos 32 bytes; no se documenta aquí ningún valor secreto.
- H-AUTH-08 está pendiente: el arranque siembra credenciales fijas de `trainer` también en producción. Consultar `HALLAZGOS.md`.
- El cuerpo de error `500` ya no devuelve el campo `exception`; el estado se conserva, pero ese detalle de respuesta cambió.
