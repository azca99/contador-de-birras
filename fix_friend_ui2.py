import re

with open('app/src/main/java/com/example/contadordebirras/ui/friends/FriendDetailScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('fun FriendBeerCard(beer: BeerEntity,', 'fun FriendBeerCard(beer: SharedBeerEntity,')

# Also, in FriendBeerCard, we use `beer.remotePhotoUrl ?: beer.photoUri`. SharedBeerEntity doesn't have `photoUri`.
content = content.replace('val imageUrl = beer.remotePhotoUrl ?: beer.photoUri', 'val imageUrl = beer.remotePhotoUrl')

with open('app/src/main/java/com/example/contadordebirras/ui/friends/FriendDetailScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)
