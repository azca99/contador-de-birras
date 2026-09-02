with open('storage.rules', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("doc != null && doc.status == 'ACCEPTED'", "doc != null && doc.data.status == 'ACCEPTED'")

with open('storage.rules', 'w', encoding='utf-8') as f:
    f.write(content)
