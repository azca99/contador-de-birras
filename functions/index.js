const functions = require("firebase-functions/v1");
const admin = require("firebase-admin");
admin.initializeApp();

exports.searchUser = functions.runWith({ enforceAppCheck: true }).https.onCall(async (data, context) => {
    if (!context.auth) {
        throw new functions.https.HttpsError("unauthenticated", "Debe iniciar sesión para buscar.");
    }
    const query = data.query;
    if (!query || typeof query !== "string" || query.length > 50) {
        throw new functions.https.HttpsError("invalid-argument", "Búsqueda inválida.");
    }

    const normalizedSearch = query.toLowerCase().trim();
    const db = admin.firestore();

    // 1. Buscar por email en la colección PRIVADA (users)
    let usersSnap = await db.collection("users").where("emailLowercase", "==", normalizedSearch).limit(1).get();
    
    let targetUid = null;
    if (!usersSnap.empty) {
        targetUid = usersSnap.docs[0].id;
    } else {
        // 2. Buscar por username en publicUsers
        let publicSnap = await db.collection("publicUsers").where("usernameLowercase", "==", normalizedSearch).limit(1).get();
        if (!publicSnap.empty) {
            targetUid = publicSnap.docs[0].id;
        }
    }

    if (!targetUid) {
        return { found: false };
    }

    // Retornar solo los datos públicos
    const publicProfile = await db.collection("publicUsers").doc(targetUid).get();
    if (!publicProfile.exists) return { found: false };

    return {
        found: true,
        uid: targetUid,
        displayName: publicProfile.data().displayName || publicProfile.data().username || "Usuario",
        photoUrl: publicProfile.data().photoUrl || null
    };
});

exports.getGroupRanking = functions.runWith({ enforceAppCheck: true }).https.onCall(async (data, context) => {
    if (!context.auth) {
        throw new functions.https.HttpsError("unauthenticated", "Debe iniciar sesión.");
    }
    const groupId = data.groupId;
    if (!groupId) throw new functions.https.HttpsError("invalid-argument", "Falta groupId.");

    const db = admin.firestore();
    const groupDoc = await db.collection("groups").doc(groupId).get();
    
    if (!groupDoc.exists) throw new functions.https.HttpsError("not-found", "Grupo no encontrado.");
    const members = groupDoc.data().members || [];
    
    if (!members.includes(context.auth.uid)) {
        throw new functions.https.HttpsError("permission-denied", "No eres miembro de este grupo.");
    }

    const createdAt = groupDoc.data().createdAt || 0;
    
    // Configurar fechas
    const now = new Date();
    const startOfMonth = new Date(now.getFullYear(), now.getMonth(), 1).getTime();
    const startOfNextMonth = new Date(now.getFullYear(), now.getMonth() + 1, 1).getTime();
    
    const dayOfWeek = now.getDay() || 7; 
    const startOfWeek = new Date(now.getFullYear(), now.getMonth(), now.getDate() - dayOfWeek + 1).getTime();
    const startOfNextWeek = startOfWeek + 7 * 24 * 60 * 60 * 1000;

    let rankings = [];

    for (const uid of members) {
        const profileSnap = await db.collection("publicUsers").doc(uid).get();
        const alias = profileSnap.exists ? (profileSnap.data().displayName || profileSnap.data().username || "Usuario") : "Usuario Anónimo";
        const photoUrl = profileSnap.exists ? profileSnap.data().photoUrl : null;

        // Recuperar cervezas del miembro desde createdAt
        const beersSnap = await db.collection("beers")
            .where("userId", "==", uid)
            .where("timestamp", ">=", createdAt)
            .get();

        let historicalCount = 0;
        let monthlyCount = 0;
        let weeklyCount = 0;

        beersSnap.forEach(doc => {
            historicalCount++;
            const t = doc.data().timestamp;
            if (t >= startOfMonth && t < startOfNextMonth) monthlyCount++;
            if (t >= startOfWeek && t < startOfNextWeek) weeklyCount++;
        });

        rankings.push({
            uid: uid,
            alias: alias,
            photoUrl: photoUrl,
            historicalBeers: historicalCount,
            monthlyBeers: monthlyCount,
            weeklyBeers: weeklyCount
        });
    }

    return { rankings: rankings };
});

const { onDocumentCreated } = require('firebase-functions/v2/firestore');
exports.notifyNewBeer = onDocumentCreated('beers/{beerId}', async (event) => {
const snap = event.data;
if (!snap) return null;
        const beerData = snap.data();
        const authorUid = beerData.userId;
        if (!authorUid) return null;

        try {
            const db = admin.firestore();
            const authorDoc = await db.collection("publicUsers").doc(authorUid).get();
            const authorName = authorDoc.exists ? (authorDoc.data().displayName || authorDoc.data().username || "Un amigo") : "Un amigo";

            // Buscar amistades aceptadas donde el autor es user1 o user2
            const friendships1 = await db.collection("friendships").where("user1", "==", authorUid).where("status", "==", "ACCEPTED").get();
            const friendships2 = await db.collection("friendships").where("user2", "==", authorUid).where("status", "==", "ACCEPTED").get();
            
            const friendUids = new Set();
            friendships1.forEach(doc => friendUids.add(doc.data().user2));
            friendships2.forEach(doc => friendUids.add(doc.data().user1));

            if (friendUids.size === 0) return null;

            let tokens = [];
            for (const fUid of friendUids) {
                const fDoc = await db.collection("users").doc(fUid).get();
                if (fDoc.exists && fDoc.data().fcmTokens) {
                    tokens = tokens.concat(fDoc.data().fcmTokens);
                }
            }

            if (tokens.length === 0) return null;

            const payload = {
                notification: {
                    title: "¡Nueva Cerveza! ??",
                    body: `${authorName} acaba de registrar una nueva cerveza.`,
                }
            };

            await admin.messaging().sendToDevice(tokens, payload);
            return null;
        } catch (error) {
            console.error(error);
            return null;
        }
    });



const { onDocumentWritten } = require('firebase-functions/v2/firestore');
exports.syncSharedBeer = onDocumentWritten('beers/{beerId}', async (event) => {
    const db = admin.firestore();
    const beerId = event.params.beerId;
    if (!event.data.after.exists) {
        await db.collection('sharedBeers').doc(beerId).delete();
        
        // Also delete the photo if it exists to avoid orphaned files
        try {
            const dataBefore = event.data.before.data();
            if (dataBefore && dataBefore.userId) {
                const bucket = admin.storage().bucket();
                const file = bucket.file(`users/${dataBefore.userId}/beers/${beerId}.jpg`);
                await file.delete();
            }
        } catch (e) {
            // Ignore if file doesn't exist
        }
        return null;
    }
    
    const data = event.data.after.data();
    const sharedData = {
        userId: data.userId,
        type: data.type,
        timestamp: data.timestamp
    };
    if (data.comment !== undefined) sharedData.comment = data.comment;
    if (data.remotePhotoUrl !== undefined) {
        sharedData.remotePhotoUrl = data.remotePhotoUrl;
    }
    if (data.updatedAt !== undefined) sharedData.updatedAt = data.updatedAt;
    
    await db.collection('sharedBeers').doc(beerId).set(sharedData);
    return null;
});
