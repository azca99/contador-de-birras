const fs = require("fs");
let content = fs.readFileSync("app/src/main/java/com/example/contadordebirras/ui/groups/GroupsScreen.kt", "utf8");
content = content.replace(`Icon(androidx.compose.material.icons.Icons.Rounded.Person, contentDescription = "Aceptar", tint = MaterialTheme.colorScheme.primary)`, `Text("v", color = MaterialTheme.colorScheme.primary)`);
content = content.replace(`Icon(androidx.compose.material.icons.Icons.Default.Warning, contentDescription = "Rechazar", tint = MaterialTheme.colorScheme.error)`, `Text("x", color = MaterialTheme.colorScheme.error)`);
fs.writeFileSync("app/src/main/java/com/example/contadordebirras/ui/groups/GroupsScreen.kt", content);
console.log("GroupsScreen.kt fixed");

