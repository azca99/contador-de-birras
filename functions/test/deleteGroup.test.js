const test = require('firebase-functions-test')();
const admin = require('firebase-admin');
const assert = require('assert');

// Mock admin init so it doesn't fail if we initialize it later
if (admin.apps.length === 0) {
    admin.initializeApp();
}

const myFunctions = require('../index.js');
const db = admin.firestore();

describe('deleteGroup Cloud Function', () => {
    let deleteGroupWrapped;

    before(() => {
        deleteGroupWrapped = test.wrap(myFunctions.deleteGroup);
    });

    after(async () => {
        test.cleanup();
    });
    
    beforeEach(async () => {
        // Clear collections in emulator
        const collections = await db.listCollections();
        for (const col of collections) {
            const docs = await col.get();
            const batch = db.batch();
            docs.forEach(d => batch.delete(d.ref));
            await batch.commit();
        }
    });

    it('should throw unauthenticated if not logged in', async () => {
        try {
            await deleteGroupWrapped({ groupId: 'group1' }, {});
            assert.fail('Should have thrown unauthenticated');
        } catch (e) {
            assert.strictEqual(e.code, 'unauthenticated');
        }
    });

    it('should throw not-found if group does not exist', async () => {
        try {
            await deleteGroupWrapped({ groupId: 'nonexistent' }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown not-found');
        } catch (e) {
            assert.strictEqual(e.code, 'not-found');
        }
    });

    it('should throw permission-denied if user is not the admin of the group', async () => {
        await db.collection('groups').doc('group1').set({ adminUid: 'admin1', name: 'Test' });
        try {
            await deleteGroupWrapped({ groupId: 'group1' }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown permission-denied');
        } catch (e) {
            assert.strictEqual(e.code, 'permission-denied');
        }
    });

    it('should successfully delete group, subcollections, and invitations if called by admin', async () => {
        // Setup initial data
        await db.collection('groups').doc('group1').set({
            adminUid: 'admin1',
            name: 'Test Group',
            members: ['admin1', 'member1']
        });
        await db.collection('groups').doc('group1').collection('comments').doc('comment1').set({
            text: 'Hello'
        });
        await db.collection('groupInvitations').doc('invitation1').set({
            groupId: 'group1',
            status: 'PENDING'
        });
        
        await deleteGroupWrapped({ groupId: 'group1' }, { auth: { uid: 'admin1' } });
        
        // Verify deletion
        const groupSnap = await db.collection('groups').doc('group1').get();
        assert.ok(!groupSnap.exists, 'Group should be deleted');
        
        const commentsSnap = await db.collection('groups').doc('group1').collection('comments').get();
        assert.strictEqual(commentsSnap.size, 0, 'Comments should be deleted');
        
        const invSnap = await db.collection('groupInvitations').where('groupId', '==', 'group1').get();
        assert.strictEqual(invSnap.size, 0, 'Invitations should be deleted');
    });
});
