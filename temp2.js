const fs = require("fs");
const content = fs.readFileSync("tests/security.test.js", "utf8");
const newContent = content.replace("`});", "});");
fs.writeFileSync("tests/security.test.js", newContent);

