with open('tests/security.test.js', 'r', encoding='latin-1') as f:
    content = f.read()

# Let's fix the test logic just in case it's a timing issue
content = content.replace(
'''  it("amigo ACCEPTED puede acceder a la foto", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]), { contentType: "image/jpeg" });
        await context.firestore().collection("friendships").doc("alice_bob").set({
            user1: "alice", user2: "bob", status: "ACCEPTED", requester: "alice"
        });
    });
    const storage = testEnv.authenticatedContext("bob").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    
    // Can read
    await assertSucceeds(fileRef.getDownloadURL());''',
'''  it("amigo ACCEPTED puede acceder a la foto", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]), { contentType: "image/jpeg" });
        await context.firestore().collection("friendships").doc("alice_bob").set({
            user1: "alice", user2: "bob", status: "ACCEPTED", requester: "alice"
        });
    });
    const storage = testEnv.authenticatedContext("bob").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    
    // Can read
    await new Promise(resolve => setTimeout(resolve, 500)); // wait for cross-service sync
    await assertSucceeds(fileRef.getDownloadURL());'''
)

with open('tests/security.test.js', 'w', encoding='latin-1') as f:
    f.write(content)
