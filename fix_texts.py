import re

with open('app/src/main/java/com/example/contadordebirras/domain/FriendsRepository.kt', 'r', encoding='latin-1') as f:
    content = f.read()

content = content.replace('"No puedes aadirte a ti mismo."', '"No puedes a\\u00f1adirte a ti mismo."')
content = content.replace('"Ocurrio error"', '"Ocurri\\u00f3 un error"')
content = content.replace('"Error de autenticacion"', '"Error de autenticaci\\u00f3n"')
content = content.replace('"Error de autenticacin"', '"Error de autenticaci\\u00f3n"')

# Decode back and encode to utf-8 properly to fix the file encoding entirely
try:
    content = content.encode('latin-1').decode('utf-8')
except Exception:
    pass # already mixed or whatever, we will just write it as utf-8 now

with open('app/src/main/java/com/example/contadordebirras/domain/FriendsRepository.kt', 'w', encoding='utf-8') as f:
    f.write(content)

