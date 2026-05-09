package com.orquestrador.vpn

object BlockList {

    enum class Category { SOCIAL, ADULT, MANGA }

    val domains: Map<Category, Set<String>> = mapOf(
        Category.SOCIAL to setOf(
            "instagram.com", "tiktok.com", "twitter.com", "x.com",
            "youtube.com", "youtu.be", "facebook.com", "snapchat.com",
            "threads.net", "pinterest.com", "reddit.com",
        ),
        Category.ADULT to setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "redtube.com",
            "youporn.com", "xhamster.com", "tube8.com", "spankbang.com",
            "brazzers.com", "onlyfans.com",
        ),
        Category.MANGA to setOf(
            "mangadex.org", "webtoons.com", "mangafire.to", "mangahub.io",
            "asurascans.com", "bato.to", "reaper-scans.com", "flamescans.org",
            "tapas.io", "leiamanga.com", "unionmangas.top", "mangalivre.net",
        ),
    )

    fun blockedCategory(domain: String, enabled: Set<Category>): Category? {
        val bare = domain.removePrefix("www.").lowercase()
        for (cat in enabled) {
            val set = domains[cat] ?: continue
            if (bare in set || "www.$bare" in set) return cat
        }
        return null
    }
}
