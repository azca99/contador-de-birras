with open('app/src/main/java/com/example/contadordebirras/ui/components/SecureFirebaseImage.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('model: String,', 'model: String?,')
content = content.replace('if (model.startsWith', 'if (model == null) return\n    if (model.startsWith')
content = content.replace('LaunchedEffect(model) {', 'LaunchedEffect(model) {\n        if (model.isBlank()) return@LaunchedEffect')

with open('app/src/main/java/com/example/contadordebirras/ui/components/SecureFirebaseImage.kt', 'w', encoding='utf-8') as f:
    f.write(content)
