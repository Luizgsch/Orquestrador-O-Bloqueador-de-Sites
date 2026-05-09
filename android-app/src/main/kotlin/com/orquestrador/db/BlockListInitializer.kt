package com.orquestrador.db

import android.content.Context
import com.orquestrador.vpn.BlockList

object BlockListInitializer {
    fun initializeDatabase(context: Context) {
        val db = BlockedDomainDatabase(context)

        if (db.getDomainCount() == 0) {
            val domainsCategoryMap = mutableMapOf<String, String>()

            BlockList.domains.forEach { (category, domains) ->
                domains.forEach { domain ->
                    domainsCategoryMap[domain] = category.name
                }
            }

            db.replaceDomains(domainsCategoryMap)
        }

        db.close()
    }
}
