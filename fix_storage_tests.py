import re

with open('tests/security.test.js', 'r', encoding='latin-1') as f:
    content = f.read()

# Fix put
content = content.replace('put(new Uint8Array([0x00]))', 'put(new Uint8Array([0x00]), { contentType: "image/jpeg" })')
content = content.replace('put(new Uint8Array([0x01]))', 'put(new Uint8Array([0x01]), { contentType: "image/jpeg" })')

with open('tests/security.test.js', 'w', encoding='latin-1') as f:
    f.write(content)
