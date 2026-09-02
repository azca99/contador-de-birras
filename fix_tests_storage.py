import re

with open('tests/security.test.js', 'r', encoding='latin-1') as f:
    content = f.read()

# Add storage config
content = content.replace(
'''  testEnv = await initializeTestEnvironment({
    projectId: "demo-beer-hunter",
    firestore: { host: "127.0.0.1", port: 8888, rules: fs.readFileSync("../firestore.rules", "utf8") },
  });''',
'''  testEnv = await initializeTestEnvironment({
    projectId: "demo-beer-hunter",
    firestore: { host: "127.0.0.1", port: 8888, rules: fs.readFileSync("../firestore.rules", "utf8") },
    storage: { host: "127.0.0.1", port: 9199, rules: fs.readFileSync("../storage.rules", "utf8") },
  });'''
)

storage_tests = '''
describe("STORAGE RULES", () => {
  beforeEach(async () => {
    await testEnv.clearStorage();
    await testEnv.clearFirestore();
  });

  it("propietario puede acceder a su foto", async () => {
    const storage = testEnv.authenticatedContext("alice").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]));
    });
    // Check read and write
    await assertSucceeds(fileRef.getDownloadURL());
    await assertSucceeds(fileRef.put(new Uint8Array([0x01])));
  });

  it("amigo ACCEPTED puede acceder a la foto", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]));
        await context.firestore().collection("friendships").doc("alice_bob").set({
            user1: "alice", user2: "bob", status: "ACCEPTED", requester: "alice"
        });
    });
    const storage = testEnv.authenticatedContext("bob").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    
    // Can read
    await assertSucceeds(fileRef.getDownloadURL());
    // Cannot write
    await assertFails(fileRef.put(new Uint8Array([0x01])));
  });

  it("PENDING no puede acceder a la foto", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]));
        await context.firestore().collection("friendships").doc("alice_bob").set({
            user1: "alice", user2: "bob", status: "PENDING", requester: "alice"
        });
    });
    const storage = testEnv.authenticatedContext("bob").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    await assertFails(fileRef.getDownloadURL());
  });

  it("usuario unicamente del mismo grupo no puede", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]));
        await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
    });
    const storage = testEnv.authenticatedContext("bob").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    await assertFails(fileRef.getDownloadURL());
  });

  it("extrano no puede", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]));
    });
    const storage = testEnv.authenticatedContext("charlie").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    await assertFails(fileRef.getDownloadURL());
  });

  it("tras eliminar amistad se pierde el acceso", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]));
        // NO friendship document
    });
    const storage = testEnv.authenticatedContext("bob").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    await assertFails(fileRef.getDownloadURL());
  });
});
'''

content = content + "\n" + storage_tests

with open('tests/security.test.js', 'w', encoding='latin-1') as f:
    f.write(content)
