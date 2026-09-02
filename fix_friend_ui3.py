import re

with open('app/src/main/java/com/example/contadordebirras/ui/friends/FriendDetailScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Fix compile errors
# 1. Argument type mismatch: actual type is 'BeerEntity', but 'SharedBeerEntity' was expected. (lines 72ish)
content = re.sub(r'items\(beers\) \{ beer ->\n\s*FriendBeerCard\(beer, dateFormat\)', 'items(beers) { beer ->\n                    FriendBeerCard(beer, dateFormat)', content)
# Ensure type in `friendsViewModel.getFriendBeers(friendProfile.uid).collectAsState(initial = emptyList<BeerEntity>())` is SharedBeerEntity
content = content.replace('emptyList<BeerEntity>()', 'emptyList()')

# 2. Unresolved reference 'locationName' and 'latitude' in FriendBeerCard
content = re.sub(r'if \(!beer\.locationName\.isNullOrEmpty\(\)\) \{\n\s*Text\(text = "En: \$\{beer\.locationName\}", style = MaterialTheme\.typography\.bodyMedium\)\n\s*\}', '', content)
content = re.sub(r'if \(beer\.latitude != null && beer\.longitude != null\) \{\n\s*Text\(text = "Ubicaci.n guardada", color = MaterialTheme\.colorScheme\.secondary, style = MaterialTheme\.typography\.bodySmall\)\n\s*\}', '', content)
content = re.sub(r'if \(beer\.latitude != null && beer\.longitude != null\) \{\n\s*Text\(text = "Ubicaci\u00f3n guardada", color = MaterialTheme\.colorScheme\.secondary, style = MaterialTheme\.typography\.bodySmall\)\n\s*\}', '', content)

with open('app/src/main/java/com/example/contadordebirras/ui/friends/FriendDetailScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)
