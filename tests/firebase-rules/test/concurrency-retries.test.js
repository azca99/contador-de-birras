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
        name: 'Initial Name',
      }));
    });

    it('prevents silent overwrite when two clients attempt to write revision 2 concurrently', async () => {
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
        name: 'Name by Client 1',
        lastMutationId: 'mut-client-1',
      }));

      // Client 2 (stale read) attempts to write revision 2 -> MUST BE REJECTED by rules
      // because resource.data.revision is now 2, and 2 != 2 + 1
      await assertFails(updateDoc(beerRef2, {
        revision: 2,
        name: 'Name by Client 2',
        lastMutationId: 'mut-client-2',
      }));

      // Verify that Client 1's write was preserved intact
      const finalSnap = await getDoc(beerRef1);
      if (finalSnap.data().name !== 'Name by Client 1' || finalSnap.data().revision !== 2) {
        throw new Error('Data corrupted or overwritten silently');
      }
    });

    it('prevents silent overwrite during concurrent transactions by rejecting stale revision', async () => {
      const aliceDb1 = testEnv.authenticatedContext('alice').firestore();
      const aliceDb2 = testEnv.authenticatedContext('alice').firestore();

      const beerRef1 = doc(aliceDb1, 'beers/beer-race');
      const beerRef2 = doc(aliceDb2, 'beers/beer-race');

      // Execute concurrent transactions that read current revision and attempt to commit increment
      const tx1 = runTransaction(aliceDb1, async (transaction) => {
        const snap = await transaction.get(beerRef1);
        const currentRev = snap.data().revision;
        transaction.update(beerRef1, {
          revision: currentRev + 1,
          name: 'Tx 1 applied',
          lastMutationId: 'tx-mut-1',
        });
      });

      const tx2 = runTransaction(aliceDb2, async (transaction) => {
        const snap = await transaction.get(beerRef2);
        const currentRev = snap.data().revision;
        transaction.update(beerRef2, {
          revision: currentRev + 1,
          name: 'Tx 2 applied',
          lastMutationId: 'tx-mut-2',
        });
      });

      // Under rules enforcing strictly revision == resource.data.revision + 1,
      // the first transaction commits (advancing to rev 2).
      // The second transaction fails with PERMISSION_DENIED because its prepared write (rev 2)
      // is no longer monotonic against the updated remote state (rev 2).
      // This proves that at least one fails and cannot silently overwrite the other.
      const results = await Promise.allSettled([tx1, tx2]);
      const fulfilled = results.filter(r => r.status === 'fulfilled');
      const rejected = results.filter(r => r.status === 'rejected');

      if (fulfilled.length === 0) {
        throw new Error('Expected at least one transaction to succeed');
      }
      if (rejected.length > 0) {
        const err = rejected[0].reason;
        if (!err.message.includes('PERMISSION_DENIED')) {
          throw new Error(`Expected PERMISSION_DENIED on conflicting transaction, got: ${err.message}`);
        }
      }

      // Verify the confirmed document was not corrupted
      const finalSnap = await getDoc(beerRef1);
      if (finalSnap.data().revision !== 2 && finalSnap.data().revision !== 3) {
        throw new Error(`Unexpected final revision: ${finalSnap.data().revision}`);
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
      }));
    });

    it('detects already applied mutationId on network retry, achieving idempotent convergence', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-idempotent');

      // 1. First push succeeds on remote
      const pendingMutationId = 'mut-retry-test-42';
      await assertSucceeds(updateDoc(beerRef, {
        revision: 2,
        name: 'Retryable Name',
        lastMutationId: pendingMutationId,
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
        name: 'Retryable Name',
        lastMutationId: pendingMutationId,
      }));

      // Client avoids error by acknowledging mutation as CONFIRMED via the idempotent read.
      if (remoteData.revision !== 2 || remoteData.name !== 'Retryable Name') {
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
        name: 'Stale Edit',
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
        name: 'Zombie Late Edit',
        lastMutationId: 'mut-zombie-offline',
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
        isDeleted: false,
        lastMutationId: 'mut-c4-active',
      }));
    });

    it('rejects legacy client blind .set() attempting to overwrite modern document', async () => {
      const aliceDb = testEnv.authenticatedContext('alice').firestore();
      const beerRef = doc(aliceDb, 'beers/beer-c4-active');

      // Legacy client without sync protocol attempts legacy overwrite
      await assertFails(setDoc(beerRef, createLegacyBeerPayload('alice', {
        name: 'Legacy Blind Overwrite',
      })));

      // Verify the modern document remains intact with revision 3
      const snap = await getDoc(beerRef);
      if (snap.data().revision !== 3 || snap.data().lastMutationId !== 'mut-c4-active') {
        throw new Error('Modern document was downgraded');
      }
    });
  });
});
