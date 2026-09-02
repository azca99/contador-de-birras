import re

with open('tests/security.test.js', 'r', encoding='latin-1') as f:
    content = f.read()

# Fix owner read
content = content.replace(
'''    it("owner read", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
        await context.firestore().collection("sharedBeers").doc("b1").set({ userId: "alice" });
      });
      const db = testEnv.authenticatedContext("alice").firestore();
      // It was checking if it could read beers directly, now it should read sharedBeers
      await assertSucceeds(db.collection("sharedBeers").doc("b1").get());
    });''',
'''    it("owner read", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
      });
      const db = testEnv.authenticatedContext("alice").firestore();
      await assertSucceeds(db.collection("beers").doc("b1").get());
    });'''
)

# Fix friend read
content = content.replace(
'''  describe("FRIEND BEERS", () => {
    beforeEach(async () => await testEnv.clearFirestore());

    it("amistad ACCEPTED puede leer", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("users").doc("alice").set({ uid: "alice" });
        await context.firestore().collection("users").doc("bob").set({ uid: "bob" });
        await context.firestore().collection("friendships").doc("alice_bob").set({ user1: "alice", user2: "bob", status: "ACCEPTED" });
        await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
      });
      const db = testEnv.authenticatedContext("bob").firestore();
      await assertSucceeds(db.collection("beers").doc("b1").get());
    });''',
'''  describe("FRIEND BEERS", () => {
    beforeEach(async () => await testEnv.clearFirestore());

    it("amistad ACCEPTED puede leer en sharedBeers y es DENEGADO en beers", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("users").doc("alice").set({ uid: "alice" });
        await context.firestore().collection("users").doc("bob").set({ uid: "bob" });
        await context.firestore().collection("friendships").doc("alice_bob").set({ user1: "alice", user2: "bob", status: "ACCEPTED" });
        await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
        await context.firestore().collection("sharedBeers").doc("b1").set({ userId: "alice" });
      });
      const db = testEnv.authenticatedContext("bob").firestore();
      await assertSucceeds(db.collection("sharedBeers").doc("b1").get());
      await assertFails(db.collection("beers").doc("b1").get());
    });'''
)

with open('tests/security.test.js', 'w', encoding='latin-1') as f:
    f.write(content)
