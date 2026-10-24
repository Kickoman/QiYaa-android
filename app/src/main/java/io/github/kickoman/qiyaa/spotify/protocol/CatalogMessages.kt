package io.github.kickoman.qiyaa.spotify.protocol

object CatalogMessages {
    const val COLLECTION_CONTENT_TYPE = "application/vnd.collection-v2.spotify.proto"

    fun collectionPageRequest(username: String, set: String, pageToken: String, limit: Int): ByteArray {
        val request = ProtoWriter()
            .string(PageRequestFields.USERNAME, username)
            .string(PageRequestFields.SET, set)
        if (pageToken.isNotEmpty()) request.string(PageRequestFields.PAGINATION_TOKEN, pageToken)
        return request.varint(PageRequestFields.LIMIT, limit.toLong()).toByteArray()
    }

    fun autoplayRequest(contextUri: String, recentTrackUris: List<String>): ByteArray {
        val request = ProtoWriter().string(AutoplayRequestFields.CONTEXT_URI, contextUri)
        for (uri in recentTrackUris) request.string(AutoplayRequestFields.RECENT_TRACK_URI, uri)
        return request.toByteArray()
    }
}

private object PageRequestFields {
    const val USERNAME = 1
    const val SET = 2
    const val PAGINATION_TOKEN = 3
    const val LIMIT = 4
}

private object AutoplayRequestFields {
    const val CONTEXT_URI = 1
    const val RECENT_TRACK_URI = 2
}
