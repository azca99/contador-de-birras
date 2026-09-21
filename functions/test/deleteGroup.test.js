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

    // 1. sin Auth -> unauthenticated
    it('1. should throw unauthenticated if not logged in', async () => {
        try {
            await deleteGroupWrapped({ groupId: 'group1' }, {});
            assert.fail('Should have thrown unauthenticated');
        } catch (e) {
            assert.strictEqual(e.code, 'unauthenticated');
        }
    });

    // 2. groupId ausente -> invalid-argument
    it('2. should throw invalid-argument if groupId is missing', async () => {
        try {
            await deleteGroupWrapped({}, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown invalid-argument');
        } catch (e) {
            assert.strictEqual(e.code, 'invalid-argument');
        }
    });

    // 3. groupId no string -> invalid-argument
    it('3. should throw invalid-argument if groupId is not a string', async () => {
        try {
            await deleteGroupWrapped({ groupId: 123 }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown invalid-argument');
        } catch (e) {
            assert.strictEqual(e.code, 'invalid-argument');
        }
    });

    // 4. grupo inexistente -> not-found
    it('4. should throw not-found if group does not exist', async () => {
        try {
            await deleteGroupWrapped({ groupId: 'nonexistent' }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown not-found');
        } catch (e) {
            assert.strictEqual(e.code, 'not-found');
        }
    });

    // 5. miembro no admin -> permission-denied
    it('5. should throw permission-denied if user is a member but not admin', async () => {
        await db.collection('groups').doc('group1').set({ adminUid: 'admin1', members: ['admin1', 'member1'], name: 'Test' });
        try {
            await deleteGroupWrapped({ groupId: 'group1' }, { auth: { uid: 'member1' } });
            assert.fail('Should have thrown permission-denied');
        } catch (e) {
            assert.strictEqual(e.code, 'permission-denied');
        }
    });

    // 6. usuario ajeno -> permission-denied
    it('6. should throw permission-denied if user is an outsider', async () => {
        await db.collection('groups').doc('group1').set({ adminUid: 'admin1', members: ['admin1', 'member1'], name: 'Test' });
        try {
            await deleteGroupWrapped({ groupId: 'group1' }, { auth: { uid: 'outsider1' } });
            assert.fail('Should have thrown permission-denied');
        } catch (e) {
            assert.strictEqual(e.code, 'permission-denied');
        }
    });

    // 7. admin -> éxito + validación de efectos
    it('7-17. should successfully delete group and verify all data effects when called by admin', async () => {
        // Setup initial data for all these assertions
        await db.collection('groups').doc('group1').set({ adminUid: 'admin1', members: ['admin1', 'member1'] });
        await db.collection('groups').doc('group2').set({ adminUid: 'admin2', members: ['admin2'] });
        
        // 9. Comentarios
        await db.collection('groups').doc('group1').collection('comments').doc('comment1').set({ text: 'Hello 1' });
        // 10. Más de un comentario
        await db.collection('groups').doc('group1').collection('comments').doc('comment2').set({ text: 'Hello 2' });
        
        // 17. Otras subcolecciones
        await db.collection('groups').doc('group1').collection('custom_subcol').doc('doc1').set({ text: 'Something else' });

        // 11. Invitaciones del grupo
        await db.collection('groupInvitations').doc('invitation1').set({ groupId: 'group1', status: 'PENDING' });
        await db.collection('groupInvitations').doc('invitation2').set({ groupId: 'group1', status: 'ACCEPTED' });
        
        // 12. Invitaciones de otro grupo
        await db.collection('groupInvitations').doc('invitation3').set({ groupId: 'group2', status: 'PENDING' });

        // 13. users
        await db.collection('users').doc('admin1').set({ someData: 'yes' });
        
        // 14. publicUsers
        await db.collection('publicUsers').doc('admin1').set({ username: 'admin1' });

        // 15. beers
        await db.collection('beers').doc('beer1').set({ name: 'IPA' });

        // 16. friendships
        await db.collection('friendships').doc('friend1').set({ user1: 'admin1' });

        // Ejecutar eliminación
        await deleteGroupWrapped({ groupId: 'group1' }, { auth: { uid: 'admin1' } });
        
        // 8. Documento raíz eliminado
        const groupSnap = await db.collection('groups').doc('group1').get();
        assert.ok(!groupSnap.exists, '8. Group document should be deleted');
        
        // 9 & 10. Comentarios eliminados
        const commentsSnap = await db.collection('groups').doc('group1').collection('comments').get();
        assert.strictEqual(commentsSnap.size, 0, '9,10. Comments should be deleted');
        
        // 17. Otras subcolecciones eliminadas
        const customSnap = await db.collection('groups').doc('group1').collection('custom_subcol').get();
        assert.strictEqual(customSnap.size, 0, '17. Custom subcollections should be deleted');
        
        // 11. Invitaciones del grupo eliminadas
        const invSnap1 = await db.collection('groupInvitations').where('groupId', '==', 'group1').get();
        assert.strictEqual(invSnap1.size, 0, '11. Group invitations should be deleted');

        // 12. Invitaciones de OTRO grupo permanecen
        const invSnap2 = await db.collection('groupInvitations').where('groupId', '==', 'group2').get();
        assert.strictEqual(invSnap2.size, 1, '12. Other group invitations should remain');

        // 13. users permanece
        const usersSnap = await db.collection('users').doc('admin1').get();
        assert.ok(usersSnap.exists, '13. Users remain');

        // 14. publicUsers permanece
        const publicUsersSnap = await db.collection('publicUsers').doc('admin1').get();
        assert.ok(publicUsersSnap.exists, '14. publicUsers remain');

        // 15. beers permanece
        const beersSnap = await db.collection('beers').doc('beer1').get();
        assert.ok(beersSnap.exists, '15. beers remain');

        // 16. friendships permanece
        const friendshipsSnap = await db.collection('friendships').doc('friend1').get();
        assert.ok(friendshipsSnap.exists, '16. friendships remain');
    });

    // 18. llamada posterior sobre grupo ya eliminado devuelve not-found
    it('18. should throw not-found when retrying deletion on an already deleted group', async () => {
        // No creamos el grupo porque supuestamente ya está borrado
        try {
            await deleteGroupWrapped({ groupId: 'already_deleted' }, { auth: { uid: 'admin1' } });
            assert.fail('Should have thrown not-found');
        } catch (e) {
            assert.strictEqual(e.code, 'not-found');
        }
    });
});
