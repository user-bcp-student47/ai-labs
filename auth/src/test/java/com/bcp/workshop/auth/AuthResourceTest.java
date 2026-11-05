package com.bcp.workshop.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class AuthResourceTest {
    @Test
    void logsInWithWorkshopUser() {
        given()
                .contentType("application/json")
                .body("{\"username\":\"student\",\"password\":\"Workshop123!\",\"client\":\"angular-workshop\"}")
                .when().post("/auth/login")
                .then()
                .statusCode(200)
                .body("user.username", is("student"))
                .body("token", notNullValue());
    }

    // Spec "Login válido del usuario formador" y "Campos adicionales": sin `client` el contrato 200 es el mismo.
    @Test
    void logsInWithTrainerWithoutClientField() {
        postLogin("{\"username\":\"trainer\",\"password\":\"Trainer123!\"}")
                .statusCode(200)
                .body("user.username", is("trainer"))
                .body("user.role", is("TRAINER"))
                .body("user.displayName", notNullValue())
                .body("expiresIn", notNullValue())
                .body("token", not(emptyOrNullString()));
    }

    // Spec "Contraseña incorrecta": usuario válido con contraseña errónea no emite token.
    @Test
    void rejectsWrongPasswordWithoutToken() {
        postLogin("{\"username\":\"student\",\"password\":\"not-the-password\"}")
                .statusCode(401)
                .body("token", nullValue());
    }

    // Spec "Usuario inexistente": usuario no registrado no emite token.
    @Test
    void rejectsUnknownUserWithoutToken() {
        postLogin("{\"username\":\"nobody\",\"password\":\"Workshop123!\"}")
                .statusCode(401)
                .body("token", nullValue());
    }

    // Spec "Usuario ausente o vacío": la validación de entrada responde 400 antes de consultar la base.
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"password\":\"Workshop123!\"}",
            "{\"username\":\"\",\"password\":\"Workshop123!\"}",
            "{\"username\":\"   \",\"password\":\"Workshop123!\"}"
    })
    void rejectsMissingOrBlankUsernameWithBadRequest(String body) {
        postLogin(body)
                .statusCode(400)
                .body("token", nullValue());
    }

    // Spec "Contraseña ausente o vacía": la validación de entrada responde 400.
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"username\":\"student\"}",
            "{\"username\":\"student\",\"password\":\"\"}",
            "{\"username\":\"student\",\"password\":\"   \"}"
    })
    void rejectsMissingOrBlankPasswordWithBadRequest(String body) {
        postLogin(body)
                .statusCode(400)
                .body("token", nullValue());
    }

    // Spec "La validación de entrada del login se conserva": cuerpo ausente.
    @Test
    void rejectsMissingBodyWithBadRequest() {
        given()
                .contentType("application/json")
                .when().post("/auth/login")
                .then()
                .statusCode(400)
                .body("token", nullValue());
    }

    // Spec "Intento de inyección en la contraseña" y "en ambos campos": la entrada nunca altera la verificación.
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"username\":\"student\",\"password\":\"' OR '1'='1\"}",
            "{\"username\":\"' OR '1'='1\",\"password\":\"' OR '1'='1\"}"
    })
    void rejectsInjectionInPasswordOrBothFieldsWithoutToken(String body) {
        postLogin(body)
                .statusCode(401)
                .body("token", nullValue());
    }

    // Spec "Intento de inyección en el usuario": solo en `username` la precedencia AND/OR ya lo rechaza hoy.
    @Test
    void rejectsInjectionInUsernameWithoutToken() {
        postLogin("{\"username\":\"' OR '1'='1\",\"password\":\"not-the-password\"}")
                .statusCode(401)
                .body("token", nullValue());
    }

    // Spec "Caracteres especiales sin coincidencia": comilla, `;` y `--` se tratan como datos, sin error interno.
    @ParameterizedTest
    @ValueSource(strings = {"o'brien", "user;name", "user--name"})
    void rejectsSpecialCharactersInUsernameWithoutServerError(String username) {
        postLogin("{\"username\":\"" + username + "\",\"password\":\"Workshop123!\"}")
                .statusCode(401)
                .body("token", nullValue());
    }

    private ValidatableResponse postLogin(String body) {
        return given()
                .contentType("application/json")
                .body(body)
                .when().post("/auth/login")
                .then();
    }
}
