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

    it('users/alice/beers/beer1.jpg para userId=alice, beerId=beer1 se acepta.', () => {
        const privateBeer = { userId: 'alice', type: { displayName: 'Rubia' }, timestamp: 123456, remotePhotoUrl: 'users/alice/beers/beer1.jpg' };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, 'users/alice/beers/beer1.jpg');
    });

    it('users/bob/beers/beer1.jpg para Alice se rechaza.', () => {
        const privateBeer = { userId: 'alice', type: { displayName: 'Rubia' }, timestamp: 123456, remotePhotoUrl: 'users/bob/beers/beer1.jpg' };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, undefined);
    });

    it('users/alice/avatar.jpg se rechaza.', () => {
        const privateBeer = { userId: 'alice', type: { displayName: 'Rubia' }, timestamp: 123456, remotePhotoUrl: 'users/alice/avatar.jpg' };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, undefined);
    });

    it('other/alice/beers/beer1.jpg se rechaza.', () => {
        const privateBeer = { userId: 'alice', type: { displayName: 'Rubia' }, timestamp: 123456, remotePhotoUrl: 'other/alice/beers/beer1.jpg' };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, undefined);
    });

    it('una ruta con intento de traversal o forma equivalente insegura se rechaza.', () => {
        const privateBeer = { userId: 'alice', type: { displayName: 'Rubia' }, timestamp: 123456, remotePhotoUrl: 'users/alice/beers/../beer1.jpg' };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, undefined);
    });

    it('una URL http://... nunca se conserva como path.', () => {
        const privateBeer = { userId: 'alice', type: { displayName: 'Rubia' }, timestamp: 123456, remotePhotoUrl: 'http://malicious.com/image.jpg' };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, undefined);
    });

    it('una URL https://... externa nunca se conserva.', () => {
        const privateBeer = { userId: 'alice', type: { displayName: 'Rubia' }, timestamp: 123456, remotePhotoUrl: 'https://malicious.com/image.jpg' };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, undefined);
    });

    it('una URL legacy válida de Firebase Storage que apunte exactamente a la cerveza correcta se convierte a la ruta segura.', () => {
        const privateBeer = {
            userId: 'alice',
            type: { displayName: 'Rubia' },
            timestamp: 123456,
            remotePhotoUrl: 'https://firebasestorage.googleapis.com/v0/b/demo/o/users%2Falice%2Fbeers%2Fbeer1.jpg?alt=media&token=1234'
        };
        const socialBeer = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.strictEqual(socialBeer.photoStoragePath, 'users/alice/beers/beer1.jpg');
    });

    it('una URL legacy válida sintácticamente pero que apunte a otro usuario o a otra cerveza se rechaza.', () => {
        const privateBeer1 = { userId: 'alice', timestamp: 123456, remotePhotoUrl: 'https://firebasestorage.googleapis.com/v0/b/demo/o/users%2Fbob%2Fbeers%2Fbeer1.jpg' };
        assert.strictEqual(sanitizeBeerForSocial(privateBeer1, 'beer1').photoStoragePath, undefined);

        const privateBeer2 = { userId: 'alice', timestamp: 123456, remotePhotoUrl: 'https://firebasestorage.googleapis.com/v0/b/demo/o/users%2Falice%2Fbeers%2Fbeer2.jpg' };
        assert.strictEqual(sanitizeBeerForSocial(privateBeer2, 'beer1').photoStoragePath, undefined);
    });

    it('ejecutar dos veces la transformación no degrada el resultado (idempotente)', () => {
        const privateBeer = {
            userId: 'alice',
            type: { displayName: 'Rubia' },
            timestamp: 123456,
            remotePhotoUrl: 'https://firebasestorage.googleapis.com/v0/b/demo/o/users%2Falice%2Fbeers%2Fbeer1.jpg'
        };
        const socialBeer1 = sanitizeBeerForSocial(privateBeer, 'beer1');
        // simulate backfill hitting it again (where remotePhotoUrl might still exist, wait, privateBeer is unmodified)
        const socialBeer2 = sanitizeBeerForSocial(privateBeer, 'beer1');
        assert.deepStrictEqual(socialBeer1, socialBeer2);
        
        // simulate trigger with raw path already outputted by previous trigger
        const privateBeerAlreadySafe = {
            userId: 'alice',
            type: { displayName: 'Rubia' },
            timestamp: 123456,
            remotePhotoUrl: 'users/alice/beers/beer1.jpg'
        };
        const socialBeer3 = sanitizeBeerForSocial(privateBeerAlreadySafe, 'beer1');
        assert.strictEqual(socialBeer3.photoStoragePath, 'users/alice/beers/beer1.jpg');
    });
});
