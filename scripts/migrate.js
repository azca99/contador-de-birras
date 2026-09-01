const admin = require("firebase-admin");
const fs = require("fs");

if (fs.existsSync("./serviceAccountKey.json")) {
    const serviceAccount = require("./serviceAccountKey.json");
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount)
    });
} else {
    admin.initializeApp(); // Uses GOOGLE_APPLICATION_CREDENTIALS if available
}

const db = admin.firestore();

async function migrate() {
    let count = 0;
    let lastDoc = null;
    let hasMore = true;

    while (hasMore) {
        let query = db.collection("publicUsers").limit(400);
        if (lastDoc) {
            query = query.startAfter(lastDoc);
        }

        const publicUsers = await query.get();
        if (publicUsers.empty) {
            hasMore = false;
            break;
        }

        const batch = db.batch();
        let batchCount = 0;

        publicUsers.forEach(doc => {
            const data = doc.data();
            if (data.email || data.emailLowercase) {
                batch.update(doc.ref, {
                    email: admin.firestore.FieldValue.delete(),
                    emailLowercase: admin.firestore.FieldValue.delete()
                });
                batchCount++;
                count++;
            }
            lastDoc = doc;
        });

        if (batchCount > 0) {
            await batch.commit();
        }
    }

    if (count > 0) {
        console.log(`Migracin completada. Eliminados emails de ${count} usuarios pblicos.`);
    } else {
        console.log("No se encontraron emails en publicUsers.");
    }
}

migrate().catch(console.error);
