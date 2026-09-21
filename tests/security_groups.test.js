const { assertFails, assertSucceeds, initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-beer-hunter",
    firestore: { host: "127.0.0.1", port: 8888, rules: fs.readFileSync("./firestore.rules", "utf8") },
  });
});

after(async () => {
  await testEnv.cleanup();
});

describe("GROUPS AND GROUP DELETION SECURITY RULES", () => {
  beforeEach(async () => await testEnv.clearFirestore());

  it("admin cannot delete directly", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Alice's Group", createdAt: 123 });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("groups").doc("g1").delete());
  });

  it("non-admin member cannot delete directly", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123 });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").delete());
  });

  it("outsider cannot delete directly", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123 });
    });
    const db = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(db.collection("groups").doc("g1").delete());
  });

  it("non-admin member CAN abandon group", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123 });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").update({
      adminUid: "alice",
      members: ["alice"],
      name: "Group",
      createdAt: 123
    }));
  });

  it("admin CANNOT use abandon flow to leave group", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123 });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("groups").doc("g1").update({
      adminUid: "alice",
      members: ["bob"],
      name: "Group",
      createdAt: 123
    }));
  });
  
  it("creation of group still works", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("groups").doc("g2").set({
      adminUid: "alice",
      members: ["alice"],
      name: "New Group",
      createdAt: 12345
    }));
  });

});
