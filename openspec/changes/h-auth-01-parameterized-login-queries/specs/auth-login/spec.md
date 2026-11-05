# Spec Delta

## Purpose
Define el comportamiento observable de `POST /auth/login` para que la entrada del cliente nunca altere la verificación de credenciales y que el contrato actual del login para entradas legítimas se conserve (Issue #24, hallazgo `H-AUTH-01`).

## ADDED Requirements

### Requirement: Las credenciales se verifican solo como datos
El sistema SHALL tratar `username` y `password` recibidos en `POST /auth/login` exclusivamente como valores a comparar, sin que su contenido pueda modificar la lógica de la verificación. Ningún valor enviado por el cliente SHALL autenticar a un usuario cuyas credenciales no coincidan exactamente con las registradas.

#### Scenario: Intento de inyección en el usuario
- **WHEN** se envía `POST /auth/login` con `username` igual a `' OR '1'='1` y una `password` no vacía
- **THEN** la respuesta es HTTP 401
- **AND** el cuerpo no contiene `token`

#### Scenario: Intento de inyección en la contraseña
- **WHEN** se envía `POST /auth/login` con `username` igual a `student` y `password` igual a `' OR '1'='1`
- **THEN** la respuesta es HTTP 401
- **AND** el cuerpo no contiene `token`

#### Scenario: Intento de inyección en ambos campos
- **WHEN** se envía `POST /auth/login` con `username` y `password` ambos iguales a `' OR '1'='1`
- **THEN** la respuesta es HTTP 401
- **AND** el cuerpo no contiene `token`

#### Scenario: Caracteres especiales sin coincidencia
- **WHEN** se envía `POST /auth/login` con un `username` que contiene comillas simples, punto y coma o guiones dobles y que no corresponde a un usuario registrado
- **THEN** la respuesta es HTTP 401
- **AND** el servicio no responde con error interno (HTTP 500)

### Requirement: El login con credenciales válidas conserva su contrato
El sistema SHALL responder HTTP 200 a `POST /auth/login` cuando `username` y `password` coinciden con un usuario registrado, y el cuerpo SHALL incluir `token`, `expiresIn` y `user` con `username`, `displayName` y `role`. El formato, los nombres de campo y el significado de estos valores SHALL permanecer sin cambios respecto al comportamiento previo a este cambio.

#### Scenario: Login válido del usuario de laboratorio
- **WHEN** se envía `POST /auth/login` con `username` igual a `student` y `password` igual a `Workshop123!`
- **THEN** la respuesta es HTTP 200
- **AND** `user.username` es `student`
- **AND** el cuerpo incluye un `token` no vacío y `expiresIn`

#### Scenario: Login válido del usuario formador
- **WHEN** se envía `POST /auth/login` con `username` igual a `trainer` y `password` igual a `Trainer123!`
- **THEN** la respuesta es HTTP 200
- **AND** `user.username` es `trainer` y `user.role` es `TRAINER`

#### Scenario: Campos adicionales de la solicitud
- **WHEN** se envía `POST /auth/login` con credenciales válidas y un campo adicional `client` igual a `angular-workshop`
- **THEN** la respuesta es HTTP 200 con el mismo contrato que sin el campo adicional

### Requirement: Credenciales incorrectas se rechazan sin emitir token
El sistema SHALL responder HTTP 401 sin `token` cuando `username` y `password` no vacíos no corresponden a un usuario registrado, incluidos el usuario correcto con contraseña incorrecta y el usuario inexistente.

#### Scenario: Contraseña incorrecta
- **WHEN** se envía `POST /auth/login` con `username` igual a `student` y una `password` distinta de `Workshop123!`
- **THEN** la respuesta es HTTP 401
- **AND** el cuerpo no contiene `token`

#### Scenario: Usuario inexistente
- **WHEN** se envía `POST /auth/login` con un `username` que no existe y cualquier `password` no vacía
- **THEN** la respuesta es HTTP 401
- **AND** el cuerpo no contiene `token`

### Requirement: La validación de entrada del login se conserva
El sistema SHALL responder HTTP 400 a `POST /auth/login` cuando falta el cuerpo, o cuando `username` o `password` están ausentes o vacíos (incluido solo espacios), sin consultar ni autenticar al usuario.

#### Scenario: Usuario ausente o vacío
- **WHEN** se envía `POST /auth/login` sin `username`, o con `username` vacío o solo espacios
- **THEN** la respuesta es HTTP 400
- **AND** el cuerpo no contiene `token`

#### Scenario: Contraseña ausente o vacía
- **WHEN** se envía `POST /auth/login` con `username` válido y sin `password`, o con `password` vacía o solo espacios
- **THEN** la respuesta es HTTP 400
- **AND** el cuerpo no contiene `token`

### Requirement: El intento de inyección queda cubierto por una prueba automatizada
La suite de pruebas del componente `auth/` SHALL incluir al menos una prueba que envíe un intento de inyección en `POST /auth/login` y verifique HTTP 401 sin `token`, y la suite completa SHALL pasar al ejecutar `mvn test` desde `auth/`.

#### Scenario: Suite de auth con prueba de inyección
- **WHEN** se ejecuta `mvn test` desde el directorio `auth/`
- **THEN** la suite finaliza sin fallos
- **AND** incluye una prueba del intento de inyección con resultado HTTP 401 y sin `token`
