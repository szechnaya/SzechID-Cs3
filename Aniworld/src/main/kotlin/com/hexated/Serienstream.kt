package com.hexated

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType

class Serienstream : Aniworld() {
    override var mainUrl = "https://s.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun load(url: String): LoadResponse? {
        return super.load(url).apply { this?.type = TvType.TvSeries }
    }
}