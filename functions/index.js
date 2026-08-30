const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

exports.notifyNewBeer = functions.firestore
    .document('beers/{beerId}')
    .onCreate(async (snap, context) => {
        const beerData = snap.data();
        const authorUid = beerData.userId;
        
        if (!authorUid) return null;

        try {
            // 1. Obtener el nombre del autor
            const authorDoc = await admin.firestore().collection('publicUsers').doc(authorUid).get();
            const authorName = authorDoc.exists ? (authorDoc.data().displayName || authorDoc.data().alias || "Un amigo") : "Un amigo";

            // 2. Buscar quién tiene a este autor en su lista de amigos
            const followersSnapshot = await admin.firestore()
                .collection('users')
                .where('friends', 'array-contains', authorUid)
                .get();

            if (followersSnapshot.empty) {
                console.log("No followers found for user:", authorUid);
                return null;
            }

            // 3. Recopilar todos los tokens
            let tokens = [];
            followersSnapshot.forEach(doc => {
                const userData = doc.data();
                // Evitar enviarse a sí mismo
                if (doc.id !== authorUid && userData.fcmTokens && Array.isArray(userData.fcmTokens)) {
                    tokens = tokens.concat(userData.fcmTokens);
                }
            });

            if (tokens.length === 0) {
                console.log("No valid FCM tokens found among followers.");
                return null;
            }

            // 4. Enviar notificación
            const payload = {
                notification: {
                    title: "¡Nueva Cerveza! 🍻",
                    body: `${authorName} acaba de registrar una nueva cerveza.`,
                }
            };

            const response = await admin.messaging().sendToDevice(tokens, payload);
            console.log(`Sent ${response.successCount} successful messages.`);
            
            // Opcional: Limpiar tokens fallidos (NotRegistered)
            response.results.forEach((result, index) => {
                const error = result.error;
                if (error && (error.code === 'messaging/invalid-registration-token' || error.code === 'messaging/registration-token-not-registered')) {
                    // Aquí podrías añadir lógica para eliminar el token inválido de la BD
                    console.log(`Token fallido a eliminar: ${tokens[index]}`);
                }
            });

            return null;
        } catch (error) {
            console.error("Error sending notification:", error);
            return null;
        }
    });