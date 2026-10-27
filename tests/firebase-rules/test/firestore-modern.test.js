const { doc, getDoc, setDoc, updateDoc, deleteDoc } = require('firebase/firestore');
const {
  createTestEnv,
  seedDocument,
  createModernBeerPayload,
  createLegacyBeerPayload,
  assertFails,
  assertSucceeds,
} = require('./helpers');

describe('Profile C: FIRESTORE MODERN STRICT PROTOCOL', () => {
  let testEnv;

  before(async () => {
    testEnv = await createTestEnv('FIRESTORE_MODERN_STRICT');
  });

  after(async () => {
    if (testEnv) {
      await testEnv.cleanup();
    }
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  describe('Identity, Ownership & Scope Boundaries', () => {
    it('allows authenticated owner to read own beer', async () => {
      await seedDocument(testEnv, 'beers/beer-1', createModernBeerPayload('alice'));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-1');

      const snapshot = await assertSucceeds(getDoc(beerRef));
      if (!snapshot.exists()) {
        throw new Error('Expected document to exist');
      }
    });

    it('rejects unauthenticated user from reading or writing', async () => {
      await seedDocument(testEnv, 'beers/beer-1', createModernBeerPayload('alice'));

      const unauthDb = testEnv.unauthenticatedContext().firestore();
      const beerRef = doc(unauthDb, 'beers/beer-1');

      await assertFails(getDoc(beerRef));
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', { revision: 1 })));
    });

    it('rejects foreign user from reading or modifying another user beer', async () => {
      await seedDocument(testEnv, 'beers/beer-1', createModernBeerPayload('alice'));

      const bobDb = testEnv.authenticatedContext('bob').firestore();
      const beerRef = doc(bobDb, 'beers/beer-1');

      await assertFails(getDoc(beerRef));
      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'bob-mut',
        name: 'Hacked',
      }));
      await assertFails(deleteDoc(beerRef));
    });

    it('rejects owner mutation that alters document ownership (immutable userId)', async () => {
      await seedDocument(testEnv, 'beers/beer-1', createModernBeerPayload('alice', {
        revision: 1,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-1');

      // Attempt to transfer document to bob
      await assertFails(updateDoc(beerRef, {
        userId: 'bob',
        revision: 2,
        lastMutationId: 'transfer-mut',
      }));
    });
  });

  describe('CreateActive Validation', () => {
    it('allows valid CreateActive starting strictly at revision 1', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-valid-1');

      await assertSucceeds(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
        lastMutationId: 'mut-init-1',
        fieldRevisions: { name: 1, brand: 1 },
      })));
    });

    it('rejects create with revision != 1 (e.g. revision: 0 or revision: 5)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();

      const beerRef0 = doc(aliceDb, 'beers/beer-inv-0');
      await assertFails(setDoc(beerRef0, createModernBeerPayload('alice', { revision: 0 })));

      const beerRef5 = doc(aliceDb, 'beers/beer-inv-5');
      await assertFails(setDoc(beerRef5, createModernBeerPayload('alice', { revision: 5 })));
    });

    it('rejects create with isDeleted == true (cannot create already deleted beer)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-deleted-init');

      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: true,
      })));
    });

    it('rejects create without valid lastMutationId', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();

      const beerRefNoMut = doc(aliceDb, 'beers/beer-no-mut');
      const payloadNoMut = createModernBeerPayload('alice');
      delete payloadNoMut.lastMutationId;
      await assertFails(setDoc(beerRefNoMut, payloadNoMut));

      const beerRefEmptyMut = doc(aliceDb, 'beers/beer-empty-mut');
      await assertFails(setDoc(beerRefEmptyMut, createModernBeerPayload('alice', {
        lastMutationId: '',
      })));
    });

    it('rejects create without valid fieldRevisions map', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();

      const beerRefNoFr = doc(aliceDb, 'beers/beer-no-fr');
      const payloadNoFr = createModernBeerPayload('alice');
      delete payloadNoFr.fieldRevisions;
      await assertFails(setDoc(beerRefNoFr, payloadNoFr));

      const beerRefInvalidFr = doc(aliceDb, 'beers/beer-invalid-fr');
      await assertFails(setDoc(beerRefInvalidFr, createModernBeerPayload('alice', {
        fieldRevisions: 'not-a-map',
      })));
    });
  });

  describe('Monotonic Revision Progression (+1 Invariant)', () => {
    beforeEach(async () => {
      await seedDocument(testEnv, 'beers/beer-mono', createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
      }));
    });

    it('allows valid monotonic advance from revision 1 to 2', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mono');

      await assertSucceeds(updateDoc(beerRef, {
        revision: 2,
        name: 'Updated Name',
        lastMutationId: 'mut-mono-2',
        'fieldRevisions.name': 2,
      }));
    });

    it('rejects revision jump (e.g. revision 1 to 3 or 10)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mono');

      await assertFails(updateDoc(beerRef, {
        revision: 3,
        name: 'Jump Name',
        lastMutationId: 'mut-jump-3',
      }));

      await assertFails(updateDoc(beerRef, {
        revision: 10,
        name: 'Big Jump Name',
        lastMutationId: 'mut-jump-10',
      }));
    });

    it('rejects revision regression (e.g. revision 1 to 0 or backwards update)', async () => {
      await seedDocument(testEnv, 'beers/beer-rev5', createModernBeerPayload('alice', {
        revision: 5,
        isDeleted: false,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-rev5');

      await assertFails(updateDoc(beerRef, {
        revision: 4,
        name: 'Regressed Name',
        lastMutationId: 'mut-regress',
      }));
    });

    it('rejects same-revision update without advance (revision N to N)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mono');

      await assertFails(updateDoc(beerRef, {
        revision: 1,
        name: 'Stagnant Name',
        lastMutationId: 'mut-same',
      }));
    });
  });

  describe('Protection Against Legacy Overwrites (.set() blind overwrites)', () => {
    it('rejects legacy .set() overwrite that strips revision or sync metadata', async () => {
      await seedDocument(testEnv, 'beers/beer-seeded', createModernBeerPayload('alice', {
        revision: 5,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-seeded');

      // Attempting to overwrite with a legacy payload lacking revision
      await assertFails(setDoc(beerRef, createLegacyBeerPayload('alice')));
    });

    it('rejects .set() overwrite that attempts arbitrary revision reset', async () => {
      await seedDocument(testEnv, 'beers/beer-seeded', createModernBeerPayload('alice', {
        revision: 5,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-seeded');

      // Attempting to overwrite with revision 1
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
      })));
    });
  });

  describe('Tombstone Lifecycle & Anti-Resurrection Protection', () => {
    it('allows transitioning an active beer to a tombstone with revision +1', async () => {
      await seedDocument(testEnv, 'beers/beer-tomb', createModernBeerPayload('alice', {
        revision: 2,
        isDeleted: false,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-tomb');

      await assertSucceeds(updateDoc(beerRef, {
        revision: 3,
        isDeleted: true,
        lastMutationId: 'mut-tomb-del',
      }));
    });

    it('STRICTLY REJECTS resurrection of a tombstone (isDeleted true -> false)', async () => {
      await seedDocument(testEnv, 'beers/beer-tombstone', createModernBeerPayload('alice', {
        revision: 4,
        isDeleted: true,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-tombstone');

      // Attempt to resurrect by setting isDeleted = false, even with revision +1
      await assertFails(updateDoc(beerRef, {
        revision: 5,
        isDeleted: false,
        lastMutationId: 'mut-resurrect-attempt',
        name: 'Zombie Beer',
      }));
    });

    it('allows metadata updates to continue on a tombstone as long as isDeleted remains true', async () => {
      await seedDocument(testEnv, 'beers/beer-tombstone-refresh', createModernBeerPayload('alice', {
        revision: 4,
        isDeleted: true,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-tombstone-refresh');

      await assertSucceeds(updateDoc(beerRef, {
        revision: 5,
        isDeleted: true,
        lastMutationId: 'mut-tomb-refresh',
      }));
    });
  });

  describe('Prohibition of Physical Hard Deletions', () => {
    it('strictly rejects deleteDoc on active beer documents', async () => {
      await seedDocument(testEnv, 'beers/beer-active', createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-active');

      await assertFails(deleteDoc(beerRef));
    });

    it('strictly rejects deleteDoc on tombstone documents', async () => {
      await seedDocument(testEnv, 'beers/beer-tomb-hard', createModernBeerPayload('alice', {
        revision: 3,
        isDeleted: true,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-tomb-hard');

      await assertFails(deleteDoc(beerRef));
    });
  });
});
