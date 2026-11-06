package io.github.mot1us.spare

/** First-screen look-ahead, not a sweep of every stream in a large playlist. */
class GuidePrefetch {
    private data class Group(val playlist: String, val category: String)
    private data class Index(val lineup: List<Channel>, val heads: List<List<Channel>>, val positions: Map<Group, Int>)
    @Volatile private var index: Index? = null
    private fun group(channel: Channel) = Group(channel.playlistId, channel.group)
    /** Called on the existing import worker, never on the TV's main thread. */
    fun prepare(channels: List<Channel>) {
        if (index?.lineup !== channels) {
            val groups = linkedMapOf<Group, MutableList<Channel>>()
            channels.asSequence().filter { !it.media }.forEach { channel -> val head = groups.getOrPut(group(channel)) { arrayListOf() }; if (head.size < 8) head.add(channel) }
            index = Index(channels, groups.values.map { it.toList() }, groups.keys.withIndex().associate { it.value to it.index })
        }
    }
    fun targets(channels: List<Channel>, visibleCategory: List<Channel>, anchor: Channel?, favorites: Set<String>): List<Channel> {
        val prepared = index?.takeIf { it.lineup === channels }
        val at = visibleCategory.indexOfFirst { it.id == anchor?.id }.coerceAtLeast(0)
        val near = visibleCategory.subList((at - 8).coerceAtLeast(0), minOf(at + 32, visibleCategory.size))
        val favoritesToWarm = if (favorites.isEmpty()) emptyList() else channels.asSequence().filter { it.id in favorites }.take(16).toList()
        val position = anchor?.let { prepared?.positions?.get(group(it)) }
        val neighbors = position?.let { n -> listOf(n - 1, n + 1, n - 2, n + 2).flatMap { prepared?.heads?.getOrNull(it).orEmpty() } }.orEmpty()
        return (listOfNotNull(anchor) + near + favoritesToWarm + neighbors).asSequence().filter { !it.media }.distinctBy { it.id }.take(96).toList()
    }
}
