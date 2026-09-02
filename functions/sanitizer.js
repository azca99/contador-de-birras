function sanitizeBeerForSocial(privateBeerData, beerId) {
    const sharedData = {
        userId: privateBeerData.userId,
        type: privateBeerData.type,
        timestamp: privateBeerData.timestamp
    };
    
    if (privateBeerData.comment !== undefined) {
        sharedData.comment = privateBeerData.comment;
    }
    if (privateBeerData.updatedAt !== undefined) {
        sharedData.updatedAt = privateBeerData.updatedAt;
    }

    const rawPhoto = privateBeerData.remotePhotoUrl;
    if (rawPhoto) {
        const expectedPath = `users/${privateBeerData.userId}/beers/${beerId}.jpg`;
        
        if (!rawPhoto.startsWith('http://') && !rawPhoto.startsWith('https://')) {
            if (rawPhoto === expectedPath) {
                sharedData.photoStoragePath = rawPhoto;
            }
        } else {
            try {
                const url = new URL(rawPhoto);
                const pathMatches = url.pathname.match(/\/o\/(.+)$/);
                if (pathMatches && pathMatches[1]) {
                    const decodedPath = decodeURIComponent(pathMatches[1]);
                    if (decodedPath === expectedPath) {
                        sharedData.photoStoragePath = decodedPath;
                    }
                }
            } catch (e) {
                // Ignore malformed URLs
            }
        }
    }
    
    return sharedData;
}

module.exports = { sanitizeBeerForSocial };
