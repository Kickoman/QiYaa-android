package io.github.kickoman.qiyaa.spotify.protocol

data class CollectionItem(val uri: String, val addedAt: Long)

data class CollectionPage(val items: List<CollectionItem>, val nextPageToken: String) {
    val last: Boolean get() = nextPageToken.isEmpty() || items.isEmpty()
}

data class ListItem(val uri: String, val name: String = "")

data class ListPage(
    val name: String = "",
    val length: Int = 0,
    val from: Int = 0,
    val truncated: Boolean = false,
    val items: List<ListItem> = emptyList(),
) {
    fun hasMoreAfter(requestedFrom: Int, pageLength: Int): Boolean = requestedFrom + pageLength < length
}

object CatalogMessages {
    const val COLLECTION_CONTENT_TYPE = "application/vnd.collection-v2.spotify.proto"

    fun collectionPageRequest(username: String, set: String, pageToken: String, limit: Int): ByteArray {
        val request = ProtoWriter()
            .string(PageRequestFields.USERNAME, username)
            .string(PageRequestFields.SET, set)
        if (pageToken.isNotEmpty()) request.string(PageRequestFields.PAGINATION_TOKEN, pageToken)
        return request.varint(PageRequestFields.LIMIT, limit.toLong()).toByteArray()
    }

    fun parseCollectionPage(body: ByteArray): CollectionPage? {
        val items = ArrayList<CollectionItem>()
        var nextPageToken = ""
        val reader = ProtoReader(body)
        while (reader.next()) {
            when (reader.field) {
                PageResponseFields.ITEM -> collectionItemOf(reader.message())?.let(items::add)
                PageResponseFields.NEXT_PAGE_TOKEN -> nextPageToken = reader.string()
            }
        }
        return CollectionPage(items, nextPageToken).takeUnless { reader.failed }
    }

    fun parseListPage(body: ByteArray): ListPage? {
        var name = ""
        var length = 0
        var from = 0
        var truncated = false
        val items = ArrayList<ListItem>()
        val metas = ArrayList<MetaItem>()
        val reader = ProtoReader(body)
        while (reader.next()) {
            when (reader.field) {
                SelectedListContentFields.LENGTH -> length = reader.varint.toInt()

                SelectedListContentFields.ATTRIBUTES -> name = nameOf(reader.message())

                SelectedListContentFields.CONTENTS -> {
                    val contents = reader.message()
                    while (contents.next()) {
                        when (contents.field) {
                            ListItemsFields.POS -> from = contents.varint.toInt()
                            ListItemsFields.TRUNCATED -> truncated = contents.varint != 0L
                            ListItemsFields.ITEMS -> items += listItemOf(contents.message())
                            ListItemsFields.META_ITEMS -> metas += metaItemOf(contents.message())
                        }
                    }
                    if (contents.failed) return null
                }
            }
        }
        if (reader.failed) return null
        val kept =
            if (metas.size != items.size) {
                items
            } else {
                items.zip(metas)
                    .filter { (_, meta) -> meta.status == 0 || meta.status == STATUS_OK }
                    .map { (item, meta) -> item.copy(name = meta.name) }
            }
        return ListPage(name, length, from, truncated, kept)
    }

    fun playlistsOf(rootlist: List<ListItem>): List<ListItem> = rootlist.filter {
        it.uri.startsWith(Ids.PLAYLIST_URI_PREFIX)
    }

    fun autoplayRequest(contextUri: String, recentTrackUris: List<String>): ByteArray {
        val request = ProtoWriter().string(AutoplayRequestFields.CONTEXT_URI, contextUri)
        for (uri in recentTrackUris) request.string(AutoplayRequestFields.RECENT_TRACK_URI, uri)
        return request.toByteArray()
    }

    private fun collectionItemOf(reader: ProtoReader): CollectionItem? {
        var uri = ""
        var addedAt = 0L
        var removed = false
        while (reader.next()) {
            when (reader.field) {
                CollectionItemFields.URI -> uri = reader.string()
                CollectionItemFields.ADDED_AT -> addedAt = reader.varint
                CollectionItemFields.IS_REMOVED -> removed = reader.varint != 0L
            }
        }
        if (reader.failed || removed || uri.isEmpty()) return null
        return CollectionItem(uri, addedAt)
    }

    private fun listItemOf(reader: ProtoReader): ListItem {
        var uri = ""
        while (reader.next()) {
            if (reader.field == ItemFields.URI) uri = reader.string()
        }
        return ListItem(uri)
    }

    private fun metaItemOf(reader: ProtoReader): MetaItem {
        var name = ""
        var status = 0
        while (reader.next()) {
            when (reader.field) {
                MetaItemFields.ATTRIBUTES -> name = nameOf(reader.message())
                MetaItemFields.STATUS_CODE -> status = reader.zigzag.toInt()
            }
        }
        return MetaItem(name, status)
    }

    private fun nameOf(attributes: ProtoReader): String {
        while (attributes.next()) {
            if (attributes.field == ListAttributesFields.NAME) return attributes.string()
        }
        return ""
    }

    private class MetaItem(val name: String, val status: Int)

    private const val STATUS_OK = 200
}

private object PageRequestFields {
    const val USERNAME = 1
    const val SET = 2
    const val PAGINATION_TOKEN = 3
    const val LIMIT = 4
}

private object PageResponseFields {
    const val ITEM = 1
    const val NEXT_PAGE_TOKEN = 2
}

private object CollectionItemFields {
    const val URI = 1
    const val ADDED_AT = 2
    const val IS_REMOVED = 3
}

private object SelectedListContentFields {
    const val LENGTH = 2
    const val ATTRIBUTES = 3
    const val CONTENTS = 5
}

private object ListItemsFields {
    const val POS = 1
    const val TRUNCATED = 2
    const val ITEMS = 3
    const val META_ITEMS = 4
}

private object ItemFields {
    const val URI = 1
}

private object MetaItemFields {
    const val ATTRIBUTES = 2
    const val STATUS_CODE = 9
}

private object ListAttributesFields {
    const val NAME = 1
}

private object AutoplayRequestFields {
    const val CONTEXT_URI = 1
    const val RECENT_TRACK_URI = 2
}
