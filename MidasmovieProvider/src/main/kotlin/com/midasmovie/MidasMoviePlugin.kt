package com.midasmovie

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MidasMoviePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MidasMovie())        
    }
}
