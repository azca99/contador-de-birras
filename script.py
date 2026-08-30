import re

with open("app/src/main/java/com/example/contadordebirras/domain/achievements/AchievementCatalog.kt", "r", encoding="utf-8") as f:
    content = f.read()

valid_prefixes = ["GEN", "CAN", "LAT", "BOT", "COP", "JAR", "PIN", "LIT", "FOT", "UBI"]

pattern = re.compile(r"\s*AchievementDefinition\([\s\S]*?\),")

def filter_achievements(match):
    block = match.group(0)
    id_match = re.search(r"id\s*=\s*\"([A-Z]+)_\d+\"", block)
    if id_match:
        prefix = id_match.group(1)
        if prefix in valid_prefixes:
            return block
    return ""

new_content = pattern.sub(filter_achievements, content)
new_content = re.sub(r"\n\s*\n", "\n\n", new_content)

with open("app/src/main/java/com/example/contadordebirras/domain/achievements/AchievementCatalog.kt", "w", encoding="utf-8") as f:
    f.write(new_content)

print("Filtered Catalog!")
