package com.example.contadordebirras.domain

/**
 * Representación centralizada de las unidades lógicas de campos editables
 * para el protocolo de sincronización multidispositivo (Fase C2).
 *
 * TYPE: BeerEntity.type
 * TIMESTAMP: fecha y hora de la cerveza
 * LOCATION: latitude + longitude + locationName como unidad lógica
 * COMMENT: comentario / notas de cata
 * PHOTO: photoUri + remotePhotoUrl + photoSource como unidad lógica
 */
enum class BeerDirtyField {
    TYPE,
    TIMESTAMP,
    LOCATION,
    COMMENT,
    PHOTO
}

object BeerDirtyFieldsCodec {

    val ALL_FIELDS: Set<BeerDirtyField> = BeerDirtyField.entries.toSet()

    /**
     * Codifica un conjunto de campos modificados a una cadena CSV sin espacios
     * en el orden determinista de declaración del enum.
     * Retorna null si el conjunto está vacío.
     */
    fun encode(fields: Set<BeerDirtyField>): String? {
        if (fields.isEmpty()) return null
        return fields
            .sortedBy { it.name }
            .joinToString(",") { it.name }
    }

    /**
     * Decodifica una cadena CSV a un conjunto de [BeerDirtyField].
     * Si la cadena es nula o vacía, retorna un conjunto vacío.
     * Tokens desconocidos o corruptos son ignorados de forma segura sin lanzar excepción.
     */
    fun decode(raw: String?): Set<BeerDirtyField> {
        if (raw.isNullOrBlank()) return emptySet()
        val result = LinkedHashSet<BeerDirtyField>()
        for (token in raw.split(',')) {
            val trimmed = token.trim()
            if (trimmed.isNotEmpty()) {
                try {
                    result.add(BeerDirtyField.valueOf(trimmed))
                } catch (e: IllegalArgumentException) {
                    // Token desconocido o corrupto: ignorar para compatibilidad y robustez
                }
            }
        }
        return result
    }

    /**
     * Fusiona un conjunto de campos existentes con nuevos campos modificados,
     * garantizando orden determinista y ausencia de duplicados.
     */
    fun merge(existing: String?, added: Set<BeerDirtyField>): String? {
        val current = decode(existing)
        return encode(current + added)
    }

    /**
     * Sobrecarga de conveniencia para fusionar dos cadenas CSV.
     */
    fun merge(existing: String?, added: String?): String? {
        return merge(existing, decode(added))
    }
}
