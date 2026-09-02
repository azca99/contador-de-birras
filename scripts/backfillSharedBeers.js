const admin = require("firebase-admin");

// Initialize with Application Default Credentials
// Set GOOGLE_APPLICATION_CREDENTIALS environment variable before running in prod
admin.initializeApp();

const db = admin.firestore();

async function migrate() {
    console.log("Starting migration: backfilling sharedBeers from beers collection...");
    let migratedCount = 0;
    
    // Process in batches
    const beersRef = db.collection("beers");
    const snapshot = await beersRef.get();
    
    if (snapshot.empty) {
        console.log("No beers found to migrate.");
        return;
    }
    
    const batches = [];
    let currentBatch = db.batch();
    let currentBatchSize = 0;
    
    snapshot.forEach(doc => {
        const data = doc.data();
        
        // Exact same sanitization logic as syncSharedBeer
        const { sanitizeBeerForSocial } = require('../functions/sanitizer');
        const sharedData = sanitizeBeerForSocial(data, doc.id);
        
        const sharedRef = db.collection("sharedBeers").doc(doc.id);
        currentBatch.set(sharedRef, sharedData);
        currentBatchSize++;
        
        if (currentBatchSize === 500) {
            batches.push(currentBatch.commit());
            currentBatch = db.batch();
            currentBatchSize = 0;
        }
        
        migratedCount++;
    });
    
    if (currentBatchSize > 0) {
        batches.push(currentBatch.commit());
    }
    
    await Promise.all(batches);
    console.log(`Migration complete. Successfully backfilled ${migratedCount} beers.`);
}

migrate().catch(console.error);
