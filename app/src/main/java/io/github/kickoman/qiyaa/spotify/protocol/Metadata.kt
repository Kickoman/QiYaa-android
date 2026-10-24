package io.github.kickoman.qiyaa.spotify.protocol

enum class ExtensionKind(val code: Int) {
    ARTIST_V4(8),
    ALBUM_V4(9),
    TRACK_V4(10),
}

object Metadata {
    fun request(uris: List<String>, kind: ExtensionKind): ByteArray {
        val query = ProtoWriter().varint(ExtensionQueryFields.EXTENSION_KIND, kind.code.toLong())
        val request = ProtoWriter()
        for (uri in uris) {
            val entity = ProtoWriter()
                .string(EntityRequestFields.ENTITY_URI, uri)
                .message(EntityRequestFields.QUERY, query)
            request.message(BatchedEntityRequestFields.ENTITY_REQUEST, entity)
        }
        return request.toByteArray()
    }
}

private object BatchedEntityRequestFields {
    const val ENTITY_REQUEST = 2
}

private object EntityRequestFields {
    const val ENTITY_URI = 1
    const val QUERY = 2
}

private object ExtensionQueryFields {
    const val EXTENSION_KIND = 1
}
