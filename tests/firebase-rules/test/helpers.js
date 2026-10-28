const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { doc, setDoc } = require('firebase/firestore');
const fs = require('fs');
const path = require('path');

const PROJECT_ID = 'demo-contador-c43';

const RULES_PATHS = {
  FIRESTORE_BASELINE: path.resolve(__dirname, '../fixtures/firestore.baseline.rules'),
  STORAGE_BASELINE: path.resolve(__dirname, '../fixtures/storage.baseline.rules'),
  FIRESTORE_LEGACY_COMPAT: path.resolve(__dirname, '../fixtures/firestore.legacy-compat.rules'),
  STORAGE_LEGACY_COMPAT: path.resolve(__dirname, '../fixtures/storage.legacy-compat.rules'),
  FIRESTORE_MODERN_STRICT: path.resolve(__dirname, '../fixtures/firestore.modern-strict.rules'),
  STORAGE_MODERN_STRICT: path.resolve(__dirname, '../fixtures/storage.modern-strict.rules'),
};

function readRules(filePath) {
  return fs.readFileSync(filePath, 'utf8');
}

/**
 * Initializes a test environment with specified firestore and storage rules.
 */
async function createTestEnv(firestoreRulesKey, storageRulesKey) {
  const firestoreRules = firestoreRulesKey ? readRules(RULES_PATHS[firestoreRulesKey]) : undefined;
  const storageRules = storageRulesKey ? readRules(RULES_PATHS[storageRulesKey]) : undefined;

  return await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: firestoreRules ? {
      rules: firestoreRules,
      host: '127.0.0.1',
      port: 8888,
    } : undefined,
    storage: storageRules ? {
      rules: storageRules,
      host: '127.0.0.1',
      port: 9199,
    } : undefined,
  });
}

/**
 * Seeds a firestore document bypassing security rules.
 */
async function seedDocument(testEnv, docPath, data) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await setDoc(doc(adminDb, docPath), data);
  });
}

/**
 * Authentic beer payload helpers matching BeerEntity, BeerSyncEngine, and C3A/C3B/C4.2 contracts.
 * Corresponds to BeerDirtyField units: TYPE, TIMESTAMP, LOCATION, COMMENT, PHOTO.
 */
function createModernBeerPayload(userId, overrides = {}) {
  return {
    userId,
    type: 'LATA',
    timestamp: 1729000000000,
    latitude: 40.4168,
    longitude: -3.7038,
    comment: 'Fresquita en terraza',
    locationName: 'Plaza Mayor',
    remotePhotoUrl: `users/${userId}/beers/sync-beer-100_mut-1001.jpg`,
    photoSource: 'CAMERA',
    updatedAt: 1729000000000,
    revision: 1,
    isDeleted: false,
    lastMutationId: 'mut-1001',
    fieldRevisions: {
      TYPE: 1,
      TIMESTAMP: 1,
      LOCATION: 1,
      COMMENT: 1,
      PHOTO: 1,
    },
    ...overrides,
  };
}

function createLegacyBeerPayload(userId, overrides = {}) {
  return {
    userId,
    type: 'LATA',
    timestamp: 1729000000000,
    latitude: 40.4168,
    longitude: -3.7038,
    comment: 'Cerveza clasica',
    locationName: 'Plaza Mayor',
    remotePhotoUrl: `users/${userId}/beers/beer-100.jpg`,
    photoSource: 'GALLERY',
    updatedAt: 1729000000000,
    ...overrides,
  };
}

/**
 * Helper to generate dummy image bytes
 */
function createDummyImageBuffer(sizeInBytes = 1024) {
  return Buffer.alloc(sizeInBytes, 0xff);
}

module.exports = {
  PROJECT_ID,
  RULES_PATHS,
  createTestEnv,
  seedDocument,
  createModernBeerPayload,
  createLegacyBeerPayload,
  createDummyImageBuffer,
  assertFails,
  assertSucceeds,
};
