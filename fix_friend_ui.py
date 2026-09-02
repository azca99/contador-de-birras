import re

with open('app/src/main/java/com/example/contadordebirras/ui/friends/FriendDetailScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('import com.example.contadordebirras.data.BeerEntity', 'import com.example.contadordebirras.data.SharedBeerEntity')
content = content.replace('beers: List<BeerEntity>', 'beers: List<SharedBeerEntity>')

# Remove locationName references in FriendBeerCard
content = re.sub(r'if \(!beer\.locationName\.isNullOrEmpty\(\)\) \{\s*Text\(text = "En: \$\{beer\.locationName\}", style = MaterialTheme\.typography\.bodyMedium\)\s*\}', '', content)

with open('app/src/main/java/com/example/contadordebirras/ui/friends/FriendDetailScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)
