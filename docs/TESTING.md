# Pruebas

## auth

### Matriz de `POST /auth/login`

La matriz sigue el contrato observado en [`ENDPOINTS.md`](./ENDPOINTS.md). Los casos de HTTP se implementan en [`AuthResourceTest.java`](../auth/src/test/java/com/bcp/workshop/auth/AuthResourceTest.java).

| Endpoint | Caso | Tipo | Resultado esperado | Test / IDs |
|---|---|---|---|---|
| `POST /auth/login` | Credenciales ficticias válidas, con `client` | Camino feliz | HTTP 200; token presente; `expiresIn` 3600; usuario, nombre, rol y `debug` conservan la forma observada. | `logsInWithWorkshopUser` — H-AUTH-01, H-AUTH-02, H-AUTH-03, H-AUTH-06 |
| `POST /auth/login` | Cuenta bootstrap TRAINER no disponible en producción | Caso borde / autorización | El repositorio no devuelve el usuario, incluso si hay un registro preexistente; el endpoint devuelve HTTP 401 y no emite token. | `UserRepositoryTest.blocksExistingTrainerAccountWhenBootstrapIsDisabled`, `productionProfileConfigurationDisablesTrainerBootstrap`, `AuthResourceTest.rejectsTrainerLoginWhenBootstrapIsDisabled` — H-AUTH-08 |
| `POST /auth/login` | `username` vacío | Caso borde | HTTP 400, mensaje `Username is required`. | `rejectsMissingUsername` — H-AUTH-06 |
| `POST /auth/login` | `password` compuesto por espacios | Caso borde | HTTP 400, mensaje `Password is required`. | `rejectsMissingPassword` — H-AUTH-06 |
| `POST /auth/login` | Cuerpo JSON `null` | Caso borde | HTTP 400, mensaje `Request is null`. | `rejectsNullRequest` — H-AUTH-06 |
| `POST /auth/login` | Contraseña no válida | Error documentado | HTTP 401, mensaje `Invalid user student or password`. | `rejectsInvalidCredentials` — H-AUTH-01, H-AUTH-03 |
| `POST /auth/login` | Nombre no registrado con comilla simple | Caso borde | HTTP 401 con el mensaje observado para credenciales inválidas; no debe causar error SQL ni autenticar a otro usuario. | `rejectsCredentialsContainingQuote` — H-AUTH-01 |
| `POST /auth/login` | Excepción inesperada del servicio | Error documentado | HTTP 500, mensaje `Internal server error`; el cuerpo no contiene `exception`. | `returnsGenericErrorWhenAuthenticationServiceThrows` — H-AUTH-05 |

El caso de consulta parametrizada con un usuario ficticio cuyo nombre contiene una comilla también se prueba en `acceptsAUsernameContainingQuoteAsLiteralAfterSqlParameterization`, usando el repositorio y el servicio.

### Código de los tests de endpoint

Los siguientes casos son métodos ejecutables de [`AuthResourceTest.java`](../auth/src/test/java/com/bcp/workshop/auth/AuthResourceTest.java); el archivo enlazado conserva el código completo y es la fuente de verdad.

```java
@Test
void logsInWithWorkshopUser() {
    String token = given()
            .contentType("application/json")
            .body("{\"username\":\"student\",\"password\":\"Workshop123!\",\"client\":\"angular-workshop\"}")
            .when().post("/auth/login")
            .then()
            .statusCode(200)
            .body("user.username", is("student"))
            .body("user.displayName", is("Workshop Student"))
            .body("user.role", is("STUDENT"))
            .body("expiresIn", is(3600))
            .body("debug", is("authenticated-with-legacy-flow"))
            .body("token", notNullValue())
            .extract()
            .path("token");

    TokenService.TokenClaims claims = tokenService.verify(token);
    assertEquals("student", claims.username());
    assertEquals("STUDENT", claims.role());
}

@Test
void rejectsInvalidCredentials() {
    given()
            .contentType("application/json")
            .body("{\"username\":\"student\",\"password\":\"wrong\"}")
            .when().post("/auth/login")
            .then()
            .statusCode(401)
            .body("message", is("Invalid user student or password"));
}

@Test
void rejectsCredentialsContainingQuote() {
    given()
            .contentType("application/json")
            .body("{\"username\":\"stu'dent\",\"password\":\"wrong\"}")
            .when().post("/auth/login")
            .then()
            .statusCode(401)
            .body("message", is("Invalid user stu'dent or password"));
}

@Test
void rejectsMissingUsername() {
    given()
            .contentType("application/json")
            .body("{\"username\":\"\",\"password\":\"Workshop123!\"}")
            .when().post("/auth/login")
            .then()
            .statusCode(400)
            .body("message", is("Username is required"));
}

@Test
void rejectsMissingPassword() {
    given()
            .contentType("application/json")
            .body("{\"username\":\"student\",\"password\":\" \"}")
            .when().post("/auth/login")
            .then()
            .statusCode(400)
            .body("message", is("Password is required"));
}

@Test
void rejectsNullRequest() {
    given()
            .contentType("application/json")
            .body("null")
            .when().post("/auth/login")
            .then()
            .statusCode(400)
            .body("message", is("Request is null"));
}

@Test
void returnsGenericErrorWhenAuthenticationServiceThrows() {
    AuthService failingAuthService = new AuthService() {
        @Override
        public Optional<LoginResult> authenticate(String username, String password) {
            throw new IllegalStateException("test-only authentication failure");
        }
    };
    QuarkusMock.installMockForType(failingAuthService, AuthService.class);

    given()
            .contentType("application/json")
            .body("{\"username\":\"student\",\"password\":\"Workshop123!\"}")
            .when().post("/auth/login")
            .then()
            .statusCode(500)
            .body("message", is("Internal server error"))
            .body("exception", nullValue());
}
```

### Pruebas relacionadas

- `allowsCorsFromConfiguredLocalOrigin` y `blocksUntrustedCorsOriginAfterRestriction` en `AuthResourceTest.java` verifican el origen local configurado y la ausencia del encabezado de autorización CORS para un origen no permitido (H-AUTH-04).
- `PasswordHasherTest.java` verifica salts, coincidencias y formatos inválidos (H-AUTH-03).
- `TokenServiceTest.java` verifica firma, claims, expiración, alteración de firma y secreto demasiado corto (H-AUTH-02, H-AUTH-04).
- `AuthResourceTest.java` también verifica la migración del esquema, la inicialización idempotente y la respuesta genérica del mapper directamente. La prueba HTTP 500 anterior cubre además la ruta REST y el mapper registrado.
- `UserRepositoryTest.java` lee la configuración SmallRye para el perfil `prod` y comprueba la omisión de siembra TRAINER y el bloqueo de un registro TRAINER preexistente; la base temporal es local a `target/`.
- `AuthResourceTest.rejectsTrainerLoginWhenBootstrapIsDisabled` comprueba por HTTP que una cuenta TRAINER no está disponible cuando el bootstrap está desactivado; la prueba restaura el valor de configuración al terminar.

### Ejecuciones realizadas

Comandos ejecutados con directorio de trabajo `auth/`. Los códigos de salida se registran tal como los devolvió Maven.

| Componente | IDs | Comando | Directorio | Código de salida | Resultado |
|---|---|---|---|---:|---|
| auth | H-AUTH-01 a H-AUTH-06 | `mvn "-Dtest=AuthResourceTest" test` | `auth/` | 0 | PASS; 13 pruebas, 0 fallos, 0 errores. |
| auth | H-AUTH-01 a H-AUTH-07 | `mvn verify` | `auth/` | 0 | PASS; 20 pruebas, 0 fallos, 0 errores. JaCoCo generó el reporte. |
| auth | H-AUTH-08 | `mvn "-Dtest=AuthResourceTest#characterizationFreshDatabaseSeedsTrainerAccount" test` | `auth/` | 0 | PASS antes del cambio; 1 prueba. Caracterizó que una base nueva sembraba TRAINER cuando el bootstrap estaba habilitado. |
| auth | H-AUTH-08 | `mvn "-Dtest=UserRepositoryTest,AuthResourceTest" test` | `auth/` | 0 | PASS después del cambio; 18 pruebas, 0 fallos, 0 errores. |
| auth | H-AUTH-08 | `mvn verify` | `auth/` | 0 | PASS después del cambio; 25 pruebas, 0 fallos, 0 errores. |

#### Intentos fallidos durante la preparación

Estos intentos no representan fallos de los tests finales. Se conservan para no confundirlos con las ejecuciones aprobadas.

| Componente | IDs | Comando | Directorio | Código de salida | Resultado |
|---|---|---|---|---:|---|
| auth | H-AUTH-05 | `mvn "-Dtest=AuthDatabaseFailureTest" test` | `auth/` | 0 | La prueba con perfil Quarkus aislado pasó sola, pero se descartó esa implementación porque la ejecución de la suite completa falló después al resolver el driver SQLite en otro contexto de prueba. El archivo temporal de prueba se eliminó. |
| auth | H-AUTH-05 | `mvn verify` | `auth/` | 1 | 20 pruebas; `AuthResourceTest.rejectsInvalidCredentials` recibió HTTP 500 en vez de 401 tras el cambio temporal de perfil. Se sustituyó por un mock de `AuthService` dentro del perfil normal de pruebas. |
| auth | H-AUTH-05 | `mvn "-Dtest=AuthResourceTest" test` | `auth/` | 1 | 13 pruebas; el intento de provocar el 500 eliminando la tabla no causó error en la petición, que respondió HTTP 200. Se reemplazó por una excepción controlada del servicio. |
| auth | H-AUTH-08 | `mvn verify` | `auth/` | 1 | Un test de integración con perfil Quarkus `prod` pasó aislado, pero al volver después al perfil `test` SQLite no encontró un driver adecuado. Se retiró ese test y se comprobó el valor de configuración `prod` con SmallRye Config junto con la inicialización y el lookup del repositorio; el `mvn verify` final pasó. |

### Cobertura

Medición realizada por JaCoCo 0.8.12 durante `mvn verify`; reporte en `auth/target/site/jacoco/index.html` y contadores en `auth/target/site/jacoco/jacoco.csv`. Para H-AUTH-08, las 5 líneas ejecutables añadidas/modificadas en `UserRepository` están cubiertas (**5/5, 100%**) y las ramas de las condiciones nuevas están cubiertas (**8/8, 100%**), según las anotaciones del informe fuente HTML. La clase completa queda en 54/79 líneas (68.35%) porque el porcentaje de clase incluye rutas previas no modificadas; no se presenta como cobertura del diff.

### Fallos, pendientes y propuestas

- **Fallos finales:** ninguno; las dos ejecuciones finales registradas terminaron con código 0.
- **Pendiente de reproducibilidad:** `migratedSchemaStoresOnlyPasswordHashes` inspecciona el esquema de la base ya inicializada; el método no crea por sí mismo el esquema antiguo. Mantener una fixture antigua determinista para que una ejecución limpia siempre ejercite la migración.
- **Pendiente de alcance:** CodeQL y Dependency Review remotos no se ejecutaron en este recorrido local. H-AUTH-09, cuenta bootstrap STUDENT que permanece sembrada en producción, está registrado como candidato separado; no se resolvió dentro de H-AUTH-08.
- **Pruebas propuestas:** hacer autosuficiente la fixture de migración antes de usar esa prueba como evidencia repetible. No se proponen rutas ni respuestas adicionales a las documentadas en `ENDPOINTS.md`.
