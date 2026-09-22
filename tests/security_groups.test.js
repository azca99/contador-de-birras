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
  it("valid invitation acceptance allows joining group", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group", createdAt: 123 });
      await context.firestore().collection("groupInvitations").doc("g1_bob").set({ groupId: "g1", inviteeUid: "bob", status: "ACCEPTED" });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").update({
      adminUid: "alice",
      members: ["alice", "bob"],
      name: "Group",
      createdAt: 123
    }));
  });

  it("valid member can create a comment", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123 });
      await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bobby" });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").set({
      text: "hello",
      authorUid: "bob",
      authorName: "Bob",
      authorUsername: "bobby",
      createdAt: 123
    }));
  });

  it("outsider cannot create a comment", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123 });
      await context.firestore().collection("publicUsers").doc("charlie").set({ displayName: "Charlie", username: "chuck" });
    });
    const db = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").set({
      text: "hello",
      authorUid: "charlie",
      authorName: "Charlie",
      authorUsername: "chuck",
      createdAt: 123
    }));
  });

  it("member can read comments", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123 });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({ text: "hello", authorUid: "alice", createdAt: 123 });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").get());
  });

  it("client cannot create directly groupInvitations", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group", createdAt: 123 });
    });
    const dbAlice = testEnv.authenticatedContext("alice").firestore();
    await assertFails(dbAlice.collection("groupInvitations").doc("g1_bob").set({
      groupId: "g1",
      groupName: "Group",
      inviteeUid: "bob",
      inviterUid: "alice",
      status: "PENDING"
    }));
  });

  it("client can read their own groupInvitations", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group", createdAt: 123 });
      await context.firestore().collection("groupInvitations").doc("g1_bob").set({
        groupId: "g1",
        groupName: "Group",
        inviteeUid: "bob",
        inviterUid: "alice",
        status: "PENDING"
      });
    });
    const dbBob = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(dbBob.collection("groupInvitations").doc("g1_bob").get());
    const dbCharlie = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(dbCharlie.collection("groupInvitations").doc("g1_bob").get());
  });

  it("client cannot resend/update invitation directly", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group", createdAt: 123 });
      await context.firestore().collection("groupInvitations").doc("g1_bob").set({
        groupId: "g1",
        groupName: "Group",
        inviteeUid: "bob",
        inviterUid: "alice",
        status: "REJECTED"
      });
    });
    const dbAlice = testEnv.authenticatedContext("alice").firestore();
    await assertFails(dbAlice.collection("groupInvitations").doc("g1_bob").update({
      status: "PENDING"
    }));
  });

  // deleting: true tests
  it("admin cannot set deleting:true", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("groups").doc("g3").set({
      adminUid: "alice",
      members: ["alice"],
      name: "Group 3",
      createdAt: 123,
      deleting: true
    }));
  });

  it("member cannot set deleting:true", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123 });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").update({
      deleting: true
    }));
  });

  it("admin cannot eliminate or change deleting flag", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group", createdAt: 123, deleting: true });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("groups").doc("g1").update({
      deleting: false
    }));
  });

  it("member cannot abandon when deleting=true", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123, deleting: true });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").update({
      adminUid: "alice",
      members: ["alice"],
      name: "Group",
      createdAt: 123
    }));
  });

  it("new invitation or resend blocked when deleting=true", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group", createdAt: 123, deleting: true });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("groupInvitations").doc("g1_bob").set({
      groupId: "g1",
      groupName: "Group",
      inviteeUid: "bob",
      inviterUid: "alice",
      status: "PENDING"
    }));
  });

  it("invitation acceptance blocked when deleting=true", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group", createdAt: 123, deleting: true });
      await context.firestore().collection("groupInvitations").doc("g1_bob").set({ groupId: "g1", groupName: "Group", inviteeUid: "bob", inviterUid: "alice", status: "PENDING" });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groupInvitations").doc("g1_bob").update({
      status: "ACCEPTED"
    }));
  });

  it("create comment blocked when deleting=true", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123, deleting: true });
      await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bobby" });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").set({
      text: "hello",
      authorUid: "bob",
      authorName: "Bob",
      authorUsername: "bobby",
      createdAt: 123
    }));
  });

  it("edit comment blocked when deleting=true", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123, deleting: true });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({ text: "old", authorUid: "bob", authorName: "Bob", authorUsername: "bobby", createdAt: 123 });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").update({
      text: "new"
    }));
  });

  it("delete comment blocked when deleting=true", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123, deleting: true });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({ text: "hello", authorUid: "bob", authorName: "Bob", authorUsername: "bobby", createdAt: 123 });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").delete());
  });

  it("normal operations work when deleting does not exist", async () => {
    // This just re-verifies one normal operation to make sure we didn't break them globally
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group", createdAt: 123 });
      await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bobby" });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({ text: "hello", authorUid: "bob", authorName: "Bob", authorUsername: "bobby", createdAt: 123 });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").update({
      text: "new"
    }));
  });
});
