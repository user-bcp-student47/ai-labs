# Plan de corrección

## auth

### Aprobación Humana
Nombre: Grissella Paz
Fecha: 26/10/2026
IDs: H-AUTH-01, H-AUTH-02, H-AUTH-03, H-AUTH-04, H-AUTH-05, H-AUTH-06, H-AUTH-07

### Alcance y selección

Se incluyen todos los hallazgos `Pendiente` existentes para auth en `HALLAZGOS.md` (H-AUTH-01 a H-AUTH-07). El orden prioriza los tres riesgos altos (SQL, token y contraseñas), después los dos medios (configuración y errores) y finalmente la separación de responsabilidades y la revisión SCA. H-AUTH-07 es una revisión pendiente, no una vulnerabilidad confirmada.

`docs/ENDPOINTS.md` no existe. Hasta que se acuerde un contrato, las pruebas conservarán el comportamiento HTTP observado: validaciones 400, autenticación fallida 401 y login exitoso 200 con los campos `token`, `expiresIn` y `user`. Los cambios de seguridad que alteren mensajes o representación interna de tokens se limitarán a lo indicado en cada apartado y deberán quedar documentados; no se inventa una aprobación humana.

### H-AUTH-01 — Parametrizar consulta de credenciales

- **Hallazgo de HALLAZGOS.md:** concatenación de credenciales en consulta SQL.
- **Problema:** `username` y `password` se insertan en el SQL ejecutado con `Statement` (`auth/src/main/java/com/bcp/workshop/auth/LegacyDatabase.java:31-35`), y llegan desde `AuthResource.login` (`auth/src/main/java/com/bcp/workshop/auth/AuthResource.java:35`).
- **Cambio propuesto:** reemplazar la concatenación por `PreparedStatement` con placeholders y enlace individual de ambos valores. Mantener la consulta y el resultado funcional para credenciales válidas e inválidas; no filtrar ni normalizar las credenciales de modo que cambie su significado.
- **Archivos tocados:** `auth/src/main/java/com/bcp/workshop/auth/UserRepository.java`; pruebas en `auth/src/test/java/com/bcp/workshop/auth/AuthResourceTest.java`.
- **Caracterización antes del cambio:** agregar casos de login válido (`student` / `Workshop123!`, HTTP 200), contraseña/nombre inválidos (HTTP 401) y valor de credencial con comilla simple (debe ser fallo normal de autenticación, sin token ni error SQL). La prueba no afirmará que parametrizar quedó demostrado solo por el estado HTTP; la verificación del enlace debe cubrir el repositorio o la implementación del statement.

### H-AUTH-02 — Sustituir el token Base64 por token firmado

- **Hallazgo de HALLAZGOS.md:** token Base64 con secreto embebido y sin firma.
- **Problema:** `LegacyTokenUtil` concatena usuario, rol, expiración y secreto en texto antes de codificarlo con Base64 (`auth/src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java:8,12-15`); el endpoint lo devuelve como `token` (`auth/src/main/java/com/bcp/workshop/auth/AuthResource.java:39-42`).
- **Cambio propuesto:** usar un formato de token firmado con claims explícitos de sujeto/usuario, rol y expiración verificable. Evaluar primero una biblioteca JWT/JWS compatible y mantenida para la versión de Quarkus declarada; justificar y revisar cualquier dependencia externa antes de agregarla. Mantener opaco el valor del campo `token`, y conservar la forma de la respuesta y `expiresIn` observadas. No registrar tokens ni secretos.
- **Archivos tocados:** `auth/src/main/java/com/bcp/workshop/auth/TokenService.java`, `auth/src/main/java/com/bcp/workshop/auth/AuthService.java`, `auth/src/main/java/com/bcp/workshop/auth/AuthResource.java`, `auth/src/main/resources/application.properties` y `auth/src/test/java/com/bcp/workshop/auth/TokenServiceTest.java`. Se evaluó la extensión oficial Quarkus SmallRye JWT Build; se eligió `Mac` del JDK con algoritmo HS256 fijo y Jackson ya presente para no agregar una dependencia.
- **Caracterización antes del cambio:** extender `AuthResourceTest` para comprobar login exitoso, presencia de token no vacío, campos de `user` y valor/semántica observados de `expiresIn`; no fijar el contenido Base64 inseguro como contrato. Añadir prueba unitaria de caracterización del token actual solo si es necesaria para registrar el formato existente, sin convertir ese formato en requisito de compatibilidad.
- **Riesgo residual:** el formato interno cambió; el campo JSON `token` y la forma de respuesta se conservaron. No se inspeccionaron consumidores fuera de auth, por el límite de alcance, así que queda pendiente confirmar que ninguno decodifique el Base64 anterior.

### H-AUTH-03 — Almacenar contraseñas con hash lento y salt

- **Hallazgo de HALLAZGOS.md:** contraseñas almacenadas en texto plano.
- **Problema:** el esquema usa una columna `password` de texto y el arranque inserta contraseñas legibles (`auth/src/main/java/com/bcp/workshop/auth/LegacyDatabase.java:22-24`).
- **Cambio propuesto:** generar y verificar hashes lentos con salt aleatorio por contraseña. Preferir una implementación estándar disponible en el JDK (por ejemplo PBKDF2) para evitar dependencia innecesaria, con parámetros/versionado explícitos. Diseñar una migración idempotente de la base existente que no deje contraseñas en claro ni rompa los usuarios ya inicializados; verificar que no se registren credenciales.
- **Archivos tocados:** `auth/src/main/java/com/bcp/workshop/auth/UserRepository.java`, `auth/src/main/java/com/bcp/workshop/auth/PasswordHasher.java`, `auth/src/main/java/com/bcp/workshop/auth/AuthService.java`, `auth/src/main/java/com/bcp/workshop/auth/UserRecord.java` y pruebas en `auth/src/test/java/com/bcp/workshop/auth/`.
- **Caracterización antes del cambio:** capturar login 200 para los usuarios ficticios inicializados y 401 para contraseña incorrecta; crear prueba de migración sobre una base SQLite temporal con el esquema anterior para demostrar que los usuarios existentes pueden autenticarse tras migrar. No usar ni copiar datos reales.

### H-AUTH-04 — Externalizar secreto y restringir CORS

- **Hallazgo de HALLAZGOS.md:** secreto hardcodeado y CORS con origen irrestricto.
- **Problema:** el secreto está declarado en código (`auth/src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java:8`) y CORS permite cualquier origen con `/.*/` (`auth/src/main/resources/application.properties:4-7`).
- **Cambio propuesto:** leer el secreto del token desde configuración externa y fallar de forma explícita si falta en el perfil que lo requiere; usar únicamente valores de prueba aislados en pruebas. Sustituir el origen universal por una lista explícita configurable. README describe el frontend local en `http://localhost:4200`; usarlo solo como origen local de desarrollo, y dejar pendiente confirmar cualquier origen de despliegue. No ampliar el alcance para inspeccionar otros componentes.
- **Archivos tocados:** `auth/src/main/resources/application.properties`, `auth/src/main/java/com/bcp/workshop/auth/TokenService.java` y pruebas en `auth/src/test/java/com/bcp/workshop/auth/AuthResourceTest.java`.
- **Caracterización antes del cambio:** agregar pruebas locales que registren la configuración CORS actual y el comportamiento HTTP de login; luego validar que el origen local configurado se acepta y un origen arbitrario se rechaza. Aislar en pruebas cualquier clave de firma. La restricción de orígenes es una corrección deliberada del comportamiento CORS permisivo, no un cambio al formato HTTP del login.
- **Pendiente externo:** confirmar por el responsable del despliegue los orígenes permitidos fuera del desarrollo local; no se presume una aprobación.

### H-AUTH-05 — Evitar detalles internos en errores HTTP

- **Hallazgo de HALLAZGOS.md:** respuesta de error revela detalles internos.
- **Problema:** la respuesta 500 incluye `error.getMessage()` y el nombre de clase de la excepción (`auth/src/main/java/com/bcp/workshop/auth/AuthResource.java:55-58`).
- **Cambio propuesto:** conservar el estado HTTP de error observado, pero responder un mensaje genérico sin excepción ni detalle interno. Registrar contexto técnico únicamente en el log del servidor con el mecanismo del proyecto; no devolver ni registrar credenciales, tokens o secretos. Evitar capturas amplias que conviertan errores en respuestas de éxito.
- **Archivos a tocar:** `auth/src/main/java/com/bcp/workshop/auth/AuthResource.java` o el manejador de excepciones que se defina; pruebas en `auth/src/test/java/com/bcp/workshop/auth/AuthResourceTest.java`.
- **Caracterización antes del cambio:** definir una forma local y aislada de provocar un error controlado de persistencia y capturar el estado/cuerpo actuales (500 con `message` y `exception`). Después de registrar esa línea base, actualizar la prueba de regresión para exigir 500 con mensaje genérico y sin clase, stacktrace ni detalle interno.
- **Cambio de comportamiento:** el estado 500 se conserva, pero el cuerpo deja de exponer el campo `exception` y el detalle crudo. La aprobación humana registrada para H-AUTH-05 cubre esta corrección.

### H-AUTH-06 — Separar responsabilidades de login

- **Hallazgo de HALLAZGOS.md:** el recurso concentra validación, persistencia, token y respuesta.
- **Problema:** `AuthResource.login` realiza validación, llamada a base de datos, creación de token, composición de respuesta y manejo de errores en un método (`auth/src/main/java/com/bcp/workshop/auth/AuthResource.java:24-58`).
- **Cambio propuesto:** separar recurso HTTP, servicio de autenticación, repositorio y componente de seguridad/token; mantener una responsabilidad por método, usar retornos tempranos para validación, evitar duplicación y centralizar constantes. No devolver directamente el registro de persistencia como DTO HTTP. Coordinar este refactor con H-AUTH-01, H-AUTH-02, H-AUTH-03 y H-AUTH-05 para no duplicar implementaciones.
- **Archivos a tocar:** `auth/src/main/java/com/bcp/workshop/auth/AuthResource.java`; nuevos componentes de servicio/repositorio/seguridad bajo `auth/src/main/java/com/bcp/workshop/auth/`; `auth/src/main/java/com/bcp/workshop/auth/UserRecord.java` solo si hace falta separar el DTO de respuesta; pruebas bajo `auth/src/test/java/com/bcp/workshop/auth/`.
- **Caracterización antes del cambio:** ampliar `AuthResourceTest` para capturar el contrato observado de entrada y salida: request nula/campos vacíos 400, credenciales inválidas 401 y usuario ficticio válido 200 con campos `token`, `expiresIn` y `user`. Cubrir que el error de persistencia mantiene el estado 500, considerando la sanitización planificada en H-AUTH-05.
- **Secuencia:** escribir y ejecutar la caracterización antes del refactor; implementar y revisar por separado API, servicio, repositorio y seguridad, como indican las instrucciones de auth.

### H-AUTH-07 — Revisar advisories de dependencias sin presuponer CVE

- **Hallazgo de HALLAZGOS.md:** Quarkus 3.27.4 y Commons Text 1.9 requieren revisión SCA; no hay vulnerabilidad confirmada.
- **Problema:** las versiones aparecen declaradas en `auth/pom.xml:13,49-50`, pero no se consultó el árbol efectivo ni evidencia de advisories aplicables.
- **Cambio propuesto:** revisar el árbol de dependencias efectivo y los advisories con los controles SCA disponibles; documentar versión afectada, alcance y corrección sugerida. Actualizar o remover dependencias solo si la evidencia lo justifica, preservar los quality gates y coordinar cualquier dependencia externa. No agregar exclusiones ni silenciar alertas.
- **Archivos a tocar:** `auth/pom.xml` únicamente si el análisis confirma una corrección necesaria; `auth/src/test/java/com/bcp/workshop/auth/AuthResourceTest.java` solo si una actualización afecta el comportamiento; actualizar este apartado con evidencia del análisis.
- **Caracterización antes del cambio:** ejecutar y registrar la prueba actual de login exitoso y el conjunto de pruebas de auth como línea base, junto al árbol de dependencias/SCA previo. No se declara resultado hasta ejecutar los comandos.

## Registro de ejecuciones auth

- 2026-10-26 — IDs H-AUTH-01 a H-AUTH-06 — desde `auth/`, `mvn -Dtest=AuthResourceTest test`: PASS, 7 pruebas, 0 fallos, 0 errores. Línea base de caracterización previa a cambios de producción; la entrada con comilla produjo una excepción SQLite que el repositorio imprimió en el log y convirtió en usuario no encontrado.
- 2026-10-26 — IDs H-AUTH-01, H-AUTH-02, H-AUTH-04 y H-AUTH-05 — desde `auth/`, `mvn -Dtest=AuthResourceTest test`: FAIL esperado antes de corregir producción, 12 pruebas, 4 fallos y 0 errores. Fallaron la búsqueda de nombre literal con comilla, forma JWT firmada, rechazo de origen CORS no permitido y sanitización del error; las caracterizaciones del comportamiento actual pasaron.
- 2026-10-26 — H-AUTH-03 — desde `auth/`, `mvn '-Dtest=AuthResourceTest#migratedSchemaStoresOnlyPasswordHashes' test`: FAIL esperado antes de migrar, 1 prueba, 1 fallo y 0 errores; aún no existe `password_hash`.
- 2026-10-26 — H-AUTH-07 — desde `auth/`, `mvn dependency:tree -Dincludes=org.apache.commons:commons-text,io.quarkus:quarkus-core`: FAIL al resolver metadata del goal abreviado. Reintento explícito `mvn org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree`: PASS; árbol efectivo muestra `org.apache.commons:commons-text:1.9` directo e `io.quarkus:quarkus-core:3.27.4`.
- 2026-10-26 — H-AUTH-07 — consulta local de advisories mediante `POST https://api.osv.dev/v1/query`: Commons Text 1.9 devuelve GHSA-599f-7c49-w659/CVE-2022-42889, con versión corregida 1.10.0; Quarkus core 3.27.4 no devuelve una coincidencia en esa consulta de paquete. Apache especifica que el riesgo de Commons Text requiere interpolación con `StringSubstitutor` y entradas no confiables; auth usa `StringEscapeUtils.escapeHtml4` en `auth/src/main/java/com/bcp/workshop/auth/AuthResource.java:71`, no esa API. Commons Text se actualizó a 1.10.0; no se afirma una vulnerabilidad ejecutable en el uso anterior. Fuentes: https://api.osv.dev/v1/vulns/GHSA-599f-7c49-w659 y https://commons.apache.org/proper/commons-text/security.html.
- 2026-10-26 — H-AUTH-07 — https://quarkus.io/blog/quarkus-3-27-4-released/ describe Quarkus 3.27.4 como release de mantenimiento LTS con correcciones de seguridad y actualización segura dentro del stream 3.27. La consulta OSV a `io.quarkus:quarkus-core@3.27.4` no encontró una coincidencia; no se cambia el BOM sin evidencia adicional.
- 2026-10-26 — IDs H-AUTH-01 a H-AUTH-06 — desde `auth/`, con una copia de `auth/data/workshop.db` en `auth/target/` como `AUTH_DB_URL`, `mvn -Dtest=AuthResourceTest,PasswordHasherTest,TokenServiceTest test`: PASS, 18 pruebas, 0 fallos, 0 errores. Correcciones verificadas: credencial literal con comilla, JWT verificable, migración hash del esquema anterior, CORS local/rechazo de origen arbitrario y respuesta genérica de error; además se conservaron 400/401/200 y la forma del login exitoso.
- 2026-10-26 — H-AUTH-07 — tras actualizar Commons Text, el mismo comando anterior compiló y ejecutó las pruebas con la nueva versión; el árbol transitivo completo y los scanners remotos aún requieren validación.
- 2026-10-26 — IDs H-AUTH-01 a H-AUTH-07 — desde `auth/`, `mvn verify` con una copia fresca del esquema SQLite antiguo bajo `auth/target/`: PASS, 19 pruebas, 0 fallos, 0 errores. JaCoCo: clases de producción modificadas, 191 líneas cubiertas de 206 (92.72%); informe en `auth/target/site/jacoco/index.html`.
- 2026-10-26 — H-AUTH-07 — tras cambiar Commons Text a 1.10.0, `mvn org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree '-Dincludes=org.apache.commons:commons-text,io.quarkus:quarkus-core'`: PASS; árbol efectivo muestra Commons Text 1.10.0 y Quarkus core 3.27.4.
- 2026-10-26 — H-AUTH-07 — consultas OSV posteriores al cambio a `org.apache.commons:commons-text@1.10.0` y `io.quarkus:quarkus-core@3.27.4`: 0 coincidencias directas para ambas coordenadas/versiones.
- 2026-10-26 — H-AUTH-02 — se consultó la guía oficial de `quarkus-smallrye-jwt-build` (https://quarkus.io/guides/security-jwt-build; https://quarkus.io/extensions/io.quarkus/quarkus-smallrye-jwt-build/). La decisión de no agregar esa dependencia evita ampliar el árbol; se usaron JCA `Mac` y Jackson ya disponibles. TokenService prueba algoritmo fijo, firma, claims, expiración, tampering y secreto mínimo.
- CodeQL y Dependency Review remotos no ejecutados en este recorrido local. No se ejecutó escáner local de árbol transitivo.
- La sección «Aprobación Humana» registra a Grissella Paz, fecha 26/10/2026 e IDs H-AUTH-01 a H-AUTH-07.
- El `mvn verify` usó un duplicado temporal de `auth/data/workshop.db` en `auth/target/`; no se probó ni migró la base original del taller.
- Fuera del perfil `test` se requiere `AUTH_TOKEN_SECRET` de al menos 32 bytes; en despliegue se debe configurar `AUTH_CORS_ORIGINS`. El origen externo de producción sigue pendiente de confirmación.
- El formato interno del token cambió y no se inspeccionaron consumidores fuera de auth; confirmar compatibilidad de cualquier consumidor que asumiera el formato Base64 anterior.
- La respuesta de error 500 conserva el estado, pero ya no conserva el campo `exception`; es el cambio intencional de seguridad aprobado para H-AUTH-05.
- Desviaciones registradas: se eligió JCA `Mac` y Jackson existente en lugar de añadir SmallRye JWT; se añadió configuración de base de datos del perfil `%test` para ejecutar migraciones en `target/` sin modificar la base original. No se cambió Quarkus 3.27.4 por falta de evidencia directa que justificara modificar el BOM.
- `auth/pom.xml` conserva la modificación previa local del plugin Quarkus; el cambio de dependencia se limitó a Commons Text.

### H-AUTH-08 — Deshabilitar el bootstrap TRAINER en producción

- **Aprobación y alcance:** H-AUTH-08 y su HU-01 se aprobaron para implementación. Decisión confirmada en conversación el 2026-10-27: deshabilitar solo la cuenta bootstrap TRAINER en producción; conservar la siembra STUDENT en todos los perfiles; no borrar registros TRAINER existentes, pero impedir su autenticación en producción.
- **Cambio:** `auth.bootstrap.trainer.enabled` queda activo por defecto y deshabilitado en `%prod`. El repositorio omite la inserción TRAINER y devuelve usuario no encontrado para ese nombre de cuenta cuando la opción está deshabilitada.
- **Archivos:** `auth/src/main/java/com/bcp/workshop/auth/UserRepository.java`, `auth/src/main/resources/application.properties`, pruebas en `auth/src/test/java/com/bcp/workshop/auth/AuthResourceTest.java` y `auth/src/test/java/com/bcp/workshop/auth/UserRepositoryTest.java`.
- **Contrato protegido:** `POST /auth/login` conserva la forma de solicitud y sus respuestas observadas; una cuenta no disponible continúa respondiendo 401 y no obtiene token. Ver `docs/ENDPOINTS.md`.
- **Riesgo residual fuera de alcance:** la cuenta bootstrap STUDENT continúa sembrándose con datos fijos en producción por decisión explícita. Se registró como candidato separado H-AUTH-09 en `HALLAZGOS.md`; no se implementó.
- **Pruebas previas al cambio:** desde `auth/`, `mvn verify`: PASS, 20 pruebas; `mvn "-Dtest=AuthResourceTest#characterizationFreshDatabaseSeedsTrainerAccount" test`: PASS, 1 prueba; confirmó que antes el bootstrap TRAINER se sembraba en una base nueva con habilitación activa.
- **Pruebas después del cambio:** desde `auth/`, `mvn "-Dtest=UserRepositoryTest,AuthResourceTest" test`: PASS, 18 pruebas; cubre la configuración `%prod`, la omisión de la siembra, el bloqueo de un registro preexistente y el HTTP 401 sin token. `mvn verify`: PASS, 25 pruebas, 0 fallos y 0 errores.
- **Cobertura:** JaCoCo 0.8.12; los 5 renglones ejecutables añadidos/modificados en `UserRepository` están cubiertos (5/5, 100%) y las ramas de las condiciones nuevas están cubiertas (8/8). El reporte está en `auth/target/site/jacoco/index.html`.
- **Intento de prueba descartado:** un `@QuarkusTest` con cambio del perfil a `prod` pasó aislado, pero causó una falla de classloader/driver SQLite al ejecutar después las pruebas del perfil test. Se retiró ese test y se verificó la configuración `%prod` con SmallRye Config y el repositorio en pruebas unitarias; la suite completa final pasó.
- **Validaciones remotas:** CodeQL y Dependency Review no se ejecutaron localmente.
