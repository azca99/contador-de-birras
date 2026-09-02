content = """package com.example.contadordebirras.data

import com.example.contadordebirras.domain.BeerType

data class SharedBeerEntity(
    val syncId: String,
    val userId: String,
    val type: BeerType,
    val timestamp: Long,
    val comment: String?,
    val remotePhotoUrl: String?,
    val updatedAt: Long
)
"""
with open('app/src/main/java/com/example/contadordebirras/data/SharedBeerEntity.kt', 'w') as f:
    f.write(content)
