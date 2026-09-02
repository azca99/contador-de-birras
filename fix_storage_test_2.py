with open('tests/security.test.js', 'r', encoding='latin-1') as f:
    content = f.read()

content = content.replace(
'''        await context.firestore().collection("friendships").doc("alice_bob").set({
            user1: "alice", user2: "bob", status: "ACCEPTED", requester: "alice"
        });''',
'''        await context.firestore().collection("friendships").doc("alice_bob").set({
            user1: "alice", user2: "bob", status: "ACCEPTED", requester: "alice"
        });
        await context.firestore().collection("friendships").doc("bob_alice").set({
            user1: "bob", user2: "alice", status: "ACCEPTED", requester: "alice"
        });'''
)

with open('tests/security.test.js', 'w', encoding='latin-1') as f:
    f.write(content)
