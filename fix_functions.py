import re

with open('functions/index.js', 'r', encoding='latin-1') as f:
    content = f.read()

new_func = '''exports.syncSharedBeer = onDocumentWritten('beers/{beerId}', async (event) => {
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
});'''

content = re.sub(r"exports\.syncSharedBeer = onDocumentWritten\('beers/\{beerId\}', async \(event\) => \{.*?\n\}\);", new_func, content, flags=re.DOTALL)

with open('functions/index.js', 'w', encoding='utf-8') as f:
    f.write(content)
