const { doc, getDoc, setDoc, updateDoc, deleteDoc } = require('firebase/firestore');
const { ref, uploadBytes, deleteObject, getBytes } = require('firebase/storage');
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

describe('C4 Release Readiness: Candidate Security Rules Verification Suite', () => {
  let testEnv;

  before(async () => {
    testEnv = await createTestEnv('FIRESTORE_CANDIDATE', 'STORAGE_CANDIDATE');
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

  // ===========================================================================
  // SCENARIO 1: Isolation between two users
  // ===========================================================================
  describe('Scenario 1: User Isolation & Cross-Account Access Denial', () => {
    it('prevents user B from reading, updating, or deleting user A beers in Firestore', async () => {
      await seedDocument(testEnv, 'beers/beer-alice-1', createModernBeerPayload('alice'));

      const bobDb = testEnv.authenticatedContext('bob').firestore();
      const aliceBeerRef = doc(bobDb, 'beers/beer-alice-1');

      await assertFails(getDoc(aliceBeerRef));
      await assertFails(updateDoc(aliceBeerRef, { comment: 'Bob maliciously editing Alice beer' }));
      await assertFails(deleteDoc(aliceBeerRef));
    });

    it('prevents user B from uploading into user A storage directory', async () => {
      const bobStorage = testEnv.authenticatedContext('bob').storage();
      const foreignPhotoRef = ref(bobStorage, 'users/alice/beers/sync-cr-100_mut-1001.jpg');

      await assertFails(uploadBytes(foreignPhotoRef, createDummyImageBuffer(), {
        contentType: 'image/jpeg',
      }));
    });

    it('prevents user B from reading or writing user A private profile (/users/{uid})', async () => {
      await seedDocument(testEnv, 'users/alice', { email: 'alice@test.com', alias: 'Alice' });

      const bobDb = testEnv.authenticatedContext('bob').firestore();
      const aliceUserRef = doc(bobDb, 'users/alice');

      await assertFails(getDoc(aliceUserRef));
      await assertFails(setDoc(aliceUserRef, { alias: 'Hacked by Bob' }, { merge: true }));
    });
  });

  // ===========================================================================
  // SCENARIO 2: Unauthenticated reads & writes rejected
  // ===========================================================================
  describe('Scenario 2: Unauthenticated Access Rejection', () => {
    it('rejects unauthenticated read, create, update, and delete on beers', async () => {
      await seedDocument(testEnv, 'beers/beer-auth-1', createModernBeerPayload('alice'));

      const unauthDb = testEnv.unauthenticatedContext().firestore();
      const beerRef = doc(unauthDb, 'beers/beer-auth-1');

      await assertFails(getDoc(beerRef));
      await assertFails(updateDoc(beerRef, { comment: 'Unauth edit' }));
      await assertFails(deleteDoc(beerRef));
      await assertFails(setDoc(doc(unauthDb, 'beers/beer-auth-new'), createModernBeerPayload('alice')));
    });

    it('rejects unauthenticated access to storage and user accounts', async () => {
      const unauthStorage = testEnv.unauthenticatedContext().storage();
      const photoRef = ref(unauthStorage, 'users/alice/beers/sync-cr-unauth_mut-1.jpg');

      await assertFails(uploadBytes(photoRef, createDummyImageBuffer(), { contentType: 'image/jpeg' }));
      await assertFails(getBytes(photoRef));

      const unauthDb = testEnv.unauthenticatedContext().firestore();
      await assertFails(getDoc(doc(unauthDb, 'users/alice')));
      await assertFails(getDoc(doc(unauthDb, 'publicUsers/alice')));
    });
  });

  // ===========================================================================
  // SCENARIO 3: Valid C4 creation and monotonic update
  // ===========================================================================
  describe('Scenario 3: Valid C4 Creation and Monotonic Progression', () => {
    it('allows valid C4 beer creation at revision 1 with active status and canonical fieldRevisions', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-c4-valid');

      await assertSucceeds(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
        lastMutationId: 'mut-c4-001',
        fieldRevisions: {
          TYPE: 1,
          TIMESTAMP: 1,
          LOCATION: 1,
          COMMENT: 1,
          PHOTO: 1,
        },
      })));
    });

    it('allows strictly monotonic update (rev 1 -> 2) with distinct mutation and causal fieldRevisions', async () => {
      await seedDocument(testEnv, 'beers/beer-c4-step', createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
        comment: 'Initial comment',
        lastMutationId: 'mut-001',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-c4-step');

      await assertSucceeds(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 2,
        isDeleted: false,
        comment: 'Updated comment in rev 2',
        lastMutationId: 'mut-002',
        fieldRevisions: {
          TYPE: 1,
          TIMESTAMP: 1,
          LOCATION: 1,
          COMMENT: 2,
          PHOTO: 1,
        },
      })));
    });

    it('allows subsequent monotonic update (rev 2 -> 3) advancing multiple modified field revisions', async () => {
      await seedDocument(testEnv, 'beers/beer-c4-multi', createModernBeerPayload('alice', {
        revision: 2,
        isDeleted: false,
        type: 'LATA',
        locationName: 'Plaza Mayor',
        lastMutationId: 'mut-002',
        fieldRevisions: {
          TYPE: 1,
          TIMESTAMP: 1,
          LOCATION: 1,
          COMMENT: 2,
          PHOTO: 1,
        },
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-c4-multi');

      await assertSucceeds(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 3,
        isDeleted: false,
        type: 'JARRA',
        latitude: 40.4200,
        longitude: -3.7100,
        locationName: 'Gran Via',
        lastMutationId: 'mut-003',
        fieldRevisions: {
          TYPE: 3,
          TIMESTAMP: 1,
          LOCATION: 3,
          COMMENT: 2,
          PHOTO: 1,
        },
      })));
    });
  });

  // ===========================================================================
  // SCENARIO 4: Non-monotonic / stale / conflicting revisions rejected
  // ===========================================================================
  describe('Scenario 4: Rejection of Non-Monotonic, Stale, and Inconsistent Mutations', () => {
    it('rejects initial creation starting at revision other than 1', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();

      await assertFails(setDoc(doc(aliceDb, 'beers/beer-bad-rev-2'), createModernBeerPayload('alice', {
        revision: 2,
      })));

      await assertFails(setDoc(doc(aliceDb, 'beers/beer-bad-rev-0'), createModernBeerPayload('alice', {
        revision: 0,
      })));
    });

    it('rejects stale or out-of-order updates (rev 1 -> 1, rev 2 -> 1, rev 1 -> 3)', async () => {
      await seedDocument(testEnv, 'beers/beer-rev-seq', createModernBeerPayload('alice', {
        revision: 2,
        lastMutationId: 'mut-initial',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-rev-seq');

      // Stale: rev 2 -> 1
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-stale',
      })));

      // Stagnant: rev 2 -> 2
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 2,
        lastMutationId: 'mut-same-rev',
      })));

      // Gap jump: rev 2 -> 4
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 4,
        lastMutationId: 'mut-gap',
      })));
    });

    it('rejects mutation reusing the same lastMutationId', async () => {
      await seedDocument(testEnv, 'beers/beer-reuse-mut', createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-duplicate-id',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-reuse-mut');

      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 2,
        comment: 'New comment',
        lastMutationId: 'mut-duplicate-id', // Reused mutation ID
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 2, PHOTO: 1 },
      })));
    });

    it('rejects update with inconsistent fieldRevisions (advancing without data change or skipping advancement)', async () => {
      await seedDocument(testEnv, 'beers/beer-field-inconsistent', createModernBeerPayload('alice', {
        revision: 1,
        type: 'LATA',
        lastMutationId: 'mut-001',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-field-inconsistent');

      // Fictitious advancement: TYPE advanced to 2 but type remained 'LATA'
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 2,
        type: 'LATA',
        lastMutationId: 'mut-002',
        fieldRevisions: { TYPE: 2, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      })));

      // Missing advancement: TYPE changed to 'BOTELLIN' but fieldRevision stayed 1
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 2,
        type: 'BOTELLIN',
        lastMutationId: 'mut-003',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      })));
    });
  });

  // ===========================================================================
  // SCENARIO 5: Physical deletes of C4 docs and zombie resurrection rejected
  // ===========================================================================
  describe('Scenario 5: Anti-Resurrection and Physical Delete Immunity', () => {
    it('forbids hard physical deletion of modern C4 documents', async () => {
      await seedDocument(testEnv, 'beers/beer-c4-nodelete', createModernBeerPayload('alice', {
        revision: 1,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-c4-nodelete');

      await assertFails(deleteDoc(beerRef));
    });

    it('allows valid logical deletion (tombstone) at revision 2', async () => {
      await seedDocument(testEnv, 'beers/beer-c4-tombstone', createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
        lastMutationId: 'mut-001',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-c4-tombstone');

      await assertSucceeds(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 2,
        isDeleted: true,
        lastMutationId: 'mut-del-002',
      })));
    });

    it('strictly forbids resurrection of a tombstone (isDeleted: true -> false)', async () => {
      await seedDocument(testEnv, 'beers/beer-c4-dead', createModernBeerPayload('alice', {
        revision: 2,
        isDeleted: true,
        lastMutationId: 'mut-del-002',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-c4-dead');

      // Attempt resurrection in rev 3
      await assertFails(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 3,
        isDeleted: false,
        lastMutationId: 'mut-resurrect-003',
      })));

      // Attempt physical deletion of tombstone
      await assertFails(deleteDoc(beerRef));
    });
  });

  // ===========================================================================
  // SCENARIO 6: Versioned photos immutability & overwrite rejection in Storage
  // ===========================================================================
  describe('Scenario 6: Versioned Photo Inviolability and Overwrite Denial in Storage', () => {
    it('allows initial upload of versioned photo and strictly rejects overwrite', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const photoPath = 'users/alice/beers/sync-cand-100_mut-1001.jpg';
      const photoRef = ref(aliceStorage, photoPath);

      // Initial upload succeeds
      await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(2048), {
        contentType: 'image/jpeg',
      }));

      // Blind overwrite attempt on existing versioned photo MUST fail
      await assertFails(uploadBytes(photoRef, createDummyImageBuffer(4096), {
        contentType: 'image/jpeg',
      }));
    });

    it('forbids deletion or mutation of existing versioned photos', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const photoPath = 'users/alice/beers/sync-cand-200_mut-2001.jpg';
      const photoRef = ref(aliceStorage, photoPath);

      await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(2048), {
        contentType: 'image/jpeg',
      }));

      // Delete attempt fails
      await assertFails(deleteObject(photoRef));
    });

    it('supports UUID-formatted versioned photos and enforces identical immutability', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const uuidPhotoPath = 'users/alice/beers/987e4567-e89b-12d3-a456-426614174000_123fcdeb-51a2-43f7-9876-543210987654.jpg';
      const photoRef = ref(aliceStorage, uuidPhotoPath);

      await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(1024), {
        contentType: 'image/jpeg',
      }));

      // Overwrite fails
      await assertFails(uploadBytes(photoRef, createDummyImageBuffer(2048), {
        contentType: 'image/jpeg',
      }));

      // Delete fails
      await assertFails(deleteObject(photoRef));
    });
  });

  // ===========================================================================
  // SCENARIO 7: Unauthorized access to other users photos
  // ===========================================================================
  describe('Scenario 7: Cross-User Storage Access Control (Friends vs Strangers)', () => {
    it('denies foreign users from reading photos unless an ACCEPTED friendship exists', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const photoPath = 'users/alice/beers/sync-cand-shared_mut-1.jpg';
      const photoRef = ref(aliceStorage, photoPath);

      await assertSucceeds(uploadBytes(photoRef, createDummyImageBuffer(1024), {
        contentType: 'image/jpeg',
      }));

      // Bob has no friendship -> Access denied
      const bobStorage = testEnv.authenticatedContext('bob').storage();
      const bobPhotoRef = ref(bobStorage, photoPath);
      await assertFails(getBytes(bobPhotoRef));

      // Establish accepted friendship between alice and bob
      await seedDocument(testEnv, 'friendships/alice_bob', {
        user1: 'alice',
        user2: 'bob',
        requester: 'alice',
        status: 'ACCEPTED',
        friendshipId: 'alice_bob',
      });

      // Now Bob can read Alice's photo
      await assertSucceeds(getBytes(bobPhotoRef));

      // Charlie with PENDING friendship cannot read
      await seedDocument(testEnv, 'friendships/alice_charlie', {
        user1: 'alice',
        user2: 'charlie',
        requester: 'charlie',
        status: 'PENDING',
        friendshipId: 'alice_charlie',
      });
      const charlieStorage = testEnv.authenticatedContext('charlie').storage();
      await assertFails(getBytes(ref(charlieStorage, photoPath)));
    });
  });

  // ===========================================================================
  // SCENARIO 8: Legacy unversioned doc lifecycle & blocking legacy regression on C4 docs
  // ===========================================================================
  describe('Scenario 8: Legacy Document Coexistence, Upgrade, and Anti-Regression', () => {
    it('allows legacy client to create, update, and hard-delete unversioned documents', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const legacyBeerRef = doc(aliceDb, 'beers/beer-legacy-flow');

      // Create legacy
      await assertSucceeds(setDoc(legacyBeerRef, createLegacyBeerPayload('alice')));

      // Update legacy
      await assertSucceeds(updateDoc(legacyBeerRef, { comment: 'Updated legacy comment' }));

      // Delete legacy physically
      await assertSucceeds(deleteDoc(legacyBeerRef));
    });

    it('allows atomic upgrade of legacy document to C4 revision 1', async () => {
      await seedDocument(testEnv, 'beers/beer-legacy-upgrade', createLegacyBeerPayload('alice'));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-legacy-upgrade');

      await assertSucceeds(setDoc(beerRef, createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
        lastMutationId: 'mut-upgrade-001',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      })));
    });

    it('blocks legacy client regression and deletion once document is upgraded to C4', async () => {
      await seedDocument(testEnv, 'beers/beer-migrated', createModernBeerPayload('alice', {
        revision: 1,
        isDeleted: false,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-migrated');

      // Legacy update without revision must be blocked
      await assertFails(setDoc(beerRef, createLegacyBeerPayload('alice')));

      // Legacy hard delete must be blocked
      await assertFails(deleteDoc(beerRef));
    });

    it('permits legacy unversioned storage photos to be created, updated, and deleted', async () => {
      const aliceStorage = testEnv.authenticatedContext('alice').storage();
      const legacyPhotoRef = ref(aliceStorage, 'users/alice/beers/mi_cerveza_cand_favorita.jpg');

      // Create
      await assertSucceeds(uploadBytes(legacyPhotoRef, createDummyImageBuffer(1024), {
        contentType: 'image/jpeg',
      }));

      // Update / overwrite legacy unversioned photo
      await assertSucceeds(uploadBytes(legacyPhotoRef, createDummyImageBuffer(2048), {
        contentType: 'image/jpeg',
      }));

      // Delete legacy unversioned photo
      await assertSucceeds(deleteObject(legacyPhotoRef));
    });
  });

  // ===========================================================================
  // SCENARIO 9: Protection of migration state / protocol lock in users/{uid}
  // ===========================================================================
  describe('Scenario 9: Account Protocol Lock & Migration Protection in /users/{userId}', () => {
    it('allows owner to write initial C4 protocol lock', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const userRef = doc(aliceDb, 'users/alice');

      await assertSucceeds(setDoc(userRef, createProtocolLockPayload({
        email: 'alice@example.com',
        alias: 'Alice Pilot',
      })));
    });

    it('rejects reverting syncProtocolVersion to LEGACY on a migrated account', async () => {
      await seedDocument(testEnv, 'users/alice', createProtocolLockPayload({
        email: 'alice@example.com',
        alias: 'Alice Pilot',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const userRef = doc(aliceDb, 'users/alice');

      await assertFails(setDoc(userRef, {
        syncProtocolVersion: 'LEGACY',
      }, { merge: true }));
    });

    it('rejects lowering minSupportedAppVersionCode or altering c4MigratedAt', async () => {
      await seedDocument(testEnv, 'users/alice', createProtocolLockPayload({
        minSupportedAppVersionCode: 3,
        c4MigratedAt: 1729000000000,
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const userRef = doc(aliceDb, 'users/alice');

      // Attempt to lower minSupportedAppVersionCode from 3 to 1
      await assertFails(setDoc(userRef, {
        minSupportedAppVersionCode: 1,
      }, { merge: true }));

      // Attempt to alter c4MigratedAt timestamp
      await assertFails(setDoc(userRef, {
        c4MigratedAt: 9999999999999,
      }, { merge: true }));
    });

    it('forbids deleting user account document once migrated to C4', async () => {
      await seedDocument(testEnv, 'users/alice', createProtocolLockPayload());

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const userRef = doc(aliceDb, 'users/alice');

      await assertFails(deleteDoc(userRef));
    });

    it('permits updating standard user profile fields (alias, emailLowercase) on migrated accounts', async () => {
      await seedDocument(testEnv, 'users/alice', createProtocolLockPayload({
        email: 'alice@example.com',
        alias: 'Alice Initial',
      }));

      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const userRef = doc(aliceDb, 'users/alice');

      // Standard profile merge write (as done by AuthRepository)
      await assertSucceeds(setDoc(userRef, {
        alias: 'Alice Updated',
        emailLowercase: 'alice@example.com',
      }, { merge: true }));
    });
  });

  // ===========================================================================
  // SCENARIO 10: Non-regression of existing features
  // ===========================================================================
  describe('Scenario 10: Non-Regression of Public Users, Friendships, Groups, and Comments', () => {
    it('maintains publicUsers creation, reading, and strict schema compliance', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const publicAliceRef = doc(aliceDb, 'publicUsers/alice');

      // Valid create
      await assertSucceeds(setDoc(publicAliceRef, {
        uid: 'alice',
        displayName: 'Alice In Chains',
        photoUrl: 'https://example.com/alice.png',
        createdAt: 1729000000000,
        updatedAt: 1729000000000,
      }));

      // Bob can read Alice public profile
      const bobDb = testEnv.authenticatedContext('bob').firestore();
      await assertSucceeds(getDoc(doc(bobDb, 'publicUsers/alice')));

      // Unauthorized fields rejected (e.g. role)
      await assertFails(setDoc(doc(aliceDb, 'publicUsers/alice_bad'), {
        uid: 'alice',
        displayName: 'Hacker',
        role: 'admin',
      }));
    });

    it('maintains friendships lifecycle (create PENDING, accept ACCEPTED)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const friendshipRef = doc(aliceDb, 'friendships/alice_bob');

      // Alice sends friend request
      await assertSucceeds(setDoc(friendshipRef, {
        user1: 'alice',
        user2: 'bob',
        requester: 'alice',
        status: 'PENDING',
        friendshipId: 'alice_bob',
      }));

      // Bob accepts
      const bobDb = testEnv.authenticatedContext('bob').firestore();
      await assertSucceeds(updateDoc(doc(bobDb, 'friendships/alice_bob'), {
        status: 'ACCEPTED',
      }));

      // Charlie cannot read or modify alice_bob friendship
      const charlieDb = testEnv.authenticatedContext('charlie').firestore();
      await assertFails(getDoc(doc(charlieDb, 'friendships/alice_bob')));
      await assertFails(deleteDoc(doc(charlieDb, 'friendships/alice_bob')));
    });

    it('maintains groups creation, reading, and nested comments security', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const groupRef = doc(aliceDb, 'groups/group-101');

      // Alice creates group
      await assertSucceeds(setDoc(groupRef, {
        name: 'Beer Lovers Club',
        adminUid: 'alice',
        members: ['alice'],
        createdAt: 1729000000000,
      }));

      // Alice reads group
      await assertSucceeds(getDoc(groupRef));

      // Bob (non-member) cannot read group
      const bobDb = testEnv.authenticatedContext('bob').firestore();
      await assertFails(getDoc(doc(bobDb, 'groups/group-101')));

      // Alice creates comment in group (requires publicUser)
      await seedDocument(testEnv, 'publicUsers/alice', {
        displayName: 'Alice Admin',
        username: 'alice_admin',
      });

      const commentRef = doc(aliceDb, 'groups/group-101/comments/comment-1');
      await assertSucceeds(setDoc(commentRef, {
        authorUid: 'alice',
        authorName: 'Alice Admin',
        authorUsername: 'alice_admin',
        text: 'Welcome to the club!',
        createdAt: 1729000010000,
      }));

      // Bob cannot post comment in Alice group
      const bobCommentRef = doc(bobDb, 'groups/group-101/comments/comment-2');
      await assertFails(setDoc(bobCommentRef, {
        authorUid: 'bob',
        authorName: 'Bob',
        authorUsername: 'bob_user',
        text: 'Intruding!',
        createdAt: 1729000020000,
      }));
    });

    it('maintains sharedBeers read access for owners and friends, blocking direct writes', async () => {
      await seedDocument(testEnv, 'sharedBeers/beer-shared-1', {
        userId: 'alice',
        type: 'PINTA',
        comment: 'Great stout',
      });

      // Direct write to sharedBeers is forbidden
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      await assertFails(setDoc(doc(aliceDb, 'sharedBeers/beer-shared-new'), {
        userId: 'alice',
      }));

      // Alice can read her shared beer
      await assertSucceeds(getDoc(doc(aliceDb, 'sharedBeers/beer-shared-1')));

      // Bob without friendship cannot read
      const bobDb = testEnv.authenticatedContext('bob').firestore();
      await assertFails(getDoc(doc(bobDb, 'sharedBeers/beer-shared-1')));

      // Establish friendship
      await seedDocument(testEnv, 'friendships/alice_bob', {
        user1: 'alice',
        user2: 'bob',
        requester: 'alice',
        status: 'ACCEPTED',
        friendshipId: 'alice_bob',
      });

      // Now Bob can read Alice shared beer
      await assertSucceeds(getDoc(doc(bobDb, 'sharedBeers/beer-shared-1')));
    });
  });
});
