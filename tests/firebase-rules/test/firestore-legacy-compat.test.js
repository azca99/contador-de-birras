const { doc, getDoc, setDoc, updateDoc, deleteDoc, deleteField } = require('firebase/firestore');
const {
  createTestEnv,
  seedDocument,
  createModernBeerPayload,
  createLegacyBeerPayload,
  assertFails,
  assertSucceeds,
} = require('./helpers');

describe('Profile B: FIRESTORE LEGACY COMPATIBILITY RULES', () => {
  let testEnv;

  before(async () => {
    testEnv = await createTestEnv('FIRESTORE_LEGACY_COMPAT');
  });

  after(async () => {
    if (testEnv) {
      await testEnv.cleanup();
    }
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  describe('Legacy Client Operations (Unversioned Documents)', () => {
    it('allows legacy client to create beer without revision', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-leg-1');

      await assertSucceeds(setDoc(beerRef, createLegacyBeerPayload('alice')));
    });

    it('allows legacy client to update beer without revision', async () => {
      await seedDocument(testEnv, 'beers/beer-leg-2', createLegacyBeerPayload('alice'));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-leg-2');

      await assertSucceeds(updateDoc(beerRef, {
        comment: 'Actualizacion legacy sin revision',
        type: 'BOTELLIN',
      }));
    });

    it('allows legacy client to physically hard delete an unversioned legacy beer', async () => {
      await seedDocument(testEnv, 'beers/beer-leg-3', createLegacyBeerPayload('alice'));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-leg-3');

      // Hard delete of legacy document is permitted for backward compatibility
      await assertSucceeds(deleteDoc(beerRef));
    });
  });

  describe('Modern Client Operations in Compatibility Mode', () => {
    it('allows modern client to create beer starting at revision 1 with authentic schema', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mod-1');

      await assertSucceeds(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
        lastMutationId: 'mut-mod-1',
        type: 'JARRA',
        timestamp: 1729000000000,
        comment: 'Cerveza moderna en compat',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      })));
    });

    it('enforces monotonic revision update (+1) and fieldRevisions consistency on modern documents', async () => {
      await seedDocument(testEnv, 'beers/beer-mod-2', createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-mod-1',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mod-2');

      // Monotonic +1 with consistent fieldRevisions succeeds
      await assertSucceeds(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-mod-2',
        comment: 'Avanzado a rev 2',
        'fieldRevisions.COMMENT': 2,
      }));

      // Non-monotonic jump (+2) fails
      await assertFails(updateDoc(beerRef, {
        revision: 4,
        lastMutationId: 'mut-mod-4',
        comment: 'Salto invalido',
        'fieldRevisions.COMMENT': 4,
      }));
    });

    it('STRICTLY REJECTS hard delete on modern documents (protects tombstones)', async () => {
      await seedDocument(testEnv, 'beers/beer-mod-3', createModernBeerPayload('alice', {
        revision: 2,
        isDeleted: false,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mod-3');

      // Hard delete must fail because document contains 'revision'
      await assertFails(deleteDoc(beerRef));
    });

    it('STRICTLY REJECTS hard delete on modern tombstone documents', async () => {
      await seedDocument(testEnv, 'beers/beer-mod-tomb', createModernBeerPayload('alice', {
        revision: 3,
        isDeleted: true,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mod-tomb');

      await assertFails(deleteDoc(beerRef));
    });
  });

  describe('Protection Against Protocol Downgrades', () => {
    it('rejects attempt to downgrade a modern document by stripping revision', async () => {
      await seedDocument(testEnv, 'beers/beer-modern-target', createModernBeerPayload('alice', {
        revision: 4,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-modern-target');

      // Legacy blind .set() without revision must fail
      await assertFails(setDoc(beerRef, createLegacyBeerPayload('alice')));
    });
  });

  describe('Upgrade Path (Legacy -> Modern Protocol)', () => {
    it('allows cleanly upgrading a legacy unversioned beer to modern revision 1', async () => {
      await seedDocument(testEnv, 'beers/beer-to-upgrade', createLegacyBeerPayload('alice'));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-to-upgrade');

      // Upgrade to modern protocol revision 1
      await assertSucceeds(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
        lastMutationId: 'mut-upgrade-1',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      })));

      // Once upgraded, hard delete is blocked
      await assertFails(deleteDoc(beerRef));

      // Subsequent update must be monotonic revision 2 with distinct mutationId
      await assertSucceeds(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-upgrade-2',
        comment: 'Upgraded & Refreshed',
        'fieldRevisions.COMMENT': 2,
      }));
    });

    it('rejects upgrading legacy beer with empty, incomplete, or invalid fieldRevisions map', async () => {
      await seedDocument(testEnv, 'beers/beer-upgrade-inv', createLegacyBeerPayload('alice'));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-upgrade-inv');

      // Empty map
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-upg-empty',
        fieldRevisions: {},
      })));

      // Incomplete map
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-upg-incomp',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1 },
      })));

      // Unknown key
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-upg-unknown',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1, BRAND: 1 },
      })));

      // Initial revision != 1
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-upg-rev2',
        fieldRevisions: { TYPE: 2, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      })));
    });

    it('enforces causal fieldRevisions key preservation and anti-fictitious advance on modern document', async () => {
      await seedDocument(testEnv, 'beers/beer-compat-fr', createModernBeerPayload('alice', {
        revision: 2,
        lastMutationId: 'mut-compat-init',
        comment: 'Comentario previo',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-compat-fr');

      // Key deletion via deleteField() is strictly rejected
      await assertFails(updateDoc(beerRef, {
        revision: 3,
        lastMutationId: 'mut-compat-del-key',
        'fieldRevisions.TYPE': deleteField(),
      }));

      // Fictitious revision advance without changing comment is strictly rejected
      await assertFails(updateDoc(beerRef, {
        revision: 3,
        lastMutationId: 'mut-compat-fictitious',
        'fieldRevisions.COMMENT': 3,
      }));

      // Real update of comment with corresponding fieldRevision succeeds
      await assertSucceeds(updateDoc(beerRef, {
        revision: 3,
        lastMutationId: 'mut-compat-real-change',
        comment: 'Comentario nuevo',
        'fieldRevisions.COMMENT': 3,
      }));
    });
  });

  describe('Anti-Resurrection Protection on Modern Documents', () => {
    it('rejects resurrection of a modern tombstone in compatibility mode', async () => {
      await seedDocument(testEnv, 'beers/beer-tomb-compat', createModernBeerPayload('alice', {
        revision: 2,
        lastMutationId: 'mut-tomb-compat',
        isDeleted: true,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-tomb-compat');

      // Attempt to resurrect
      await assertFails(updateDoc(beerRef, {
        revision: 3,
        isDeleted: false,
        lastMutationId: 'mut-resurrect',
        comment: 'Zombie',
        'fieldRevisions.COMMENT': 3,
      }));
    });
  });
});
