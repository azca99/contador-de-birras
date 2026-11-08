# Informe de Resultados — Piloto Funcional C4 en Birrómetro

**Fecha:** 8 de noviembre de 2026  
**Rama:** `feat/c4-piloto-ui`  
**Commit base:** `fe9233ab6326b214917d5f71f451cc59646412a9`  
**Estado:** Piloto Funcional Validado Exitosamente en Entorno de Pruebas Aislado  

---

## 1. Entorno de Ejecución y Configuración de Pruebas

El primer piloto funcional del Protocolo C4 integrado en las pantallas habituales de la aplicación se ha ejecutado y validado en el siguiente entorno controlado:

- **Dispositivo físico / AVD:** Google Pixel 7, Android 15 (API 35).
- **Backend remoto:** Firebase Local Emulator Suite (Auth, Firestore, Storage) configurado contra el proyecto ficticio `demo-contador-c44` en `10.0.2.2`.
- **Aislamiento de empaquetado:** Identificador de aplicación `com.daniel.contadordebirras.c4pilot` mediante `applicationIdSuffix = ".c4pilot"` en la variante DEBUG, permitiendo la coexistencia en el mismo dispositivo sin interferir con la instalación oficial de producción.
- **Aislamiento de almacenamiento local:** Base de datos Room dedicada `c4_debug_disposable.db`, separada por completo de `beer_database`.
- **Cuenta de pruebas:** Usuario ficticio `c4_pilot_user@demo-contador-c44.test` gestionado exclusivamente por Firebase Auth Emulator.

---

## 2. Resultados Validados Manualmente

Se han ejecutado y confirmado manualmente las siguientes capacidades del flujo de usuario:

1. **Instalación y coexistencia independiente:**
   - La aplicación piloto se instala y ejecuta sin entrar en conflicto con la instalación principal ni con sus preferencias compartidas.

2. **Autenticación e inicialización desasistida:**
   - Comprobación y autenticación automática contra Firebase Auth Emulator con la cuenta ficticia autorizada.
   - Validación estricta de identidad: si existe una sesión previa con un correo distinto, se cierra de inmediato.

3. **Registro habitual de cerveza (Inicio):**
   - Creación de cerveza desde el botón principal **+1 BIRRA** en `MainScreen`.
   - Generación atómica de la mutación durable en `local_mutation_history` (`isLocalOnlyCreation = true`, `status = PENDING`) y persistencia local en `BeerEntity` con `syncStatus = PENDING`.

4. **Visualización y badges reactivos (Historial):**
   - Visualización inmediata de la cerveza en `HistoryScreen` bajo la cabecera `PILOTO C4 · DATOS DE PRUEBA`.
   - Estado inicial reflejado en la tarjeta como chip naranja: **`PENDING (C4)`**.

5. **Sincronización desasistida mediante WorkManager:**
   - Disparo de `BeerSyncWorker` delegando la sincronización en segundo plano al proveedor `StagingC4SyncProvider`.
   - PUSH de la mutación local a Firestore Emulator (`users/<uid>/beers/<syncId>`), actualizando la revisión remota (`remoteRevision: 1`).
   - Notificación reactiva inmediata a través del `InvalidationTracker` de Room hacia los Flows de Compose: **transición visual observada en directo de `PENDING (C4)` a `SYNCED (C4)`** sin recargar la pantalla.

6. **PUSH manual y persistencia tras reinicio de proceso:**
   - PUSH manual previamente validado desde el panel de diagnóstico técnico C4.
   - Cierre forzado del proceso mediante `am force-stop` y reapertura: los datos se recuperan íntegros desde `c4_debug_disposable.db` conservando su estado sincronizado.

---

## 3. Verificaciones Automatizadas

El código que sustenta este piloto cuenta con cobertura automatizada completa:

- **Pruebas unitarias:** 32 pruebas unitarias ejecutadas y superadas sin errores (`BUILD SUCCESSFUL`):
  - `BeerRepositoryC4PilotTest`: validación de mutaciones durables (creación, edición, borrado), comprobación estricta de identidad del usuario piloto, bloqueo en modo `HALTED` y bloqueo fail-closed ante cualquier error de lectura de estado.
  - `BeerSyncWorkerTest`: validación de delegación C4 en staging y bloqueo absoluto del camino Legacy en el paquete `.c4pilot`.
  - Suite de arquitectura C4 (`sync.c4.*`, `C4StagingCutoverManagerTest`, `RoomC4SyncPersistenceTest`, `AndroidC4CoordinatorUnitTest`).
- **Compilación de empaquetado:** Tarea Gradle `assembleDebug` superada con éxito (APK generado correctamente).

---

## 4. ADVERTENCIA FORMAL: No Autorización para Producción

> [!CAUTION]
> **El éxito de este piloto funcional NO autoriza ni habilita la activación del Protocolo C4 en producción.**
> 
> El entorno de producción (`master`) debe permanecer estrictamente en modo Legacy (`BeerSyncEngine` activo, C4 inactivo por defecto). El alcance de este piloto está rigurosamente acotado a pruebas locales aisladas con emuladores y cuentas ficticias.

---

## 5. Deuda Técnica y Pendientes de Seguridad Identificados

Durante la integración y validación se ha registrado el siguiente aspecto de seguridad pendiente:

- **Recuperación de cuentas en estado `HALTED`:**
  - El botón actual en el panel de diagnóstico (*"Recuperar cuenta HALTED a C4_ACTIVE (Explícito)"*) restablece directamente la bandera a `C4_ACTIVE`.
  - **Riesgo:** Si una cuenta entró en `HALTED` debido a una discrepancia de invariantes, un fallo de red a mitad de migración o un error de protocolo, una reactivación directa sin validar la integridad del almacenamiento local ni reconciliar con el servidor remoto podría generar inconsistencias.
  - **Acción requerida para producción:** Diseñar un procedimiento de auto-recuperación o diagnóstico interactivo que verifique el estado del checkpoint de migración y la coherencia de las mutaciones locales antes de restaurar `C4_ACTIVE`.

---

## 6. Prerrequisitos para la Llegada de C4 a Producción

Para que el Protocolo C4 pueda desplegarse y activarse de forma segura en producción para los usuarios finales, es indispensable abordar los siguientes bloques:

1. **Migración de cuentas reales (Cutover en Producción):**
   - Pruebas del gestor de cutover (`C4StagingCutoverManager`) con cuentas con volumen real de cervezas.
   - Verificación de la escritura del protocolo lock en el documento de cuenta de Firestore antes de liberar la bandera de activación.

2. **Reglas de seguridad de Firestore para Producción:**
   - Despliegue y validación de las reglas de seguridad C4 revisadas en `firestore.rules` (validación de revisiones remotas monotónicas, restricciones de esquema y mutaciones idempotentes).

3. **Gestión de Fotografías bajo el Protocolo C4:**
   - Soporte completo para subida y descarga de fotos en Firebase Storage dentro del ciclo de mutaciones de C4, incluyendo resolución de URLs remotas y limpieza de huérfanos.

4. **Verificación multidispositivo y resolución de conflictos:**
   - Validación en escenarios reales de edición simultánea desde dos dispositivos físicos conectados a la misma cuenta, comprobando la aplicación de parches y la resolución determinista de conflictos en casos límite.
