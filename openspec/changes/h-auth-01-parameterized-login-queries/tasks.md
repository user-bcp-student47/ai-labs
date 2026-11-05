# Tasks

Referencias: Issue #24 (`H-AUTH-01`), [proposal.md](proposal.md), [specs/auth-login/spec.md](specs/auth-login/spec.md) y [design.md](design.md). Todo el trabajo ocurre en `auth/`; las pruebas se ejecutan con `mvn` desde `auth/`.

## 1. Caracterización del contrato vigente (sin tocar código de producción)

- [x] 1.1 Ejecutar `mvn test` desde `auth/` con el código actual y verificar que la prueba existente `logsInWithWorkshopUser` pasa (línea base verde).
- [x] 1.2 En `AuthResourceTest`, agregar pruebas de login exitoso de `trainer` / `Trainer123!` (200, `user.role` = `TRAINER`, `token` no vacío) y de `student` con el campo adicional `client`; verificar que ambas pasan contra el código actual.
- [x] 1.3 En `AuthResourceTest`, agregar pruebas de rechazo (`student` con contraseña incorrecta y usuario inexistente): 401 y cuerpo sin `token`; verificar que pasan contra el código actual.
- [x] 1.4 En `AuthResourceTest`, agregar pruebas de validación: `username` ausente, vacío y solo espacios; `password` ausente, vacía y solo espacios; cuerpo ausente. Verificar que cada una responde 400 sin `token` contra el código actual y registrar cualquier respuesta distinta de 400 como diferencia respecto a la spec, sin cambiar código de producción.
- [x] 1.5 Ejecutar `mvn test` desde `auth/` y verificar que toda la suite pasa y que `git status --short -- auth/src/main` no muestra cambios.

## 2. Regresión del hallazgo (debe fallar antes de la corrección)

- [x] 2.1 En `AuthResourceTest`, agregar pruebas de inyección con `' OR '1'='1` en `password` (con `username` = `student`) y en ambos campos, esperando 401 sin `token`.
- [x] 2.2 En `AuthResourceTest`, agregar la prueba de inyección con `' OR '1'='1` solo en `username` y una contraseña arbitraria, esperando 401 sin `token`.
- [x] 2.3 En `AuthResourceTest`, agregar la prueba de `username` con comilla simple, `;` y `--` sin coincidencia, esperando 401 y no 500.
- [x] 2.4 Ejecutar `mvn test` desde `auth/` contra el código sin corregir y verificar que fallan las pruebas de 2.1 (respuesta 200 hoy); anotar en el resultado de esta tarea si 2.2 y 2.3 pasan o fallan antes del cambio, y que `git status --short -- auth/src/main` sigue sin cambios.
  - Resultado (17 pruebas, 2 fallos): fallan las dos de 2.1 (inyección en `password` y en ambos campos: esperado 401, actual 200). 2.2 (inyección solo en `username`) y 2.3 (`o'brien`, `user;name`, `user--name`) ya pasan antes del cambio, con 401. `git status --short -- auth/src/main` sin cambios.

## 3. Corrección en la capa de acceso a datos

- [x] 3.1 En `LegacyDatabase.findByCredentials`, reemplazar la consulta armada por concatenación por una consulta con marcadores y valores enlazados para `username` y `password`, sin cambiar firma, columnas devueltas ni manejo de errores; verificar con una búsqueda de texto que el SQL de ese método ya no concatena `username` ni `password` y que el proyecto compila.
- [x] 3.2 Ejecutar `mvn test` desde `auth/` y verificar que pasan las pruebas de los grupos 1 y 2, incluidas las que fallaban en 2.4.

## 4. Verificación del cambio completo

- [x] 4.1 Ejecutar `mvn verify` desde `auth/` y verificar que la suite completa pasa y que el reporte de JaCoCo muestra 85% o más de cobertura en el código modificado (`findByCredentials`).
  - Resultado: 17 pruebas, 0 fallos; `findByCredentials` con 94.6% de instrucciones y 90.9% de líneas (ramas 75%; la línea y la rama sin cubrir corresponden al `catch` que se conserva sin cambios).
- [x] 4.2 Verificar con `git diff --stat` desde la raíz que los únicos archivos modificados bajo `auth/` son `LegacyDatabase.java` y `AuthResourceTest.java`, y que no hay cambios en `api/`, `web/`, `docs/` ni documentos generales.
  - Resultado: el diff de archivos rastreados contiene solo esos dos archivos. Los no rastreados `docs/reto-incidencias/`, `docs/spec_ejmplo.md` y `web/log.log` ya existían antes de este cambio y no forman parte de él.
- [x] 4.3 Contrastar con los cuatro criterios de aceptación del Issue #24 (consulta parametrizada, login válido 200 con `user.username` y `token`, inyección 401 sin `token`, `mvn test` verde con prueba de inyección) y verificar que cada uno tiene una prueba o evidencia concreta.
  - Evidencia: `PreparedStatement` con `username=? AND password=?` y sin concatenación en `LegacyDatabase.findByCredentials`; `logsInWithWorkshopUser`; `rejectsInjectionInPasswordOrBothFieldsWithoutToken` y `rejectsInjectionInUsernameWithoutToken`; `mvn test` y `mvn verify` en verde.
- [x] 4.4 Ejecutar `openspec validate "h-auth-01-parameterized-login-queries" --strict` desde la raíz y verificar que el cambio sigue siendo válido.

## Workflow follow-up

- Confirmar las preguntas abiertas de [design.md](design.md) (contrato acordado de `/auth/login` y resultado real de la inyección solo en `username`) con las evidencias de los grupos 1 y 2.
- Cada grupo se cierra en un commit local propio sobre `feature/h-auth-01`, sin crear ni cambiar de rama y sin push en el Día 1.
- Tras la revisión y aprobación humanas, archivar el cambio con OpenSpec y verificar el resultado archivado.
