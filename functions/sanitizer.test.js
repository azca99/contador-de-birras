const assert = require('assert');
const { sanitizeBeerForSocial } = require('./sanitizer');

describe('sanitizeBeerForSocial', () => {
    it('beer privada con latitude/longitude/locationName produce social beer sin esos campos', () => {
        const privateBeer = {
            userId: 'alice',
            type: { displayName: 'Rubia' },
            timestamp: 123456,
            latitude: 40.4168,
            longitude: -3.7038,
            locationName: 'Madrid',
            syncStatus: 'SYNCED',
            address: 'Calle Falsa 123'
        };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.userId, 'alice');
        assert.strictEqual(socialBeer.latitude, undefined);
        assert.strictEqual(socialBeer.longitude, undefined);
        assert.strictEqual(socialBeer.locationName, undefined);
        assert.strictEqual(socialBeer.syncStatus, undefined);
        assert.strictEqual(socialBeer.address, undefined);
    });

    it('beer nueva con Storage path conserva únicamente la ruta segura', () => {
        const privateBeer = {
            userId: 'alice',
            type: { displayName: 'Rubia' },
            timestamp: 123456,
            remotePhotoUrl: 'users/alice/beers/beer1.jpg'
        };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, 'users/alice/beers/beer1.jpg');
    });

    it('beer legacy con https NO produce una URL HTTP en sharedBeers sino que la transforma si es válida', () => {
        const privateBeer = {
            userId: 'alice',
            type: { displayName: 'Rubia' },
            timestamp: 123456,
            remotePhotoUrl: 'https://firebasestorage.googleapis.com/v0/b/demo-beer-hunter.appspot.com/o/users%2Falice%2Fbeers%2Fbeer1.jpg?alt=media&token=1234'
        };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, 'users/alice/beers/beer1.jpg');
    });

    it('beer legacy con https malformada omite la foto', () => {
        const privateBeer = {
            userId: 'alice',
            type: { displayName: 'Rubia' },
            timestamp: 123456,
            remotePhotoUrl: 'https://malicious.com/fake.jpg'
        };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, undefined);
    });

    it('ejecutar dos veces la transformación no degrada el resultado (idempotente)', () => {
        const privateBeer = {
            userId: 'alice',
            type: { displayName: 'Rubia' },
            timestamp: 123456,
            remotePhotoUrl: 'https://firebasestorage.googleapis.com/v0/b/demo/o/users%2Falice%2Fbeers%2Fbeer1.jpg'
        };
        const socialBeer1 = sanitizeBeerForSocial(privateBeer, 'beer1');
        
        // Simular que el backfill o function vuelve a procesar el output (o algo similar, pero pasamos el private original)
        const socialBeer2 = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.deepStrictEqual(socialBeer1, socialBeer2);
    });
});
