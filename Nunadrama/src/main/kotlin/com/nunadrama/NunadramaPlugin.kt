package com.nunadrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NunadramaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Nunadrama())