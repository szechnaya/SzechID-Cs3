package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.SubsExtractors.invokeOpenSubs
import com.hexated.SubsExtractors.invokeWatchsomuch
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI

private const val TRACKER_LIST_URL =
    "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"

class StremioC : MainAPI() {
    override var mainUrl = "https://stremio.github.io/stremio-static-addon-example"
    override var name = "StremioC"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
    private val cinemataUrl = "https://v3-cinemeta.strem.io"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        mainUrl = mainUrl.fixSourceUrl()
        val res = tryParseJson<Manifest>(request("${mainUrl}/manifest.json").body.string()) ?: return null
        val lists = mutableListOf<HomePageList>()
        res.catalogs.apmap { catalog ->
            catalog.toHomePageList(this).let {
                if (it.list.isNotEmpty()) lists.add(it)
            }
        }
        return HomePageResponse(
            lists,
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        mainUrl = mainUrl.fixSourceUrl()
        val res = tryParseJson<Manifest>(request("${mainUrl}/manifest.json").body.string()) ?: return null
        val list = mutableListOf<SearchResponse>()
        res.catalogs.apmap { catalog ->
            list.addAll(catalog.search(query, this))
        }
        return list.distinct()
    }

    override suspend fun load(url: String): LoadResponse {
        val res = parseJson<CatalogEntry>(url)
        mainUrl =
            if ((res.type == "movie" || res.type == "series") && isImdborTmdb(res.id)) cinemataUrl else mainUrl
        val json = app.get("${mainUrl}/meta/${res.type}/${res.id}.json")
            .parsedSafe<CatalogResponse>()?.meta ?: throw RuntimeException(url)
        return json.toLoadResponse(this, res.id)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        val request = request("${mainUrl}/stream/${loadData.type}/${loadData.id}.json")
        if (request.code.isSuccessful()) {
            val res = tryParseJson<StreamsResponse>(request.body.string()) ?: return false
            res.streams.forEach { stream ->
                stream.runCallback(subtitleCallback, callback)
            }
        } else {
            argamap(
                {
                    invokeStremioX(loadData.type, loadData.id, subtitleCallback, callback)
                },
                {
                    invokeWatchsomuch(
                        loadData.imdbId,
                        loadData.season,
                        loadData.episode,
                        subtitleCallback
                    )
                },
                {
                    invokeOpenSubs(
                        loadData.imdbId,
                        loadData.season,
                        loadData.episode,
                        subtitleCallback
                    )
                },
            )
        }

        return true
    }

    private suspend fun invokeStremioX(
        type: String?,
        id: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sites =
            AcraApplication.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList()
                ?: mutableListOf()
        sites.filter { it.parentJavaClass == "StremioX" }.apmap { site ->
            val request = request("${site.url.fixSourceUrl()}/stream/${type}/${id}.json").body.string()
            val res =
                tryParseJson<StreamsResponse>(request)
                    ?: return@apmap
            res.streams.forEach { stream ->
                stream.runCallback(subtitleCallback, callback)
            }
        }
    }

    data class LoadData(
        val type: String? = null,
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val imdbId: String? = null,
    )

    data class CustomSite(
        @JsonProperty("parentJavaClass") val parentJavaClass: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("lang") val lang: String,
    )

    // check if id is imdb/tmdb cause stremio addons like torrentio works base on imdbId
    private fun isImdborTmdb(url: String?): Boolean {
        return imdbUrlToIdNullable(url) != null || url?.startsWith("tmdb:") == true
    }

    private data class Manifest(val catalogs: List<Catalog>)
    private data class Catalog(
        var name: String?,
        val id: String,
        val type: String?,
        val types: MutableList<String> = mutableListOf()
    ) {
        init {
            if (type != null) types.add(type)
        }

        suspend fun search(query: String, provider: StremioC): List<SearchResponse> {
            val entries = mutableListOf<SearchResponse>()
            types.forEach { type ->
                val json = request("${provider.mainUrl}/catalog/${type}/${id}/search=${query}.json").body.string()
                val res =
                    tryParseJson<CatalogResponse>(json)
                        ?: return@forEach
                res.metas?.forEach { entry ->
                    entries.add(entry.toSearchResponse(provider))
                }
            }
            return entries
        }

        suspend fun toHomePageList(provider: StremioC): HomePageList {
            val entries = mutableListOf<SearchResponse>()
            types.forEach { type ->
                val json = request("${provider.mainUrl}/catalog/${type}/${id}.json").body.string()
                val res =
                    tryParseJson<CatalogResponse>(json)
                        ?: return@forEach
                res.metas?.forEach { entry ->
                    entries.add(entry.toSearchResponse(provider))
                }
            }
            return HomePageList(
                "$type - ${name ?: id}",
                entries
            )
        }
    }

    private data class CatalogResponse(val metas: List<CatalogEntry>?, val meta: CatalogEntry?)

    private data class Trailer(
        val source: String?,
        val type: String?
    )
    private data class CatalogEntry(
        @JsonProperty("name") val name: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("background") val background: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("imdbRating") val imdbRating: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("videos") val videos: List<Video>?,
        @JsonProperty("genre") val genre: List<String>?,
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("cast") val cast: List<String>?,
        @JsonProperty("year") val yearNum: String? = null,
        @JsonProperty("trailers") val trailersSources: ArrayList<Trailer>? = arrayListOf()
    ) {
        fun toSearchResponse(provider: StremioC): SearchResponse {
            return provider.newMovieSearchResponse(
                fixTitle(name),
                this.toJson(),
                TvType.Others
            ) {
                posterUrl = poster
            }
        }

        suspend fun toLoadResponse(provider: StremioC, imdbId: String?): LoadResponse {
            if (videos.isNullOrEmpty()) {
                return provider.newMovieLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.Movie,
                    LoadData(type, id, imdbId = imdbId)
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    rating = imdbRating.toRatingInt()
                    plot = description
                    year = yearNum?.toIntOrNull()
                    tags = genre ?: genres
                    addActors(cast)
                    addTrailer(trailersSources?.map { "https://www.youtube.com/watch?v=${it.source}" }?.randomOrNull())
                    addImdbId(imdbId)
                }
            } else {
                return provider.newTvSeriesLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.TvSeries,
                    videos.map {
                        it.toEpisode(provider, type, imdbId)
                    }
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    rating = imdbRating.toRatingInt()
                    plot = description
                    year = yearNum?.toIntOrNull()
                    tags = genre ?: genres
                    addActors(cast)
                    addTrailer(trailersSources?.map { "https://www.youtube.com/watch?v=${it.source}" }?.randomOrNull())
                    addImdbId(imdbId)
                }
            }

        }
    }

    private data class Video(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season") val seasonNumber: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("description") val description: String? = null,
    ) {
        fun toEpisode(provider: StremioC, type: String?, imdbId: String?): Episode {
            return provider.newEpisode(
                LoadData(type, id, seasonNumber, episode ?: number, imdbId)
            ) {
                this.name = name ?: title
                this.posterUrl = thumbnail
                this.description = overview ?: description
                this.season = seasonNumber
                this.episode = episode ?: number
            }
        }
    }

    private data class StreamsResponse(val streams: List<Stream>)
    private data class Subtitle(
        val url: String?,
        val lang: String?,
        val id: String?,
    )

    private data class ProxyHeaders(
        val request: Map<String,String>?,
    )

    private data class BehaviorHints(
        val proxyHeaders: ProxyHeaders?,
        val headers: Map<String,String>?,
    )
    private data class Stream(
        val name: String?,
        val title: String?,
        val url: String?,
        val description: String?,
        val ytId: String?,
        val externalUrl: String?,
        val behaviorHints: BehaviorHints?,
        val infoHash: String?,
        val sources: List<String> = emptyList(),
        val subtitles: List<Subtitle> = emptyList()
    ) {
        suspend fun runCallback(
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            if (url != null) {
                callback.invoke(
                    ExtractorLink(
                        name ?: "",
                        fixRDSourceName(name, title),
                        url,
                        "",
                        getQualityFromName(description),
                        headers = behaviorHints?.proxyHeaders?.request ?: behaviorHints?.headers ?: mapOf(),
                        isM3u8 = URI(url).path.endsWith(".m3u8")
                    )
                )
                subtitles.map { sub ->
                    subtitleCallback.invoke(
                        SubtitleFile(
                            SubtitleHelper.fromThreeLettersToLanguage(sub.lang ?: "") ?: sub.lang
                            ?: "",
                            sub.url ?: return@map
                        )
                    )
                }
            }
            if (ytId != null) {
                loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
            }
            if (externalUrl != null) {
                loadExtractor(externalUrl, subtitleCallback, callback)
            }
            if (infoHash != null) {
                val resp = app.get(TRACKER_LIST_URL).text
                val otherTrackers = resp
                    .split("\n")
                    .filterIndexed { i, _ -> i % 2 == 0 }
                    .filter { s -> s.isNotEmpty() }.joinToString("") { "&tr=$it" }

                val sourceTrackers = sources
                    .filter { it.startsWith("tracker:") }
                    .map { it.removePrefix("tracker:") }
                    .filter { s -> s.isNotEmpty() }.joinToString("") { "&tr=$it" }

                val magnet = "magnet:?xt=urn:btih:${infoHash}${sourceTrackers}${otherTrackers}"
                callback.invoke(
                    ExtractorLink(
                        name ?: "",
                        title ?: name ?: "",
                        magnet,
                        "",
                        Qualities.Unknown.value
                    )
                )
            }
        }
    }
}
