import re

with open('app/src/main/java/com/example/contadordebirras/ui/friends/FriendDetailScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Remove the bad lines
bad_block = """            if (beer.locationName != null) {
                Text(text = "Y"? ${beer.locationName}", style = MaterialTheme.typography.bodySmall)
            } else if (beer.latitude != null && beer.longitude != null) {
                Text(text = "Y"? Ubicacin registrada", style = MaterialTheme.typography.bodySmall)
            }"""

bad_block_2 = """            if (beer.locationName != null) {
                Text(text = "📍 ${beer.locationName}", style = MaterialTheme.typography.bodySmall)
            } else if (beer.latitude != null && beer.longitude != null) {
                Text(text = "📍 Ubicación registrada", style = MaterialTheme.typography.bodySmall)
            }"""

content = content.replace(bad_block, "")
content = content.replace(bad_block_2, "")

# Find it with regex if not exact
content = re.sub(r'if \(beer\.locationName != null\) \{.*?\}.*?\} else if \(beer\.latitude != null && beer\.longitude != null\) \{.*?\}', '', content, flags=re.DOTALL)

with open('app/src/main/java/com/example/contadordebirras/ui/friends/FriendDetailScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)
