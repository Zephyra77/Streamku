package com.kitanonton

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KitanontonPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Kitanonton())
    }
}