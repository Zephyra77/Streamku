package com.dramaindo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.dramaindo.extractors.Mitedrive
import com.dramaindo.extractors.Berkasdrive

@CloudstreamPlugin
class DramaindoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dramaindo())
        registerExtractorAPI(Mitedrive())
        registerExtractorAPI(Berkasdrive())
    }
}
