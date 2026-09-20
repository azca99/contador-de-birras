const test = require('firebase-functions-test')();
const admin = require('firebase-admin');
const assert = require('assert');

// Mock admin init so it doesn't fail if we initialize it later
if (admin.apps.length === 0) {
    admin.initializeApp();
}

const myFunctions = require('../index.js');
const db = admin.firestore();

describe('setUsername Cloud Function', () => {
    let setUsernameWrapped;

    before(() => {
        setUsernameWrapped = test.wrap(myFunctions.setUsername);
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
            await setUsernameWrapped({ username: 'bob123' }, {});
            assert.fail('Should have thrown unauthenticated');
        } catch (e) {
            assert.strictEqual(e.code, 'unauthenticated');
        }
    });

    it('should throw invalid-argument for bad username', async () => {
        try {
            await setUsernameWrapped({ username: 'a' }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown invalid-argument');
        } catch (e) {
            assert.strictEqual(e.code, 'invalid-argument');
        }
    });

    it('should successfully create first username and publicUser document', async () => {
        await setUsernameWrapped({ username: 'Alice99' }, { auth: { uid: 'user1' } });
        
        const usernameDoc = await db.collection('usernames').doc('alice99').get();
        assert.ok(usernameDoc.exists);
        assert.strictEqual(usernameDoc.data().uid, 'user1');
        
        const publicUserDoc = await db.collection('publicUsers').doc('user1').get();
        assert.ok(publicUserDoc.exists);
        assert.strictEqual(publicUserDoc.data().username, 'Alice99');
        assert.ok(publicUserDoc.data().createdAt);
    });

    it('should throw already-exists if username is taken by someone else', async () => {
        await db.collection('usernames').doc('bob').set({ uid: 'user2', usernameLowercase: 'bob' });
        
        try {
            await setUsernameWrapped({ username: 'Bob' }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown already-exists');
        } catch (e) {
            assert.strictEqual(e.code, 'already-exists');
        }
    });

    it('should throw already-exists if legacy username exists in publicUsers without reservation', async () => {
        await db.collection('publicUsers').doc('legacyUser').set({
            uid: 'legacyUser',
            username: 'Bob',
            usernameLowercase: 'bob'
        });
        
        try {
            await setUsernameWrapped({ username: 'Bob' }, { auth: { uid: 'user1' } });
            assert.fail('Should have thrown already-exists');
        } catch (e) {
            assert.strictEqual(e.code, 'already-exists');
        }
    });

    it('should allow legacy owner to reclaim their own username and create reservation', async () => {
        await db.collection('publicUsers').doc('legacyUser').set({
            uid: 'legacyUser',
            username: 'Bob',
            usernameLowercase: 'bob'
        });
        
        await setUsernameWrapped({ username: 'Bob' }, { auth: { uid: 'legacyUser' } });
        
        const usernameDoc = await db.collection('usernames').doc('bob').get();
        assert.ok(usernameDoc.exists);
        assert.strictEqual(usernameDoc.data().uid, 'legacyUser');
    });

    it('should allow user to change username and free the old one', async () => {
        await setUsernameWrapped({ username: 'UserOld' }, { auth: { uid: 'user1' } });
        
        let oldDoc = await db.collection('usernames').doc('userold').get();
        assert.ok(oldDoc.exists);
        
        await setUsernameWrapped({ username: 'UserNew' }, { auth: { uid: 'user1' } });
        
        oldDoc = await db.collection('usernames').doc('userold').get();
        assert.ok(!oldDoc.exists, 'Old username should be freed');
        
        const newDoc = await db.collection('usernames').doc('usernew').get();
        assert.ok(newDoc.exists);
        assert.strictEqual(newDoc.data().uid, 'user1');
        
        const publicUserDoc = await db.collection('publicUsers').doc('user1').get();
        assert.strictEqual(publicUserDoc.data().username, 'UserNew');
    });

    it('idempotencia: same user sending same request twice should succeed', async () => {
        await setUsernameWrapped({ username: 'UserOld' }, { auth: { uid: 'user1' } });
        await setUsernameWrapped({ username: 'UserOld' }, { auth: { uid: 'user1' } });
        
        const usernameDoc = await db.collection('usernames').doc('userold').get();
        assert.ok(usernameDoc.exists);
    });

    it('should handle concurrent requests for the same username', async function() {
        this.timeout(5000);
        const promises = [
            setUsernameWrapped({ username: 'Concurrent' }, { auth: { uid: 'user1' } }).catch(e => e),
            setUsernameWrapped({ username: 'Concurrent' }, { auth: { uid: 'user2' } }).catch(e => e)
        ];
        
        const results = await Promise.all(promises);
        
        // One should succeed, one should fail with already-exists
        const successCount = results.filter(r => r.success === true).length;
        const errorCount = results.filter(r => r && r.code === 'already-exists').length;
        
        assert.strictEqual(successCount, 1);
        assert.strictEqual(errorCount, 1);
    });
});
