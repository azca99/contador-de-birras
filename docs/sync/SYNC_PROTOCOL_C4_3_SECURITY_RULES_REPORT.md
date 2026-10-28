# INFORME TÉCNICO DE VALIDACIÓN DE REGLAS DE SEGURIDAD (FASE C4.3)
**Auditoría Correctiva de Reglas y Fidelidad al Esquema Real**
**Fecha:** 28 de Octubre de 2026
**Rama:** `feat/sync-protocol-c4-3-firebase-rules`
**Base Master:** `38e785bbbe2047c12efe7478317cd0d0e225842a`
**Proyecto Emulador:** `demo-contador-c43` (Puertos: Firestore 8888, Storage 9199)

---

## 1. RESUMEN EJECUTIVO

En la Fase C4.3 se ha desarrollado una suite automatizada de pruebas de seguridad perimetral para **Cloud Firestore** y **Firebase Storage** usando la suite oficial de emuladores de Firebase (`firebase-tools 15.20.0`), `@firebase/rules-unit-testing 5.0.2` y el SDK modular v12 de Firebase sobre Node.js.

Tras la auditoría correctiva de fidelidad al esquema real de `BeerEntity`, `BeerSyncEngine` y los contratos C3A/C3B/C4.2:
1. Se ha eliminado por completo el uso de campos sustitutos no canónicos (`name`, `brand`, `rating`, `style`, `alcohol`), sustituyéndolos por el esquema auténtico del modelo de datos (`type`, `timestamp`, `latitude`, `longitude`, `comment`, `locationName`, `remotePhotoUrl`, `photoSource`, `updatedAt`, `userId`, `revision`, `isDeleted`, `lastMutationId`, `fieldRevisions`).
2. Se ha implementado en las reglas de transición (Perfil B) y estrictas (Perfil C) la verificación formal de integridad de `fieldRevisions` para el conjunto acotado de unidades lógicas de `BeerDirtyField`: `TYPE`, `TIMESTAMP`, `LOCATION`, `COMMENT`, `PHOTO`.
3. Se ha blindado `isDeleted` exigiendo tipo booleano estricto, prohibiendo su supresión (`deleteField()`), su sustitución por nulo/texto/entero, impidiendo la resurrección de tombstones y protegiendo la inmutabilidad del propietario (`userId`).
4. Se ha incorporado la prohibición de reutilizar el mismo `lastMutationId` en avances de revisión (`request.resource.data.lastMutationId != resource.data.lastMutationId`), distinguiendo la idempotencia de reintento de una mutación contradictoria.
5. Se ha resuelto la ambigüedad en Storage mediante una convención inequívoca de nombrado que discrimina fotos legacy (incluyendo aquellas con guiones bajos como `mi_cerveza.jpg`) de fotos versionadas inmutables (`(UUID|sync-*)_(UUID|mut-*).ext`).
6. Se ha clarificado la semántica de concurrencia diferenciando Compare-And-Swap (CAS) en reglas, reintentos transaccionales del SDK y el rol del coordinador Android.

La suite automatizada consta de **68 casos de prueba** (55 originales adaptados + 13 nuevos tests negativos y de límites) ejecutados herméticamente contra los emuladores locales oficiales: **68 passing (100% de éxito)**.

> [!WARNING]
> **Aviso de Estado de Reglas:** Los perfiles B y C contenidos en `tests/firebase-rules/fixtures/` son especificaciones de prueba y validación de seguridad. NO son reglas definitivas de producción desplegadas ni sustituyen la verificación integral del coordinador en C4.4.

---

## 2. ESQUEMA AUTÉNTICO Y CORRESPONDENCIA CON BeerDirtyField

En concordancia estricta con `BeerEntity.kt`, `BeerSyncEngine.kt` y `BeerDirtyField.kt`, los documentos de Firestore y sus metadatos causales se estructuran del siguiente modo:

| Unidad Lógica (`BeerDirtyField`) | Campos Firestore Correspondientes | Tipo / Descripción |
| :--- | :--- | :--- |
| **`TYPE`** | `type` | `String` (ej. `"LATA"`, `"BOTELLIN"`, `"JARRA"`, `"PINTA"`, `"COPA"`) |
| **`TIMESTAMP`** | `timestamp` | `Long` (milisegundos epoch de consumo) |
| **`LOCATION`** | `latitude`, `longitude`, `locationName` | `Double?`, `Double?`, `String?` (coordenadas y topónimo) |
| **`COMMENT`** | `comment` | `String?` (notas de cata o contexto) |
| **`PHOTO`** | `remotePhotoUrl`, `photoSource` | `String?`, `String?` (ruta Storage y origen: CAMERA/GALLERY) |
| **Control de Identidad** | `userId` | `String` (UID del propietario, estrictamente inmutable) |
| **Sincronización C4** | `revision` | `Int` / `Long` (revisión monotónica, inicia en 1) |
| **Estado Causal** | `isDeleted` | `Boolean` (estado tombstone, no puede crearse `true`) |
| **Idempotencia** | `lastMutationId` | `String` (identificador único de la mutación aplicada) |
| **Vectores de Revisión** | `fieldRevisions` | `Map<String, Long>` (revisión de cada unidad lógica) |
| **Auditoría Local** | `updatedAt` | `Long` (timestamp de actualización local) |

---

## 3. INTEGRIDAD ESTRUCTURAL DE `fieldRevisions` EN SECURITY RULES

### 3.1 Garantías Implementadas en Perfiles B y C
A diferencia de esquemas genéricos no tipados, las reglas candidatas implementan validación explícita sobre el mapa `fieldRevisions`:
1. **Dominio Acotado de Claves:**
   `fr.keys().hasOnly(['TYPE', 'TIMESTAMP', 'LOCATION', 'COMMENT', 'PHOTO'])` y `fr.size() <= 5`. Cualquier clave ajena (ej. `BRAND`, `RATING`) es rechazada con `PERMISSION_DENIED`.
2. **Tipo Entero y No Negatividad:**
   Cada valor debe cumplir `fr[key] is int && fr[key] >= 1`. Se rechazan cadenas, flotantes, booleanos, ceros o negativos.
3. **Cota Superior Monotónica:**
   Ningún campo puede declarar una revisión superior a la revisión del documento: `fr[key] <= maxRev`.
4. **Protección Anti-Reducción:**
   En cada actualización, las revisiones existentes por campo no pueden decrementarse: `newFr[key] >= oldFr[key]`.
5. **Correspondencia Causal de Modificaciones:**
   Si los datos de una unidad lógica cambian (ej. `type` o `comment`), la regla exige que la revisión de esa unidad en `fieldRevisions` avance exactamente a la nueva revisión del documento (`request.resource.data.revision`).

### 3.2 Límites Intrínsecos del Motor de Reglas
- **Evaluación Stateless:** El motor de reglas compara únicamente `resource.data` y `request.resource.data`. No almacena el historial de revisiones ni puede verificar si mutaciones intermedias de otros dispositivos fueron integradas.
- **Detección de No-Op:** Si un cliente avanza `fieldRevisions.COMMENT` a la nueva revisión sin alterar el texto del comentario (por ejemplo, en una mutación táctica de confirmación o un "touch"), las reglas lo permiten siempre que cumpla la cota monotónica. La reconciliación de valores concurrentes idénticos pertenece al coordinador local.

---

## 4. BLINDAJE DE `isDeleted` Y CICLO DE VIDA DE TOMBSTONES

### 4.1 Restricciones Formales
- **Tipado Booleano Obligatorio:** En creaciones y actualizaciones modernas se exige `request.resource.data.isDeleted is bool`. Se rechazan intentos de inyectar `null`, enteros (`1`/`0`) o cadenas (`"true"`).
- **Prohibición de Eliminación de Campo:** Intentos de suprimir `isDeleted`, `lastMutationId` o `fieldRevisions` mediante `FieldValue.delete()` son interceptados y denegados.
- **Creación Activa:** Ningún documento nuevo puede crearse directamente con `isDeleted == true`.
- **Invariante Anti-Resurrección:**
  ```javascript
  (resource.data.get('isDeleted', false) == true ? request.resource.data.isDeleted == true : true)
  ```
  Una vez consolidado como tombstone (`isDeleted == true`), el documento jamás puede regresar a `isDeleted == false`. La única vía para "recrear" la cerveza es generar una nueva entidad con un `syncId` diferente.
- **Inmutabilidad del Propietario:** Se exige `request.resource.data.userId == resource.data.userId`. Un documento no puede transferirse a otro usuario.

---

## 5. NOMENCLATURA DISAMBIGUADA EN FIREBASE STORAGE

### 5.1 El Problema de Ambigüedad con Guiones Bajos
En el diseño inicial, la expresión regular `^[a-zA-Z0-9_-]+_[a-zA-Z0-9_-]+\.(ext)$` clasificaba erróneamente cualquier fichero legacy con guiones bajos (ej. `mi_cerveza.jpg` o `cerveza_favorita_2024.jpg`) como archivo versionado moderno. Esto provocaba que en el Perfil B se denegara indebidamente a los clientes legacy la actualización o borrado de sus fotos antiguas.

### 5.2 Convención Inequívoca Implementada
Se ha implementado una convención estricta que exige prefijos identificables o UUIDs estándar de 36 caracteres en ambas secciones del nombre:
```javascript
function isVersionedPhoto() {
  return fileName.matches('^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|sync-[a-zA-Z0-9-]+)_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|mut-[a-zA-Z0-9-]+)\\.(jpg|jpeg|png|webp)$');
}
```
**Comportamiento verificado:**
1. **Fotos Legacy:** `mi_cerveza.jpg`, `cerveza_favorita_2024.jpg`, `foto_1.jpg` -> `isVersionedPhoto() == false`. En Perfil B, el propietario puede subirlas, sobreescribirlas y borrarlas.
2. **Fotos Versionadas Modernas:** `sync-beer-100_mut-1001.jpg`, `c73bcdcc-2669-4bf6-81d3-e4ae73fb0001_8d8b9e6e-3559-4b65-9f5e-123456789abc.jpg` -> `isVersionedPhoto() == true`. Se exige `resource == null` en creación, prohibiéndose de forma terminante la sobreescritura y el borrado (`update, delete: if false`).
3. **Nombres Ambiguos / Inválidos:** `sync-100_something.jpg`, `sync-100.jpg`, `foto.exe` -> En Perfil C son rechazados.

---

## 6. CONCURRENCIA: CAS, TRANSACCIONES SDK Y REGLAS FIREBASE

Es imprescindible diferenciar las tres capas de concurrencia:

```mermaid
flowchart TD
    subgraph Capa 1: Reglas de Seguridad (Servidor)
        CAS["CAS Server-Side<br>(revision == resource.data.revision + 1)"]
    end
    subgraph Capa 2: Driver / SDK Cliente
        TX["Firestore runTransaction<br>(Optimistic Concurrency & Automatic Retry)"]
    end
    subgraph Capa 3: Coordinador Android C4
        COORD["C4 Engine & Room<br>(Pending Queue, CAS local, Two-way Merge)"]
    end
    COORD --> TX
    TX --> CAS
```

1. **Compare-And-Swap (CAS) en Reglas de Servidor:**
   La regla `request.resource.data.revision == resource.data.revision + 1` actúa como un pestillo atómico en el servidor. Si dos clientes intentan una escritura ciega basada en una revisión obsoleta, la primera escritura avanza la revisión y la segunda es rechazada con `PERMISSION_DENIED`. No existe sobreescritura silenciosa.
2. **Reintentos del SDK Transaccional (`runTransaction`):**
   Cuando los clientes utilizan transacciones, el SDK detecta la contención optimista. En caso de conflicto de lectura-modificación-escritura, el SDK reintenta transparentemente el bloque transaccional: re-lee el documento actualizado (ahora con la nueva revisión), computa el incremento correspondiente y lo envía. Ambas transacciones se serializan limpiamente (rev 1 -> 2 -> 3) sin pérdidas de datos.
3. **Garantía Independiente de Reglas:**
   Incluso si un cliente móvil omite transacciones o experimenta fallos de concurrencia, las Reglas de Seguridad impiden en todo momento que se introduzca una revisión duplicada, saltada o regresionada.

---

## 7. MATRIZ DE COMPORTAMIENTO ACTUALIZADA (PASS / REJECT)

A continuación se detalla la matriz completa tras la auditoría correctiva:

| Operación / Escenario | Perfil A (Baseline) | Perfil B (Legacy-Compat) | Perfil C (Modern-Strict) | Estado Verificado |
| :--- | :---: | :---: | :---: | :---: |
| **Lectura propia autenticada (esquema real)** | PASS | PASS | PASS | VERIFICADO |
| **Lectura/Escritura sin autenticación** | REJECT | REJECT | REJECT | VERIFICADO |
| **Acceso ajeno / Cross-user** | REJECT | REJECT | REJECT | VERIFICADO |
| **Alteración de `userId` (robo de propiedad)** | REJECT | REJECT | REJECT | VERIFICADO |
| **Creación legacy (sin metadatos C4)** | PASS | PASS | REJECT | VERIFICADO |
| **Creación C4 válida (`revision: 1`, `isDeleted: false`)** | PASS | PASS | PASS | VERIFICADO |
| **Creación con `revision != 1` (ej. 0 o 5)** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Creación con `isDeleted: true` inicial** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Creación sin `lastMutationId` o vacío** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **`fieldRevisions` con valor no entero (string/bool)** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **`fieldRevisions` con valor negativo o cero** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **`fieldRevisions` con valor superior a `revision`** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **`fieldRevisions` con claves desconocidas (ej. `BRAND`)** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **`fieldRevisions` con reducción/regresión de campo** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Modificación de campo sin actualizar `fieldRevisions`** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Actualización setting `isDeleted: null / "true" / 1`** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Eliminación de `isDeleted` con `FieldValue.delete()`** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Eliminación de `lastMutationId` con `FieldValue.delete()`** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Avance monotónico exacto (`revision N -> N+1`)** | PASS | PASS | PASS | VERIFICADO |
| **Salto arbitrario de revisión (ej. `rev 1 -> 999`)** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Regresión de revisión (ej. `rev 5 -> 4`)** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Estancamiento de revisión (ej. `rev 2 -> 2`)** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Reutilización de mismo `lastMutationId` en nueva rev** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Sobreescritura legacy (`.set()`) sobre doc C4** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Transición a Tombstone (`isDeleted: true`, `rev +1`)** | PASS | PASS | PASS | VERIFICADO |
| **Intento de resurrección (`isDeleted: true -> false`)** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Borrado físico (`deleteDoc`) de doc activo legacy** | PASS | PASS | REJECT | VERIFICADO |
| **Borrado físico (`deleteDoc`) de doc C4 o Tombstone** | PASS *(Vulnerabilidad)* | REJECT | REJECT | VERIFICADO |
| **Upgrade de doc legacy a C4 `rev: 1`** | N/A | PASS | REJECT | VERIFICADO |
| **Foto legacy con guiones bajos (`mi_cerveza.jpg`)** | PASS | PASS | REJECT | VERIFICADO |
| **Foto moderna versionada (`syncId_mutId.jpg`)** | PASS | PASS | PASS | VERIFICADO |
| **Sobreescritura de foto versionada** | PASS *(Vulnerabilidad)* | REJECT *(Inmutable)* | REJECT *(Inmutable)* | VERIFICADO |
| **Borrado de foto versionada** | PASS *(Vulnerabilidad)* | REJECT *(Inmutable)* | REJECT *(Inmutable)* | VERIFICADO |
| **Lectura de foto por amigo (`isFriend` en Firestore)** | PASS | PASS | PASS | VERIFICADO |

---

## 8. RESULTADOS DE LA VALIDACIÓN AUTOMATIZADA

### 8.1 Suite de Emuladores Firebase
- **Comando exacto ejecutado desde la raíz del repositorio:**
  ```powershell
  firebase emulators:exec --only firestore,storage --project demo-contador-c43 "npm test"
  ```
- **Directorio de ejecución:** `c:\Users\Daniel\.gemini\antigravity\scratch\beer-hunter\tests\firebase-rules`
- **Resultados:**
  - **Total de tests:** 68
  - **Tests nuevos:** 13 (integridad profunda de `fieldRevisions`, rechazo de tipos inválidos en `isDeleted`, rechazo de `deleteField()`, prohibición de reuso de `lastMutationId`, fotos legacy con guiones bajos, validación de colecciones auxiliares).
  - **Passed:** 68
  - **Failed:** 0
  - **Skipped:** 0
  - **Tiempo de ejecución:** 16 segundos

### 8.2 Pruebas Unitarias JVM
- **Comando:** `.\gradlew testDebugUnitTest --rerun-tasks`
- **Resultados:** 344/344 passing (100% éxito)

### 8.3 Compilación Android
- **Comando:** `.\gradlew assembleDebug`
- **Resultados:** BUILD SUCCESSFUL

### 8.4 Comprobación de Diff
- **Comando:** `git diff --check`
- **Resultados:** Salida limpia (código 0)

---

## 9. LIMITACIONES PENDIENTES Y HOJA DE RUTA PARA C4.4

La presente Fase C4.3 sella la barrera perimetral de seguridad. Quedan explícitamente delimitadas las responsabilidades que deberán validarse en la Fase C4.4:

1. **Adopción de Nomenclatura en la App:**
   El cliente Android (en `BeerSyncEngine` / `BeerRepository`) debe evolucionar para nombrar las fotografías versionadas conforme a la convención inequívoca:
   `users/{userId}/beers/{syncId}_{mutationId}.jpg`.
2. **Serialización Canónica de `fieldRevisions`:**
   Garantizar que el mapper de Firestore en Android serialice las claves de `fieldRevisions` como cadenas mayúsculas deterministas (`TYPE`, `TIMESTAMP`, `LOCATION`, `COMMENT`, `PHOTO`).
3. **Gestión de Respuestas `PERMISSION_DENIED`:**
   El coordinador C4.4 debe tratar cualquier `PERMISSION_DENIED` por salto de revisión o tombstone como un indicador determinista de conflicto remoto, activando inmediatamente el flujo de PULL y re-planificación con `SyncProtocolPullPlanner`.
4. **Cola Durable de Mutaciones:**
   Asegurar que toda mutación local se escriba en Room con su `lastMutationId` antes de emitir cualquier petición de red hacia Firestore o Storage.
