const { doc, getDoc, setDoc, updateDoc, deleteDoc } = require('firebase/firestore');
const { ref, uploadBytes, deleteObject } = require('firebase/storage');
const {
  createTestEnv,
  seedDocument,
  createModernBeerPayload,
  createLegacyBeerPayload,
  createDummyImageBuffer,
  assertFails,
  assertSucceeds,
} = require('./helpers');

describe('Profile A: BASELINE RULES AUDIT', () => {
  let testEnv;

  before(async () => {
    testEnv = await createTestEnv('FIRESTORE_BASELINE', 'STORAGE_BASELINE');
  });

  after(async () => {
    if (testEnv) {
      await testEnv.cleanup();
    }
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
    await testEnv.clearStorage();
  });

  describe('Basic Access Control (Baseline)', () => {
    it('allows owner to read and write own beer', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-1');

      await assertSucceeds(setDoc(beerRef, {
        userId: 'alice',
        name: 'Mahou 5 Estrellas',
        rating: 4.0,
      }));

      const snapshot = await assertSucceeds(getDoc(beerRef));
      if (!snapshot.exists()) {
        throw new Error('Expected document to exist');
      }
    });

    it('rejects unauthenticated user from reading or writing', async () => {
      await seedDocument(testEnv, 'beers/beer-1', {
        userId: 'alice',
        name: 'Mahou 5 Estrellas',
      });

      const unauthDb = testEnv.unauthenticatedContext().firestore();
      const beerRef = doc(unauthDb, 'beers/beer-1');

      await assertFails(getDoc(beerRef));
      await assertFails(setDoc(beerRef, { userId: 'alice', name: 'Hacked' }));
    });

    it('rejects foreign user from reading, updating, or deleting another user beer', async () => {
      await seedDocument(testEnv, 'beers/beer-1', {
        userId: 'alice',
        name: 'Mahou 5 Estrellas',
      });

      const bobDb = testEnv.authenticatedContext('bob').firestore();
      const beerRef = doc(bobDb, 'beers/beer-1');

      await assertFails(getDoc(beerRef));
      await assertFails(updateDoc(beerRef, { name: 'Bob Hacked' }));
      await assertFails(deleteDoc(beerRef));
    });
  });

  describe('Documented Security Vulnerabilities Permitted by Baseline', () => {
    it('[VULNERABILITY] allows arbitrary revision jump (e.g. rev 1 -> 999)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-vuln-1');

      // In baseline, create allows any arbitrary revision jump
      await assertSucceeds(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 999,
      })));
    });

    it('[VULNERABILITY] allows arbitrary revision regression (e.g. rev 5 -> 2)', async () => {
      await seedDocument(testEnv, 'beers/beer-vuln-2', createModernBeerPayload('alice', {
        revision: 5,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-vuln-2');

      // In baseline, update allows regressing revision backwards
      await assertSucceeds(updateDoc(beerRef, {
        revision: 2,
        name: 'Regressed Name',
      }));
    });

    it('[VULNERABILITY] allows resurrection of soft-deleted records (isDeleted true -> false)', async () => {
      await seedDocument(testEnv, 'beers/beer-vuln-3', createModernBeerPayload('alice', {
        revision: 3,
        isDeleted: true,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-vuln-3');

      // In baseline, nothing prevents resurrecting a tombstone
      await assertSucceeds(updateDoc(beerRef, {
        isDeleted: false,
        name: 'Resurrected Beer',
      }));
    });

    it('[VULNERABILITY] allows hard physical deletion of tombstones, destroying causal history', async () => {
      await seedDocument(testEnv, 'beers/beer-vuln-4', createModernBeerPayload('alice', {
        revision: 4,
        isDeleted: true,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-vuln-4');

      // In baseline, deleteDoc physically erases the tombstone
      await assertSucceeds(deleteDoc(beerRef));
    });

    it('[VULNERABILITY] allows blind .set() overwrite stripping all sync metadata', async () => {
      await seedDocument(testEnv, 'beers/beer-vuln-5', createModernBeerPayload('alice', {
        revision: 10,
        syncId: 'sync-123',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-vuln-5');

      // Blind overwrite with legacy structure without revision or mutationId
      await assertSucceeds(setDoc(beerRef, createLegacyBeerPayload('alice')));
    });

    it('[VULNERABILITY] allows overwriting and physically deleting versioned photos in Storage', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const photoRef = ref(aliceStorage, 'users/alice/beers/sync-100_mut-1.jpg');

      // 1. Initial upload succeeds
      await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(100), {
        contentType: 'image/jpeg',
      }));

      // 2. In baseline, overwriting the exact same versioned photo succeeds (violates immutability!)
      await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(200), {
        contentType: 'image/jpeg',
      }));

      // 3. In baseline, physically deleting the versioned photo succeeds
      await assertSucceeds(deleteObject(photoRef));
    });
  });
});
