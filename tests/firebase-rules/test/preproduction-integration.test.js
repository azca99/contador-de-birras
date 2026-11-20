const {
  createTestEnv,
  seedDocument,
  createModernBeerPayload,
  createLegacyBeerPayload,
  createProtocolLockPayload,
  createDummyImageBuffer,
  assertFails,
  assertSucceeds,
} = require('./helpers');
const { doc, getDoc, setDoc, updateDoc, deleteDoc, collection, query, where, getDocs } = require('firebase/firestore');
const { ref, uploadBytes, getBytes } = require('firebase/storage');
const assert = require('assert');

/**
 * Suite de Integración Real de Preproducción: Migración Legacy -> C4 en Entorno Aislado.
 *
 * Ejecuta sobre Firebase Emulator REAL (Auth, Firestore y Storage) con las reglas candidatas endurecidas:
 * 1. Migración de cuenta Legacy preexistente:
 *    - Documento legacy sincronizado (rev 0 / sin rev) con foto legacy.
 *    - Documento legacy borrado (tombstone).
 *    - Documento nuevo offline con foto versionada.
 * 2. Bloqueo de protocolo C4 en /users/{uid} (syncProtocolVersion: 'C4', minSupportedAppVersionCode: 3, c4MigratedAt).
 * 3. Rechazo estricto de escrituras legacy post-corte (create, update y hard-delete denegados por reglas).
 * 4. Idempotencia y reanudación segura de la migración.
 * 5. Recuperación limpia desde un nuevo cliente (Client B) sin datos locales previos.
 * 6. Coexistencia aislada con cuentas Legacy no migradas.
 */
describe('Preproduction Real Emulator Integration: Legacy -> C4 Cutover', () => {
  let testEnv;
  const aliceUid = 'alice_preprod_c4';
  const bobUid = 'bob_legacy_unmigrated';

  before(async () => {
    testEnv = await createTestEnv('FIRESTORE_CANDIDATE', 'STORAGE_CANDIDATE');
  });

  after(async () => {
    await testEnv.cleanup();
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
    await testEnv.clearStorage();
  });

  it('1. Recorrido completo: Migración de datos legacy existentes, bloqueo C4 y recuperación desde cliente nuevo', async () => {
    const aliceContext = testEnv.authenticatedContext(aliceUid);
    const aliceDb = aliceContext.firestore();
    const aliceStorage = aliceContext.storage();

    const beerSyncedId = 'sync-legacy-synced-101';
    const beerDeletedId = 'sync-legacy-deleted-102';
    const beerOfflineId = 'sync-c4-offline-103';

    // -------------------------------------------------------------------------
    // PASO A: Sembrar estado previo auténtico de la instalación Legacy
    // -------------------------------------------------------------------------
    // 1. Foto legacy en Storage: users/{uid}/beers/{syncId}.jpg
    const legacyPhotoPath = `users/${aliceUid}/beers/${beerSyncedId}.jpg`;
    await testEnv.withSecurityRulesDisabled(async (adminContext) => {
      const adminStorage = adminContext.storage();
      const photoRef = ref(adminStorage, legacyPhotoPath);
      await uploadBytes(photoRef, createDummyImageBuffer(512), { contentType: 'image/jpeg' });
    });

    // 2. Documento legacy sincronizado en Firestore (sin campos C4)
    const legacyDocPayload = createLegacyBeerPayload(aliceUid, {
      comment: 'Cerveza legacy en bar tradicional',
      remotePhotoUrl: legacyPhotoPath,
      locationName: 'Taberna El Abuelo',
    });
    await seedDocument(testEnv, `beers/${beerSyncedId}`, legacyDocPayload);

    // 3. Documento legacy previo a borrar
    const legacyToDeletePayload = createLegacyBeerPayload(aliceUid, {
      comment: 'Cerveza que será borrada durante migración',
    });
    await seedDocument(testEnv, `beers/${beerDeletedId}`, legacyToDeletePayload);

    // -------------------------------------------------------------------------
    // PASO B: Ejecutar la secuencia de modernización CAS (Cutover)
    // -------------------------------------------------------------------------
    // 1. Ascenso CAS de beerSyncedId a revisión 1
    const upgradedBeerRef = doc(aliceDb, `beers/${beerSyncedId}`);
    await assertSucceeds(
      updateDoc(upgradedBeerRef, {
        revision: 1,
        isDeleted: false,
        lastMutationId: 'mut-upg-101',
        fieldRevisions: {
          TYPE: 1,
          TIMESTAMP: 1,
          LOCATION: 1,
          COMMENT: 1,
          PHOTO: 1,
        },
        updatedAt: Date.now(),
      })
    );

    // 2. Subir fotografía versionada de la cerveza offline a Storage
    const offlinePhotoPath = `users/${aliceUid}/beers/${beerOfflineId}_mut-create-103.jpg`;
    const offlinePhotoRef = ref(aliceStorage, offlinePhotoPath);
    await assertSucceeds(
      uploadBytes(offlinePhotoRef, createDummyImageBuffer(1024), { contentType: 'image/jpeg' })
    );

    // 3. Crear cerveza offline con CreateActive en revisión 1
    const offlineBeerRef = doc(aliceDb, `beers/${beerOfflineId}`);
    await assertSucceeds(
      setDoc(
        offlineBeerRef,
        createModernBeerPayload(aliceUid, {
          syncId: beerOfflineId,
          comment: 'Cerveza artesanal creada sin conexión',
          locationName: 'Microcervecería Local',
          remotePhotoUrl: offlinePhotoPath,
          revision: 1,
          lastMutationId: 'mut-create-103',
        })
      )
    );

    // 4. Modernización de beerDeletedId:
    // Paso A: Ascenso CAS inicial a rev 1 (isDeleted: false)
    const tombstoneBeerRef = doc(aliceDb, `beers/${beerDeletedId}`);
    await assertSucceeds(
      updateDoc(tombstoneBeerRef, {
        revision: 1,
        isDeleted: false,
        lastMutationId: 'mut-upg-102',
        fieldRevisions: {
          TYPE: 1,
          TIMESTAMP: 1,
          LOCATION: 1,
          COMMENT: 1,
          PHOTO: 1,
        },
        updatedAt: Date.now(),
      })
    );

    // Paso B: Escritura de tombstone moderno a rev 2 (isDeleted: true)
    await assertSucceeds(
      updateDoc(tombstoneBeerRef, {
        revision: 2,
        isDeleted: true,
        lastMutationId: 'mut-tomb-102',
        fieldRevisions: {
          TYPE: 1,
          TIMESTAMP: 1,
          LOCATION: 1,
          COMMENT: 1,
          PHOTO: 1,
        },
        updatedAt: Date.now(),
      })
    );

    // 5. Escribir el bloqueo remoto de protocolo C4 en /users/{uid}
    const userDocRef = doc(aliceDb, `users/${aliceUid}`);
    const protocolLock = createProtocolLockPayload({
      c4MigratedAt: Date.now(),
    });
    await assertSucceeds(setDoc(userDocRef, protocolLock, { merge: true }));

    // -------------------------------------------------------------------------
    // PASO C: Verificar el bloqueo de protocolo remoto persistente
    // -------------------------------------------------------------------------
    const userSnap = await getDoc(userDocRef);
    assert.strictEqual(userSnap.exists(), true);
    assert.strictEqual(userSnap.data().syncProtocolVersion, 'C4');
    assert.strictEqual(userSnap.data().minSupportedAppVersionCode, 3);
    assert.strictEqual(userSnap.data().accountSyncState, 'MIGRATED_TO_C4');
    assert.ok(userSnap.data().c4MigratedAt > 0);

    // -------------------------------------------------------------------------
    // PASO D: Comprobar rechazo estricto de cualquier intento de escritura Legacy post-corte
    // -------------------------------------------------------------------------
    // Intento 1: Crear documento legacy (sin revision) bajo cuenta bloqueada en C4
    const rogueLegacyCreateRef = doc(aliceDb, `beers/sync-rogue-legacy-new`);
    await assertFails(
      setDoc(rogueLegacyCreateRef, {
        userId: aliceUid,
        type: 'CANA',
        timestamp: Date.now(),
        comment: 'Intento de creación legacy en cuenta C4',
        updatedAt: Date.now(),
      })
    );

    // Intento 2: Actualizar documento sin revision (isLegacyUpdate) bajo cuenta bloqueada en C4
    await assertFails(
      updateDoc(upgradedBeerRef, {
        comment: 'Modificación legacy no autorizada',
        updatedAt: Date.now(),
      })
    );

    // Intento 3: Hard delete bajo cuenta bloqueada en C4
    await assertFails(deleteDoc(upgradedBeerRef));

    // -------------------------------------------------------------------------
    // PASO E: Recuperación limpia desde un nuevo cliente (Client B)
    // -------------------------------------------------------------------------
    // Simulamos un segundo cliente autenticado como alice pero iniciando desde cero
    const clientBContext = testEnv.authenticatedContext(aliceUid);
    const clientBDb = clientBContext.firestore();
    const clientBStorage = clientBContext.storage();

    // Consulta de colección descubriendo todos los documentos del usuario
    const beersQuery = query(collection(clientBDb, 'beers'), where('userId', '==', aliceUid));
    const querySnapshot = await assertSucceeds(getDocs(beersQuery));

    const retrievedDocs = {};
    querySnapshot.forEach((d) => {
      retrievedDocs[d.id] = d.data();
    });

    // 1. Verificación del total exacto de documentos
    assert.strictEqual(Object.keys(retrievedDocs).length, 3);

    // 2. Cerveza 1 recuperada íntegra con metadatos y foto legacy
    const retrieved1 = retrievedDocs[beerSyncedId];
    assert.ok(retrieved1);
    assert.strictEqual(retrieved1.revision, 1);
    assert.strictEqual(retrieved1.isDeleted, false);
    assert.strictEqual(retrieved1.comment, 'Cerveza legacy en bar tradicional');
    assert.strictEqual(retrieved1.locationName, 'Taberna El Abuelo');
    assert.strictEqual(retrieved1.remotePhotoUrl, legacyPhotoPath);

    // Descargar foto legacy desde Storage para validar accesibilidad
    const downloadedLegacyPhoto = await assertSucceeds(
      getBytes(ref(clientBStorage, retrieved1.remotePhotoUrl))
    );
    assert.strictEqual(downloadedLegacyPhoto.byteLength, 512);

    // 3. Cerveza offline recuperada íntegra con foto versionada
    const retrieved3 = retrievedDocs[beerOfflineId];
    assert.ok(retrieved3);
    assert.strictEqual(retrieved3.revision, 1);
    assert.strictEqual(retrieved3.isDeleted, false);
    assert.strictEqual(retrieved3.comment, 'Cerveza artesanal creada sin conexión');
    assert.strictEqual(retrieved3.remotePhotoUrl, offlinePhotoPath);

    // Descargar foto versionada desde Storage
    const downloadedVersionedPhoto = await assertSucceeds(
      getBytes(ref(clientBStorage, retrieved3.remotePhotoUrl))
    );
    assert.strictEqual(downloadedVersionedPhoto.byteLength, 1024);

    // 4. Tombstone recuperado con isDeleted: true (no resucitado)
    const retrieved2 = retrievedDocs[beerDeletedId];
    assert.ok(retrieved2);
    assert.strictEqual(retrieved2.revision, 2);
    assert.strictEqual(retrieved2.isDeleted, true);
  });

  it('2. Idempotencia: La reejecución de corte y bloqueo no modifica ni corrompe el estado', async () => {
    const aliceContext = testEnv.authenticatedContext(aliceUid);
    const aliceDb = aliceContext.firestore();

    const userDocRef = doc(aliceDb, `users/${aliceUid}`);
    const originalTimestamp = 1729000000000;
    const initialLock = createProtocolLockPayload({
      c4MigratedAt: originalTimestamp,
    });
    await assertSucceeds(setDoc(userDocRef, initialLock, { merge: true }));

    // Reejecución con setDoc merge
    const secondLock = createProtocolLockPayload({
      c4MigratedAt: originalTimestamp,
    });
    await assertSucceeds(setDoc(userDocRef, secondLock, { merge: true }));

    const userSnap = await getDoc(userDocRef);
    assert.strictEqual(userSnap.data().syncProtocolVersion, 'C4');
    assert.strictEqual(userSnap.data().minSupportedAppVersionCode, 3);
    assert.strictEqual(userSnap.data().c4MigratedAt, originalTimestamp);
  });

  it('3. Coexistencia: Cuenta Bob no migrada conserva operativa legacy sin interferencias', async () => {
    const bobContext = testEnv.authenticatedContext(bobUid);
    const bobDb = bobContext.firestore();

    const bobBeerId = 'sync-bob-legacy-1';
    const bobBeerRef = doc(bobDb, `beers/${bobBeerId}`);

    // Bob puede crear cervezas en formato legacy porque su cuenta NO tiene bloqueo C4
    await assertSucceeds(
      setDoc(bobBeerRef, createLegacyBeerPayload(bobUid, {
        comment: 'Cerveza legacy de Bob',
      }))
    );

    // Bob puede actualizar en formato legacy
    await assertSucceeds(
      updateDoc(bobBeerRef, {
        comment: 'Cerveza legacy de Bob actualizada',
        updatedAt: Date.now(),
      })
    );

    // Sembrar documento privado de Alice
    await seedDocument(testEnv, `beers/sync-alice-private-1`, createModernBeerPayload(aliceUid, {
      comment: 'Cerveza privada de Alice',
    }));

    // Bob no puede acceder a las cervezas de Alice
    const aliceDocRef = doc(bobDb, `beers/sync-alice-private-1`);
    await assertFails(getDoc(aliceDocRef));
  });
});
