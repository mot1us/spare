package io.github.mot1us.spare

/** Immutable lookup built off-thread; category changes do not scan every provider's channels. */
class GuideChannelIndex {
    private data class Index(val channels: List<Channel>, val groups: Map<String, List<Channel>>, val playlists: Map<String, List<Channel>>, val ids: Set<String>)
    @Volatile private var index: Index? = null
    fun prepare(channels: List<Channel>, legacy: String) {
        if (index?.channels === channels) return
        index = Index(channels, channels.groupBy { it.group }, channels.groupBy { it.playlistId.ifBlank { legacy } }, channels.mapTo(HashSet(channels.size)) { it.id })
    }
    fun candidates(channels: List<Channel>, playlist: String?, group: String?): List<Channel> {
        val ready = index?.takeIf { it.channels === channels } ?: return channels
        return when { group != null -> ready.groups[group].orEmpty(); playlist != null -> ready.playlists[playlist].orEmpty(); else -> channels }
    }
    fun contains(channels: List<Channel>, id: String) = index?.takeIf { it.channels === channels }?.ids?.contains(id) ?: channels.any { it.id == id }
}
