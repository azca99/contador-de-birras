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
 * Sample test data helpers
 */
function createModernBeerPayload(userId, overrides = {}) {
  return {
    userId,
    name: 'IPA Especial',
    brand: 'Craft Brewery',
    rating: 4.5,
    style: 'IPA',
    alcohol: 6.2,
    syncId: 'sync-beer-100',
    revision: 1,
    isDeleted: false,
    lastMutationId: 'mut-1001',
    fieldRevisions: {
      name: 1,
      brand: 1,
      rating: 1,
      style: 1,
      alcohol: 1,
    },
    ...overrides,
  };
}

function createLegacyBeerPayload(userId, overrides = {}) {
  return {
    userId,
    name: 'Cerveza Clasica',
    brand: 'Mahou',
    rating: 3.5,
    style: 'Lager',
    alcohol: 5.0,
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
