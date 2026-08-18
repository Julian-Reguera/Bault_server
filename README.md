<!-- README pendiente. Por ahora contiene el catalogo de endpoints y el contrato de codigos de error. -->

## API endpoints

Base URL: `/api`. Salvo los `/api/auth/public/**`, todos los endpoints requieren un JWT
válido en `Authorization: Bearer <accessToken>`. Los endpoints de devices, folders y transfers
requieren además la autoridad `DEVICE_ACTIVE` (device en estado `ACTIVE`); los de devices
también aceptan `DEVICE_DISABLED`.

Los errores siguen la forma `{code, message, details?}` documentada en [API error codes](#api-error-codes).

### Autenticación (`/api/auth`)

| Método | Endpoint | Auth | Uso |
|---|---|---|---|
| POST | `/public/register/password` | pública | Registra un usuario nuevo con `{email, password, firstName?, lastName?}`. NO emite tokens ni crea Device: envía un código de verificación al email y devuelve `202`. El primer login válido creará el Device. |
| POST | `/public/email/verify/request` | pública | Reenvía un código de verificación al email si existe. Respuesta constante `202` (no filtra si el email está registrado). |
| POST | `/public/email/verify/confirm` | pública | Consume el código con `{email, code}` y marca `emailVerified=true`. Sin esto, el login devuelve `403 EMAIL_NOT_VERIFIED`. |
| POST | `/public/login/password` | pública | Autentica con `{email, password}`. Opcionalmente `{deviceId, deviceSecret}` para reconocer un device existente; si no, registra uno nuevo y devuelve `deviceSecret` **solo esta vez**. Aplica rate-limit (`LOGIN_RATE_LIMITED` a los N fallos). Devuelve `{accessToken, refreshToken, deviceId, deviceSecret?}`. |
| POST | `/public/refresh` | pública | Rota el refresh token (uno-por-vez). Con `{refreshToken}` devuelve nuevo par de tokens. Detecta reuso (`REFRESH_TOKEN_REUSED`) y revoca todo el device. |
| POST | `/public/logout` | pública (posesión) | Revoca un refresh token concreto con `{refreshToken}`. Idempotente. |
| POST | `/public/password/reset/request` | pública | Envía código de reset al `{email}`. Respuesta constante `202` (no filtra existencia). |
| POST | `/public/password/reset/confirm` | pública | Cambia la contraseña con `{email, code, newPassword}` y revoca **todas** las sesiones del usuario. |
| POST | `/secured/logout-all` | JWT válido | Revoca todos los refresh tokens del usuario autenticado (cierra sesión en todos sus devices). |

### Usuario (`/api/account`)

| Método | Endpoint | Auth | Uso |
|---|---|---|---|
| GET | `/account` | JWT | Devuelve el bundle que necesita la pantalla "Cuenta": datos del usuario + plan activo + uso mensual + fechas de suscripción. Un solo call para toda la vista. |

### Plan de pago (`/api/plans`)

| Método | Endpoint | Auth | Uso |
|---|---|---|---|
| GET | `/plans` | JWT | Catálogo de planes disponibles (los que están `enabled=true`). Marca `isCurrent=true` en el que tiene el usuario actualmente para poder pintarlo distinto en la UI. |

### Devices (`/api/devices`)

Autorización: JWT + device en status `ACTIVE` o `DISABLED` (los `BLOCKED`/`REMOVED` cortan en el filtro).

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/` | Lista todos los devices del usuario con su `status` y `online` (presencia WS). Es lo que alimenta la pantalla de "mis dispositivos". |
| GET | `/{deviceId}` | Detalle de un device concreto. Devuelve `404 DEVICE_NOT_FOUND` si está REMOVED (tombstoned). |
| PATCH | `/{deviceId}` | Renombra el alias del device con `{alias}`. Emite `device.updated` por WS al resto de devices del usuario. |
| POST | `/{deviceId}/activate` | Mueve `DISABLED → ACTIVE`. Falla con `409 DEVICE_PLAN_LIMIT_REACHED` si el plan del usuario ya tiene el máximo de ACTIVEs. |
| POST | `/{deviceId}/deactivate` | Mueve `ACTIVE → DISABLED`. Bloqueado durante 7 días desde la última activación (`DEVICE_DEACTIVATE_LOCKED`) para evitar rotar devices para saltarse el límite del plan. |
| POST | `/{deviceId}/block` | Marca el device como `BLOCKED` (bloqueo lado servidor). Revoca todos sus refresh tokens; el device recibirá `403 DEVICE_BLOCKED` en cualquier request. |
| POST | `/{deviceId}/unblock` | Devuelve un `BLOCKED` a `DISABLED`. Solo aplica sobre devices `BLOCKED`. |
| DELETE | `/{deviceId}` | Marca el device como `REMOVED` (tombstone; no borra la fila para preservar histórico). Revoca sus refresh tokens y emite `device.removed`. |

### Folders (`/api/folders`)

Autorización: JWT + `DEVICE_ACTIVE`.

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/` | Lista todas las folders compartidas de **cualquier** device del usuario (para vistas cross-device). |
| GET | `/device/{deviceId}` | Folders compartidas por un device concreto del usuario. Incluye `folder-status` con el status del device. |
| GET | `/shared-with-me` | Folders que **otros** devices del mismo usuario han compartido con el device caller. Le sirve al cliente para saber a qué destinos puede escribir/leer. |
| POST | `/` | Crea una folder compartida con `{path, sharing?, encrypted?}`. `sharing` ∈ `{NONE, READ, READ_WRITE}` (default `NONE`). Si `encrypted=true`, genera una DEK AES-256 y la persiste envuelta con el KEK activo. |
| PATCH | `/{folderId}` | Cambia el nivel de sharing con `{sharing}`. Solo puede llamarlo el device dueño. |
| DELETE | `/{folderId}` | Deja de compartir la folder (soft: `enabled=false`). Solo el owner device. |
| GET | `/browse/{folderId}?path=...` | Lista el contenido de una folder haciendo RPC via WS al device dueño (que es quien tiene el filesystem real). Requiere que el owner esté online. `path` es una subruta dentro de la folder; sin `..` (traversal blocked). |

### Transferencias (`/api/transfers`)

Autorización: JWT + `DEVICE_ACTIVE`. Modelo de tres tipos según quién inicia:
- **download-request**: `owner = receiver`, tira del sender.
- **upload-request**: `owner = sender`, empuja al receiver.
- **third-party-request**: `owner ≠ sender ≠ receiver`, un tercer device orquesta.

#### Histórico y utilidades

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/?status=&deviceId=&senderId=&receiverId=&q=&cursor=&size=` | Listado paginado (cursor-based) del histórico de transferencias del usuario. `status` ∈ `{all, ok, active, failed}`. Devuelve `{items, nextCursor, filters}` con los dropdowns de senders/receivers ya calculados. |
| GET | `/export-csv?...` | Exporta el histórico filtrado como CSV en streaming. Mismos filtros que el listado. |
| POST | `/{transferId}/retry` | Crea una copia PENDING de una transferencia FAILED/DENIED/CANCELLED, revalidando que sender/receiver siguen ACTIVE y que la folder implicada sigue compartida con el sharing requerido. Solo el owner original puede reintentar. |

#### Crear transferencias

| Método | Endpoint | Uso |
|---|---|---|
| POST | `/download-request` | El caller (receiver) pide descargar `originPath` de una folder compartida (`READ`+) del sender. Body: `{senderDeviceId, originFolderId, originPath, destinationPath?, sizeBytes}`. Notifica al sender por WS con `transfer.upload-requested`. Header opcional `Idempotency-Key`. |
| POST | `/upload-request` | El caller (sender) ofrece enviar `originPath` a una folder compartida (`READ_WRITE`+) del receiver. Body: `{receiverDeviceId, destinationFolderId, originPath, destinationPath?, sizeBytes}`. Notifica al receiver con `transfer.download-offered`. Header opcional `Idempotency-Key`. |
| POST | `/third-party-request` | El caller (owner, tercer device) orquesta una transferencia entre sender y receiver (ambos del mismo usuario, ninguno = owner). Body: `{senderDeviceId, receiverDeviceId, originFolderId, destinationFolderId, originPath, destinationPath?, sizeBytes}`. Notifica a los DOS peers. Header opcional `Idempotency-Key`. |

#### Descubrimiento y consulta

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/pending-as-peer` | Devuelve las transferencias PENDING de tipo third-party en las que el device caller participa (como sender o receiver) pero **no** es el owner. Útil como catch-up si perdió la notificación WS por estar offline. Cada item incluye `role: "sender"|"receiver"` para saber qué acción debe tomar. |
| GET | `/{transferId}/keys` | Devuelve, por folder implicada, si está cifrada y (solo al device que la necesita) la DEK en claro Base64 para cifrar/descifrar en el cliente. Sender recibe la DEK del origin; receiver la del destination. |

#### Ejecución del pipe

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/{transferId}/download` | El receiver reclama el pipe. Handshake simétrico con `/upload`: si es el primero en llegar avisa al sender con `transfer.download-started` y bloquea hasta que el sender también reclame el pipe. Cuando ambos han hecho el handshake, uno de los dos hace la transición `PENDING → IN_PROGRESS` y se streamean los bytes. Aplica throttling según plan y techo global. Si el sender no llega antes de 30 s, la transferencia se queda en `PENDING` y devuelve `504 TRANSFER_UPLOADER_TIMEOUT`. |
| POST | `/{transferId}/upload` | El sender entrega los bytes (body `application/octet-stream`). Headers: `X-Filename?`, `X-Content-Type?`, `Content-Length?`. Handshake simétrico con `/download`: puede llegar primero o segundo. Si llega primero avisa al receiver con `transfer.upload-started` y bloquea hasta que el receiver reclame el pipe. Si el receiver no llega antes de 30 s, la transferencia se queda en `PENDING` y devuelve `504 TRANSFER_DOWNLOADER_TIMEOUT`. |
| DELETE | `/{transferId}/cancel` | El owner cancela. Aplica en `PENDING` o `IN_PROGRESS`; en `IN_PROGRESS` aborta el pipe (interrumpe el streaming en curso o desbloquea al peer que esperaba el handshake). Emite `transfer.updated CANCELLED`. |
| POST | `/{transferId}/deny` | El peer (no el owner) rechaza. Aplica en `PENDING` o `IN_PROGRESS`; en `IN_PROGRESS` aborta el pipe. Emite `transfer.denied` al owner. |

### WebSocket (`/api/ws`)

Endpoint STOMP-over-WebSocket con JWT en el handshake (`Authorization: Bearer ...`).

- Broker prefixes: `/topic`, `/queue`. App prefix: `/app`. User prefix: `/user`.
- Presencia: el device se marca `online` cuando se suscribe a `/queue/device.{deviceId}` (canal RPC exclusivo por device).
- Fan-out de eventos del usuario: `/user/queue/events` (creación/actualización de devices, folders, transfers, presencia).

## API error codes

Todas las respuestas de error de la API tienen la forma canónica:

```json
{
  "code": "EMAIL_NOT_VERIFIED",
  "message": "Email not verified",
  "details": { "email": "user@example.com" }
}
```

- `code` — identificador estable del error. **El cliente traduce por `code`**, nunca por `message`.
- `message` — texto fallback en inglés, solo para logs/debugging.
- `details` — parámetros estructurados para interpolar en el mensaje traducido (opcional).

El status HTTP también forma parte del contrato de cada código (columna "Status").

Fuente de la verdad: [`baultServer.exceptions.ApiErrorCode`](src/main/java/baultServer/exceptions/ApiErrorCode.java).
Cualquier código nuevo **debe** añadirse ahí y a esta tabla en el mismo commit.

### Genéricos

| Código | Status | Uso |
|---|---|---|
| `INTERNAL_ERROR` | 500 | Fallback para cualquier excepción no mapeada. |
| `MISSING_FIELD` | 400 | Falta un campo requerido en el body. `details.field` = nombre del campo. |
| `INVALID_FIELD` | 400 | Un campo tiene un valor no válido. `details.field` = nombre del campo. |

### Autenticación

| Código | Status | Uso |
|---|---|---|
| `EMAIL_INVALID_FORMAT` | 400 | El email no cumple el patrón de formato. |
| `EMAIL_ALREADY_REGISTERED` | 409 | El email ya está registrado (register). |
| `EMAIL_NOT_VERIFIED` | 403 | Login rechazado por email sin verificar. `details.email` presente. |
| `INVALID_CREDENTIALS` | 401 | Password incorrecto (Spring Security `BadCredentialsException`). |
| `UNAUTHENTICATED` | 401 | Falta autenticación o el JWT es inválido/expirado. |
| `ACCESS_DENIED` | 403 | Autenticado pero sin permisos suficientes. |
| `USER_NOT_FOUND` | 404 | El usuario referenciado no existe. |
| `USER_DISABLED` | 403 | La cuenta está deshabilitada (Spring Security `DisabledException`/`LockedException`). |
| `LOGIN_RATE_LIMITED` | 429 | Demasiados fallos de login para ese email; bloqueado temporalmente. `details.retryAfterSeconds`. Umbrales configurables vía `bault.security.login.{max-failures,window-seconds,lockout-seconds}` (defaults: 5 fallos, ventana 900s, lockout 300s). |

### Refresh tokens

| Código | Status | Uso |
|---|---|---|
| `REFRESH_TOKEN_INVALID` | 401 | Refresh token no reconocido. |
| `REFRESH_TOKEN_EXPIRED` | 401 | Refresh token expirado. |
| `REFRESH_TOKEN_REUSED` | 401 | Refresh token ya revocado (posible robo → se revoca todo el device). |

### Códigos de email (verificación / reset)

| Código | Status | Uso |
|---|---|---|
| `EMAIL_CODE_NOT_FOUND` | 400 | No hay ningún código activo para ese `(user, purpose)`. |
| `EMAIL_CODE_INVALID` | 400 | El código no coincide. |
| `EMAIL_CODE_EXPIRED` | 410 | El código encontrado ya expiró. |
| `EMAIL_CODE_COOLDOWN` | 429 | Aún no ha pasado el cooldown para pedir otro código. `details.retryAfterSeconds`. |

### Dispositivos

| Código | Status | Uso |
|---|---|---|
| `DEVICE_NOT_FOUND` | 404 | Device inexistente o marcado como REMOVED. |
| `DEVICE_INVALID_SECRET` | 401 | `deviceSecret` no valida contra el hash almacenado. |
| `DEVICE_BLOCKED` | 403 | Device en status BLOCKED. |
| `DEVICE_NOT_ACTIVE` | 403 | Device no está ACTIVE (endpoint requiere autoridad `DEVICE_ACTIVE`). |
| `DEVICE_NOT_OWNED_BY_USER` | 403 | El device referenciado no pertenece al usuario autenticado. |
| `DEVICE_OFFLINE` | 503 | El device peer no está online (WS). `details.role` = "sender"/"receiver"/"owner". |
| `DEVICE_STATE_TRANSITION_INVALID` | 409 | Transición de status no permitida. `details.{action,status}`. |
| `DEVICE_PLAN_LIMIT_REACHED` | 409 | El plan no admite más devices ACTIVE. |
| `DEVICE_DEACTIVATE_LOCKED` | 409 | No se puede desactivar dentro de la ventana post-activación. `details.lockDays`. |
| `DEVICE_TOKEN_MISSING` | 401 | El JWT no lleva `deviceId`. |
| `DEVICE_TOKEN_UNKNOWN` | 401 | El `deviceId` del JWT no corresponde a ningún device. |

### Device RPC (llamadas server→device por STOMP)

| Código | Status | Uso |
|---|---|---|
| `DEVICE_RPC_TIMEOUT` | 504 | El device dueño no respondió dentro del timeout. |
| `DEVICE_RPC_DENIED` | 403 | El device dueño rechazó la operación. |
| `DEVICE_RPC_ERROR` | 502 | El device dueño respondió con error genérico. |
| `DEVICE_RPC_INTERRUPTED` | 500 | Espera interrumpida. |

### Folders

| Código | Status | Uso |
|---|---|---|
| `FOLDER_NOT_FOUND` | 404 | Folder inexistente. |
| `FOLDER_DISABLED` | 403 | Folder deshabilitada. |
| `FOLDER_NOT_OWNED_BY_USER` | 403 | La folder pertenece a otro usuario. |
| `FOLDER_NOT_OWNED_BY_DEVICE` | 403 | La folder no pertenece al device esperado. |
| `FOLDER_SHARING_INSUFFICIENT` | 403 | Nivel de sharing insuficiente. `details.{required,current}`. |
| `FOLDER_INVALID_SHARING` | 400 | Valor de sharing no reconocido. |
| `FOLDER_PATH_TRAVERSAL` | 403 | Path contiene `..`. |

### Transferencias

| Código | Status | Uso |
|---|---|---|
| `TRANSFER_NOT_FOUND` | 404 | Transferencia inexistente. |
| `TRANSFER_NOT_OWNED_BY_USER` | 403 | La transferencia pertenece a otro usuario. |
| `TRANSFER_STATE_CONFLICT` | 409 | La transferencia no está en un estado válido para la operación. `details.{action,status[,expected]}`. |
| `TRANSFER_PEERS_MUST_DIFFER` | 400 | `senderDeviceId == receiverDeviceId`. |
| `TRANSFER_OWNER_MUST_DIFFER_FROM_PEERS` | 400 | En `third-party-request`, el device caller (owner) coincide con sender o receiver. |
| `TRANSFER_KEYS_FORBIDDEN` | 403 | El caller no es ni sender ni receiver de la transferencia. |
| `TRANSFER_ONLY_PEER_MAY_DENY` | 403 | Solo el peer (no el owner) puede llamar `/deny`. |
| `TRANSFER_ONLY_SENDER_MAY_UPLOAD` | 403 | Solo el sender puede hacer `/upload`. |
| `TRANSFER_ONLY_RECEIVER_MAY_DOWNLOAD` | 403 | Solo el receiver puede hacer `/download`. |
| `TRANSFER_SENDER_NOT_OWNED_BY_USER` | 403 | El sender de la transferencia no pertenece al usuario autenticado. |
| `TRANSFER_RECEIVER_NOT_OWNED_BY_USER` | 403 | El receiver de la transferencia no pertenece al usuario autenticado. |
| `TRANSFER_UPLOAD_IN_PROGRESS` | 409 | Ya hay un uploader claimeado el pipe. |
| `TRANSFER_DOWNLOAD_IN_PROGRESS` | 409 | Ya hay un downloader claimeado el pipe. |
| `TRANSFER_DOWNLOADER_TIMEOUT` | 504 | El uploader se plantó en el pipe pero el downloader nunca llegó. |
| `TRANSFER_UPLOADER_TIMEOUT` | 504 | El downloader se plantó en el pipe pero el uploader nunca publicó metadata. |
| `TRANSFER_UPLOAD_FAILED` | 503 | El upload falló por excepción en el streaming. |
| `TRANSFER_INTERRUPTED` | 503 | Hilo del transfer interrumpido. |
| `TRANSFER_MISSING_PLAN` | 403 | El usuario no tiene BillingPlan asignado. |
| `TRANSFER_CONCURRENT_LIMIT` | 429 | Se alcanzó el límite de transferencias concurrentes del plan. `details.maxConcurrent`. |
| `TRANSFER_MONTHLY_QUOTA_EXCEEDED` | 507 | La transferencia excedería la cuota mensual del plan. `details.{usedBytes,fileBytes,quotaBytes}`. |
| `TRANSFER_INVALID_STATUS_FILTER` | 400 | Filtro de status desconocido. `details.value`. |
| `TRANSFER_INVALID_CURSOR` | 400 | Cursor de paginación mal formado. |
| `TRANSFER_SIZE_NEGATIVE` | 400 | `sizeBytes` es negativo. |
| `TRANSFER_RETRY_NOT_ALLOWED` | 409 | Solo se pueden reintentar FAILED/DENIED/CANCELLED. `details.status`. |
| `TRANSFER_RETRY_MISSING_PEERS` | 409 | La transferencia original no tiene sender o receiver. |
| `TRANSFER_RETRY_MISSING_FOLDER` | 409 | La transferencia original no tiene folder que revalidar. |
| `TRANSFER_RETRY_FOLDER_GONE` | 409 | La folder ya no existe. |
| `TRANSFER_RETRY_FOLDER_DISABLED` | 409 | La folder ya no está habilitada. |
| `TRANSFER_RETRY_SHARING_CHANGED` | 409 | El sharing bajó por debajo del requerido. `details.{required,current}`. |
| `TRANSFER_RETRY_FOLDER_REASSIGNED` | 409 | La folder ha cambiado de owner device. |
| `TRANSFER_RETRY_ONLY_ORIGINAL_OWNER` | 403 | Solo el owner original puede reintentar. `details.originalOwnerId`. |
| `TRANSFER_RETRY_DEVICE_NOT_ACTIVE` | 409 | Sender o receiver ya no está ACTIVE. `details.{role,status}`. |

### Denegaciones del sender durante el pipe (relayed al downloader)

Se emiten desde `/api/transfers/{id}/download` cuando el sender rechaza durante `awaitMetadata`.
`details.senderCode` conserva el enum original y `details.senderMessage` el texto opcional.

| Código | Status | Origen (`UploaderDenialException.Code`) |
|---|---|---|
| `TRANSFER_SENDER_DENIED_FILE_NOT_FOUND` | 410 | `FILE_NOT_FOUND` |
| `TRANSFER_SENDER_DENIED_FOLDER_NOT_SHARED` | 403 | `FOLDER_NOT_SHARED` |
| `TRANSFER_SENDER_DENIED_ACCESS_DENIED` | 403 | `ACCESS_DENIED` |
| `TRANSFER_SENDER_DENIED_FILE_TOO_LARGE` | 413 | `FILE_TOO_LARGE` |
| `TRANSFER_SENDER_DENIED_OTHER` | 424 | `OTHER` |

### Cómo añadir un error nuevo

1. Añadir la constante a [`ApiErrorCode`](src/main/java/baultServer/exceptions/ApiErrorCode.java) con su `HttpStatus` y mensaje fallback en inglés.
2. Lanzar `throw new ApiException(ApiErrorCode.XXX)` (o con `Map<String,Object>` de `details`) desde el punto donde se detecta.
3. Añadir la fila correspondiente a la tabla de arriba (misma categoría) en el mismo commit.
4. Nunca renombrar un `code` ya publicado — es contrato con el cliente.
