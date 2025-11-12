
package com.filmapik

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmapikPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Filmapik())
        registerExtractorAPI(EfekStream())
        registerExtractorAPI(ShortIcu())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(Ico3c())
    }
}