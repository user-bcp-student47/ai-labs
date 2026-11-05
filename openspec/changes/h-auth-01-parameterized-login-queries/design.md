# Design

## Context

Motivación y alcance: ver [proposal.md](proposal.md) (Issue #24, hallazgo `H-AUTH-01`). Requisitos y escenarios: [specs/auth-login/spec.md](specs/auth-login/spec.md).

Estado actual relevante, observado en `auth/` (Quarkus 3.27.4, Java 21, SQLite vía `sqlite-jdbc`):

- `AuthResource.login` valida el cuerpo (400), llama a `LegacyDatabase.findByCredentials(username, password)` y, si devuelve `null`, responde 401; si devuelve un `UserRecord`, genera el token con `LegacyTokenUtil` y responde 200.
- `LegacyDatabase.findByCredentials` construye el SQL con `+` y lo ejecuta con `Statement`; la comparación de contraseña se hace en la consulta, en texto plano. Cualquier excepción se captura, se imprime y el método devuelve `null`, lo que `AuthResource` traduce a 401.
- La clase conecta con `DriverManager` por cada llamada; `DB_URL` viene de `AUTH_DB_URL` con valor por defecto `jdbc:sqlite:./data/workshop.db`.
- `AuthResourceTest` cubre solo el login válido de `student`; no hay pruebas de 400, 401 ni de inyección.
- `docs/ENDPOINTS.md` no incluye el contrato de `/auth/login`; la línea base es el comportamiento observado (ver Supuestos del proposal).

Restricción de precedencia SQL que condiciona las pruebas (análisis, **por confirmar** en el paso de caracterización): en la consulta actual `AND` se evalúa antes que `OR`. Con `username = ' OR '1'='1` y una contraseña que no coincide, la condición queda como `username='' OR ('1'='1' AND password='x')`, que no devuelve filas; en cambio la inyección en `password` (o en ambos campos) sí produce una condición siempre verdadera. Por tanto, una prueba que inyecte solo en `username` con contraseña arbitraria podría pasar hoy y no demostraría la corrección.

## Goals / Non-Goals

**Goals:**
- Que ningún valor de `username` o `password` pueda alterar la estructura de la consulta de credenciales (requisito "Las credenciales se verifican solo como datos").
- Que el cambio sea mínimo, localizado en la capa de acceso a datos y revisable de forma aislada.
- Que existan pruebas que fallen antes del cambio y pasen después, y que fijen el contrato actual del login.

**Non-Goals:**
- Hash de contraseñas, token firmado, secretos, CORS, actualización de dependencias, mensajes de error y separación de capas: son otros hallazgos (ver proposal, fuera de alcance).
- Cambiar la firma pública de `findByCredentials`, el esquema de `legacy_users` o el pool/gestión de conexiones.
- Introducir un ORM, un framework de acceso a datos o nuevas dependencias.
- Rediseñar `AuthResource` ni mover su lógica de validación.

## Contratos protegidos

Se conservan sin cambios (detalle en la spec):

| Contrato | Comportamiento protegido |
|---|---|
| `POST /auth/login` éxito | 200 con `token`, `expiresIn` y `user` (`username`, `displayName`, `role`), más los campos adicionales que hoy emite la respuesta. |
| Validación de entrada | 400 para cuerpo, `username` o `password` ausentes, vacíos o solo espacios. |
| Rechazo | 401 sin `token` para credenciales que no coinciden, con el mensaje actual. |
| Firma de `findByCredentials` | `UserRecord findByCredentials(String, String)`, devuelve `null` si no hay coincidencia. |
| Datos sembrados | Usuarios `student` y `trainer` y sus contraseñas actuales. |
| Salida de errores internos | Un fallo de base de datos sigue resolviéndose como "sin usuario" (401), tal como hoy. |

Efecto observable intencional y único: los intentos de inyección dejan de autenticar (401 sin `token`).

## Componentes afectados

| Componente | Cambio |
|---|---|
| `auth/src/main/java/com/bcp/workshop/auth/LegacyDatabase.java` | Reemplazar la construcción del SQL por una consulta con marcadores de posición y enlace de parámetros. |
| `auth/src/test/java/com/bcp/workshop/auth/AuthResourceTest.java` | Agregar pruebas de caracterización y de regresión. |
| `AuthResource`, `LegacyTokenUtil`, `LoginRequest`, `UserRecord`, `application.properties`, `pom.xml` | Sin cambios. |
| `api/`, `web/`, `docs/ENDPOINTS.md`, documentos generales | Sin cambios. |

## Decisions

### D1. Consulta parametrizada con `PreparedStatement`

Se mantiene `DriverManager` y la misma consulta, pero con dos marcadores (`username=? AND password=?`) y valores enlazados con `setString`. El método sigue devolviendo `UserRecord` o `null`.

- **Por qué:** es la corrección estándar para la inyección SQL, la exige el Issue como criterio de aceptación, no cambia la semántica de la consulta y mantiene el diff en un solo método.
- **Alternativas consideradas:**
  - *Escapar comillas manualmente:* frágil, depende del dialecto y no elimina la clase de defecto. Descartada.
  - *Lista de caracteres prohibidos / rechazar entradas con comillas:* cambiaría el contrato (400 donde hoy hay 401) y bloquearía contraseñas legítimas con caracteres especiales. Descartada.
  - *Obtener la fila solo por `username` y comparar la contraseña en Java:* también cierra la inyección, pero adelanta el diseño del hallazgo de hashing (comparación, tiempos, salt) y amplía el alcance. Se pospone a ese hallazgo.
  - *Adoptar un ORM o `AgroalDataSource` de Quarkus:* cambio de arquitectura y dependencias nuevas, fuera del Issue. Descartada.

### D2. No tocar el manejo de errores ni la gestión de conexiones en este cambio

El `try/catch` que devuelve `null` y la conexión por llamada se conservan.

- **Por qué:** el contrato protegido incluye que un fallo interno se resuelva como 401; cambiarlo mezclaría este Issue con el hallazgo de errores.
- **Alternativa:** propagar excepciones y devolver 500. Descartada por alterar comportamiento observable no cubierto por el Issue.

### D3. Pruebas de caracterización antes del cambio, vía HTTP

Las pruebas se escriben con `@QuarkusTest` y RestAssured contra `POST /auth/login`, el mismo estilo de la suite actual, y no contra `LegacyDatabase` directamente.

- **Por qué:** los requisitos de la spec son de comportamiento observable; probar por HTTP evita acoplarse a la implementación y sirve igual antes y después del cambio.
- **Alternativa:** pruebas unitarias de `findByCredentials`. Se pueden añadir como complemento, pero no sustituyen la prueba de contrato.

## Estrategia incremental

Cada paso es un commit revisable por separado, sobre la rama actual `feature/h-auth-01` y con pruebas ejecutadas con `mvn test` desde `auth/`.

1. **Caracterización (sin tocar código de producción).** Agregar pruebas que fijan el comportamiento vigente y que deben pasar con el código actual: login válido de `student` y de `trainer`, contraseña incorrecta (401), usuario inexistente (401), `username` y `password` vacíos o ausentes y cuerpo ausente (400), campo `client` adicional (200).
2. **Prueba de inyección en rojo.** Agregar la prueba de inyección que debe **fallar** con el código actual: inyección en `password` y en ambos campos (200 hoy, esperado 401). La inyección solo en `username` se agrega también, pero se registra explícitamente si ya pasa antes del cambio (ver Contexto).
3. **Corrección.** Parametrizar la consulta en `LegacyDatabase.findByCredentials` (D1). Sin otros cambios.
4. **Verificación.** Todas las pruebas de los pasos 1 y 2 en verde, y `mvn test` completo en verde desde `auth/`. Confirmar en revisión que el SQL ya no se arma por concatenación.

## Pruebas de caracterización necesarias antes de modificar

Deben existir y pasar con el código **actual** antes del paso 3, ya que protegen los contratos de la sección anterior:

| Prueba | Escenario de la spec | Resultado esperado hoy y después |
|---|---|---|
| Login `student` / `Workshop123!` | Login válido del usuario de laboratorio | 200, `user.username=student`, `token` no vacío, `expiresIn` presente |
| Login `trainer` / `Trainer123!` | Login válido del usuario formador | 200, `user.role=TRAINER` |
| Login con `client` adicional | Campos adicionales de la solicitud | 200, mismo contrato |
| `student` con contraseña incorrecta | Contraseña incorrecta | 401, sin `token` |
| Usuario inexistente | Usuario inexistente | 401, sin `token` |
| `username` ausente, vacío o solo espacios | Usuario ausente o vacío | 400, sin `token` |
| `password` ausente, vacía o solo espacios | Contraseña ausente o vacía | 400, sin `token` |
| Cuerpo ausente | La validación de entrada se conserva | 400, sin `token` |

Pruebas de regresión del hallazgo (el paso 2 las deja en rojo y el paso 3 en verde):

| Prueba | Escenario de la spec | Antes | Después |
|---|---|---|---|
| Inyección en `password` con `username=student` | Intento de inyección en la contraseña | 200 (rojo) | 401, sin `token` |
| Inyección en ambos campos | Intento de inyección en ambos campos | 200 (rojo) | 401, sin `token` |
| Inyección en `username` con contraseña arbitraria | Intento de inyección en el usuario | Por confirmar | 401, sin `token` |
| `username` con comilla simple, `;` o `--`, sin coincidencia | Caracteres especiales sin coincidencia | 401 o 500 según el valor | 401, nunca 500 |

Los datos de prueba usan solo los usuarios sembrados y valores de laboratorio; no se añaden secretos reales.

## Risks / Trade-offs

- **[La prueba de inyección en `username` ya pasa hoy y da falsa confianza]** → Incluir inyección en `password` y en ambos campos, que sí deben fallar antes del cambio; documentar el resultado real del paso 2.
- **[Regresión del contrato de login por un cambio accidental en la consulta]** → Las pruebas de caracterización del paso 1 corren antes y después; el diff se limita a `findByCredentials`.
- **[Comportamiento ante entradas con caracteres especiales legítimos]** → Hoy una comilla simple provoca un error SQL capturado (401); tras el cambio se compara como dato (401 sin coincidencia). El resultado observable es el mismo, pero ahora por la vía correcta; queda cubierto por la prueba de caracteres especiales.
- **[El error interno sigue ocultándose como 401 (D2)]** → Trade-off aceptado: se mantiene el contrato y se aborda en el hallazgo de errores.
- **[Contraseñas en texto plano comparadas en SQL]** → Fuera de alcance; este cambio no lo empeora ni lo corrige. Queda abierto para el hallazgo de hashing.
- **[Cobertura]** → La meta del repositorio es 85% o más del código modificado; `findByCredentials` queda cubierto por las pruebas de los pasos 1 y 2. JaCoCo no se modifica.

## Rollback

El cambio es un solo método y su prueba, en commits independientes. Si una verificación falla tras el paso 3: revertir ese commit (`git revert`) restaura la consulta anterior sin afectar las pruebas de caracterización, y esas pruebas siguen sirviendo para validar cualquier nuevo intento. No hay migración de datos, cambio de esquema ni de configuración que deshacer. Revertir reabre la vulnerabilidad, por lo que debe tratarse como estado temporal.

## Open Questions

- ¿Existe un contrato acordado de `POST /auth/login` fuera de este repositorio que difiera del comportamiento observado? No altera el enfoque, pero conviene confirmarlo antes de cerrar el cambio.
- Resultado real del paso 2 para la inyección solo en `username`: se registra al ejecutar la caracterización y no cambia el diseño.
