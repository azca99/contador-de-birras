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
        // If it's already a safe storage path format (e.g. users/uid/beers/id.jpg)
        if (!rawPhoto.startsWith('http://') && !rawPhoto.startsWith('https://')) {
            sharedData.photoStoragePath = rawPhoto;
        } else {
            // Attempt to extract path from Firebase Storage URL
            // Format: https://firebasestorage.googleapis.com/v0/b/bucket-name/o/users%2F{userId}%2Fbeers%2F{beerId}.jpg?...
            try {
                const url = new URL(rawPhoto);
                const pathMatches = url.pathname.match(/\/o\/(.+)$/);
                if (pathMatches && pathMatches[1]) {
                    const decodedPath = decodeURIComponent(pathMatches[1]);
                    // Only allow paths that belong to the user's beer folder
                    if (decodedPath.startsWith(`users/${privateBeerData.userId}/beers/`)) {
                        sharedData.photoStoragePath = decodedPath;
                    }
                }
            } catch (e) {
                // Ignore malformed URLs and simply omit the photo field
            }
        }
    }
    
    return sharedData;
}

module.exports = { sanitizeBeerForSocial };
