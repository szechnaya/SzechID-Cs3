package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Anilibria : MainAPI() {
    override var mainUrl = "https://anilibria.tv"
    override var name = "Anilibria"
    override val hasMainPage = true
    override var lang = "ru"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("Фильм", true) -> TvType.Movie
                t.contains("ТВ", true) -> TvType.Anime
                else -> TvType.OVA
            }
        }
    }

    override val mainPage = mainPageOf(
        "1" to "Новое",
        "2" to "Популярное",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.post(
            "$mainUrl/public/catalog.php", data = mapOf(
                "page" to "$page",
                "xpage" to "catalog",
                "sort" to request.data,
                "finish" to "1",
                "search" to "{\"year\":\"\",\"genre\":\"\",\"season\":\"\"}"
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Home>()?.table?.let { Jsoup.parse(it) }
        val home = document?.select("a")?.mapNotNull {
            it.toSearchResult()
        } ?: throw ErrorLoadingException("Invalid json responses")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrl(this.attr("href"))
        val title = this.selectFirst("span")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "$mainUrl/public/search.php",
            data = mapOf("search" to query, "small" to "1"),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Search>()?.mes?.let { Jsoup.parse(it) }

        return document?.select("a")?.mapNotNull {
            it.toSearchResult()
        } ?: throw ErrorLoadingException("Invalid json responses")
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.release-title")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("img#adminPoster")?.attr("src"))
        val trackTitle = (document.selectFirst("h1.release-title br")?.nextSibling()
            ?: document.selectFirst("h1.release-title")?.text()?.substringAfter("/")?.trim()).toString()
        val type = document.selectFirst("div#xreleaseInfo b:contains(Тип:)")?.nextSibling()
            .toString().substringBefore(",").trim()
        val trackType = type.let {
            if(it.contains("Фильм", true)) "movie" else "tv"
        }
        val year = document.selectFirst("div#xreleaseInfo b:contains(Сезон:)")?.nextElementSibling()
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val (malId, anilistId, image, cover) = getTracker(trackTitle, trackType, year)
        val episodes = document.select("script").find { it.data().contains("var player =") }?.data()
            ?.substringAfter("file:[")?.substringBefore("],")?.let { data ->
                tryParseJson<List<Episodes>>("[$data]")?.mapNotNull { eps ->
                    Episode(
                        eps.file ?: return@mapNotNull null,
                        name = eps.title ?: return@mapNotNull null,
                        posterUrl = fixUrlNull(eps.poster),
                    )
                }
            }
        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = image ?: poster
            backgroundPosterUrl = cover ?: image ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            plot = document.select("p.detail-description").text().trim()
            this.tags = document.selectFirst("div#xreleaseInfo b:contains(Жанры:)")?.nextSibling()
                .toString().split(",").map { it.trim() }
            addMalId(malId)
            addAniListId(anilistId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        data.split(",").map { it.trim() }.map { m3u ->
            val quality = Regex("\\[([0-9]+p)]").find(m3u)?.groupValues?.getOrNull(1)
            val link = m3u.removePrefix("[$quality]").trim()
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    link,
                    "$mainUrl/",
                    getQualityFromName(quality),
                    true
                )
            )
        }

        return true
    }

    private suspend fun getTracker(title: String?, type: String?, year: Int?): Tracker {
        val res = app.get("https://api.consumet.org/meta/anilist/$title")
            .parsedSafe<AniSearch>()?.results?.find { media ->
                (media.title?.english.equals(title, true) || media.title?.romaji.equals(
                    title,
                    true
                )) || (media.type.equals(type, true) && media.releaseDate == year)
            }
        return Tracker(res?.malId, res?.aniId, res?.image, res?.cover)
    }

    data class Tracker(
        val malId: Int? = null,
        val aniId: String? = null,
        val image: String? = null,
        val cover: String? = null,
    )

    data class Title(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
    )

    data class Results(
        @JsonProperty("id") val aniId: String? = null,
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("releaseDate") val releaseDate: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("cover") val cover: String? = null,
    )

    data class AniSearch(
        @JsonProperty("results") val results: ArrayList<Results>? = arrayListOf(),
    )

    private data class Episodes(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster") val poster: String? = null,
    )

    private data class Home(
        @JsonProperty("table") val table: String? = null,
    )

    private data class Search(
        @JsonProperty("mes") val mes: String? = null,
    )

}