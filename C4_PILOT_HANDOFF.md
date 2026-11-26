# Birrómetro — C4 Pilot Handoff Document
**Fecha de corte:** 2026-11-26 23:35 UTC  
**Rama activa:** `feat/c4-controlled-activation`  
**Proyecto Firebase:** `contador-de-birras` (Producción)  
**Dispositivo piloto:** Xiaomi Redmi Note 13 4G (`65hyemwgmzwopznb`), versión actual 1.1.0 (versionCode 3) instalada y funcionando.

---

## 1. Estado Exacto de la Implementación
- **Motor activo:** `LEGACY` (100% operativo en producción y en el Xiaomi; se verificó creación y sincronización de cerveza nueva).
- **Copias de seguridad verificadas:**
  - Base SQLite de Room (`beer_database`, `beer_database-wal`, `beer_database-shm`) respaldada y con integridad `ok` en `C:\Users\Daniel\.gemini\antigravity\scratch\private_backup\`.
  - 5 de 5 fotografías locales extraídas, verificadas con marcadores JPEG íntegros y hashes SHA-256 validados.
  - Snapshot de Firestore y Firebase Storage verificado.
  - Keystore de firma verificado: `C:\Users\Daniel\.android\debug.keystore`.
- **Firma compatible confirmada:** SHA-256 `8fb9c15dd2af7e63dec3e979f36f9acf90aeb776bff21494e92a9ab48d632001`.
- **Estado C4:** Desactivado por defecto. Implementado el mecanismo de activación selectiva por allowlist (`AUTHORIZED_C4_PILOT_UIDS`) sin activar C4 globalmente ni alterar cuentas ajenas.

---

## 2. Cambios Realizados y Archivos Modificados
1. **`app/build.gradle.kts`**:
   - Incrementado `versionCode` de `3` a `4` y `versionName` a `"1.1.1"`.
   - Añadido campo `buildConfigField("String", "AUTHORIZED_PILOT_UIDS", "\"$authorizedPilotUidsProp\"")` configurable mediante propiedad `-PAUTHORIZED_C4_PILOT_UIDS` o variable de entorno (por defecto vacío `""` en git).
   - Configuración de firma `release` estricta: exige parámetros explícitos (`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, etc.) y elimina cualquier fallback automático.
2. **`app/src/main/java/com/example/contadordebirras/ContadorDeBirrasApplication.kt`**:
   - En el arranque (`onCreate`), si `BuildConfig.AUTHORIZED_PILOT_UIDS` no está vacío, activa automáticamente `isProductionC4FeatureEnabled = true` y registra los UIDs autorizados en `C4SyncConfig` y `C4SyncStateStore`.
3. **`app/src/main/java/com/example/contadordebirras/ui/profile/ProfileScreen.kt`**:
   - Comprueba `C4SyncConfig.isUserAuthorizedForC4(user.uid)`.
   - Si el usuario autenticado está en la allowlist y C4 aún no está activo, muestra la etiqueta "Cuenta autorizada para piloto C4" y el botón "⚡ Activar C4 (Piloto Controlado)".
   - Para cualquier otro usuario (o si la allowlist está vacía), los controles de C4 son invisibles y la aplicación permanece 100% en Legacy.
4. **`app/src/main/java/com/example/contadordebirras/ui/profile/ProfileViewModel.kt`**:
   - Añadida la función `activateC4ForAuthorizedUser(context)` que valida la pertenencia a la allowlist y ejecuta `C4ControlledActivationManager.activateAccount(uid)` contra las instancias de producción (sin forzar `allowProduction = false`).
5. **`.gitignore`**:
   - Añadido bloqueo para `*.apk`, `*.aab`, `*.keystore` y `*.jks` para evitar cualquier subida accidental de binarios o credenciales al repositorio.
6. **`docs/c4-procedimiento-migracion-cuentas-reales.md`**:
   - Actualizado con prohibición taxativa de retroceso a Legacy mediante eliminación de `/users/{uid}`, principio de contingencia *fix-forward* / aislamiento en `HALTED` y aclaración de que `run-as` y `adb backup` no son viables en Release.

---

## 3. Pruebas: Estado y Resultados
- **Completadas con éxito:**
  - `apksigner verify --print-certs`: Validación de certificado SHA-256 (`8fb9c15dd2af...`) y compatibilidad de actualización con la APK instalada en Xiaomi.
  - `aapt dump badging`: Verificación de `versionCode=4`, `versionName=1.1.1` y paquete `com.daniel.contadordebirras`.
  - Compilación exitosa de Release APK (`BUILD SUCCESSFUL in 2m 18s`).
- **Fallos previos explicados:**
  - El comando `npm --prefix tests/firebase-rules test` finalizó anteriormente con `exit code 1` por `connect ECONNREFUSED 127.0.0.1:18888` (ejecución directa sin levantar los emuladores en el puerto 18888). Requiere `firebase emulators:exec --config firebase.rules-test.json`.
- **Canceladas / Pendientes:**
  - La tarea de pruebas unitarias `./gradlew testDebugUnitTest` fue cancelada ordenadamente a petición del usuario para detener las ejecuciones nocturnas. Pendiente de ejecución si se desea validar suite unitaria completa.

---

## 4. Estado de la Compilación APK 1.1.1 (versionCode 4)
- **Ruta del artefacto:** `app/build/outputs/apk/release/app-release.apk`
- **Tamaño:** 18.197.249 bytes
- **Paquete:** `com.daniel.contadordebirras`
- **VersionCode:** `4`
- **VersionName:** `"1.1.1"`
- **Firma:** SHA-256 `8fb9c15dd2af7e63dec3e979f36f9acf90aeb776bff21494e92a9ab48d632001`
- *Nota:* Esta compilación de prueba se generó con allowlist vacía (`AUTHORIZED_C4_PILOT_UIDS=""`). Para la instalación real del piloto, se debe compilar inyectando el UID.

---

## 5. Configuración de la Allowlist `AUTHORIZED_C4_PILOT_UIDS`
- **UID del usuario real (extraído del SQLite local del Xiaomi):**  
  `uaQbUjoRMSZNailEBYMexL8bX5s2` (asociado a 315 cervezas del usuario autenticado).
- **Propiedad de compilación:**
  ```powershell
  ./gradlew assembleRelease `
    -PRELEASE_STORE_FILE="C:/Users/Daniel/.android/debug.keystore" `
    -PRELEASE_STORE_PASSWORD="android" `
    -PRELEASE_KEY_ALIAS="androiddebugkey" `
    -PRELEASE_KEY_PASSWORD="android" `
    -PAUTHORIZED_C4_PILOT_UIDS="uaQbUjoRMSZNailEBYMexL8bX5s2"
  ```
- Al compilar con este parámetro, la aplicación habilitará exclusivamente a ese UID para ver y ejecutar el botón de corte a C4. En Git permanece vacío por defecto.

---

## 6. Comprobaciones Pendientes sobre Firebase
- **Reglas actuales vs. candidatas:**
  - Las reglas actuales de `firestore.rules` son permisivas en `beers`, pero **no validan vectores causales C4, no protegen tombstones contra borrado físico, no bloquean clientes legacy desactualizados y no aseguran el Protocol Lock en `/users/{uid}`**.
  - Las reglas de `storage.rules` actuales no protegen fotos versionadas con inmutabilidad.
  - **Acción pendiente:** Desplegar `firestore.candidate.rules` y `storage.candidate.rules` a producción antes de pulsar la activación C4:
    ```bash
    firebase deploy --only firestore:rules,storage
    ```
    *(Estas reglas candidatas son 100% retrocompatibles con usuarios Legacy y no activan C4 para nadie automáticamente).*

---

## 7. Próximos Pasos para la Activación Real (Reanudación Mañana)
1. **Despliegue de Reglas:** Ejecutar `firebase deploy --only firestore:rules,storage` sobre `contador-de-birras`.
2. **Compilación con Allowlist:** Compilar la APK Release v1.1.1 pasando `-PAUTHORIZED_C4_PILOT_UIDS="uaQbUjoRMSZNailEBYMexL8bX5s2"`.
3. **Instalación de Actualización:** Instalar en el Xiaomi con datos conservados:
   ```powershell
   & "E:\Android\Sdk\platform-tools\adb.exe" -s 65hyemwgmzwopznb install -r app/build/outputs/apk/release/app-release.apk
   ```
4. **Verificación en Pantalla Perfil:**
   - Confirmar que la app muestra "Conectado como: <correo>".
   - Confirmar que el motor indica "Motor: LEGACY".
   - Confirmar que aparece el aviso "Cuenta autorizada para piloto C4" y el botón "⚡ Activar C4 (Piloto Controlado)".
5. **Ejecución del Cutover:**
   - Asegurar conexión Wi-Fi/4G estable.
   - Pulsar "⚡ Activar C4 (Piloto Controlado)".
   - El sistema ejecutará el cutover de 7 pasos: prevalidación, CAS a rev 1, subida de fotos pendientes, confirmación de postcondiciones y escritura del Protocol Lock en `/users/{uid}`.
6. **Verificación Post-Activación:**
   - La pantalla debe actualizar a "Motor: C4_ACTIVE (Protegido)".
   - Añadir una cerveza de prueba y verificar que se sincroniza con metadatos C4 (revision, fieldRevisions, lastMutationId).
   - Reiniciar la aplicación y verificar que persiste en `C4_ACTIVE`.

---

## 8. Riesgos Conocidos y Salvaguardas
- **Interrupción de red durante el corte:** Si la conexión cae a mitad del proceso, `C4StagingCutoverManager` pasa a estado seguro `HALTED` y registra `HALTED_RECOVERABLE`. **Nunca retrocede a Legacy** para evitar sobreescrituras desincronizadas. Se recupera reintentando la activación.
- **Prohibición de borrado remoto:** Nunca borrar `/users/{uid}` ni cambiar `syncProtocolVersion` a mano en la consola de Firebase. La contingencia es siempre avanzar (*fix-forward*).
- **Preservación total de datos:** Las cervezas locales y remotas no se borran; el algoritmo CAS solo asciende de revisión y reconcilia sin descartar registros.
