const { ref, uploadBytes, deleteObject, getBytes } = require('firebase/storage');
const {
  createTestEnv,
  seedDocument,
  createDummyImageBuffer,
  assertFails,
  assertSucceeds,
} = require('./helpers');

describe('Storage Security Suite across Profiles', () => {

  describe('Storage Baseline Profile (Profile A)', () => {
    let testEnv;

    before(async () => {
      testEnv = await createTestEnv('FIRESTORE_BASELINE', 'STORAGE_BASELINE');
    });

    after(async () => {
      if (testEnv) await testEnv.cleanup();
    });

    beforeEach(async () => {
      await testEnv.clearFirestore();
      await testEnv.clearStorage();
    });

    it('permits owner upload and blocks foreign upload', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const photoRef = ref(aliceStorage, 'users/alice/beers/beer-photo.jpg');

      await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(1024), {
        contentType: 'image/jpeg',
      }));

      const bobStorage = testEnv.authenticatedContext('bob').storage();
      const bobPhotoRef = ref(bobStorage, 'users/alice/beers/beer-photo.jpg');

      await assertFails(uploadBytes(bobPhotoRef, createDummyImageBuffer(1024), {
        contentType: 'image/jpeg',
      }));
    });

    it('blocks unauthenticated access', async () => {
      const unauthStorage = testEnv.unauthenticatedContext().storage();
      const photoRef = ref(unauthStorage, 'users/alice/beers/beer-photo.jpg');

      await assertFails(uploadBytes(photoRef, createDummyImageBuffer(1024), {
        contentType: 'image/jpeg',
      }));
      await assertFails(getBytes(photoRef));
    });

    it('enforces 5MB size limit', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const photoRef = ref(aliceStorage, 'users/alice/beers/huge-photo.jpg');

      // 6MB buffer exceeds the 5MB limit
      const oversizedBuffer = createDummyImageBuffer(6 * 1024 * 1024);
      await assertFails(uploadBytes(photoRef, oversizedBuffer, {
        contentType: 'image/jpeg',
      }));
    });

    it('enforces image MIME type restriction', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const photoRef = ref(aliceStorage, 'users/alice/beers/malicious.sh');

      await assertFails(uploadBytes(photoRef, createDummyImageBuffer(100), {
        contentType: 'text/plain',
      }));

      await assertFails(uploadBytes(photoRef, createDummyImageBuffer(100), {
        contentType: 'application/pdf',
      }));
    });
  });

  describe('Storage Legacy Compatibility Profile (Profile B)', () => {
    let testEnv;

    before(async () => {
      testEnv = await createTestEnv('FIRESTORE_LEGACY_COMPAT', 'STORAGE_LEGACY_COMPAT');
    });

    after(async () => {
      if (testEnv) await testEnv.cleanup();
    });

    beforeEach(async () => {
      await testEnv.clearFirestore();
      await testEnv.clearStorage();
    });

    it('allows legacy photos with underscores (e.g. mi_cerveza.jpg) to be created, overwritten, and deleted', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();

      // Legacy files with underscores
      const legacyPhotos = [
        'users/alice/beers/mi_cerveza.jpg',
        'users/alice/beers/cerveza_favorita_2024.jpg',
        'users/alice/beers/foto_1.jpg',
      ];

      for (const path of legacyPhotos) {
        const photoRef = ref(aliceStorage, path);

        // 1. Create succeeds
        await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(100), {
          contentType: 'image/jpeg',
        }));

        // 2. Overwrite / Update succeeds (true legacy compatibility)
        await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(200), {
          contentType: 'image/jpeg',
        }));

        // 3. Delete succeeds
        await assertSucceeds(deleteObject(photoRef));
      }
    });

    it('ENFORCES STRICT IMMUTABILITY on versioned photos (syncId_mutationId.ext)', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();

      // Test both sync-XXX_mut-YYY format and UUID_UUID format
      const versionedPhotos = [
        'users/alice/beers/sync-beer-100_mut-1001.jpg',
        'users/alice/beers/123e4567-e89b-12d3-a456-426614174000_987fcdeb-51a2-43f7-9876-543210987654.jpg',
      ];

      for (const path of versionedPhotos) {
        const versionedRef = ref(aliceStorage, path);

        // Initial create succeeds
        await assertSucceeds(uploadBytes(versionedRef, createDummyImageBuffer(100), {
          contentType: 'image/jpeg',
        }));

        // Attempted overwrite MUST FAIL
        await assertFails(uploadBytes(versionedRef, createDummyImageBuffer(200), {
          contentType: 'image/jpeg',
        }));

        // Attempted hard delete MUST FAIL
        await assertFails(deleteObject(versionedRef));
      }
    });
  });

  describe('Storage Modern Strict Protocol (Profile C)', () => {
    let testEnv;

    before(async () => {
      testEnv = await createTestEnv('FIRESTORE_MODERN_STRICT', 'STORAGE_MODERN_STRICT');
    });

    after(async () => {
      if (testEnv) await testEnv.cleanup();
    });

    beforeEach(async () => {
      await testEnv.clearFirestore();
      await testEnv.clearStorage();
    });

    it('rejects unversioned and ambiguous photo names in modern protocol', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();

      const invalidNames = [
        'users/alice/beers/mi_cerveza.jpg',
        'users/alice/beers/photo.jpg',
        'users/alice/beers/sync-beer-100_something.jpg', // Invalid mutation pattern
        'users/alice/beers/sync-100.jpg', // Missing mutation part
        'users/alice/beers/sync-100_mut-1.exe', // Non-image extension
      ];

      for (const path of invalidNames) {
        const photoRef = ref(aliceStorage, path);
        await assertFails(uploadBytes(photoRef, createDummyImageBuffer(100), {
          contentType: 'image/jpeg',
        }));
      }
    });

    it('allows valid versioned photo upload and strictly forbids overwrite and deletion', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const versionedRef = ref(aliceStorage, 'users/alice/beers/sync-beer-200_mut-99.jpg');

      // Create succeeds
      await assertSucceeds(uploadBytes(versionedRef, createDummyImageBuffer(500), {
        contentType: 'image/jpeg',
      }));

      // Overwrite fails
      await assertFails(uploadBytes(versionedRef, createDummyImageBuffer(600), {
        contentType: 'image/jpeg',
      }));

      // Delete fails
      await assertFails(deleteObject(versionedRef));
    });
  });

  describe('Friend Access via Cross-Service Firestore Lookup', () => {
    let testEnv;

    before(async () => {
      testEnv = await createTestEnv('FIRESTORE_MODERN_STRICT', 'STORAGE_MODERN_STRICT');
    });

    after(async () => {
      if (testEnv) await testEnv.cleanup();
    });

    beforeEach(async () => {
      await testEnv.clearFirestore();
      await testEnv.clearStorage();
    });

    it('allows accepted friend to read photo via Firestore friendship verification', async () => {
      // 1. Seed friendship between alice and bob with status ACCEPTED
      // Alphabetical order: 'alice' < 'bob' -> 'alice_bob'
      await seedDocument(testEnv, 'friendships/alice_bob', {
        user1: 'alice',
        user2: 'bob',
        status: 'ACCEPTED',
        requester: 'alice',
        friendshipId: 'alice_bob',
      });

      // 2. Alice uploads a versioned photo
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const photoRef = ref(aliceStorage, 'users/alice/beers/sync-1_mut-1.jpg');
      await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(256), {
        contentType: 'image/jpeg',
      }));

      // 3. Bob (accepted friend) reads Alice's photo -> SUCCEEDS
      const bobStorage = testEnv.authenticatedContext('bob').storage();
      const bobViewRef = ref(bobStorage, 'users/alice/beers/sync-1_mut-1.jpg');
      await assertSucceeds(getBytes(bobViewRef));
    });

    it('blocks pending friend and stranger from reading photo', async () => {
      // Seed pending friendship for charlie
      await seedDocument(testEnv, 'friendships/alice_charlie', {
        user1: 'alice',
        user2: 'charlie',
        status: 'PENDING',
        requester: 'charlie',
        friendshipId: 'alice_charlie',
      });

      // Alice uploads photo
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const photoRef = ref(aliceStorage, 'users/alice/beers/sync-2_mut-1.jpg');
      await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(256), {
        contentType: 'image/jpeg',
      }));

      // Charlie (status PENDING) -> FAILS
      const charlieStorage = testEnv.authenticatedContext('charlie').storage();
      const charlieViewRef = ref(charlieStorage, 'users/alice/beers/sync-2_mut-1.jpg');
      await assertFails(getBytes(charlieViewRef));

      // Dave (no friendship document) -> FAILS
      const daveStorage = testEnv.authenticatedContext('dave').storage();
      const daveViewRef = ref(daveStorage, 'users/alice/beers/sync-2_mut-1.jpg');
      await assertFails(getBytes(daveViewRef));
    });
  });
});
