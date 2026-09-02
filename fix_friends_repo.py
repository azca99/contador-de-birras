import re

with open('app/src/main/java/com/example/contadordebirras/domain/FriendsRepository.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('import com.example.contadordebirras.data.BeerEntity', 'import com.example.contadordebirras.data.SharedBeerEntity')
content = content.replace('fun getFriendBeers(friendUid: String): Flow<List<BeerEntity>>', 'fun getFriendBeers(friendUid: String): Flow<List<SharedBeerEntity>>')

# Replace the BeerEntity mapping
old_mapping = '''BeerEntity(
                            id = 0,
                            type = BeerType.valueOf(doc.getString("type") ?: "RUBIA"),
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            latitude = doc.getDouble("latitude"),
                            longitude = doc.getDouble("longitude"),
                            photoUri = null,
                            comment = doc.getString("comment"),
                            locationName = doc.getString("locationName"),
                            syncId = doc.id,
                            syncStatus = SyncStatus.SYNCED,
                            remotePhotoUrl = doc.getString("remotePhotoUrl"),
                            updatedAt = doc.getLong("updatedAt") ?: 0L
                        )'''
new_mapping = '''SharedBeerEntity(
                            syncId = doc.id,
                            userId = friendUid,
                            type = BeerType.valueOf(doc.getString("type") ?: "RUBIA"),
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            comment = doc.getString("comment"),
                            remotePhotoUrl = doc.getString("remotePhotoUrl"),
                            updatedAt = doc.getLong("updatedAt") ?: 0L
                        )'''

if old_mapping in content:
    content = content.replace(old_mapping, new_mapping)
else:
    print("MAPPING NOT FOUND")

with open('app/src/main/java/com/example/contadordebirras/domain/FriendsRepository.kt', 'w', encoding='utf-8') as f:
    f.write(content)
