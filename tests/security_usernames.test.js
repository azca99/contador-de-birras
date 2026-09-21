const { assertFails, assertSucceeds, initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

describe("USERNAMES AND PUBLIC USERS (security_usernames.test.js)", () => {
  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "beer-hunter",
      firestore: {
        rules: fs.readFileSync("../firestore.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  let alice, unauth;
  beforeEach(async () => {
    await testEnv.clearFirestore();
    alice = testEnv.authenticatedContext("alice").firestore();
    unauth = testEnv.unauthenticatedContext().firestore();
  });

  it("client cannot write to usernames collection", async () => {
    await assertFails(alice.collection("usernames").doc("alice").set({ uid: "alice" }));
  });

  it("client cannot read from usernames collection if authenticated", async () => {
    await assertFails(alice.collection("usernames").doc("alice").get());
  });
  
  it("unauthenticated cannot read from usernames collection", async () => {
    await assertFails(unauth.collection("usernames").doc("alice").get());
  });

  it("client cannot create publicUsers with username", async () => {
    await assertFails(alice.collection("publicUsers").doc("alice").set({ 
      uid: "alice", 
      displayName: "Alice", 
      username: "alice", 
      usernameLowercase: "alice",
      createdAt: 123, 
      updatedAt: 123 
    }));
  });
  
  it("client cannot update publicUsers to change username", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("publicUsers").doc("alice").set({
        uid: "alice",
        displayName: "Alice",
        username: "alice",
        usernameLowercase: "alice",
        createdAt: 123,
        updatedAt: 123,
        usernameUpdatedAt: 123
      });
    });
    
    // Trying to change username
    await assertFails(alice.collection("publicUsers").doc("alice").update({
      username: "hacker"
    }));
    
    // Trying to change displayName should succeed
    await assertSucceeds(alice.collection("publicUsers").doc("alice").update({
      displayName: "Alice New",
      photoUrl: "https://example.com/photo.jpg"
    }));
  });

  it("client cannot delete publicUsers document", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("publicUsers").doc("alice").set({
        uid: "alice",
        displayName: "Alice",
        username: "alice",
        usernameLowercase: "alice",
        createdAt: 123,
        updatedAt: 123,
        usernameUpdatedAt: 123
      });
    });
    
    // Owner cannot delete
    await assertFails(alice.collection("publicUsers").doc("alice").delete());
    
    // Other users cannot delete
    let bob = testEnv.authenticatedContext("bob").firestore();
    await assertFails(bob.collection("publicUsers").doc("alice").delete());
  });
});
