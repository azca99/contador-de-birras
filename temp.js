const fs = require("fs");
const content = fs.readFileSync("tests/security.test.js", "utf8");

// Append new tests to the end of the file
const newTests = `

  describe("GROUP INVITATIONS", () => {
    beforeEach(async () => await testEnv.clearFirestore());

    it("admin puede invitar", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group 1", createdAt: 123 });
      });
      const db = testEnv.authenticatedContext("alice").firestore();
      await assertSucceeds(db.collection("groupInvitations").doc("g1_bob").set({
        groupId: "g1", groupName: "Group 1", inviterUid: "alice", inviteeUid: "bob", status: "PENDING"
      }));
    });

    it("no admin no puede invitar", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group 1", createdAt: 123 });
      });
      const db = testEnv.authenticatedContext("bob").firestore();
      await assertFails(db.collection("groupInvitations").doc("g1_charlie").set({
        groupId: "g1", groupName: "Group 1", inviterUid: "bob", inviteeUid: "charlie", status: "PENDING"
      }));
    });

    it("invitee puede aceptar y actualizar grupo (transaction)", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group 1", createdAt: 123 });
        await context.firestore().collection("groupInvitations").doc("g1_bob").set({
          groupId: "g1", groupName: "Group 1", inviterUid: "alice", inviteeUid: "bob", status: "PENDING"
        });
      });
      const db = testEnv.authenticatedContext("bob").firestore();
      
      const batch = db.batch();
      batch.update(db.collection("groupInvitations").doc("g1_bob"), { status: "ACCEPTED" });
      batch.update(db.collection("groups").doc("g1"), { members: ["alice", "bob"] });
      
      await assertSucceeds(batch.commit());
    });

    it("invitee no puede unirse sin PENDING", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group 1", createdAt: 123 });
      });
      const db = testEnv.authenticatedContext("bob").firestore();
      
      const batch = db.batch();
      // Trying to accept non-existent invitation will fail
      batch.set(db.collection("groupInvitations").doc("g1_bob"), { status: "ACCEPTED", groupId: "g1", groupName: "Group 1", inviterUid: "alice", inviteeUid: "bob" });
      batch.update(db.collection("groups").doc("g1"), { members: ["alice", "bob"] });
      
      await assertFails(batch.commit());
    });

    it("charlie no puede autoaceptar invitacion de bob", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], name: "Group 1", createdAt: 123 });
        await context.firestore().collection("groupInvitations").doc("g1_bob").set({
          groupId: "g1", groupName: "Group 1", inviterUid: "alice", inviteeUid: "bob", status: "PENDING"
        });
      });
      const db = testEnv.authenticatedContext("charlie").firestore();
      
      const batch = db.batch();
      batch.update(db.collection("groupInvitations").doc("g1_bob"), { status: "ACCEPTED" });
      
      await assertFails(batch.commit());
    });
  });

  describe("COMMENTS - ANTI SPOOFING", () => {
    beforeEach(async () => await testEnv.clearFirestore());

    it("Bob puede crear comentario con sus datos legitimos", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob M", username: "bobm", uid: "bob", photoUrl: "", createdAt: 123, updatedAt: 123, usernameUpdatedAt: 123 });
        await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group 1", createdAt: 123 });
      });
      const db = testEnv.authenticatedContext("bob").firestore();
      await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "bob", authorName: "Bob M", authorUsername: "bobm", text: "Hello", createdAt: 123
      }));
    });

    it("Bob no puede crear comentario con authorName de Alice", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob M", username: "bobm", uid: "bob", photoUrl: "", createdAt: 123, updatedAt: 123, usernameUpdatedAt: 123 });
        await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group 1", createdAt: 123 });
      });
      const db = testEnv.authenticatedContext("bob").firestore();
      await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "bob", authorName: "Alice A", authorUsername: "bobm", text: "Hello", createdAt: 123
      }));
    });

    it("Bob no puede crear comentario con authorUsername de Alice", async () => {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob M", username: "bobm", uid: "bob", photoUrl: "", createdAt: 123, updatedAt: 123, usernameUpdatedAt: 123 });
        await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"], name: "Group 1", createdAt: 123 });
      });
      const db = testEnv.authenticatedContext("bob").firestore();
      await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "bob", authorName: "Bob M", authorUsername: "alicea", text: "Hello", createdAt: 123
      }));
    });
  });
\`;`;

// Find the last "});" corresponding to the end of the suite
const lastBracket = content.lastIndexOf("});");
const newContent = content.substring(0, lastBracket) + newTests + content.substring(lastBracket);
fs.writeFileSync("tests/security.test.js", newContent);
console.log("Appended");

