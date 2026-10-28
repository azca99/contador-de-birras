const { doc, getDoc, setDoc, updateDoc, deleteDoc, deleteField } = require('firebase/firestore');
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
        comment: 'Hacked',
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

  describe('CreateActive Validation with Authentic Schema', () => {
    it('allows valid CreateActive starting strictly at revision 1 with real schema', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-valid-1');

      await assertSucceeds(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
        lastMutationId: 'mut-init-1',
        type: 'LATA',
        timestamp: 1729000000000,
        comment: 'Primera birra C4',
        latitude: 40.4168,
        longitude: -3.7038,
        locationName: 'Sol',
        remotePhotoUrl: 'users/alice/beers/sync-100_mut-1.jpg',
        photoSource: 'CAMERA',
        fieldRevisions: {
          TYPE: 1,
          TIMESTAMP: 1,
          LOCATION: 1,
          COMMENT: 1,
          PHOTO: 1,
        },
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

    it('rejects create with empty or incomplete fieldRevisions map (Test C)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();

      // Empty map
      const beerRefEmpty = doc(aliceDb, 'beers/beer-fr-empty');
      await assertFails(setDoc(beerRefEmpty, createModernBeerPayload('alice', {
        fieldRevisions: {},
      })));

      // Incomplete map (missing PHOTO)
      const beerRefIncomplete = doc(aliceDb, 'beers/beer-fr-incomplete');
      await assertFails(setDoc(beerRefIncomplete, createModernBeerPayload('alice', {
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1 },
      })));

      // Unknown key
      const beerRefUnknown = doc(aliceDb, 'beers/beer-fr-unknown-key');
      await assertFails(setDoc(beerRefUnknown, createModernBeerPayload('alice', {
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1, EXTRA: 1 },
      })));

      // Initial revision != 1
      const beerRefRev2 = doc(aliceDb, 'beers/beer-fr-rev2');
      await assertFails(setDoc(beerRefRev2, createModernBeerPayload('alice', {
        fieldRevisions: { TYPE: 2, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      })));
    });
  });

  describe('Deep fieldRevisions Integrity Constraints', () => {
    beforeEach(async () => {
      await seedDocument(testEnv, 'beers/beer-fr-test', createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-1',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      }));
    });

    it('rejects field revision values that are not integers (strings, floats, booleans)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-fr-test');

      // String value
      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-fr-str',
        'fieldRevisions.COMMENT': '2',
      }));

      // Boolean value
      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-fr-bool',
        'fieldRevisions.COMMENT': true,
      }));
    });

    it('rejects field revision values that are negative or zero', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-fr-test');

      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-fr-neg',
        'fieldRevisions.COMMENT': -1,
      }));

      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-fr-zero',
        'fieldRevisions.COMMENT': 0,
      }));
    });

    it('rejects field revision values greater than document revision', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-fr-test');

      // Doc revision is advancing to 2, but field revision claims 5
      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-fr-excess',
        'fieldRevisions.COMMENT': 5,
      }));
    });

    it('rejects unrecognized keys in fieldRevisions', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-fr-test');

      // Unrecognized keys like BRAND or RATING
      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-fr-unknown',
        'fieldRevisions.BRAND': 2,
      }));
    });

    it('protects against reduction / backwards regression of fieldRevisions', async () => {
      await seedDocument(testEnv, 'beers/beer-fr-rev3', createModernBeerPayload('alice', {
        revision: 3,
        lastMutationId: 'mut-fr-3',
        fieldRevisions: { TYPE: 2, TIMESTAMP: 1, LOCATION: 1, COMMENT: 3, PHOTO: 1 },
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-fr-rev3');

      // Attempting to regress COMMENT from 3 to 1 while advancing doc revision to 4
      await assertFails(updateDoc(beerRef, {
        revision: 4,
        lastMutationId: 'mut-fr-regress',
        'fieldRevisions.COMMENT': 1,
      }));
    });

    it('enforces correspondence between modified fields and fieldRevisions updates', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-fr-test');

      // Modifying comment to 'Nuevo comentario' WITHOUT updating fieldRevisions.COMMENT -> REJECTED
      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-inconsistent-edit',
        comment: 'Nuevo comentario',
        // fieldRevisions.COMMENT omitted / left at 1
      }));

      // Modifying comment WITH updating fieldRevisions.COMMENT to 2 -> SUCCEEDS
      await assertSucceeds(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-consistent-edit',
        comment: 'Nuevo comentario',
        'fieldRevisions.COMMENT': 2,
      }));
    });

    it('rejects deleting existing key in fieldRevisions via FieldValue.delete() (Test A)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-fr-test');

      // Negative test: deleteField() on fieldRevisions.TYPE while type remains unchanged -> REJECTED
      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-del-type-fr',
        'fieldRevisions.TYPE': deleteField(),
      }));

      // Negative test: deleteField() on fieldRevisions.COMMENT -> REJECTED
      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-del-comment-fr',
        'fieldRevisions.COMMENT': deleteField(),
      }));
    });

    it('rejects advancing fieldRevision without modifying underlying data (anti-fictitious advance) (Test B)', async () => {
      await seedDocument(testEnv, 'beers/beer-fictitious', createModernBeerPayload('alice', {
        revision: 3,
        lastMutationId: 'mut-prev-3',
        comment: 'Comentario original',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-fictitious');

      // Document is at rev 3 with COMMENT rev 1.
      // Next mutation advances doc to rev 4, but comment is NOT modified.
      // Attempting to send fieldRevisions.COMMENT = 4 -> MUST BE REJECTED by emulator
      await assertFails(updateDoc(beerRef, {
        revision: 4,
        lastMutationId: 'mut-fictitious-advance',
        'fieldRevisions.COMMENT': 4,
      }));

      // If comment IS actually modified, advancing fieldRevisions.COMMENT to 4 SUCCEEDS
      await assertSucceeds(updateDoc(beerRef, {
        revision: 4,
        lastMutationId: 'mut-real-advance',
        comment: 'Comentario modificado de verdad',
        'fieldRevisions.COMMENT': 4,
      }));
    });
  });

  describe('isDeleted Type and Integrity Constraints', () => {
    beforeEach(async () => {
      await seedDocument(testEnv, 'beers/beer-del-test', createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-1',
        isDeleted: false,
      }));
    });

    it('rejects update setting isDeleted to non-boolean (null, string, integer)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-del-test');

      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-null-del',
        isDeleted: null,
      }));

      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-str-del',
        isDeleted: 'true',
      }));

      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-num-del',
        isDeleted: 1,
      }));
    });

    it('rejects attempt to remove isDeleted via FieldValue.delete()', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-del-test');

      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-rm-del',
        isDeleted: deleteField(),
      }));
    });

    it('rejects attempt to remove lastMutationId or fieldRevisions via FieldValue.delete()', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-del-test');

      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: deleteField(),
      }));

      await assertFails(updateDoc(beerRef, {
        revision: 2,
        lastMutationId: 'mut-valid-next',
        fieldRevisions: deleteField(),
      }));
    });
  });

  describe('Monotonic Revision Progression (+1 Invariant)', () => {
    beforeEach(async () => {
      await seedDocument(testEnv, 'beers/beer-mono', createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-mono-1',
        isDeleted: false,
      }));
    });

    it('allows valid monotonic advance from revision 1 to 2', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mono');

      await assertSucceeds(updateDoc(beerRef, {
        revision: 2,
        comment: 'Comentario actualizado',
        lastMutationId: 'mut-mono-2',
        'fieldRevisions.COMMENT': 2,
      }));
    });

    it('rejects revision jump (e.g. revision 1 to 3 or 10)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mono');

      await assertFails(updateDoc(beerRef, {
        revision: 3,
        comment: 'Salto 3',
        lastMutationId: 'mut-jump-3',
        'fieldRevisions.COMMENT': 3,
      }));

      await assertFails(updateDoc(beerRef, {
        revision: 10,
        comment: 'Gran Salto 10',
        lastMutationId: 'mut-jump-10',
        'fieldRevisions.COMMENT': 10,
      }));
    });

    it('rejects revision regression (e.g. revision 5 to 4)', async () => {
      await seedDocument(testEnv, 'beers/beer-rev5', createModernBeerPayload('alice', {
        revision: 5,
        lastMutationId: 'mut-rev-5',
        isDeleted: false,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-rev5');

      await assertFails(updateDoc(beerRef, {
        revision: 4,
        comment: 'Regresion',
        lastMutationId: 'mut-regress',
      }));
    });

    it('rejects same-revision update without advance (revision N to N)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mono');

      await assertFails(updateDoc(beerRef, {
        revision: 1,
        comment: 'Misma revision',
        lastMutationId: 'mut-same',
      }));
    });
  });

  describe('Protection Against MutationId Reuse & Idempotency Boundary', () => {
    beforeEach(async () => {
      await seedDocument(testEnv, 'beers/beer-mut-reuse', createModernBeerPayload('alice', {
        revision: 2,
        lastMutationId: 'mut-existing-123',
        isDeleted: false,
      }));
    });

    it('rejects advancing to next revision while reusing the exact same lastMutationId', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-mut-reuse');

      // Attempting to advance revision 2 -> 3 using the SAME lastMutationId 'mut-existing-123'
      await assertFails(updateDoc(beerRef, {
        revision: 3,
        lastMutationId: 'mut-existing-123',
        comment: 'Contradictory Payload Reusing MutationId',
        'fieldRevisions.COMMENT': 3,
      }));
    });
  });

  describe('Protection Against Legacy Overwrites (.set() blind overwrites)', () => {
    it('rejects legacy .set() overwrite that strips revision or sync metadata', async () => {
      await seedDocument(testEnv, 'beers/beer-seeded', createModernBeerPayload('alice', {
        revision: 5,
        lastMutationId: 'mut-5',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-seeded');

      // Attempting to overwrite with a legacy payload lacking revision
      await assertFails(setDoc(beerRef, createLegacyBeerPayload('alice')));
    });

    it('rejects .set() overwrite that attempts arbitrary revision reset', async () => {
      await seedDocument(testEnv, 'beers/beer-seeded', createModernBeerPayload('alice', {
        revision: 5,
        lastMutationId: 'mut-5',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-seeded');

      // Attempting to overwrite with revision 1
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-reset-1',
      })));
    });
  });

  describe('Tombstone Lifecycle & Anti-Resurrection Protection', () => {
    it('allows transitioning an active beer to a tombstone with revision +1', async () => {
      await seedDocument(testEnv, 'beers/beer-tomb', createModernBeerPayload('alice', {
        revision: 2,
        lastMutationId: 'mut-tomb-prev',
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

    it('allows applying tombstone with global revision +1 while preserving all fieldRevisions unchanged (Test F)', async () => {
      await seedDocument(testEnv, 'beers/beer-tomb-fr-test', createModernBeerPayload('alice', {
        revision: 2,
        lastMutationId: 'mut-tomb-init',
        fieldRevisions: { TYPE: 2, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
        isDeleted: false,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-tomb-fr-test');

      // Global revision +1, isDeleted: true, no data fields changed, fieldRevisions preserved -> SUCCEEDS
      await assertSucceeds(updateDoc(beerRef, {
        revision: 3,
        isDeleted: true,
        lastMutationId: 'mut-tomb-ack',
      }));
    });

    it('STRICTLY REJECTS resurrection of a tombstone (isDeleted true -> false)', async () => {
      await seedDocument(testEnv, 'beers/beer-tombstone', createModernBeerPayload('alice', {
        revision: 4,
        lastMutationId: 'mut-tomb-4',
        isDeleted: true,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-tombstone');

      // Attempt to resurrect by setting isDeleted = false, even with revision +1 and distinct mutationId
      await assertFails(updateDoc(beerRef, {
        revision: 5,
        isDeleted: false,
        lastMutationId: 'mut-resurrect-attempt',
        comment: 'Zombie Beer',
        'fieldRevisions.COMMENT': 5,
      }));
    });

    it('allows metadata updates to continue on a tombstone as long as isDeleted remains true', async () => {
      await seedDocument(testEnv, 'beers/beer-tombstone-refresh', createModernBeerPayload('alice', {
        revision: 4,
        lastMutationId: 'mut-tomb-4',
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
