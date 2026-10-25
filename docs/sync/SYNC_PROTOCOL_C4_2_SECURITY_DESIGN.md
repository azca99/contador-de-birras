# Diseño de Seguridad y Despliegue Preventivo del Protocolo Multidispositivo (Fase C4.2)

**Documento:** `docs/sync/SYNC_PROTOCOL_C4_2_SECURITY_DESIGN.md`  
**Estado:** DOCUMENTACIÓN TÉCNICA OFFLINE (NO DESPLEGADA)  
**Fecha:** 25 de Octubre de 2026  
**Repositorio:** `azca99/contador-de-birras`  

---

## 1. Alcance y Advertencia Preventiva

Este documento recoge la arquitectura técnica y propuestas de reglas de seguridad de Firebase Firestore y Cloud Storage necesarias para dar soporte al protocolo de sincronización multidispositivo con control de concurrencia optimista (OCC), revisiones monotónicas y protección estricta de tombstones.

> [!CAUTION] Reglas No Desplegadas
> Ninguna de las reglas descritas en este documento está desplegada en producción. Los archivos `firestore.rules` y `storage.rules` de la raíz del repositorio se mantienen intactos. Las reglas propuestas no deben desplegarse hasta completar las fases C4.3, C4.4 y validar exhaustivamente la compatibilidad con clientes legacy en emuladores oficiales de Firebase.

---

## 2. Análisis del Estado Actual en Producción

### 2.1 Reglas Firestore Vigentes
En `firestore.rules`, la colección `/beers/{beerId}` (donde `beerId` corresponde al `syncId` de la entidad) se encuentra protegida únicamente por comprobación de ownership:

```javascript
match /beers/{beerId} {
  function isBeerOwner() {
    return isSignedIn() && request.auth.uid == resource.data.userId;
  }
  
  allow read: if isBeerOwner();
  allow create: if isSignedIn() && request.auth.uid == request.resource.data.userId;
  allow update: if isBeerOwner() && request.resource.data.userId == resource.data.userId;
  allow delete: if isBeerOwner();
}
```

### 2.2 Vulnerabilidades Identificadas
1. **Borrado Físico (Hard Delete) Incondicional:**  
   `allow delete: if isBeerOwner();` autoriza la eliminación física (`delete()`) de cualquier documento. Si un cliente elimina físicamente una birra, otros dispositivos sincronizados pierden la referencia histórica y no pueden distinguir entre una entidad nunca creada y una eliminada intencionalmente, posibilitando resurrecciones zombi o pérdida de datos.
2. **Sobrescritura Ciega sin Control de Concurrencia:**  
   `allow update` permite modificar el documento sin verificar la revisión causal ni campos individuales. Si dos dispositivos modifican campos ortogonales o concurrentes, el último en escribir sobrescribe ciegamente los cambios del primero.
3. **Inexistencia de Metadata de Protocolo:**  
   No se valida la presencia ni la integridad de `revision`, `fieldRevisions`, `lastMutationId` ni `isDeleted`.

---

## 3. Propuesta de Reglas de Seguridad para Firestore (Offline)

### 3.1 Estructura del Documento Moderno
Un documento moderno gestionado por el protocolo multidispositivo debe contener:
- `userId` (string, inmutable): UID del usuario autenticado propietario.
- `revision` (int >= 1, monotónica creciente): Número de revisión del documento.
- `isDeleted` (bool): `false` para documentos activos, `true` para tombstones.
- `lastMutationId` (string, no vacío): UUID de la mutación que aplicó el último cambio.
- `fieldRevisions` (map): Mapa `campo -> revision` que acredita cuándo se actualizó cada propiedad.
- Campos de datos (`type`, `timestamp`, `comment`, `photoUrl`, `latitude`, `longitude`, etc.).

### 3.2 Reglas Firestore Propuestas
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    
    function isSignedIn() {
      return request.auth != null;
    }
    
    function isOwner(uid) {
      return isSignedIn() && request.auth.uid == uid;
    }
    
    match /beers/{beerId} {
      function isBeerOwner() {
        return isOwner(resource.data.userId);
      }
      
      function hasValidModernMetadata() {
        let d = request.resource.data;
        return d.revision is int && d.revision >= 1
            && d.isDeleted is bool
            && d.lastMutationId is string && d.lastMutationId.size() > 0
            && d.fieldRevisions is map;
      }

      // Lectura restringida al propietario
      allow read: if isBeerOwner();

      // Creación: Primera revisión obligatoria (revision == 1)
      allow create: if isSignedIn()
                    && request.auth.uid == request.resource.data.userId
                    && request.resource.data.revision == 1
                    && request.resource.data.isDeleted == false
                    && hasValidModernMetadata();

      // Actualización: Monotonía estricta e inmutabilidad de propietario
      allow update: if isBeerOwner()
                    && request.resource.data.userId == resource.data.userId
                    && request.resource.data.revision == resource.data.revision + 1
                    && hasValidModernMetadata()
                    // Protección de tombstones: un tombstone no puede ser revertido a activo sin protocolo
                    && (!resource.data.isDeleted || request.resource.data.isDeleted);

      // Borrado físico: Terminantemente PROHIBIDO para todos los documentos modernos
      allow delete: if false;
    }
  }
}
```

---

## 4. Estrategia de Compatibilidad y Plan de Despliegue Gradual

### 4.1 El Peligro de Clientes Legacy con `.set(map)`
Los clientes antiguos de *Contador de Birras* utilizan llamadas `.set(map)` enviando únicamente los campos de la entidad (`userId`, `type`, `timestamp`, etc.) **sin** `revision`, `lastMutationId` ni `fieldRevisions`.
Si las reglas modernas se desplegaran de forma abrupta:
1. Cualquier escritura de un cliente antiguo fallaría con `PERMISSION_DENIED`, provocando que el usuario perciba la app rota.
2. Si un cliente antiguo hiciera un `.set()` permisivo sobre un documento moderno previamente actualizado a revisión 10, **borraría los campos de protocolo**, reseteando el documento a un estado legacy sin causalidad.

### 4.2 Fases de Despliegue Gradual

#### Fase 1: Ventana de Compatibilidad Controlada
Permite que clientes legacy sigan operando sobre sus documentos antiguos, pero impide que degraden documentos modernos:
```javascript
allow update: if isBeerOwner() && (
  // Ruta moderna: Documento moderno avanza monotónicamente e inmutable en userId
  (resource.data.get('revision', 0) >= 1 &&
   request.resource.data.userId == resource.data.userId &&
   request.resource.data.get('revision', 0) == resource.data.revision + 1 &&
   hasValidModernMetadata())
  ||
  // Ruta legacy temporal: Solo permitida si el documento remoto NUNCA ha tenido revisión moderna
  (resource.data.get('revision', 0) == 0 &&
   !('revision' in request.resource.data) &&
   request.resource.data.userId == resource.data.userId)
);

// Borrado físico solo tolerado temporalmente si el documento es legacy (revision == 0)
allow delete: if isBeerOwner() && resource.data.get('revision', 0) == 0;
```

#### Fase 2: Distribución de la Versión con Nuevo Protocolo
Se publica la actualización de la aplicación en producción con el motor C4 completamente integrado. Durante esta fase se monitoriza la tasa de adopción de la nueva versión.

#### Fase 3: Endurecimiento Final
Una vez que el porcentaje de clientes legacy activos esté por debajo del umbral de retiro, se activa la prohibición universal de borrado (`allow delete: if false;`) y se exige metadata moderna en el 100% de las operaciones.

---

## 5. Diseño Preventivo para Cloud Storage (Offline)

### 5.1 Situación Actual y Vulnerabilidades
En `storage.rules`, la ruta actual de subida fotográfica es mutable:
`users/{userId}/beers/{fileName}`
Donde `fileName` tradicionalmente corresponde a `{syncId}.jpg`.
- **Riesgo:** Cada nueva toma fotográfica sobreescribe el archivo `{syncId}.jpg`. Si dos dispositivos capturan fotos concurrentemente, la última destruye la foto del otro cliente sin posibilidad de reconciliación.

### 5.2 Propuesta de Rutas Inmutables Versionadas
La ruta de almacenamiento debe evolucionar a:
`users/{userId}/beers/{syncId}_{mutationId}.jpg`

### 5.3 Reglas Storage Propuestas y Clarificación de Compatibilidad Legacy
> [!WARNING]
> **Compatibilidad de Storage vs. Clientes Legacy:**
> Una regla estricta de `allow update: if false;` garantiza la inmutabilidad total de las fotografías versionadas (`{syncId}_{mutationId}.jpg`).
> Sin embargo, **no debe prometerse compatibilidad transparente con actualizaciones fotográficas de clientes antiguos**: si un cliente antiguo intenta sobreescribir la foto de una cerveza existente en `users/{userId}/beers/{syncId}.jpg`, dicha actualización fallará con `PERMISSION_DENIED`.
>
> Durante una ventana de compatibilidad transitoria, existen dos opciones de arquitectura:
> 1. **Endurecimiento intencional (Recomendado):** Bloquear actualizaciones en Storage (`allow update: if false;`), aceptando que clientes antiguos solo pueden subir fotos al crear cervezas, debiendo actualizar a la versión moderna para editar fotos de forma segura.
> 2. **Ruta condicional transitoria:** Permitir temporalmente `allow update` únicamente sobre nombres de archivo legacy sin patrón de mutación (`!fileName.matches('.*_mut_.*')`), retirando esta excepción en la Fase 3.

```javascript
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /users/{userId}/beers/{fileName} {
      allow read: if request.auth != null && (request.auth.uid == userId || isFriend());
      
      // Creación con validación de tamaño (< 5MB) y tipo MIME
      allow create: if request.auth != null 
                    && request.auth.uid == userId
                    && request.resource.size < 5 * 1024 * 1024
                    && request.resource.contentType.matches('image/.*');

      // Inmutabilidad: Una fotografía versionada jamás se sobreescribe
      // (Nota: Impide actualizaciones in-place de clientes antiguos)
      allow update: if false;

      // Borrado físico aplazado a política de purga formal
      allow delete: if false;
    }
  }
}
```

---

## 6. Procedimiento para Validación con Emuladores Oficiales Firebase

Antes de activar el protocolo en producción o modificar las reglas en vivo, se deberán ejecutar los siguientes pasos en un entorno controlado:

1. **Instalación de Firebase CLI y Java JDK:**
   `npm install -g firebase-tools`
2. **Configuración de `firebase.json` con emuladores:**
   Habilitar `firestore` y `storage` en puertos locales (ej. 8080 y 9199).
3. **Suite de Tests de Reglas (`@firebase/rules-unit-testing`):**
   Crear una suite de pruebas automatizadas en TypeScript/JavaScript que pruebe:
   - Escritura válida moderna (PASS).
   - Intento de escritura sin avance monotónico (REJECT).
   - Intento de sobreescritura de foto existente (REJECT).
   - Intento de borrado físico de documento moderno (REJECT).
   - Simulación de cliente legacy con `.set()` sobre documento moderno (REJECT).
   - Simulación de cliente legacy sobre documento legacy (PASS durante Fase 1).

---

## 7. Limitaciones Explícitas del Simulador C4.2

1. **`FakeFirestore` y `FakeStorage` son simuladores en memoria JVM:**
   No evalúan el motor de reglas CEL de Google Cloud ni simulan la latencia o réplica geográfica multirregión de Firestore.
2. **Precondición Simulada de `FieldOriginEvidence`:**
   En los tests del banco multidispositivo, las instancias de `FieldOriginEvidence` son inyectadas por el arnés de pruebas para modelar el comportamiento del planificador de resolución de conflictos C3B ante evidencia conocida. **No constituyen evidencia extraída automáticamente por el SDK de Firebase**. La extracción y certificación causal real se implementará en C4.3 y C4.4.
3. **Migración Room v9→v10:**
   La persistencia real de Room con la nueva tabla `local_mutation_history` permanece pendiente de validación en pruebas instrumentadas de Android en dispositivos reales o emuladores de sistema antes de su liberación.
