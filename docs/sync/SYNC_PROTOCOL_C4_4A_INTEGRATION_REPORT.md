# INFORME TÉCNICO DE INTEGRACIÓN ANDROID AISLADA (FASE C4.4A)
**Integración de Room v10 + Firebase SDK + Protocolo C4: Cierre Definitivo de Riesgos**
**Fecha:** 3 de Noviembre de 2026  
**Rama:** `feat/sync-protocol-c4-4a-android-integration`  
**Base Master:** `8ff051fd7177b48b24b264da33c0b30249360836`  
**Entorno de Emuladores:** Puertos Firestore 8888, Storage 9199, Auth 9099 | Proyecto: `demo-contador-c44` / `demo-contador-c43`

---

## 1. RESUMEN EJECUTIVO

En la Fase C4.4A se ha completado la arquitectura, corrección de defectos, blindaje preventivo y validación de la **integración Android aislada** del Protocolo de Sincronización C4, conectando de forma rigurosa:
1. La base de datos local **Room v10** (entidades `BeerEntity` y `LocalMutationHistoryEntity`).
2. Los planificadores puros de sincronización **C3A (`SyncProtocolPushPlanner`)** y **C3B (`SyncProtocolPullPlanner`)**.
3. El clasificador formal de compatibilidad legacy **C4.1A (`LegacyBeerClassifier`)**.
4. Los SDK oficiales de **Cloud Firestore** y **Firebase Storage** sobre emuladores locales herméticos.

> [!IMPORTANT]
> **Aislamiento Absoluto de Producción:**
> - El motor en ejecución (`BeerSyncEngine.kt`), los workers de sincronización periódica, la interfaz de usuario y las reglas de producción (`firestore.rules`, `storage.rules`, `firebase.json`) han permanecido **100% intactos e inalterados**.
> - El nuevo orquestador y sus adaptadores residen exclusivamente en el paquete aislado `com.example.contadordebirras.sync.c4`.
> - Quedó estrictamente prohibida cualquier conexión o fallback hacia entornos de Firebase en producción (`C4TestEnvironmentFactory` verifica obligatoriamente antes de instanciar `FirebaseApp` el proyecto `demo-contador-c44`, los puertos 9099, 8888, 9199 y hosts locales autorizados).
> - No se ejecutó ningún comando sobre el dispositivo físico (`adb devices` desierto; se respetó íntegramente la ausencia de dispositivos físicos).

---

## 2. ARQUITECTURA Y BLINDAJE DE COMPONENTES (8 COMPONENTES AISLADOS)

Todos los componentes de producción aislados se ubican en `app/src/main/java/com/example/contadordebirras/sync/c4/`:

```
app/src/main/java/com/example/contadordebirras/sync/c4/
├── C4SyncConstants.kt           # Constantes de puertos, hosts, colección y nombrado versionado
├── C4SyncResult.kt              # Jerarquía sellada de resultados (Transacciones, Storage, Push, Pull)
├── C4FirestoreDocument.kt       # Modelo de documento con esquema auténtico y 5 claves canónicas
├── FirestoreC4SyncAdapter.kt    # Adaptador transaccional CAS sobre Firestore SDK
├── StorageC4SyncAdapter.kt      # Adaptador de almacenamiento inmutable versionado sobre Storage SDK
├── RoomC4SyncPersistence.kt     # Persistencia durable atómica en Room v10 y tabla de historial
├── AndroidC4SyncCoordinator.kt  # Coordinador orquestador de PUSH y PULL integrado
└── C4TestEnvironmentFactory.kt  # Factoría hermética para inicializar FirebaseApp contra emuladores locales
```

### Detalle de Responsabilidades y Cierre de Riesgos:

1. **`C4SyncConstants.kt`**:
   - Define los puertos locales (`Firestore 8888`, `Storage 9199`, `Auth 9099`) y anfitriones (`127.0.0.1` para JVM/host y `10.0.2.2` para AVD).
   - Establece las 5 claves canónicas de `fieldRevisions`: `TYPE`, `TIMESTAMP`, `LOCATION`, `COMMENT`, `PHOTO`.
   - Implementa la regex determinista de fotos versionadas inmutables `users/$owner/beers/${syncId}_$mutationId.jpg`.

2. **`C4SyncResult.kt`**:
   - Modela tipadamente los resultados: `C4TransactionResult` (`Success`, `Conflict`, `IdempotentAck`, `NotFound`, `OwnershipMismatch`, `TombstonePrevailed`, `Error`), `C4StorageResult` (`Success`, `OverwriteRejected`, `Error`), `C4CoordinatorPushResult` y `C4CoordinatorPullResult`.

3. **`C4FirestoreDocument.kt`**:
   - Mapea el esquema canónico completo de Firestore a partir de `DocumentSnapshot`.
   - Garantiza que `fieldRevisions` se extraiga y serialice preservando valores numéricos monotónicos y tipos canónicos.
   - Serializa `syncId` tanto a nivel de clave en el payload como en la referencia del documento.

4. **`FirestoreC4SyncAdapter.kt`**:
   - Ejecuta lecturas forzadas desde `Source.SERVER` mediante `readServerDocument(syncId)`, evitando datos obsoletos o cachés locales desincronizadas.
   - Ejecuta `executeCreateActive` en transacción: verifica ausencia previa, asigna revisión 1 y las 5 claves canónicas inicializadas en 1.
   - Ejecuta `executeApplyPatch` en transacción CAS: valida precondición `remoteRevision == baseRevision`, ownership y que el documento no sea tombstone. Incrementa `revision` en +1 y avanza selectivamente las `fieldRevisions` de los campos editados.
   - Detecta idempotencia por coincidencia de `lastMutationId`, retornando `IdempotentAck`.
   - Ejecuta `executeWriteTombstone` marcando `isDeleted = true` e incrementando la revisión sin alterar `fieldRevisions`.

5. **`StorageC4SyncAdapter.kt`**:
   - Valida el formato de nombrado versionado antes de subir.
   - **Verificación de Identidad con MD5 y Tamaño**: Si el archivo ya existe en Storage, obtiene sus metadatos (`md5Hash`, `sizeBytes`) y los compara con el hash MD5 y tamaño del contenido local mediante `computeMd5Base64`. Si coinciden, retorna éxito idempotente (`alreadyExisted = true`); si los hashes no coinciden o no pueden verificarse, retorna formalmente `OverwriteRejected` impidiendo la corrupción de datos.
   - **Autorización Estricta de Primera Subida**: Ante `StorageException`, **exclusivamente** el código `StorageException.ERROR_OBJECT_NOT_FOUND` se interpreta como permiso para proceder con `putBytes`. Cualquier otro error aborta retornando `C4StorageResult.Error`.

6. **`RoomC4SyncPersistence.kt` (Cierre Estricto de Tombstones)**:
   - **Parámetros Enlazados y Transaccionalidad Total**: `updateBeerEntityDirect` usa sentencias preparadas con parámetros enlazados explícitos y `executeUpdateDelete()`.
   - **Validación Estricta en `confirmTombstonePushSuccess`**:
     * Exige que `beer.lastMutationId == expectedMutationId`. Si la mutación cambió, retorna `false` inmediatamente sin modificar datos.
     * Valida la existencia y estado elegible del historial (`PENDING` o `PARTIALLY_CONFIRMED`) **ANTES** de aplicar cualquier cambio sobre la entidad `beers`.
     * Se preserva la fila en `DELETED` con `dirtyFields = null` y `lastMutationId = null` **sin borrado físico**.
     * Si la actualización de la entidad o el historial no afectan exactamente a 1 fila, se ejecuta rollback atómico automático.

7. **`AndroidC4SyncCoordinator.kt` (Verificación de Confirmaciones Locales)**:
   - **Comprobación del Boolean de Persistencia**: En todas las ramas de `executePush` (`CreateActive`, `ApplyPatch`, `WriteTombstone`, `AlreadyApplied`, `RemoteTombstoneWins`), se evalúa el `Boolean` devuelto por los métodos de Room persistence.
   - **Prohibición de Falso Éxito**: Si la persistencia local retorna `false`, el coordinador **jamás** devuelve `Success` ni `IdempotentRecovery`, retornando de forma segura `C4CoordinatorPushResult.Blocked`.
   - **Conservación de Mutaciones Pendientes**: Al abortar la confirmación y hacer rollback en SQLite, la entidad y su registro en historial retienen su estado original (`PENDING`, `lastMutationId`, `dirtyFields`), garantizando reintentos limpios posteriores.

8. **`C4TestEnvironmentFactory.kt` (Aislamiento de Emuladores Pre-Inicialización)**:
   - **Validación Previa Incondicional**: Antes de invocar `FirebaseApp.initializeApp`, valida obligatoriamente:
     * `projectId == "demo-contador-c44"`
     * `host in setOf("127.0.0.1", "10.0.2.2", "localhost")`
     * `authPort == 9099`, `firestorePort == 8888`, `storagePort == 9199`
   - Si cualquiera diverge, lanza `SecurityException` de inmediato impidiendo la creación del contenedor FirebaseApp.
   - Incluye verificación post-configuración en `firestoreSettings.host` como defensa en profundidad.

---

## 3. RESOLUCIÓN DEL BLOQUEO DE FIREBASE AUTH

### Diagnóstico y Desacoplamiento
Durante las pruebas bajo Robolectric, invocar `FirebaseAuth.signInAnonymously().await()` dentro de `runBlocking` en el hilo de test generaba un deadlock debido a la dependencia de los callbacks de `com.google.android.gms.tasks.Task` sobre el Looper principal.
- **Sin modificar SDKs**: No se alteraron archivos `firebase-auth-interop.aar` ni se empleó reflexión sobre APIs privadas.
- **Desacoplamiento Limpio**:
  1. Suite JVM (`AndroidC4CoordinatorUnitTest.kt`): Cobertura desacoplada mediante dobles de prueba tipados (`MockK`) con verificación de los 13 escenarios del protocolo más regresiones de fallo de confirmación.
  2. Suite Instrumented (`C4IntegrationInstrumentedTest.kt`): Preparada y compilada para ejecución nativa sobre AVD desacoplado de hilos.

---

## 4. MATRIZ DE ESTADO DE EJECUCIÓN Y VALIDACIÓN

| Componente / Suite | Alcance | Resultado | Observaciones |
| :--- | :--- | :--- | :--- |
| **Persistencia Room v10** | `RoomC4SyncPersistenceTest.kt` (14 tests) | **PASS** | Incluye pruebas negativas de mismatch de `lastMutationId` e historial ausente en `confirmTombstonePushSuccess`. |
| **Adaptador Storage C4** | `StorageC4SyncAdapterTest.kt` (4 tests) | **PASS** | Hash MD5, tamaño, `ERROR_OBJECT_NOT_FOUND` exclusivo, inmutabilidad versionada. |
| **Mappers y Esquema C4** | `C4SerializationAndMappersTest.kt` (5 tests) | **PASS** | Serialización canónica de 5 claves de `fieldRevisions`. |
| **Aislamiento Factory** | `C4TestEnvironmentFactoryTest.kt` (5 tests) | **PASS** | Rechazo preventivo de projectIds, hosts o puertos divergentes antes de instanciar FirebaseApp. |
| **Coordinador C4 (A–M)** | `AndroidC4CoordinatorUnitTest.kt` (18 tests) | **PASS** | 13 escenarios C4 + Storage + Tombstones + Lost ACK + Regresión de fallo en confirmación local. |
| **Suite Base del Repositorio** | `testDebugUnitTest` completo (390 tests) | **PASS** | **390 tests passing (100% éxito)** en 1m 52s (`BUILD SUCCESSFUL`). |
| **Reglas Firebase C4.3** | `tests/firebase-rules` (74 tests) | **PASS** | Emuladores oficiales (`demo-contador-c43`): **74/74 passing (100% éxito)** en 15s. |
| **Compilación Instrumented** | `compileDebugAndroidTestKotlin` | **PASS** | Suite instrumentada C4 compila exitosamente (`BUILD SUCCESSFUL` en 30s). |
| **Ensamblado Debug APK** | `assembleDebug` | **PASS** | APK de desarrollo compila sin errores de enlace ni dependencias (`BUILD SUCCESSFUL`). |
| **Demostración Multi-Cliente** | `C4IntegrationInstrumentedTest.kt` | **NO EJECUTADO EN ESTE ENTORNO** | Código completo y compilado sin mocks. No se dispone de AVD activo en el host (`adb devices` desierto); se declara transparentemente. |

---

## 5. TRAZABILIDAD DE ESCENARIOS C4 Y REGRESIONES DE SEGURIDAD

| Escenario / Prueba | Nombre del Test | Garantía Demostrada |
| :--- | :--- | :--- |
| **Escenario A** | `scenarioA_createActive_localOnlyToRemoteRev1` | Local-only (-1/-1) transita a CreateActive rev 1; Room confirma a SYNCED (1/1) y registra historial CONFIRMED. |
| **Escenario B** | `scenarioB_partialPatch_advancesRemoteRevisionAndPreservesLocalBaseRevision` | PATCH parcial avanza `remoteRevision` a 3; dirtyFields particionado y `baseRevision` congelada en 2. |
| **Escenario C** | `scenarioC_conflict_obsoleteRemoteRevisionDetected` | Avance remoto por otro dispositivo; operación local bloqueada como Conflict y estado local permanece PENDING. |
| **Escenario D** | `scenarioD_orthogonalFields_convergeDeterministically` | Modificaciones ortogonales (`TYPE` vs `COMMENT`); convergencia determinista adoptando remoto y conservando edición local. |
| **Escenario E** | `scenarioE_concurrentConflictSameField_blockedWithoutOverwrite` | Conflicto concurrente sobre el mismo campo (`COMMENT`); PULL bloqueado sin sobrescritura ciega. |
| **Escenario F** | `scenarioF_lostAckRecovery_recoversViaIdempotentRead` | Pérdida simulada de ACK tras commit en Firestore; reintento detecta coincidencia de `mutationId` y recupera idempotentemente a SYNCED. |
| **Escenario G** | `scenarioG_remoteTombstoneWins_marksDeletedAndSupersedesLocalMutation` | Remoto tombstone rev 2 frente a edición local; prevalece DELETE WINS: local transita a DELETED y mutación desplazada a SUPERSEDED. |
| **Escenario H** | `scenarioH_resurrectionBlocked_tombstoneCannotBeOverwrittenWithActive` | Intento de crear entidad sobre tombstone existente; bloqueado formalmente impidiendo resurrección. |
| **Escenario I** | `scenarioI_ownershipMismatch_rejectsForeignAccess` | Modificación sobre UID ajeno rechazada por Firestore transaccional y el coordinador. |
| **Escenario J** | `scenarioJ_nonAuthoritativeRead_blocked` | Lectura remota no autoritativa; PULL se rehúsa a aplicar cambios o inferir ausencias. |
| **Escenario K** | `scenarioK_legacyDocument_classifiedAndUpgraded` | Detección de documento remoto legacy (rev 0); coordinador exige resolución o migración formal. |
| **Escenario L** | `scenarioL_invalidCausalMetadata_blocked` | Documento remoto con metadatos causales incompletos; operación bloqueada. |
| **Escenario M** | `scenarioM_idempotentRetry_noDuplicateIncrement` | Reintento de mutación ya confirmada; no produce incremento espurio de revisión. |
| **Tombstone Mismatch** | `confirmTombstonePushSuccess_rejectsWhenLastMutationIdMismatchAndLeavesDataIntact` | Si `lastMutationId` local difiere del esperado, rechaza confirmación y no altera datos. |
| **Tombstone Sin Historial**| `confirmTombstonePushSuccess_rejectsWhenHistoryIsMissingAndLeavesEntityIntact` | Si falta el registro en `local_mutation_history`, rechaza confirmación y preserva la entidad intacta. |
| **Tombstone Retry** | `scenario_pushWriteTombstone_idempotentRetryOnLostAck` | Reintento de subida de tombstone con mutación ya aplicada en servidor (`AlreadyApplied`): recuperación idempotente sin borrado físico. |
| **Regresión Confirmación**| `regression_pushFailsGracefully_whenRoomConfirmationFails_preservesPendingMutation` | Si la confirmación en Room falla (devuelve false), el coordinador devuelve Blocked (nunca Success) y la mutación local permanece PENDING. |
| **Factory Security 1** | `rejects_invalidProjectId_beforeInitializingFirebaseApp` | Lanza SecurityException si projectId != demo-contador-c44 antes de inicializar FirebaseApp. |
| **Factory Security 2** | `rejects_unauthorizedHost_beforeInitializingFirebaseApp` | Lanza SecurityException si el host no está en la lista blanca local autorizada. |
| **Factory Security 3** | `rejects_invalidAuthPort_beforeInitializingFirebaseApp` | Lanza SecurityException si el puerto de Auth diverge de 9099. |
| **Factory Security 4** | `rejects_invalidFirestorePort_beforeInitializingFirebaseApp` | Lanza SecurityException si el puerto de Firestore diverge de 8888. |
| **Factory Security 5** | `rejects_invalidStoragePort_beforeInitializingFirebaseApp` | Lanza SecurityException si el puerto de Storage diverge de 9199. |

---

## 6. CONTROL DE ALCANCE Y VERIFICACIÓN GIT

La rama `feat/sync-protocol-c4-4a-android-integration` contiene exclusivamente los componentes aislados de la integración C4.4A:

```
Nuevos componentes de producción:
- app/src/main/java/com/example/contadordebirras/sync/c4/C4SyncConstants.kt
- app/src/main/java/com/example/contadordebirras/sync/c4/C4SyncResult.kt
- app/src/main/java/com/example/contadordebirras/sync/c4/C4FirestoreDocument.kt
- app/src/main/java/com/example/contadordebirras/sync/c4/FirestoreC4SyncAdapter.kt
- app/src/main/java/com/example/contadordebirras/sync/c4/StorageC4SyncAdapter.kt
- app/src/main/java/com/example/contadordebirras/sync/c4/RoomC4SyncPersistence.kt
- app/src/main/java/com/example/contadordebirras/sync/c4/AndroidC4SyncCoordinator.kt
- app/src/main/java/com/example/contadordebirras/sync/c4/C4TestEnvironmentFactory.kt

Nuevas pruebas unitarias e instrumentadas:
- app/src/test/java/com/example/contadordebirras/sync/c4/RoomC4SyncPersistenceTest.kt
- app/src/test/java/com/example/contadordebirras/sync/c4/StorageC4SyncAdapterTest.kt
- app/src/test/java/com/example/contadordebirras/sync/c4/C4SerializationAndMappersTest.kt
- app/src/test/java/com/example/contadordebirras/sync/c4/AndroidC4CoordinatorUnitTest.kt
- app/src/test/java/com/example/contadordebirras/sync/c4/C4TestEnvironmentFactoryTest.kt
- app/src/androidTest/java/com/example/contadordebirras/sync/c4/C4IntegrationInstrumentedTest.kt
- tests/firebase.test.json

Documentación técnica:
- docs/sync/SYNC_PROTOCOL_C4_4A_INTEGRATION_REPORT.md
```

### Componentes Inalterados (Intactos respecto a master):
- `BeerSyncEngine.kt` (motor legacy activo sin tocar).
- `BeerRepository.kt`, `BeerDao.kt`, `BeerDatabase.kt` (v10).
- `SyncProtocolPushPlanner.kt`, `SyncProtocolPullPlanner.kt`, `LegacyBeerClassifier.kt`.
- `firestore.rules`, `storage.rules`, `firebase.json` de la raíz del proyecto.
- Banco de simulación C4.2 y suite de emuladores C4.3.

---

## 7. CONCLUSIÓN Y CIERRE DEFINITIVO

La Fase C4.4A queda **cerrada y blindada de forma definitiva**:
- Tombstones: verificación estricta de `lastMutationId`, pre-validación de historial, preservación sin borrado físico.
- Coordinador: verificación obligatoria de persistencia en Room; fallo local bloquea el PUSH y conserva cambios pendientes.
- Aislamiento: validación estricta de proyecto, host y puertos antes de instanciar FirebaseApp.
- 100% de éxito en 390 pruebas unitarias JVM, 74 pruebas de emulador de reglas y ensamblado/compilación Android.
- No se realiza merge a `master`; cambios publicados en `feat/sync-protocol-c4-4a-android-integration`.
