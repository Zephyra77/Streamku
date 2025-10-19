package com.nimeindo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NimeindoProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Nimeindo())
    }
}
