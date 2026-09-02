const { assertFails, assertSucceeds, initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-beer-hunter",
    firestore: { host: "127.0.0.1", port: 8888, rules: fs.readFileSync("../firestore.rules", "utf8") },
    storage: { host: "127.0.0.1", port: 9199, rules: fs.readFileSync("../storage.rules", "utf8") },
  });
});

after(async () => {
  await testEnv.cleanup();
});

describe("BEERS", () => {
  beforeEach(async () => await testEnv.clearFirestore());

  it("owner create", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("beers").doc("b1").set({ userId: "alice" }));
  });

  it("owner read", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
      await context.firestore().collection("sharedBeers").doc("b1").set({ userId: "alice" });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    // It was checking if it could read beers directly, now it should read sharedBeers
    await assertSucceeds(db.collection("sharedBeers").doc("b1").get());
  });

  it("owner update", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice", type: "IPA" });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("beers").doc("b1").update({ type: "Stout", userId: "alice" }));
  });

  it("owner delete", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
      await context.firestore().collection("sharedBeers").doc("b1").set({ userId: "alice" });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("beers").doc("b1").delete());
  });

  it("stranger read", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
      await context.firestore().collection("sharedBeers").doc("b1").set({ userId: "alice" });
    });
    const db = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(db.collection("beers").doc("b1").get());
  });

  it("stranger update", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice", type: "IPA" });
    });
    const db = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(db.collection("beers").doc("b1").update({ type: "Stout" }));
  });

  it("stranger delete", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
      await context.firestore().collection("sharedBeers").doc("b1").set({ userId: "alice" });
    });
    const db = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(db.collection("beers").doc("b1").delete());
  });

  it("spoof userId create", async () => {
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("beers").doc("b1").set({ userId: "alice" }));
  });

  it("change userId after creation", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
      await context.firestore().collection("sharedBeers").doc("b1").set({ userId: "alice" });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("beers").doc("b1").update({ userId: "bob" }));
  });
});

describe("FRIENDSHIPS", () => {

  it("A crea PENDING -> B acepta -> status pasa a ACCEPTED", async () => {
    const dbA = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(dbA.collection("friendships").doc("alice_bob").set({
      user1: "alice",
      user2: "bob",
      requester: "alice",
      status: "PENDING",
      friendshipId: "alice_bob"
    }));
    const dbB = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(dbB.collection("friendships").doc("alice_bob").update({ status: "ACCEPTED" }));
  });
  beforeEach(async () => await testEnv.clearFirestore());

  it("A puede enviar PENDING a B", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("friendships").doc("alice_bob").set({
      user1: "alice",
      user2: "bob",
      requester: "alice",
      status: "PENDING",
      friendshipId: "alice_bob"
    }));
  });

  it("B puede aceptar", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", requester: "alice", status: "PENDING", friendshipId: "alice_bob"
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("friendships").doc("alice_bob").update({ status: "ACCEPTED" }));
  });

  it("B puede rechazar", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", requester: "alice", status: "PENDING", friendshipId: "alice_bob"
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("friendships").doc("alice_bob").delete());
  });

  it("requester puede cancelar PENDING", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", requester: "alice", status: "PENDING", friendshipId: "alice_bob"
      });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("friendships").doc("alice_bob").delete());
  });

  it("cualquiera puede eliminar ACCEPTED", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", requester: "alice", status: "ACCEPTED", friendshipId: "alice_bob"
      });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("friendships").doc("alice_bob").delete());
  });

  it("requester no puede autoaceptar", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", requester: "alice", status: "PENDING", friendshipId: "alice_bob"
      });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("friendships").doc("alice_bob").update({ status: "ACCEPTED" }));
  });

  it("A no puede crear directamente ACCEPTED", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("friendships").doc("alice_bob").set({
      user1: "alice", user2: "bob", requester: "alice", status: "ACCEPTED", friendshipId: "alice_bob"
    }));
  });

  it("C no puede leer A-B", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", requester: "alice", status: "ACCEPTED", friendshipId: "alice_bob"
      });
    });
    const db = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(db.collection("friendships").doc("alice_bob").get());
  });

  it("C no puede modificar A-B", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", requester: "alice", status: "ACCEPTED", friendshipId: "alice_bob"
      });
    });
    const db = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(db.collection("friendships").doc("alice_bob").delete());
  });
});

describe("FRIEND BEERS", () => {
  beforeEach(async () => await testEnv.clearFirestore());

  it("amistad ACCEPTED puede leer", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
      await context.firestore().collection("sharedBeers").doc("b1").set({ userId: "alice" });
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", requester: "alice", status: "ACCEPTED", friendshipId: "alice_bob"
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    // It was checking if it could read beers directly, now it should read sharedBeers
    await assertSucceeds(db.collection("sharedBeers").doc("b1").get());
  });

  it("PENDING no puede leer", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
      await context.firestore().collection("sharedBeers").doc("b1").set({ userId: "alice" });
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", requester: "alice", status: "PENDING", friendshipId: "alice_bob"
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("beers").doc("b1").get());
  });

  it("desconocido no puede leer", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
      await context.firestore().collection("sharedBeers").doc("b1").set({ userId: "alice" });
    });
    const db = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(db.collection("beers").doc("b1").get());
  });
});

describe("USERS", () => {
  beforeEach(async () => await testEnv.clearFirestore());

  it("usuario lee/escribe el suyo", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("users").doc("alice").set({ email: "alice@a.com" }));
    await assertSucceeds(db.collection("users").doc("alice").get());
  });

  it("otro no lee", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("users").doc("alice").set({ email: "alice@a.com" });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("users").doc("alice").get());
  });

  it("otro no escribe", async () => {
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("users").doc("alice").set({ email: "bob@a.com" }));
  });
});

describe("PUBLICUSERS", () => {
  beforeEach(async () => await testEnv.clearFirestore());

  it("lectura pï¿½blica autenticada si procede", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("publicUsers").doc("alice").set({ displayName: "Alice" });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("publicUsers").doc("alice").get());
  });

  it("escritura de email falla", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("publicUsers").doc("alice").set({ email: "a@a.com" }));
  });

  it("escritura de emailLowercase falla", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("publicUsers").doc("alice").set({ emailLowercase: "a@a.com" }));
  });
  
  it("escritura de campos privados falla en update", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("publicUsers").doc("alice").set({ displayName: "Alice" });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("publicUsers").doc("alice").update({ email: "a@a.com" }));
  });
});

describe("GROUPS", () => {
  beforeEach(async () => await testEnv.clearFirestore());

  it("admin crea grupo vï¿½lido", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").set({
      adminUid: "alice",
      members: ["alice"],
      name: "Group 1",
      createdAt: 12345
    }));
  });

  it("admin NO puede añadir directamente a Bob modificando members", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({
        adminUid: "alice", members: ["alice"], name: "Group 1", createdAt: 12345
      });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    
    await assertFails(db.collection("groups").doc("g1").update({
      members: ["alice", "bob"]
    }));
  });

  it("admin expulsa", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({
        adminUid: "alice", members: ["alice", "bob"], name: "Group 1", createdAt: 12345
      });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").update({ members: ["alice"] }));
  });

  it("admin borra", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({
        adminUid: "alice", members: ["alice", "bob"], name: "Group 1", createdAt: 12345
      });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").delete());
  });

  it("miembro cambia adminUid falla", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({
        adminUid: "alice", members: ["alice", "bob"], name: "Group 1", createdAt: 12345
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").update({ adminUid: "bob" }));
  });

  it("miembro aï¿½ade tercero falla", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({
        adminUid: "alice", members: ["alice", "bob"], name: "Group 1", createdAt: 12345
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").update({ members: ["alice", "bob", "charlie"] }));
  });

  it("miembro expulsa tercero falla", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({
        adminUid: "alice", members: ["alice", "bob", "charlie"], name: "Group 1", createdAt: 12345
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").update({ members: ["alice", "bob"] }));
  });
  
  it("miembro se autoexpulsa exito", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({
        adminUid: "alice", members: ["alice", "bob"], name: "Group 1", createdAt: 12345
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").update({ members: ["alice"] }));
  });

  it("externo lee falla", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({
        adminUid: "alice", members: ["alice", "bob"], name: "Group 1", createdAt: 12345
      });
    });
    const db = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(db.collection("groups").doc("g1").get());
  });

  it("creaciï¿½n con adminUid ajeno falla", async () => {
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").set({
      adminUid: "alice", members: ["bob"], name: "Group 1", createdAt: 12345
    }));
  });
});

describe("COMMENTS", () => {
  beforeEach(async () => await testEnv.clearFirestore());

  it("miembro lee", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bob", uid: "bob" });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({ text: "Hi", authorUid: "alice" });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").get());
  });

  it("miembro crea propio", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bob", uid: "bob" });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").set({
      authorUid: "bob", authorName: "Bob", authorUsername: "bob", text: "Hi", createdAt: 123
    }));
  });

  it("autor edita texto", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bob", uid: "bob" });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "bob", authorName: "Bob", authorUsername: "bob", text: "Hi", createdAt: 123
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").update({ text: "Hola" }));
  });

  it("autor borra", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bob", uid: "bob" });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "bob", authorName: "Bob", authorUsername: "bob", text: "Hi", createdAt: 123
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").delete());
  });

  it("admin borra", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bob", uid: "bob" });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "bob", authorName: "Bob", authorUsername: "bob", text: "Hi", createdAt: 123
      });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").delete());
  });

  it("externo lee", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bob", uid: "bob" });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "bob", authorName: "Bob", authorUsername: "bob", text: "Hi", createdAt: 123
      });
    });
    const db = testEnv.authenticatedContext("charlie").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").get());
  });

  it("spoof authorUid", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bob", uid: "bob" });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").set({
      authorUid: "alice", authorName: "Bob", authorUsername: "bob", text: "Hi", createdAt: 123
    }));
  });

  it("cambiar authorUid", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bob", uid: "bob" });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "bob", authorName: "Bob", authorUsername: "bob", text: "Hi", createdAt: 123
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").update({ authorUid: "alice" }));
  });

  it("cambiar createdAt", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bob", uid: "bob" });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "bob", authorName: "Bob", authorUsername: "bob", text: "Hi", createdAt: 123
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").update({ createdAt: 456 }));
  });

  it("editar comentario ajeno", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
        await context.firestore().collection("publicUsers").doc("bob").set({ displayName: "Bob", username: "bob", uid: "bob" });
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "alice", authorName: "Alice", authorUsername: "alice", text: "Hi", createdAt: 123
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").update({ text: "Hacked" }));
  });


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
});


describe("SOCIAL BEER PRIVACY AND GROUPS BYPASS", () => {
  let alice, bob, charlie, unknown, admin;
  beforeEach(async () => {
    await testEnv.clearFirestore();
    alice = testEnv.authenticatedContext("alice").firestore();
    bob = testEnv.authenticatedContext("bob").firestore();
    charlie = testEnv.authenticatedContext("charlie").firestore();
    unknown = testEnv.unauthenticatedContext().firestore();
    await testEnv.withSecurityRulesDisabled(async (context) => {
      admin = context.firestore();
      await admin.collection("users").doc("alice").set({ uid: "alice" });
      await admin.collection("users").doc("bob").set({ uid: "bob" });
      await admin.collection("users").doc("charlie").set({ uid: "charlie" });
      await admin.collection("publicUsers").doc("alice").set({ displayName: "Alice" });
      await admin.collection("publicUsers").doc("bob").set({ displayName: "Bob" });
      await admin.collection("publicUsers").doc("charlie").set({ displayName: "Charlie" });
    });
  });

  it("Alice crea una cerveza privada con ubicacion y Bob (ACCEPTED) NO puede leerla en /beers", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("beer_alice_1").set({
        userId: "alice", type: "RUBIA", latitude: 40.0, longitude: -3.0, locationName: "Madrid"
      });
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", status: "ACCEPTED", requester: "alice", friendshipId: "alice_bob"
      });
    });
    await assertFails(bob.collection("beers").doc("beer_alice_1").get());
  });

  it("Bob puede leer la version social en /sharedBeers, y NO contiene ubicacion", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("sharedBeers").doc("beer_alice_1").set({
        userId: "alice", type: "RUBIA"
      });
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", status: "ACCEPTED", requester: "alice", friendshipId: "alice_bob"
      });
    });

    const doc = await assertSucceeds(bob.collection("sharedBeers").doc("beer_alice_1").get());
    const data = doc.data() || {};
    if (data.latitude !== undefined) throw new Error("Has latitude");
  });

  it("Charlie, que solo comparte grupo con Alice, NO puede consultar cerveza privada ni social", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("beer_alice_1").set({ userId: "alice", type: "RUBIA" });
      await context.firestore().collection("sharedBeers").doc("beer_alice_1").set({ userId: "alice", type: "RUBIA" });
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "charlie"] });
    });
    await assertFails(charlie.collection("beers").doc("beer_alice_1").get());
    await assertFails(charlie.collection("sharedBeers").doc("beer_alice_1").get());
  });

  it("Amistad PENDING no concede acceso a sharedBeers", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("sharedBeers").doc("beer_alice_1").set({ userId: "alice", type: "RUBIA" });
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", status: "PENDING", requester: "alice", friendshipId: "alice_bob"
      });
    });
    await assertFails(bob.collection("sharedBeers").doc("beer_alice_1").get());
  });

  it("Usuario desconocido no tiene acceso a sharedBeers", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("sharedBeers").doc("beer_alice_1").set({ userId: "alice", type: "RUBIA" });
    });
    await assertFails(unknown.collection("sharedBeers").doc("beer_alice_1").get());
  });

  it("Bypass de grupos: admin no puede anadir directamente a un miembro", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice"], createdAt: 1000 });
    });
    await assertFails(alice.collection("groups").doc("g1").update({
      members: ["alice", "bob"]
    }));
  });

});


describe("STORAGE RULES", () => {
  beforeEach(async () => {
    await testEnv.clearStorage();
    await testEnv.clearFirestore();
  });

  it("propietario puede acceder a su foto", async () => {
    const storage = testEnv.authenticatedContext("alice").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]), { contentType: "image/jpeg" });
    });
    // Check read and write
    await assertSucceeds(fileRef.getDownloadURL());
    await assertSucceeds(fileRef.put(new Uint8Array([0x01]), { contentType: "image/jpeg" }));
  });

  it("amigo ACCEPTED puede acceder a la foto", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]), { contentType: "image/jpeg" });
        await context.firestore().collection("friendships").doc("alice_bob").set({
            user1: "alice", user2: "bob", status: "ACCEPTED", requester: "alice"
        });
        await context.firestore().collection("friendships").doc("bob_alice").set({
            user1: "bob", user2: "alice", status: "ACCEPTED", requester: "alice"
        });
    });
    const storage = testEnv.authenticatedContext("bob").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    
    // Can read
    await new Promise(resolve => setTimeout(resolve, 500)); // wait for cross-service sync
    await assertSucceeds(fileRef.getDownloadURL());
    // Cannot write
    await assertFails(fileRef.put(new Uint8Array([0x01]), { contentType: "image/jpeg" }));
  });

  it("PENDING no puede acceder a la foto", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]), { contentType: "image/jpeg" });
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
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]), { contentType: "image/jpeg" });
        await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
    });
    const storage = testEnv.authenticatedContext("bob").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    await assertFails(fileRef.getDownloadURL());
  });

  it("extrano no puede", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]), { contentType: "image/jpeg" });
    });
    const storage = testEnv.authenticatedContext("charlie").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    await assertFails(fileRef.getDownloadURL());
  });

  it("tras eliminar amistad se pierde el acceso", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.storage().ref("users/alice/beers/photo.jpg").put(new Uint8Array([0x00]), { contentType: "image/jpeg" });
        // NO friendship document
    });
    const storage = testEnv.authenticatedContext("bob").storage();
    const fileRef = storage.ref("users/alice/beers/photo.jpg");
    await assertFails(fileRef.getDownloadURL());
  });
});
