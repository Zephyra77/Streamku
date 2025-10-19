package com.pusatfilm

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PusatfilmPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pusatfilm())
        registerExtractorAPI(Kotakajaib())
    }
}