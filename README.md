# D1 NFC demo — Android (motor HCE invisible + POS)

Dos apps para que el demo D1 Showcase pague **sin contacto de verdad**:

| App | Qué es | Dónde corre |
|---|---|---|
| **D1 Wallet** (`wallet/`, `io.argenticpay.d1wallet`) | **Motor NFC sin pantalla ni ícono.** El demo web corre en Chrome (o como PWA) igual que siempre; cuando corre en un Android con este APK instalado, la activación y los pagos usan el HCE real. El demo le habla por `intent://` (`d1wallet://nfc/<acción>`), una actividad transparente hace la acción (biometría real, `provision`, `replenish`, wallet predeterminada…) y publica la respuesta en la sesión Live (`POST /nfc/events`, type `wallet`), que el demo lee con `GET /nfc/events`. El `HostApduService` responde los APDU del POS con un criptograma calculado con una LUK de un solo uso. | Cualquier Android con NFC (8.0+) y Chrome |
| **D1 POS** (`pos/`, `io.argenticpay.d1pos`) | El lector: importe → "Acerque tarjeta o teléfono" → SELECT PPSE / SELECT AID / GPO (+ READ RECORD / GENERATE AC) → autoriza contra la Lambda `d1-nfc` → APROBADO / RECHAZADA → ticket por impresora Bluetooth ESC/POS (58 mm). También lee tarjetas reales (el backend las rechaza: "token desconocido"). **SDK2**: además es periférico Bluetooth LE (`BleTap`): el demo web `index2.html` se conecta por Web Bluetooth sin app, recibe la venta, manda el criptograma y recibe el resultado. | Celular Android con NFC **o** terminal Android (Q2s, iMin, Netum…) |

El backend está en `d1-nfc-backend-v2.zip` (Lambda + DynamoDB; `./deploy.sh --rest-api hnkl4mjikh` lo publica en la REST API del bot). Las dos apps traen esa URL de API (`…/prod`) por defecto; la del wallet la actualiza el demo en cada intent (Demo ⚙ → «API NFC (Live)»).

## 1. Compilar sin Android Studio (GitHub Actions)

1. Creá un repo vacío en GitHub (privado está bien) y subí esta carpeta tal cual (o pedile a Claude que la pushee).
2. En cada push a `main`, el workflow `.github/workflows/build.yml` compila los dos APK (debug, firmados con la clave debug) y los publica:
   - como artefacto `apks` del run, y
   - en el release **`latest`** del repo → `d1-wallet.apk` y `d1-pos.apk` descargables directo desde el teléfono.
3. En el teléfono: abrí la URL del release, bajá el APK, permití "instalar apps desconocidas" para el navegador e instalá.

> Si preferís Android Studio: *Open* la carpeta, esperá el sync, `Run` cada módulo.

> Primer build: si el workflow falla, mandá el log del paso «Build debug APKs» (Actions → el run → el paso en rojo) y se corrige
> en el momento; el código se revisó a mano pero no se compiló en CI todavía.

## 2. Instalación y configuración

**D1 Wallet (motor NFC, invisible)**
- Instalá `d1-wallet.apk` (Chrome → link del release → permitir instalar de esta fuente). **No aparece ningún ícono**: no hay nada que abrir.
- Abrí el demo en Chrome (`https://sdk.argenticpay.io/index.html` o `index1.html`; mejor agregado a la pantalla de inicio como PWA). Al tocar **Pago sin contacto → Activar** por primera vez, el demo dispara `intent://nfc/setup`: si el motor está instalado responde y el demo queda en modo real (Demo ⚙ → «Motor NFC (Android)» = vinculado); si no está, Chrome vuelve al demo y sigue la simulación de siempre. También se puede vincular/olvidar a mano desde Demo ⚙.
- Durante la activación el teléfono muestra el BiometricPrompt real y vuelve solo a Chrome; el token y las 5 LUK quedan guardados en el teléfono. Después tocá **Wallet predeterminada** (diálogo del sistema) o Ajustes → Conexiones → NFC → Pagos sin contacto → **D1 Wallet**.
- Permisos: notificaciones (se piden en la primera vinculación; sirven para el "vea su teléfono" del modo 2 toques).
- Sesión Live: el demo crea un código de 4 dígitos al vincular (Demo ⚙ → «Sesión Live (código)»); el mismo código va en el proyector (LIVE lo reutiliza si está cargado) y en D1 POS.

**D1 POS (lector)**
- Instalá en el segundo equipo (celular Android con NFC o terminal). ⚙ → API, moneda, terminal ID, sesión Live, impresora Bluetooth (emparejala antes desde Ajustes del Android; botón "Imprimir ticket de prueba").
- Elegí comercio, tipeá el importe, **COBRAR**, acercá el teléfono.

## 3. Cómo es un pago (lo que ve cada parte)

```
POS                                   Teléfono (D1 Wallet · HCE)                          Lambda d1-nfc
SELECT PPSE ───────────────────────▶  FCI con AID Visa/MC
SELECT AID ────────────────────────▶  FCI + PDOL (TTQ, monto, moneda, UN…)
GPO (monto, UN) ───────────────────▶  ¿CDCVM válido?  no → 9F6C 01 00 "see phone" + BiometricPrompt
   "VEA SU TELÉFONO"                  usuario verifica → 60 s de credencial
GPO (2.º toque) ───────────────────▶  toma LUK[atc], ARQC = HMAC(LUK, tokenRef|ATC|monto|moneda|UN)
                                       77 { 57 Track2(DPAN), 9F26 ARQC, 9F36 ATC, 9F6C 01 80 … }
POST /nfc/authorize {dpan, atc, arqc, monto, UN} ─────────────────────────────────────────▶ busca el token por DPAN,
                                                                                             recalcula el HMAC con esa LUK,
                                                                                             la quema (single use)
◀──────────────────────────────────── approved / authCode / luksLeft ─────────────────────── 00 / 88 / 89 / 104
APROBADO + ticket                     push "Compra aprobada" en el demo · replenish si quedan < 2
```

**1 toque (por defecto)**: con el teléfono desbloqueado, el desbloqueo cuenta como CDCVM (igual que el *device unlock CDCVM* del SDK) y el primer GPO ya sale con criptograma. **2 toques** (app Demo → «Pago NFC en 2 toques»): el POS dice «vea su teléfono», el motor abre el BiometricPrompt sobre la pantalla (o una notificación para tocar, si Android no deja abrirlo desde el servicio) y el 2.º toque paga.

## 4. Puente web ↔ motor (intents + sesión Live)

El demo web emula `window.D1Native` con la misma API de antes, pero cada llamada es un intent de Chrome y cada respuesta llega por la sesión Live:

| Llamada del demo | Intent | Respuesta (evento `wallet` en `GET /nfc/events`) |
|---|---|---|
| vincular (Activar la primera vez / Demo ⚙) | `d1wallet://nfc/setup?api&session&tap` (+ `S.browser_fallback_url` = el demo con `#d1w=0`) | `{type:"state", state}` · sin app: Chrome vuelve al demo con `#d1w=0` |
| `provision(scheme, last4, exp, holder)` | `…/provision?scheme&last4&exp&holder&auth=1&title` | `{type:"auth", ok}` + `{type:"provision", ok, last4, dpan, luks, state}` |
| `replenish()` | `…/replenish` | `{type:"replenished", luks, state}` |
| `deleteToken()` | `…/delete&auth=1` | `{type:"deleted", state}` |
| `preauth(seconds)` | `…/preauth?seconds` | BiometricPrompt → `{type:"auth"}` + `{type:"ready", until}` |
| `setTapMode(1|2)` | `…/tapMode?tap=` | `{type:"tapMode", value}` |
| `setDefaultPaymentApp()` | `…/default` | diálogo del sistema → `{type:"defaultWallet", value}` |
| `authenticate(title)` | — (la biometría va en el intent siguiente: `auth=1`) | `{type:"auth", ok:true, deferred:true}` |

Todos los intents llevan `api`, `session` y `tap`; la actividad es transparente, no queda en Recientes y hace `finish()` al terminar, así que el usuario vuelve a Chrome. Chrome exige un gesto del usuario para abrir un `intent://`: por eso la biometría y la acción van en un solo viaje.

Eventos espontáneos del HCE (mismos nombres que antes, ahora por la sesión): `transactionStarted`, `apdu {msg, apdu}`, `authRequired {amount, currency}`, `transactionCompleted {amount, currency, atc, arqc, luksLeft, last4, scheme}`, `noCard`, `noKeys`, `replenished`, `replenishFailed`, `deactivated`; además un evento `tap` (type propio) que el POS de pantalla del proyector espeja.

## 4b. SDK2 — tap por Bluetooth LE (sin app en el teléfono)

El POS anuncia el servicio GATT `a1d1b1e0-0001-4d31-b1e0-d1b1e0000001` con tres características:

| Característica | UUID (…-0001 → …-000N) | Sentido | Contenido |
|---|---|---|---|
| `evt` | `a1d1b1e0-0002-…` | POS → teléfono (notify) | tipo corto: `sale` · `tap` · `result` · `reset` |
| `data` | `a1d1b1e0-0003-…` | POS → teléfono (read) | JSON completo del último evento (`{t, amount, currency, un, merchant, mcc, terminal}` / `{t:"result", approved, authCode, de39, reason, rrn, ticket, luksLeft}`) |
| `cmd` | `a1d1b1e0-0004-…` | teléfono → POS (write) | `{t:"crypto", dpan, atc, arqc, aid, last4, scheme, tokenRef, un, amount, currency}` · `{t:"cancel"}` |

La notificación es corta y el teléfono lee `data` después: así no importa el MTU. Las escrituras largas llegan como *prepared writes* y el servidor las junta. Requiere que el equipo pueda anunciar (`BluetoothAdapter.bluetoothLeAdvertiser != null`): la mayoría de los Android lo soporta; si no, el POS lo dice en ⚙ y hay que usar otro Android como lector.

Secuencia: **COBRAR** → `sale` por BLE (+ NFC activo) → si el teléfono entra al campo NFC, `tap` por BLE; si no, el usuario confirma con la huella en la hoja de pago → el web calcula el ARQC con una LUK (`HMAC-SHA256(LUK, tokenRef|ATC|monto|moneda|UN)`) y escribe `crypto` → el POS autoriza en `/nfc/authorize` como con NFC → `result` por BLE → APROBADO + ticket ("VENTA · TAP BLUETOOTH").

## 5. Notas

- Perfil contactless "demo" estilo qVSDC: todo en el GPO (`57`, `9F26`, `9F36`, `9F10`, `9F27`, `9F37`, `9F6C`); `READ RECORD` y `GENERATE AC` se responden por compatibilidad. No es un kernel certificado: un POS comercial real lo leerá pero rechazará (no hay emisor detrás).
- El criptograma es HMAC-SHA256 con la LUK (en EMV real es 3DES/AES con claves de sesión del TSP); la semántica (clave por transacción, ATC, UN, replenishment) es la misma que explica el demo.
- Las LUK se guardan en SharedPreferences (demo). En el NFC Wallet SDK real viven cifradas y atadas al dispositivo (WSE).
- Sin actividad LAUNCHER no hay ícono (Android 10+ solo sintetiza uno cuando el manifest declara un launcher deshabilitado). La app se ve en Ajustes → Aplicaciones y en la lista de apps de pago NFC.
- Impresora: cualquier térmica Bluetooth SPP 58 mm (las de ML sirven). Para la impresora interna de un iMin/Sunmi se agrega su SDK más adelante.
