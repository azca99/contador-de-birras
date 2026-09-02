with open('app/src/main/java/com/example/contadordebirras/domain/BeerRepository.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace downloadUrl with just path
content = content.replace(
    'remoteUrl = storageRef.downloadUrl.await().toString()',
    'remoteUrl = "users/${user.uid}/beers/${beer.syncId}.jpg"'
)

with open('app/src/main/java/com/example/contadordebirras/domain/BeerRepository.kt', 'w', encoding='utf-8') as f:
    f.write(content)
