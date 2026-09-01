const { assertFails, assertSucceeds, initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-beer-hunter",
    firestore: { rules: fs.readFileSync("firestore.rules", "utf8") },
  });
});

after(async () => {
  await testEnv.cleanup();
});

describe("Firestore Security Rules", () => {
  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  it("Propietario puede crear cerveza", async () => {
    const alice = testEnv.authenticatedContext("alice");
    await assertSucceeds(alice.firestore().collection("beers").doc("beer1").set({ userId: "alice" }));
  });

  it("Usuario no puede crear cerveza haciendose pasar por otro", async () => {
    const bob = testEnv.authenticatedContext("bob");
    await assertFails(bob.firestore().collection("beers").doc("beer2").set({ userId: "alice" }));
  });

  it("Amigo aceptado puede leer cervezas", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("beer1").set({ userId: "alice" });
      await context.firestore().collection("friendships").doc("alice_bob").set({ user1: "alice", user2: "bob", status: "ACCEPTED" });
    });
    const bob = testEnv.authenticatedContext("bob");
    await assertSucceeds(bob.firestore().collection("beers").doc("beer1").get());
  });

  it("Usuario aleatorio no puede leer cerveza privada", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("beer1").set({ userId: "alice" });
    });
    const charlie = testEnv.authenticatedContext("charlie");
    await assertFails(charlie.firestore().collection("beers").doc("beer1").get());
  });
});

