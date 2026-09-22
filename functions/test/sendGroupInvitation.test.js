const test = require('firebase-functions-test')();
const admin = require('firebase-admin');
const assert = require('assert');

if (admin.apps.length === 0) {
    admin.initializeApp();
}

const myFunctions = require('../index.js');
const db = admin.firestore();

describe('sendGroupInvitation Cloud Function', () => {
    let sendGroupInvitationWrapped;

    before(() => {
        sendGroupInvitationWrapped = test.wrap(myFunctions.sendGroupInvitation);
    });

    after(async () => {
        test.cleanup();
    });

    beforeEach(async () => {
        const collections = await db.listCollections();
        for (let collection of collections) {
            const snapshot = await collection.get();
            const batch = db.batch();
            snapshot.docs.forEach((doc) => {
                batch.delete(doc.ref);
            });
            await batch.commit();
        }
    });

    it('1. should throw unauthenticated if not logged in', async () => {
        try {
            await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2' }, {});
            assert.fail('Should have thrown');
        } catch (e) {
            assert.strictEqual(e.code, 'unauthenticated');
        }
    });

    it('2. should throw invalid-argument if groupId is invalid', async () => {
        try {
            await sendGroupInvitationWrapped({ inviteeUid: 'user2' }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown');
        } catch (e) {
            assert.strictEqual(e.code, 'invalid-argument');
        }
    });

    it('3. should throw invalid-argument if inviteeUid is invalid', async () => {
        try {
            await sendGroupInvitationWrapped({ groupId: 'g1' }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown');
        } catch (e) {
            assert.strictEqual(e.code, 'invalid-argument');
        }
    });

    it('4. should throw not-found if group does not exist', async () => {
        try {
            await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2' }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown');
        } catch (e) {
            assert.strictEqual(e.code, 'not-found');
        }
    });

    it('5. should throw permission-denied if caller is not in members', async () => {
        await db.collection('groups').doc('g1').set({ adminUid: 'admin1', members: ['admin1'], name: 'Group 1' });
        try {
            await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2' }, { auth: { uid: 'user3' } });
            assert.fail('Should have thrown');
        } catch (e) {
            assert.strictEqual(e.code, 'permission-denied');
        }
    });

    it('6. caller is normal member -> can invite', async () => {
        await db.collection('groups').doc('g1').set({ adminUid: 'admin1', members: ['admin1', 'user1'], name: 'Group 1' });
        await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2' }, { auth: { uid: 'user1' } });
        const inv = await db.collection('groupInvitations').doc('g1_user2').get();
        assert.ok(inv.exists);
        assert.strictEqual(inv.data().inviterUid, 'user1');
    });

    it('7. admin -> can invite', async () => {
        await db.collection('groups').doc('g1').set({ adminUid: 'admin1', members: ['admin1'], name: 'Group 1' });
        await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2' }, { auth: { uid: 'admin1' } });
        const inv = await db.collection('groupInvitations').doc('g1_user2').get();
        assert.ok(inv.exists);
        assert.strictEqual(inv.data().inviterUid, 'admin1');
    });

    it('8. should throw invalid-argument if trying to invite oneself', async () => {
        try {
            await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user1' }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown');
        } catch (e) {
            assert.strictEqual(e.code, 'invalid-argument');
        }
    });

    it('9. should throw already-exists if invitee is already a member', async () => {
        await db.collection('groups').doc('g1').set({ adminUid: 'admin1', members: ['admin1', 'user2'], name: 'Group 1' });
        try {
            await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2' }, { auth: { uid: 'admin1' } });
            assert.fail('Should have thrown');
        } catch (e) {
            assert.strictEqual(e.code, 'already-exists');
        }
    });

    it('10. should throw failed-precondition if group is deleting:true', async () => {
        await db.collection('groups').doc('g1').set({ adminUid: 'admin1', members: ['admin1'], name: 'Group 1', deleting: true });
        try {
            await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2' }, { auth: { uid: 'admin1' } });
            assert.fail('Should have thrown');
        } catch (e) {
            assert.strictEqual(e.code, 'failed-precondition');
        }
    });

    it('11. success creates deterministic document properly', async () => {
        await db.collection('groups').doc('g1').set({ adminUid: 'admin1', members: ['admin1'], name: 'Group 1' });
        await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2' }, { auth: { uid: 'admin1' } });
        const inv = await db.collection('groupInvitations').doc('g1_user2').get();
        assert.ok(inv.exists);
        assert.strictEqual(inv.data().groupId, 'g1');
        assert.strictEqual(inv.data().inviteeUid, 'user2');
        assert.strictEqual(inv.data().status, 'PENDING');
    });

    it('12. inviterUid comes from authenticated context', async () => {
        await db.collection('groups').doc('g1').set({ adminUid: 'admin1', members: ['admin1'], name: 'Group 1' });
        // Enviar con auth.uid=admin1 pero intentar forzar inviterUid (no es posible por el diseño de data, pero verificamos que usa auth.uid)
        await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2', inviterUid: 'hacker' }, { auth: { uid: 'admin1' } });
        const inv = await db.collection('groupInvitations').doc('g1_user2').get();
        assert.strictEqual(inv.data().inviterUid, 'admin1');
    });

    it('13. groupName comes from the real group document', async () => {
        await db.collection('groups').doc('g1').set({ adminUid: 'admin1', members: ['admin1'], name: 'Real Group Name' });
        await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2', groupName: 'Fake Name' }, { auth: { uid: 'admin1' } });
        const inv = await db.collection('groupInvitations').doc('g1_user2').get();
        assert.strictEqual(inv.data().groupName, 'Real Group Name');
    });

    it('14. resending is allowed when appropriate (overwrites PENDING or REJECTED)', async () => {
        await db.collection('groups').doc('g1').set({ adminUid: 'admin1', members: ['admin1'], name: 'Group 1' });
        await db.collection('groupInvitations').doc('g1_user2').set({ groupId: 'g1', inviteeUid: 'user2', status: 'REJECTED' });
        
        await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2' }, { auth: { uid: 'admin1' } });
        const inv = await db.collection('groupInvitations').doc('g1_user2').get();
        assert.strictEqual(inv.data().status, 'PENDING');
    });

    it('15. invitation of another group is not modified', async () => {
        await db.collection('groups').doc('g1').set({ adminUid: 'admin1', members: ['admin1'], name: 'Group 1' });
        await db.collection('groups').doc('g2').set({ adminUid: 'admin1', members: ['admin1'], name: 'Group 2' });
        
        await db.collection('groupInvitations').doc('g2_user2').set({ groupId: 'g2', inviteeUid: 'user2', status: 'REJECTED' });
        
        await sendGroupInvitationWrapped({ groupId: 'g1', inviteeUid: 'user2' }, { auth: { uid: 'admin1' } });
        
        const inv1 = await db.collection('groupInvitations').doc('g1_user2').get();
        assert.strictEqual(inv1.data().status, 'PENDING');
        
        const inv2 = await db.collection('groupInvitations').doc('g2_user2').get();
        assert.strictEqual(inv2.data().status, 'REJECTED'); // Remains unchanged
    });
});
