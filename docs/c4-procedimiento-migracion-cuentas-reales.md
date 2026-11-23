# Procedimiento Operativo: Migración Segura de Cuentas Reales a C4 (Legacy → C4)

**Estado del documento:** Preparación para Producción  
**Rama de referencia:** `feat/c4-migration-rehearsal`  
**Protocolo:** C4 (Causal Vector Clocks, Idempotencia CAS, Versionado Inmutable de Storage)  
**Proyecto de producción:** `contador-de-birras` (Cloud Firestore & Firebase Storage)  
**Versión mínima requerida de la aplicación:** `minSupportedAppVersionCode >= 3`

---

## 1. Introducción y Principios de Integridad

El presente documento define el procedimiento técnico, organizativo y de contingencia para la migración definitiva de las cuentas de usuario desde el protocolo de sincronización **Legacy** (revisión 0/0, sobreescritura LWW y URLs mutables de Storage) hacia el protocolo moderno **C4**.

### Invariantes Formales de Seguridad

1. **Cero Pérdida de Datos (Anti-Data-Loss):** Ninguna cerveza, fotografía, ubicación, comentario o timestamp puede ser descartado, truncado o eliminado durante el corte. Si una entidad local no tiene réplica remota, se preserva intacta y el sistema se detiene en estado seguro antes de realizar escrituras destructivas.
2. **Atomicidad Lógica por Cuenta:** La migración se efectúa cuenta a cuenta de forma aislada. La coexistencia controlada entre cuentas legacy no migradas y cuentas C4 migradas está garantizada por el perímetro de reglas de seguridad (`firestore.candidate.rules`).
3. **Punto de No Retorno Remoto:** Una vez que los documentos de una cuenta son ascendidos a revisión $\ge 1$ y se escribe el bloqueo de protocolo `/users/{uid}` (`syncProtocolVersion: 'C4'`), las reglas perimetrales prohíben taxativamente cualquier operación de clientes legacy (`isAccountLockedToC4`).
4. **Fail-Safe Defensivo:** Ante cualquier anomalía, pérdida de conectividad o excepción inesperada, el sistema detiene el motor local en estado **`HALTED`** conservando intactas las mutaciones en `local_mutation_history`. **Queda terminantemente prohibido el retroceso automático a Legacy.**
5. **Estrategia Fix-Forward:** La contingencia ante fallos se resuelve completando o recuperando la migración hacia C4 mediante `C4HaltedRecoveryManager` o herramientas de diagnóstico, nunca degradando las reglas de Firebase a un estado permisivo.

---

## 2. Fase 0: Copia de Seguridad Verificada (Backups & Snapshots)

Antes de autorizar cualquier migración en el entorno de producción, es requisito indispensable disponer de una copia de seguridad verificable e inmutable de las bases de datos y almacenes de archivos.

### 2.1 Exportación y Verificación de Integridad de Cloud Firestore

Se ejecutará una exportación completa de la colección `beers` y la colección de metadatos `users` hacia un bucket de Cloud Storage dedicado con políticas de retención activadas (Object Lock / Bucket Lock):

```bash
# 1. Definir variables de operación con timestamp ISO
EXPORT_TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_BUCKET="gs://contador-de-birras-backups/firestore-pre-c4-${EXPORT_TIMESTAMP}"

# 2. Ejecutar exportación administrada de Firestore mediante gcloud
OPERATION_ID=$(gcloud firestore export ${BACKUP_BUCKET} \
    --project=contador-de-birras \
    --collection-ids='beers','users' \
    --format="value(name)")

# 3. Monitorizar hasta conclusión y verificar estado
gcloud firestore operations describe ${OPERATION_ID} \
    --project=contador-de-birras \
    --format="yaml(done,error,metadata)"
```

**Verificación de Integridad y Recuperabilidad:**
- La operación debe reportar `done: true` y el campo `error` debe estar vacío/ausente.
- Comprobar que en el bucket existen el archivo descriptor de exportación y los directorios de colecciones:
  ```bash
  gsutil ls -l "${BACKUP_BUCKET}/*.overall_export_metadata"
  gsutil ls -l "${BACKUP_BUCKET}/all_namespaces/all_kinds/"
  ```
- Procedimiento de restauración verificado:
  ```bash
  # Restauración en caso de contingencia mayor (en base aislada o proyecto de recuperación):
  gcloud firestore import ${BACKUP_BUCKET} --project=contador-de-birras
  ```

### 2.2 Snapshot y Verificación de Firebase Storage

Las fotografías existentes bajo la ruta legacy `users/{uid}/beers/{syncId}.jpg` deben ser respaldadas para evitar pérdida o sobrescritura accidental:

```bash
# Sincronización recursiva del directorio de usuarios hacia el bucket de backup
STORAGE_BACKUP="gs://contador-de-birras-backups/storage-pre-c4-${EXPORT_TIMESTAMP}"

gsutil -m rsync -r \
    gs://contador-de-birras.appspot.com/users \
    ${STORAGE_BACKUP}/users
```

**Verificación de Integridad:**
- Comparar tamaño total en bytes y número de objetos entre origen y destino:
  ```bash
  gsutil du -s gs://contador-de-birras.appspot.com/users
  gsutil du -s ${STORAGE_BACKUP}/users
  ```
- Ejecutar un dry-run de comprobación de diferencias (debe reportar 0 discrepancias):
  ```bash
  gsutil -m rsync -r -n gs://contador-de-birras.appspot.com/users ${STORAGE_BACKUP}/users
  ```

### 2.3 Salvaguarda de Datos Locales no Sincronizados (Entornos Release)

> [!WARNING]
> **Limitación de Acceso en Compilaciones Release:**  
> En aplicaciones compiladas en modo Release (`android:debuggable="false"`), comandos como `run-as` son denegados por el kernel de Android (`run-as: package not debuggable`). Asimismo, `adb backup` está deshabilitado por el sistema en versiones modernas de Android (Android 12+) para salvaguardar la privacidad del usuario. **No deben presentarse `run-as` ni `adb backup` como mecanismos viables de respaldo en producción.**

**Alternativa Segura para Datos Locales antes de la Migración:**
1. **Sincronización Legacy Previa Completa:** Antes de habilitar C4 para una cuenta, el usuario debe abrir su aplicación en modo Legacy con conectividad estable hasta que el indicador de sincronización confirme 0 cambios pendientes (`syncStatus == SYNCED` en todas las entidades). De esta manera, todos los datos locales quedan debidamente respaldados en Firestore antes de iniciar la migración.
2. **Prevalidación Bloqueante en el Cliente:** `C4CutoverManager` implementa una verificación previa obligatoria: si en Room existen entidades locales no reflejadas en el servidor o mutaciones pendientes no conciliadas, el proceso de cutover se aborta de forma no destructiva con resultado `CutoverResult.Blocked`, impidiendo cualquier pérdida de datos o elevación de estado incompleta.
3. **Auditoría Forense en Entornos de Diagnóstico:** Si excepcionalmente se requiere volcar la base Room de un dispositivo piloto para análisis técnico, debe realizarse utilizando una versión interna debug o una utilidad de exportación integrada en la aplicación hacia almacenamiento externo (SAF).

---

## 3. Fase 1: Inventario Previo, Catálogo y Reconciliación

Antes de iniciar la migración de un usuario o de un lote de usuarios, se debe generar un inventario exhaustivo del estado actual de sus datos para su posterior conciliación.

### 3.1 Extracción del Catálogo Previo por Cuenta

Para cada cuenta `uid` seleccionada:

1. **Documentos Remotos en Firestore (`/beers`):**
   - Recuento total de documentos donde `userId == uid`.
   - Lista exhaustiva de identificadores `syncId`.
   - Recuento de documentos con `remotePhotoUrl != null`.
   - Verificación de que todos los documentos se encuentran en revisión 0 o sin campos C4 (invariante legacy).
2. **Archivos en Storage (`users/{uid}/beers/`):**
   - Listado de archivos `.jpg` existentes en Storage.
   - Comprobar que cada `remotePhotoUrl` presente en Firestore tiene su correspondiente objeto en Storage.
3. **Estado Local en Room (en el dispositivo cliente):**
   - Recuento de cervezas activas (`syncStatus IN ('SYNCED', 'PENDING')`).
   - Recuento de cervezas borradas (`syncStatus == 'DELETED'`).
   - Cervezas locales con fotografías locales en `photoUri`.
   - Detección previa de cervezas huérfanas locales: si existen cervezas locales en `SYNCED` que no figuran en Firestore, la prevalidación de `C4StagingCutoverManager` bloqueará el proceso para evitar pérdidas de datos.

### 3.2 Script de Dry-Run de Conciliación

Un script administrativo ejecutará una simulación sin escrituras para verificar:
- Ausencia de documentos con `userId` discrepante.
- Ausencia de documentos corruptos (`revision > 0` con `fieldRevisions` vacío).
- Ausencia de tombstones remotos huérfanos sin metadatos.

Si el dry-run detecta cualquier anomalía, la cuenta se excluye del lote de migración y se asigna a revisión manual.

---

## 4. Fase 2: Estrategia de Ejecución Gradual (Canary & Phased Rollout)

Para minimizar el radio de impacto, la migración productiva se organizará en cohortes progresivas con periodos de observación intercalados.

```
+-------------------------------------------------------------------------------+
|                        DESPLIEGUE POR COHORTES C4                             |
+-------------------------------------------------------------------------------+
| Cohorte 0: Cuentas Internas / QA Real (5-10 cuentas)           -> Obs: 24h    |
| Cohorte 1: Canary Externo (1% de usuarios activos)             -> Obs: 48h    |
| Cohorte 2: Fase de Expansión (10% de usuarios activos)         -> Obs: 48h    |
| Cohorte 3: Fase Mayoritaria (50% de usuarios activos)          -> Obs: 72h    |
| Cohorte 4: Migración General (100% de la base instalada)                      |
+-------------------------------------------------------------------------------+
```

### 4.1 Requisitos Previos por Dispositivo Cliente

1. **Actualización de la Aplicación:** El usuario debe disponer de una versión de la app con `versionCode >= 3` (el código actual en `app/build.gradle.kts` tiene `versionCode = 2`, por lo que el primer despliegue con C4 requerirá incrementar a 3). Las versiones obsoletas no disponen del motor C4 ni de la base de datos Room v10 compatible.
2. **Reglas de Seguridad Candidatas Desplegadas:** El archivo `firestore.candidate.rules` y `storage.rules` debe estar activo en producción.
3. **Ejecución del Cutover:**
   - Se activa mediante el orquestador `C4CutoverManager` en el arranque o inicio de sesión.
   - Sigue la secuencia atómica de 7 pasos: Prevalidación -> Ascenso CAS remoto a rev 1 -> Alineación Room -> PULL colección -> PUSH de pendientes y fotos -> Verificación de postcondiciones -> Bloqueo remoto `/users/{uid}`.

### 4.2 Formato del Bloqueo Remoto de Protocolo

La confirmación final en `/users/{uid}` se realiza mediante `writeAccountProtocolLock(uid)`, que efectúa una escritura atómica e idempotente con fusión de campos (`set(..., SetOptions.merge())`) sin sobreescribir otros datos del perfil de usuario:
```json
{
  "syncProtocolVersion": "C4",
  "minSupportedAppVersionCode": 3,
  "c4MigratedAt": 1700000000000,
  "accountSyncState": "MIGRATED_TO_C4"
}
```
Una vez presente este documento, las reglas de Firestore deniegan cualquier creación, actualización o borrado desde clientes que no utilicen el protocolo C4.

---

## 5. Fase 3: Monitorización en Tiempo Real y Métricas de Éxito

Durante la ejecución de cada cohorte se monitorizarán las siguientes métricas en los paneles de Google Cloud y Firebase:

### 5.1 Métricas Clave de Desempeño (SLIs)

| Métrica | Definición | Umbral Aceptable | Umbral Crítico (Parada) |
| :--- | :--- | :--- | :--- |
| **Cutover Success Rate** | % de ejecuciones con resultado `Completed` | $\ge 99.0\%$ | $< 98.0\%$ |
| **Incomplete Rate (Offline/Red)** | % de ejecuciones detenidas por conectividad | $\le 1.0\%$ | $> 2.0\%$ |
| **Blocked Rate (Anomalías/Prevalidación)** | % de cuentas bloqueadas por seguridad | $0.0\%$ | $> 0.1\%$ |
| **Rule Permission Denied** | Intentos de escritura legacy rechazados por reglas | Monitorizar | Incremento anómalo no previsto |
| **Storage Upload Errors** | Fallos en subida de fotografías versionadas | $\le 0.1\%$ | $> 0.5\%$ |

### 5.2 Criterios de Aceptación Post-Migración por Cuenta

Una cuenta se considera migrada con éxito únicamente si cumple:
- **Total de entidades:** `count(Room activas) == count(Firestore isDeleted == false)`.
- **Tombstones:** `count(Room DELETED) == count(Firestore isDeleted == true)`.
- **Revisiones:** Todos los documentos en Firestore y Room tienen `revision >= 1` y metadatos causales íntegros (5 campos válidos).
- **Fotografías:** Todas las fotos legacy conservadas; todas las fotos nuevas alojadas bajo `users/{uid}/beers/{syncId}_{mutationId}.jpg`.
- **Mutaciones pendientes:** `count(local_mutation_history PENDING) == 0`.
- **Duplicados:** 0 identificadores `syncId` repetidos.
- **Estado del motor:** `C4SyncConfig.engineMode == C4_ACTIVE`, checkpoint `COMPLETED`.

---

## 6. Fase 4: Estrategia de Contingencia y Criterios de Parada

### 6.1 Freno de Emergencia (Emergency Stop)

Se ordenará la suspensión inmediata del avance de cohortes si se cumple cualquiera de las siguientes condiciones:
1. Cualquier reporte de pérdida o corrupción de datos en un cliente migrado.
2. Más de 2 cuentas bloqueadas consecutivas en `CutoverResult.Blocked`.
3. Tasa de errores en Firebase Storage $> 0.5\%$.
4. Bloqueo masivo de WorkManager por inconsistencia de sesión.

### 6.2 Prohibición de Rollback a Reglas Permisivas y Bloqueo Irreversible

> [!CAUTION]
> **REGLAS DE ORO DE SEGURIDAD E INTEGRIDAD:**  
> 1. **Prohibición de alterar o eliminar el bloqueo remoto:** Bajo ninguna circunstancia se debe eliminar o modificar el documento `/users/{uid}` (o su campo `syncProtocolVersion: 'C4'`) para intentar "revertir" una cuenta a Legacy. Las reglas perimetrales prohíben taxativamente dicha reversión y el borrado del documento de usuario migrado.
> 2. **Conservación permanente de revisiones y tombstones:** Una cuenta que ha migrado a C4 almacena sus entidades con revisiones $\ge 1$ y tombstones lógicos (`isDeleted: true`). Cualquier intento de ejecutar un cliente Legacy sobre estos documentos provocaría rechazos por reglas de seguridad y desincronizaciones causales irreversibles.
> 3. **Prohibición de debilitar reglas Firebase:** No se deben relajar ni debilitar las reglas perimetrales (`firestore.candidate.rules`) para admitir retrocesos.
> 4. **Estrategia única ante incidentes:** La única vía de resolución admitida es la detención en estado seguro **HALTED**, diagnóstico de la causa raíz y corrección hacia adelante (**Fix-Forward**).

### 6.3 Procedimiento de Contingencia Fix-Forward

Ante cualquier anomalía en una cuenta en proceso de migración o ya migrada:

1. **Aislamiento Seguro en HALTED:** El motor local entra automáticamente en estado seguro `HALTED` ante discrepancias o bloqueos, manteniendo intactas todas las mutaciones locales en `local_mutation_history` y bloqueando escrituras remotas inconsistentes.
2. **Diagnóstico Automatizado:** Ejecutar `C4HaltedRecoveryManager.diagnoseAccount(uid)` para identificar la causa:
   - *Fallo de red o desconexión durante PUSH:* Reintentar la sincronización cuando se restablezca la conectividad; las mutaciones C4 se reaplican de forma idempotente con protección CAS.
   - *Fallo en escritura del Protocol Lock (`HALTED_RECOVERABLE_LOCK_FAILED`):* Reintentar la llamada atómica `writeAccountProtocolLock(uid)` directamente desde el gestor de recuperación.
   - *Discrepancia en entidades:* Realizar diagnóstico mediante conciliación remota sin alterar el bloqueo de protocolo ni degradar los metadatos causales existentes.
3. **Reactivación Explícita:** Una vez subsanada la causa raíz y con el diagnóstico en verde (`isRecoverable == true`), invocar `recoverHaltedAccountExplicitly(uid)`. En ningún caso se permite la degradación a Legacy.

---

## 7. Fase 5: Desacoplamiento de Piezas Piloto para Producción

Para compilar y distribuir la versión final de producción de la aplicación, es imperativo desacoplar los componentes creados exclusivamente para el piloto de pruebas.

### Lista de Desacoplamiento Obligatorio

| Componente / Archivo | Configuración en Piloto (DEBUG/Staging) | Configuración Requerida para Producción (RELEASE) |
| :--- | :--- | :--- |
| **`C4SyncConstants.PILOT_USER_EMAIL`** | `"c4_pilot_user@demo-contador-c44.test"` (fijo) | **Eliminar dependencia:** Utilizar exclusivamente `FirebaseAuth.getInstance().currentUser?.email`. |
| **`C4SyncConstants.PILOT_FIREBASE_PROJECT_ID`** | `"demo-contador-c44"` (ficticio) | **Eliminar dependencia:** Utilizar el proyecto real de producción `FirebaseApp.getInstance().options.projectId`. |
| **Endpoints de Emuladores** | `10.0.2.2:8888` (Firestore), `10.0.2.2:9199` (Storage), `10.0.2.2:9099` (Auth) | **Desconectar:** No invocar `useEmulator()`. Conectar a los servicios gestionados de Google Cloud. |
| **`StagingC4SyncProvider`** | Instanciación manual con puertos de emulador y sesión forzada | **Sustituir por `ProductionC4SyncProvider`:** Proveedor de producción integrado en la arquitectura existente (`ContadorDeBirrasApplication`, sin dependencias inexistentes de Hilt) con las instancias estándar de Firebase del contenedor oficial. |
| **`applicationIdSuffix`** | `".c4pilot"` en `build.gradle.kts` | **Retirar:** Compilar bajo el `applicationId` oficial de la aplicación (`com.daniel.contadordebirras`). |
| **`versionCode` / `minSupportedAppVersionCode`** | `versionCode = 2` actual en `app/build.gradle.kts` | **Incrementar `versionCode = 3`:** Para el lanzamiento de producción con C4, la versión de la app debe subir de 2 a 3 para cumplir el requisito de seguridad perimetral `minSupportedAppVersionCode >= 3`. |
| **Panel de Depuración (`C4SyncDebugScreen`)** | Accesible desde la interfaz o menú de desarrollo | **Ocultar / Deshabilitar:** Excluir de los builds de tipo `release` mediante sourceSets o directivas `@VisibleForTesting`. |
| **Firma Release y Keystore** | Clave debug local en emulador (`CN=Android Debug`) | **Exigencia Estricta de Keystore de Producción:** Configurar `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` y `RELEASE_KEY_PASSWORD`. Sin fallback automático a debug. La firma de desarrollo observada en el emulador no es apta para distribución pública. |
| **Trazas y Logs Verbosos** | `Log.d("C4Cutover", ...)` imprimiendo payloads y estados intermedios | Desactivar logs detallados en Release para prevenir fugas de información sensible en Logcat. |

---

## 8. Checklist de Validación Final Previa a la Migración

Antes de autorizar la migración de la primera cuenta real:

- [ ] Exportación completa de Firestore ejecutada y comprobada en Cloud Storage.
- [ ] Snapshot recursivo de Firebase Storage ejecutado y verificado.
- [ ] `firestore.candidate.rules` desplegadas y validadas con la suite de 108 pruebas de seguridad.
- [ ] `storage.rules` desplegadas con validación de fotos versionadas e inmutabilidad.
- [ ] Suite de pruebas unitarias Robolectric (`C4MigrationRehearsalTest` y `C4StagingCutoverManagerTest`) superadas al 100%.
- [ ] Piezas del piloto desacopladas y aplicación release compilada con `versionCode = 3` (subido desde 2) y `applicationId = com.daniel.contadordebirras`.
- [ ] Cohorte 0 identificada y notificada.
- [ ] Protocolo de contingencia y equipo de guardia asignado durante las ventanas de ejecución.
