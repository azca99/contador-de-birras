import re

files_to_patch = [
    'app/src/main/java/com/example/contadordebirras/ui/friends/FriendDetailScreen.kt',
    'app/src/main/java/com/example/contadordebirras/ui/history/HistoryScreen.kt',
    'app/src/main/java/com/example/contadordebirras/ui/main/MainScreen.kt'
]

for filepath in files_to_patch:
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    content = content.replace('import coil.compose.AsyncImage', 'import com.example.contadordebirras.ui.components.SecureFirebaseImage')
    content = content.replace('AsyncImage(', 'SecureFirebaseImage(')
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)
