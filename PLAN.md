# Plan de correccion

## Alcance — api

Este plan cubre los nueve hallazgos de api marcados `Aprobado` en
`HALLAZGOS.md`. `docs/ENDPOINTS.md` no existe: se preservaran las rutas,
los formatos de respuesta y los comportamientos validos observados. Los
cambios de seguridad que nieguen acceso entre usuarios son intencionales y se
limitan a solicitudes no autorizadas.

Antes de cambiar codigo se escribiran y ejecutaran las pruebas de
caracterizacion descritas en cada hallazgo desde `api/`, con base de datos y
archivos temporales. Se registraran comandos y resultados reales en
`docs/TESTING.md` cuando ese entregable se cree. No se declaran pruebas
realizadas.

## H-API-01 — Token Base64 con secreto embebido y sin firma

- **Problema:** Base64 no autentica el contenido; los campos de identidad y rol
  se aceptan sin firma, emisor ni audiencia verificados.
- **Cambio propuesto:** integrar una biblioteca JWT mantenida y verificada por
  SCA; validar firma, algoritmo permitido, emisor, audiencia y expiracion con
  configuracion externa. No incluir secretos ni permitir fallback al token
  Base64 legado. Coordinar con el servicio emisor, fuera del alcance de codigo
  de este plan, el algoritmo, claims y claves. Mantener el header Bearer y las
  respuestas de los endpoints para solicitudes autorizadas.
- **Archivos a tocar:** `api/app/main.py`; nuevo `api/app/security.py`;
  `api/tests/test_api.py`; `api/requirements.txt` solo si la biblioteca
  seleccionada se justifica y sus versiones/advisories se verifican.
- **Caracterizacion previa:** escribir pruebas que documenten el resultado
  actual para token vigente, vencido, malformado y ausente, incluyendo status
  HTTP y forma de respuesta. Antes de migrar, agregar pruebas de JWT firmado
  con clave ficticia, firma incorrecta, issuer/audience incorrectos, expiracion
  y algoritmo no permitido.
- **Pendiente de coordinacion:** confirmar claims, algoritmo y mecanismo de
  distribucion de claves con el emisor antes de implementar. No leer ni cambiar
  `auth/`.

## H-API-02 — El cliente puede elegir el owner

- **Problema:** `owner` de query y del body puede prevalecer sobre la identidad
  autenticada en tareas y resumen.
- **Cambio propuesto:** derivar el owner de la identidad verificada. Conservar
  el parametro `owner` por compatibilidad sintactica, pero devolver 403 si
  difiere de la identidad. Rechazar tambien un `owner` distinto en el body de
  creacion. No habilitar excepciones por rol.
- **Archivos a tocar:** `api/app/main.py` o, tras H-API-07,
  `api/app/routes.py` y `api/app/services.py`; `api/tests/test_api.py`.
- **Caracterizacion previa:** escribir pruebas para GET tasks, GET summary y
  POST tasks con owner omitido, igual al usuario y distinto; guardar status,
  owner devuelto y alcance de los datos actuales. Despues, conservar los casos
  propios y agregar pruebas que verifiquen 403 para acceso/asignacion cruzada.
- **Contrato:** no existe `docs/ENDPOINTS.md`; las solicitudes propias
  conservaran su respuesta. La denegacion de acceso a otro owner es el cambio
  de seguridad aprobado.

## H-API-03 — Filtros e inserts SQL concatenan entradas del cliente

- **Problema:** filtros por owner y campos de creacion se interpolan en SQL.
- **Cambio propuesto:** mover acceso a datos a repositorio y usar placeholders
  SQLite con parametros en todos los filtros e inserts. Mantener columnas,
  orden de listado, valores por defecto, status HTTP y forma JSON observados.
- **Archivos a tocar:** `api/app/main.py` o, tras H-API-07,
  `api/app/repository.py`; `api/tests/test_api.py`.
- **Caracterizacion previa:** escribir pruebas de lectura y creacion con datos
  normales y texto ficticio que incluya comillas en owner, title y description.
  Registrar el resultado actual. Tras el cambio, comprobar que el texto se
  trata como dato literal, se conserva en la respuesta/base y no altera otros
  registros.

## H-API-04 — Carga de archivos usa el nombre recibido sin validarlo

- **Problema:** el nombre del cliente se usa como ruta y el archivo completo se
  lee en memoria; no hay politica visible de extension o tamano.
- **Cambio propuesto:** normalizar el nombre, resolver el destino y verificar
  que permanezca dentro del directorio de uploads; validar extension y tamano
  antes de persistir, y procesar el archivo acotando memoria. Usar constantes
  con nombre para limites y extensiones. Conservar respuesta y status para
  cargas validas.
- **Archivos a tocar:** `api/app/main.py` o, tras H-API-07,
  `api/app/services.py`; `api/tests/test_api.py`.
- **Caracterizacion previa:** escribir una prueba para carga valida (incluido
  el nombre y la forma de respuesta actuales) y registrar si se permiten
  nombres con rutas, extensiones arbitrarias y archivos grandes. Agregar
  pruebas de rechazo para rutas fuera del directorio, extension no permitida y
  tamano excedido despues de acordar la politica.
- **Pendiente de producto:** definir extensiones permitidas, tamano maximo y
  status/mensaje de rechazo antes de implementar; no inventar esos valores.

## H-API-05 — Dependencias fijadas requieren verificacion SCA

- **Problema:** `urllib3` y `python-multipart` estan fijadas; no se ha
  comprobado si las versiones estan afectadas por advisories.
- **Cambio propuesto:** ejecutar un scanner SCA aprobado contra el manifiesto y
  versiones resueltas. Actualizar solo paquetes con advisory aplicable, a una
  version corregida compatible, justificando cada cambio. No silenciar
  findings ni agregar una dependencia de runtime; si hace falta instalar un
  scanner, coordinarlo primero.
- **Archivos a tocar:** `api/requirements.txt` solo si el analisis confirma una
  actualizacion; `docs/TESTING.md` para evidencia de scanner y pruebas cuando
  ese documento se cree.
- **Caracterizacion previa:** antes de tocar el manifiesto, registrar versiones
  fijadas/resueltas y ejecutar la suite existente como baseline. Guardar la
  salida del scanner inicial; despues repetir scanner y suite para comparar.
- **Pendiente:** hasta verificar advisories, no afirmar que existe un CVE ni
  cambiar versiones por conjetura.

## H-API-06 — Excepciones amplias exponen detalles internos

- **Problema:** errores de token y del health check pueden devolver texto crudo
  de excepciones al cliente.
- **Cambio propuesto:** capturar excepciones esperadas de forma especifica,
  registrar detalles solo en logs del servidor y devolver mensajes genericos.
  Mantener 401 para autenticacion invalida y las claves de respuesta actuales
  del health check; en fallo de red, no exponer el texto de la excepcion.
- **Archivos a tocar:** `api/app/main.py` o, tras H-API-07,
  `api/app/security.py` y `api/app/routes.py`; `api/tests/test_api.py`.
- **Caracterizacion previa:** escribir pruebas para token invalido y fallo
  simulado del health check, registrando status, claves y cuerpo actuales.
  Despues, comprobar que se conserva la estructura acordada sin incluir clase,
  mensaje ni detalles internos de la excepcion.

## H-API-07 — Responsabilidades concentradas en un modulo

- **Problema:** inicializacion de SQLite, rutas, reglas de negocio y mapeo de
  respuestas estan concentrados en `main.py`.
- **Cambio propuesto:** separar incrementalmente rutas FastAPI, servicios y
  repositorio SQLite; mantener `create_app` como punto de composicion y
  conservar el acceso de pruebas. No cambiar endpoints ni respuestas validas.
- **Archivos a tocar:** `api/app/main.py`; nuevos
  `api/app/routes.py`, `api/app/services.py` y `api/app/repository.py`;
  `api/tests/test_api.py`.
- **Caracterizacion previa:** ampliar pruebas TestClient para todos los
  endpoints, status, claves/valores JSON, datos iniciales, defaults y cierre
  con base temporal. Ejecutarlas antes de mover responsabilidades y repetirlas
  tras cada extraccion.
- **Orden:** ejecutar la separacion despues de fijar la caracterizacion y
  coordinarla con H-API-02, H-API-03 y H-API-06 para evitar duplicar cambios en
  `main.py`.

## H-API-08 — Requests y responses usan diccionarios sin modelos Pydantic

- **Problema:** payloads y respuestas tipados como `dict[str, Any]` dejan
  validaciones y formas de datos implicitas.
- **Cambio propuesto:** definir modelos Pydantic de entrada y salida para
  tareas, resumen y respuestas pertinentes; mantener nombres JSON (incluido
  `createdAt`), defaults y comportamiento de solicitudes validas. Rechazar
  entradas invalidas con errores de validacion FastAPI, sin convertirlas a
  strings silenciosamente.
- **Archivos a tocar:** nuevo `api/app/schemas.py`; `api/app/main.py` o
  `api/app/routes.py` tras H-API-07; `api/tests/test_api.py`. Pydantic ya esta
  declarado en `api/requirements.txt`; no se propone agregar dependencia.
- **Caracterizacion previa:** escribir pruebas para payload valido completo y
  minimo, defaults, tipos, respuesta JSON y alias `createdAt` observados.
  Despues agregar casos de campos ausentes, tipos invalidos y campos
  inesperados conforme al modelo acordado.

## H-API-09 — Condicionales anidados y duplicacion en consultas

- **Problema:** `summary` anida condiciones para contar prioridades y se repite
  la proyeccion de columnas de tareas en consultas.
- **Cambio propuesto:** extraer el calculo del resumen a una funcion pequena
  con condiciones combinadas o guard clauses; centralizar la proyeccion SQL en
  el repositorio sin crear abstracciones adicionales. Usar nombres para
  estados/valores compartidos y conservar la semantica actual.
- **Archivos a tocar:** `api/app/main.py` o, tras H-API-07,
  `api/app/services.py` y `api/app/repository.py`; `api/tests/test_api.py`.
- **Caracterizacion previa:** escribir pruebas para totales por estado y
  `highPriorityOpen`, incluyendo prioridades HIGH con estados TODO, DONE y
  ARCHIVED, y verificar orden/formato de tareas. Comparar exactamente la
  respuesta antes y despues de refactorizar.

## Orden y dependencias

1. Acordar contrato del token con el emisor y politica de extension/tamano de
   archivos antes de implementar H-API-01 y H-API-04.
2. Escribir y ejecutar las caracterizaciones de los nueve hallazgos antes de
   modificar codigo.
3. Verificar SCA de forma independiente; no cambiar dependencias sin evidencia.
4. Implementar seguridad de token y autorizacion; luego parametrizar SQL,
   asegurar uploads y sanitizar errores.
5. Introducir modelos Pydantic y separar capas de forma incremental, aplicando
   los cambios de calidad durante esas extracciones para evitar trabajo
   duplicado.
6. Ejecutar regresion y registrar resultados reales. La meta de cobertura es
   85% del codigo modificado; no reducir umbrales existentes.

**Aprobacion humana:** el plan y los nueve hallazgos estan aprobados segun las
confirmaciones recibidas. La implementacion permanece en pausa hasta resolver
los pendientes de contrato JWT y politica de archivos indicados en H-API-01 y
H-API-04.

## Registro de ejecuciones

### Caracterizacion previa — api

- **Fecha:** 2026-11-02.
- **IDs:** H-API-01, H-API-02, H-API-03, H-API-04, H-API-05, H-API-06,
  H-API-07, H-API-08 y H-API-09.
- **Directorio de trabajo:** `api/`.
- **Comando:** `.\.venv\Scripts\python.exe -m pytest`.
- **Resultado real:** PASS; 13 pruebas aprobadas, 27 avisos deprecados, 0.92 s.
- **Alcance:** se ejecutaron las pruebas de caracterizacion previas; no se
  cambio la implementacion.
- **Cobertura:** no evaluada; no se modifico codigo de produccion. Se medira
  al implementar los hallazgos.
- **H-API-05:** la prueba confirma los pins `urllib3==2.5.0` y
  `python-multipart==0.0.21`. No se ejecuto analisis SCA porque `pip-audit` no
  esta disponible; el analisis de advisories sigue pendiente.
- **Despues de correcciones:** no aplica; no se implementaron correcciones.
