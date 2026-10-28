const { doc, getDoc, setDoc, updateDoc, runTransaction } = require('firebase/firestore');
const {
  createTestEnv,
  seedDocument,
  createModernBeerPayload,
  createLegacyBeerPayload,
  assertFails,
  assertSucceeds,
} = require('./helpers');

describe('Concurrency, Retries & Race Condition Simulations', () => {

  describe('Concurrent Edits & Transactional Monotonicity (Profile C)', () => {
    let testEnv;

    before(async () => {
      testEnv = await createTestEnv('FIRESTORE_MODERN_STRICT');
    });

    after(async () => {
      if (testEnv) await testEnv.cleanup();
    });

    beforeEach(async () => {
      await testEnv.clearFirestore();
      await seedDocument(testEnv, 'beers/beer-race', createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-init',
        comment: 'Initial Comment',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      }));
    });

    it('prevents silent overwrite during blind concurrent writes (CAS enforced by rules)', async () => {
      const aliceDb1 = testEnv.authenticatedContext('alice').firestore();
      const aliceDb2 = testEnv.authenticatedContext('alice').firestore();

      const beerRef1 = doc(aliceDb1, 'beers/beer-race');
      const beerRef2 = doc(aliceDb2, 'beers/beer-race');

      // Both clients read revision 1
      const snap1 = await getDoc(beerRef1);
      const snap2 = await getDoc(beerRef2);
      if (snap1.data().revision !== 1 || snap2.data().revision !== 1) {
        throw new Error('Initial state mismatch');
      }

      // Client 1 successfully updates to revision 2
      await assertSucceeds(updateDoc(beerRef1, {
        revision: 2,
        comment: 'Comment by Client 1',
        lastMutationId: 'mut-client-1',
        'fieldRevisions.COMMENT': 2,
      }));

      // Client 2 (holding stale read of revision 1) attempts to write revision 2
      // Must be rejected by Security Rules (2 != 2 + 1)
      await assertFails(updateDoc(beerRef2, {
        revision: 2,
        comment: 'Comment by Client 2',
        lastMutationId: 'mut-client-2',
        'fieldRevisions.COMMENT': 2,
      }));

      // Verify that Client 1's write was preserved intact and never silently overwritten
      const finalSnap = await getDoc(beerRef1);
      if (finalSnap.data().comment !== 'Comment by Client 1' || finalSnap.data().revision !== 2) {
        throw new Error('Data corrupted or overwritten silently');
      }
    });

    it('handles concurrent read-modify-write safely via Firestore runTransaction', async () => {
      const aliceDb1 = testEnv.authenticatedContext('alice').firestore();
      const aliceDb2 = testEnv.authenticatedContext('alice').firestore();

      const beerRef1 = doc(aliceDb1, 'beers/beer-race');
      const beerRef2 = doc(aliceDb2, 'beers/beer-race');

      // Execute concurrent transactions that read current revision and increment strictly by 1
      const tx1 = runTransaction(aliceDb1, async (transaction) => {
        const snap = await transaction.get(beerRef1);
        const currentRev = snap.data().revision;
        const currentFr = snap.data().fieldRevisions || {};
        transaction.update(beerRef1, {
          revision: currentRev + 1,
          comment: `Tx 1 applied at rev ${currentRev + 1}`,
          lastMutationId: `tx-mut-1-at-${currentRev + 1}`,
          fieldRevisions: {
            ...currentFr,
            COMMENT: currentRev + 1,
          },
        });
      });

      const tx2 = runTransaction(aliceDb2, async (transaction) => {
        const snap = await transaction.get(beerRef2);
        const currentRev = snap.data().revision;
        const currentFr = snap.data().fieldRevisions || {};
        transaction.update(beerRef2, {
          revision: currentRev + 1,
          comment: `Tx 2 applied at rev ${currentRev + 1}`,
          lastMutationId: `tx-mut-2-at-${currentRev + 1}`,
          fieldRevisions: {
            ...currentFr,
            COMMENT: currentRev + 1,
          },
        });
      });

      // Both transactions run concurrently.
      // If the SDK retries on contention, both commit serially (advancing 1 -> 2 -> 3).
      // If contention is returned as an error, at least one commits and neither overwrites silently.
      const results = await Promise.allSettled([tx1, tx2]);
      const fulfilled = results.filter(r => r.status === 'fulfilled');

      if (fulfilled.length === 0) {
        throw new Error('Expected at least one transaction to commit');
      }

      const finalSnap = await getDoc(beerRef1);
      const finalRev = finalSnap.data().revision;

      if (fulfilled.length === 2) {
        // Both serialized cleanly via SDK contention retry
        if (finalRev !== 3) {
          throw new Error(`Expected revision 3 after two serialized increments, got ${finalRev}`);
        }
      } else {
        // One transaction succeeded; the other failed safely without silent overwrite
        if (finalRev !== 2) {
          throw new Error(`Expected revision 2 after single commit, got ${finalRev}`);
        }
      }
    });
  });

  describe('Retry & Idempotency Simulation (Profile C)', () => {
    let testEnv;

    before(async () => {
      testEnv = await createTestEnv('FIRESTORE_MODERN_STRICT');
    });

    after(async () => {
      if (testEnv) await testEnv.cleanup();
    });

    beforeEach(async () => {
      await testEnv.clearFirestore();
      await seedDocument(testEnv, 'beers/beer-idempotent', createModernBeerPayload('alice', {
        revision: 1,
        lastMutationId: 'mut-init',
        comment: 'Comentario original',
        fieldRevisions: { TYPE: 1, TIMESTAMP: 1, LOCATION: 1, COMMENT: 1, PHOTO: 1 },
      }));
    });

    it('detects already applied mutationId on network retry, achieving idempotent convergence', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-idempotent');

      // 1. First push succeeds on remote
      const pendingMutationId = 'mut-retry-test-42';
      await assertSucceeds(updateDoc(beerRef, {
        revision: 2,
        comment: 'Comentario reintentable',
        lastMutationId: pendingMutationId,
        'fieldRevisions.COMMENT': 2,
      }));

      // 2. Client simulates network timeout before receiving ACK.
      // On reconnect/retry, client performs a PULL/read before re-pushing:
      const remoteSnap = await getDoc(beerRef);
      const remoteData = remoteSnap.data();

      // Client checks if mutation was already applied:
      const alreadyApplied = remoteData.lastMutationId === pendingMutationId;
      if (!alreadyApplied) {
        throw new Error('Expected idempotency check to succeed');
      }

      // If client had blindly re-attempted the same push (rev: 2), rules would reject with PERMISSION_DENIED:
      await assertFails(updateDoc(beerRef, {
        revision: 2,
        comment: 'Comentario reintentable',
        lastMutationId: pendingMutationId,
        'fieldRevisions.COMMENT': 2,
      }));

      // Client avoids error by acknowledging mutation as CONFIRMED via the idempotent read.
      if (remoteData.revision !== 2 || remoteData.comment !== 'Comentario reintentable') {
        throw new Error('Idempotent state verification failed');
      }
    });
  });

  describe('Late Write vs Remote Tombstone (Profile C & B)', () => {
    let testEnv;

    before(async () => {
      testEnv = await createTestEnv('FIRESTORE_MODERN_STRICT');
    });

    after(async () => {
      if (testEnv) await testEnv.cleanup();
    });

    beforeEach(async () => {
      await testEnv.clearFirestore();
      // Document deleted on remote: Tombstone at revision 2
      await seedDocument(testEnv, 'beers/beer-tomb-race', createModernBeerPayload('alice', {
        revision: 2,
        isDeleted: true,
        lastMutationId: 'mut-remote-tombstone',
      }));
    });

    it('rejects stale offline write with old revision (revision 2 on tombstone rev 2)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-tomb-race');

      // Stale client tries to write active update at rev 2
      await assertFails(updateDoc(beerRef, {
        revision: 2,
        isDeleted: false,
        comment: 'Stale Edit',
        lastMutationId: 'mut-stale-offline',
      }));
    });

    it('rejects late write attempting to resurrect tombstone even with revision +1 (rev 3)', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-tomb-race');

      // Late write attempts to increment revision to 3 while setting isDeleted: false
      // Anti-resurrection rule forbids transitioning isDeleted from true to false!
      await assertFails(updateDoc(beerRef, {
        revision: 3,
        isDeleted: false,
        comment: 'Zombie Late Edit',
        lastMutationId: 'mut-zombie-offline',
        'fieldRevisions.COMMENT': 3,
      }));

      // The tombstone remains in place and deleted
      const snap = await getDoc(beerRef);
      if (snap.data().isDeleted !== true || snap.data().revision !== 2) {
        throw new Error('Tombstone was corrupted');
      }
    });
  });

  describe('Legacy Downgrade Attempt by Outdated Client (Profile B)', () => {
    let testEnv;

    before(async () => {
      testEnv = await createTestEnv('FIRESTORE_LEGACY_COMPAT');
    });

    after(async () => {
      if (testEnv) await testEnv.cleanup();
    });

    beforeEach(async () => {
      await testEnv.clearFirestore();
      // Modern document already at revision 3
      await seedDocument(testEnv, 'beers/beer-c4-active', createModernBeerPayload('alice', {
        revision: 3,
        lastMutationId: 'mut-c4-active',
        isDeleted: false,
      }));
    });

    it('rejects legacy client blind .set() attempting to overwrite modern document', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-c4-active');

      // Legacy client without sync protocol attempts legacy overwrite
      await assertFails(setDoc(beerRef, createLegacyBeerPayload('alice', {
        comment: 'Legacy Blind Overwrite',
      })));

      // Verify the modern document remains intact with revision 3
      const snap = await getDoc(beerRef);
      if (snap.data().revision !== 3 || snap.data().lastMutationId !== 'mut-c4-active') {
        throw new Error('Modern document was downgraded');
      }
    });
  });
});
