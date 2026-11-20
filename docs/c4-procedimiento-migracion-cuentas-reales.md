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

### 2.1 Exportación de Cloud Firestore

Se ejecutará una exportación completa de la colección `beers` y la colección de metadatos `users` hacia un bucket de Cloud Storage dedicado con políticas de retención activadas (Object Lock / Bucket Lock):

```bash
# 1. Definir variables de operación con timestamp ISO
EXPORT_TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_BUCKET="gs://contador-de-birras-backups/firestore-pre-c4-${EXPORT_TIMESTAMP}"

# 2. Ejecutar exportación administrada de Firestore mediante gcloud
gcloud firestore export ${BACKUP_BUCKET} \
    --project=contador-de-birras \
    --collection-ids='beers','users'

# 3. Comprobar el estado y verificar el archivo de metadatos
gcloud firestore operations list \
    --project=contador-de-birras \
    --filter="metadata.type:EXPORT_DOCUMENTS" \
    --limit=1
```

**Criterio de Aceptación:**
- La operación debe reportar `done: true` y no registrar ningún error en `status`.
- El bucket de destino debe contener los archivos `.overall_export_metadata` y los chunks de datos de Firestore.

### 2.2 Snapshot de Firebase Storage

Las fotografías existentes bajo la ruta legacy `users/{uid}/beers/{syncId}.jpg` deben ser respaldadas para evitar pérdida o sobrescritura accidental:

```bash
# Sincronización recursiva del directorio de usuarios hacia el bucket de backup
STORAGE_BACKUP="gs://contador-de-birras-backups/storage-pre-c4-${EXPORT_TIMESTAMP}"

gsutil -m rsync -r \
    gs://contador-de-birras.appspot.com/users \
    ${STORAGE_BACKUP}/users
```

**Verificación de Integridad:**
- Comparar el número total de objetos y el tamaño en bytes entre el origen y el destino:
  ```bash
  gsutil du -s gs://contador-de-birras.appspot.com/users
  gsutil du -s ${STORAGE_BACKUP}/users
  ```

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

1. **Actualización de la Aplicación:** El usuario debe disponer de la versión de la app con `versionCode >= 3`. Las versiones obsoletas no disponen del motor C4 ni de la base de datos Room v10 compatible.
2. **Reglas de Seguridad Candidatas Desplegadas:** El archivo `firestore.candidate.rules` y `storage.rules` debe estar activo en producción.
3. **Ejecución del Cutover:**
   - Se activa mediante el orquestador `C4CutoverManager` en el arranque o inicio de sesión.
   - Sigue la secuencia atómica de 7 pasos: Prevalidación -> Ascenso CAS remoto a rev 1 -> Alineación Room -> PULL colección -> PUSH de pendientes y fotos -> Verificación de postcondiciones -> Bloqueo remoto `/users/{uid}`.

### 4.2 Formato del Bloqueo Remoto de Protocolo

La confirmación final en `/users/{uid}` escribe de forma transaccional:
```json
{
  "syncProtocolVersion": "C4",
  "minSupportedAppVersionCode": 3,
  "accountSyncState": "MIGRATED_TO_C4",
  "migratedAt": 1700000000000,
  "updatedAt": 1700000000000
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

### 6.2 Prohibición de Rollback a Reglas Permisivas

> [!CAUTION]
> **REGLA DE ORO DE SEGURIDAD:**  
> Bajo ninguna circunstancia se debe revertir `firestore.rules` a las reglas permisivas legacy de producción.  
> Revertir a las reglas permisivas permitiría que clientes desactualizados o peticiones legacy sobreescriban documentos ya modernizados a revisión $\ge 1$, corrompiendo los vectores causales y destruyendo la trazabilidad de mutaciones.

### 6.3 Procedimiento de Contingencia Fix-Forward

Ante cualquier incidencia en una cuenta migrada o semi-migrada:

1. **Aislamiento en HALTED:** El cliente se detiene automáticamente en `HALTED` gracias a las salvaguardas de `C4StagingCutoverManager`.
2. **Diagnóstico Automatizado:** Ejecutar `C4HaltedRecoveryManager.diagnoseAccount(uid)` para identificar la causa:
   - *Fallo de red durante PUSH:* Reintentar la sincronización tras recuperar conectividad; el sistema procesa las mutaciones pendientes de forma idempotente.
   - *Fallo en escritura de bloqueo remoto (`HALTED_RECOVERABLE_LOCK_FAILED`):* Reintentar la llamada `writeAccountProtocolLock(uid)` directamente desde el gestor de recuperación.
   - *Cervezas locales ausentes en Firestore:* Exportar la base Room del dispositivo a través del soporte técnico y ejecutar una reconciliación manual controlada antes de reactivar.
3. **Reactivación Explícita:** Una vez resuelta la causa y verificado el diagnóstico en verde (`isRecoverable == true`), ejecutar `recoverHaltedAccountExplicitly(uid)`.

---

## 7. Fase 5: Desacoplamiento de Piezas Piloto para Producción

Para compilar y distribuir la versión final de producción de la aplicación, es imperativo desacoplar los componentes creados exclusivamente para el piloto de pruebas.

### Lista de Desacoplamiento Obligatorio

| Componente / Archivo | Configuración en Piloto (DEBUG/Staging) | Configuración Requerida para Producción (RELEASE) |
| :--- | :--- | :--- |
| **`C4SyncConstants.PILOT_USER_EMAIL`** | `"piloto-c4@azcaratech.com"` (fijo) | **Eliminar dependencia:** Utilizar exclusivamente `FirebaseAuth.getInstance().currentUser?.email`. |
| **`C4SyncConstants.PILOT_FIREBASE_PROJECT_ID`** | `"demo-contador-c44"` (ficticio) | **Eliminar dependencia:** Utilizar el proyecto real de producción `FirebaseApp.getInstance().options.projectId`. |
| **Endpoints de Emuladores** | `10.0.2.2:8888` (Firestore), `10.0.2.2:9199` (Storage), `10.0.2.2:9099` (Auth) | **Desconectar:** No invocar `useEmulator()`. Conectar a los servicios gestionados de Google Cloud. |
| **`StagingC4SyncProvider`** | Instanciación manual con puertos de emulador y sesión forzada | **Sustituir por `ProductionC4SyncProvider`:** Inyección de dependencias Hilt con las instancias estándar de Firebase del contenedor de producción. |
| **`applicationIdSuffix`** | `".c4pilot"` en `build.gradle.kts` | **Retirar:** Compilar bajo el `applicationId` oficial de la aplicación (`com.azcaratech.birras`). |
| **Panel de Depuración (`C4SyncDebugScreen`)** | Accesible desde la interfaz o menú de desarrollo | **Ocultar / Deshabilitar:** Excluir de los builds de tipo `release` mediante sourceSets o directivas `@VisibleForTesting`. |
| **`minSupportedAppVersionCode`** | Valor de prueba en piloto | Establecer en el `versionCode` oficial de la primera versión release que contenga C4 (mínimo 3). |
| **Trazas y Logs Verbosos** | `Log.d("C4Cutover", ...)` imprimiendo payloads y estados intermedios | Desactivar logs detallados en Release para prevenir fugas de información sensible en Logcat. |

---

## 8. Checklist de Validación Final Previa a la Migración

Antes de autorizar la migración de la primera cuenta real:

- [ ] Exportación completa de Firestore ejecutada y comprobada en Cloud Storage.
- [ ] Snapshot recursivo de Firebase Storage ejecutado y verificado.
- [ ] `firestore.candidate.rules` desplegadas y validadas con la suite de 108 pruebas de seguridad.
- [ ] `storage.rules` desplegadas con validación de fotos versionadas e inmutabilidad.
- [ ] Suite de pruebas unitarias Robolectric (`C4MigrationRehearsalTest` y `C4StagingCutoverManagerTest`) superadas al 100%.
- [ ] Piezas del piloto desacopladas y aplicación release compilada con `versionCode >= 3`.
- [ ] Cohorte 0 identificada y notificada.
- [ ] Protocolo de contingencia y equipo de guardia asignado durante las ventanas de ejecución.
