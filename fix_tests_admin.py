import re

with open('tests/security.test.js', 'r', encoding='latin-1') as f:
    content = f.read()

# Replace the skipped test with an active test asserting failure
target = r'it\.skip\("admin anade", async \(\) => \{.*?\n\s*await assertSucceeds\(db\.collection\("groups"\)\.doc\("g1"\)\.update\(\{\n\s*members: \["alice", "bob"\]\n\s*\}\)\);\n\s*\}\);'
replacement = '''it("admin NO puede añadir directamente a Bob modificando members", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("groups").doc("g1").set({
        adminUid: "alice", members: ["alice"], name: "Group 1", createdAt: 12345
      });
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    
    await assertFails(db.collection("groups").doc("g1").update({
      members: ["alice", "bob"]
    }));
  });'''

content = re.sub(target, replacement, content, flags=re.DOTALL)

with open('tests/security.test.js', 'w', encoding='latin-1') as f:
    f.write(content)
