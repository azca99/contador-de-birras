const { assertFails, assertSucceeds, initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-beer-hunter",
    firestore: { host: "127.0.0.1", port: 8888, rules: fs.readFileSync("../firestore.rules", "utf8") },
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
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("beers").doc("b1").get());
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
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("beers").doc("b1").delete());
  });

  it("stranger read", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
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
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("beers").doc("b1").update({ userId: "bob" }));
  });
});

describe("FRIENDSHIPS", () => {
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
      await context.firestore().collection("friendships").doc("alice_bob").set({
        user1: "alice", user2: "bob", requester: "alice", status: "ACCEPTED", friendshipId: "alice_bob"
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("beers").doc("b1").get());
  });

  it("PENDING no puede leer", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("beers").doc("b1").set({ userId: "alice" });
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

  it("lectura pública autenticada si procede", async () => {
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

  it("admin crea grupo válido", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").set({
      adminUid: "alice",
      members: ["alice"],
      name: "Group 1",
      createdAt: 12345
    }));
  });

  it("admin añade", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({
        adminUid: "alice", members: ["alice"], name: "Group 1", createdAt: 12345
      });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    
    await assertSucceeds(db.collection("groups").doc("g1").update({
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

  it("miembro añade tercero falla", async () => {
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

  it("creación con adminUid ajeno falla", async () => {
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
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({ text: "Hi", authorUid: "alice" });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").get());
  });

  it("miembro crea propio", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(db.collection("groups").doc("g1").collection("comments").doc("c1").set({
      authorUid: "bob", authorName: "Bob", authorUsername: "bob", text: "Hi", createdAt: 123
    }));
  });

  it("autor edita texto", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
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
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").set({
      authorUid: "alice", authorName: "Bob", authorUsername: "bob", text: "Hi", createdAt: 123
    }));
  });

  it("cambiar authorUid", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({ adminUid: "alice", members: ["alice", "bob"] });
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
      await context.firestore().collection("groups").doc("g1").collection("comments").doc("c1").set({
        authorUid: "alice", authorName: "Alice", authorUsername: "alice", text: "Hi", createdAt: 123
      });
    });
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(db.collection("groups").doc("g1").collection("comments").doc("c1").update({ text: "Hacked" }));
  });
});

