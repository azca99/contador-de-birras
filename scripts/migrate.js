const admin = require("firebase-admin");
const serviceAccount = require("./serviceAccountKey.json");

// Asumiendo que el usuario configurar serviceAccountKey.json para ejecutar esto manualmente.
admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

const db = admin.firestore();

async function migrate() {
    const publicUsers = await db.collection("publicUsers").get();
    const batch = db.batch();
    let count = 0;
    
    publicUsers.forEach(doc => {
        const data = doc.data();
        if (data.email || data.emailLowercase) {
            batch.update(doc.ref, {
                email: admin.firestore.FieldValue.delete(),
                emailLowercase: admin.firestore.FieldValue.delete()
            });
            count++;
        }
    });

    if (count > 0) {
        await batch.commit();
        console.log(`Migracin completada. Eliminados emails de ${count} usuarios pblicos.`);
    } else {
        console.log("No se encontraron emails en publicUsers.");
    }
}

migrate().catch(console.error);

