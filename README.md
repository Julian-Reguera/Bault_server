<!-- README pendiente. Por ahora solo contiene el contrato de códigos de error. -->

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
