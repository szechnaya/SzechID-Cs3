package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URI

open class Movierulzhd : MainAPI() {

    override var mainUrl = "https://movierulzvid.gold"

    var directUrl = ""
    override var name = "Movierulzhd"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "movies" to "Movies",
        "tvshows" to "TV Shows",
        "genre/netflix" to "Netflix",
        "genre/amazon-prime" to "Amazon Prime",
        "genre/Zee5" to "Zee5",
        "seasons" to "Season",
        "episodes" to "Episode",
    )

    val interceptor = CloudflareKiller()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        var document = app.get("$mainUrl/${request.data}/page/$page").document
        if (document.select("title").text() == "Just a moment...") {
            document = app.get(request.data + page, interceptor = interceptor).document
        }
        val home =
            document.select("div.items.normal article, div#archive-content article").mapNotNull {
                it.toSearchResult()
            }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episodes/") -> {
                var title = uri.substringAfter("$mainUrl/episodes/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            uri.contains("/seasons/") -> {
                var title = uri.substringAfter("$mainUrl/seasons/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            else -> {
                uri
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 > a")?.text() ?: return null
        val href = getProperLink(fixUrl(this.selectFirst("h3 > a")!!.attr("href")))
        val posterUrl = fixUrlNull(this.select("div.poster img").last()?.attr("src"))
        val quality = getQualityFromString(this.select("span.quality").text())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
            posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/search/$query"
        var document = app.get(link).document
        if (document.select("title").text() == "Just a moment...") {
            document = app.get(link, interceptor = interceptor).document
        }

        return document.select("div.result-item").map {
            val title =
                it.selectFirst("div.title > a")!!.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = getProperLink(it.selectFirst("div.title > a")!!.attr("href"))
            val posterUrl = it.selectFirst("img")!!.attr("src").toString()
            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        var document = request.document
        if (document.select("title").text() == "Just a moment...") {
            document = app.get(url, interceptor = interceptor).document
        }
        directUrl = getBaseUrl(request.url)
        val title =
            document.selectFirst("div.data > h1")?.text()?.trim().toString()
        val poster = fixUrlNull(document.select("div.poster img:last-child").attr("src"))
        val tags = document.select("div.sgeneros > a").map { it.text() }

        val year = Regex(",\\s?(\\d+)").find(
            document.select("span.date").text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val tvType = if (document.select("ul#section > li:nth-child(1)").text()
                .contains("Episodes") || document.select("ul#playeroptionsul li span.title")
                .text().contains(
                    Regex("Episode\\s+\\d+|EP\\d+|PE\\d+")
                )
        ) TvType.TvSeries else TvType.Movie
        val description = document.select("div.wp-content > p").text().trim()
        val trailer = document.selectFirst("div.embed iframe")?.attr("src")
        val rating =
            document.selectFirst("span.dt_rating_vgs")?.text()?.toRatingInt()
        val actors = document.select("div.persons > div[itemprop=actor]").map {
            Actor(
                it.select("meta[itemprop=name]").attr("content"),
                it.select("img:last-child").attr("src")
            )
        }

        val recommendations = document.select("div.owl-item").map {
            val recName =
                it.selectFirst("a")!!.attr("href").toString().removeSuffix("/").split("/").last()
            val recHref = it.selectFirst("a")!!.attr("href")
            val recPosterUrl = it.selectFirst("img")?.attr("src").toString()
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
                posterHeaders = interceptor.getCookieHeaders(url).toMap()
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = if (document.select("ul.episodios > li").isNotEmpty()) {
                document.select("ul.episodios > li").map {
                    val href = it.select("a").attr("href")
                    val name = fixTitle(it.select("div.episodiotitle > a").text().trim())
                    val image = it.select("div.imagen > img").attr("src")
                    val episode =
                        it.select("div.numerando").text().replace(" ", "").split("-").last()
                            .toIntOrNull()
                    val season =
                        it.select("div.numerando").text().replace(" ", "").split("-").first()
                            .toIntOrNull()
                    Episode(
                        href,
                        name,
                        season,
                        episode,
                        image
                    )
                }
            } else {
                document.select("ul#playeroptionsul > li").map {
                    val name = it.selectFirst("span.title")?.text()
                    val type = it.attr("data-type")
                    val post = it.attr("data-post")
                    val nume = it.attr("data-nume")
                    Episode(
                        LinkData(type, post, nume).toJson(),
                        name,
                    )
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
                posterHeaders = interceptor.getCookieHeaders(url).toMap()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
                posterHeaders = interceptor.getCookieHeaders(url).toMap()
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (data.startsWith("{")) {
            val loadData = AppUtils.tryParseJson<LinkData>(data)
            val source = app.post(
                url = "$directUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to "${loadData?.post}",
                    "nume" to "${loadData?.nume}",
                    "type" to "${loadData?.type}"
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsed<ResponseHash>().embed_url
            if (!source.contains("youtube")) loadExtractor(source, data, subtitleCallback, callback)
        } else {
            var document = app.get(data).document
            if (document.select("title").text() == "Just a moment...") {
                document = app.get(data, interceptor = interceptor).document
            }
            val id = document.select("meta#dooplay-ajax-counter").attr("data-postid")
            val type = if (data.contains("/movies/")) "movie" else "tv"

            document.select("ul#playeroptionsul > li").map {
                it.attr("data-nume")
            }.apmap { nume ->
                val source = app.post(
                    url = "$directUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsed<ResponseHash>().embed_url

                when {
                    !source.contains("youtube") -> loadExtractor(
                        source,
                        data,
                        subtitleCallback,
                        callback
                    )
                    else -> return@apmap
                }
            }
        }
        return true
    }

    data class LinkData(
        val type: String? = null,
        val post: String? = null,
        val nume: String? = null,
    )

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String?,
    )

}
