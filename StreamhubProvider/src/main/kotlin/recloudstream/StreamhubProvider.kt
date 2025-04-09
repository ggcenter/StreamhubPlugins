package recloudstream

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.Episode as CSEpisode

class StreamhubProvider : MainAPI() {

    data class Host(
        val i: Int,
        val t: String
    )

    data class HostsResponse(
        val hosts: List<Host>
    )

    data class Season(
        val season: Int,
        val name: String? = null,
        val episodes: List<Episode>
    )

    data class Episode(
        val id: String,
        val name: String,
        val episode: Int,
        val poster_path: String? = null,
        val description: String? = null,
        val streams: List<Stream>? = null
    )

    data class Stream(
        val i: Int,
        val h: String
    )

    data class VideoItem(
        val id: String,
        val title: String,
        val type: String,
        @Suppress("PropertyName")
        val poster_path: String
    )

    data class VideoSearchResponse(
        val list: List<VideoItem>
    )

    data class VideoDetailResponse(
        val id: String,
        val title: String,
        val description: String,
        val type: String,
        val poster_path: String,
        val streams: List<Stream>? = null,
        val seasons: List<Season>? = null
    )

    override var mainUrl = "https://api.Streamhub.com"
    override var name = "Streamhub"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val imageBaseUrl = "https://image.tmdb.org/t/p/w500"
    private val randomString = getRandomString()
    private val githubRawBaseUrl = "https://raw.githubusercontent.com/ggcenter/streamhub/refs/heads/main/public/github"

    override var lang = "pl"

    override val hasMainPage = true

    private suspend fun makeApiRequest(url: String): String {
        return app.get(
            "$githubRawBaseUrl/$url",
            headers = mapOf("Authorization" to "Bearer $randomString")
        ).text
    }

    private fun getRandomString(): String {
        val parts = arrayOf("github", "pat", "11AB5WZ2Q0QQjAmhZKAFkL", "rmvlSr3mmBj1QJmcJ6gGMNaS5zuK8k2J2GemoSJwsZG4WNXLANAWfPZaDw8")
        return parts.joinToString("_")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = makeApiRequest("main_page.json")
        val popular = tryParseJson<VideoSearchResponse>(response)?.list ?: emptyList()

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Popularne filmy",
                    popular.filter { it.type == "movie" }.map { it.toSearchResponse(this) },
                    true
                ),
                HomePageList(
                    "Popularne seriale",
                    popular.filter { it.type == "tv" }.map { it.toSearchResponse(this) },
                    true
                ),
            ),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>
        val response = makeApiRequest("search.json")
        val searchResults = tryParseJson<VideoSearchResponse>(response)?.list ?: return emptyList()
        return searchResults.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Zakładamy, że url to już samo ID
        val videoId = url
        val response = makeApiRequest("/data/$videoId.json")
        val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: return null
        return videoDetail.toLoadResponse(this)
    }

    private fun VideoItem.toSearchResponse(provider: StreamhubProvider): SearchResponse {
        // Określenie typu na podstawie pola type
        val tvType = if (this.type == "movie") TvType.Movie else TvType.TvSeries

        return if (this.type == "movie") {
            // Dla filmów
            provider.newMovieSearchResponse(
                this.title,
                this.id,
                tvType
            ) {
                this.posterUrl = provider.imageBaseUrl + this.poster_path
            }
        } else {
            // Dla seriali
            provider.newTvSeriesSearchResponse(
                this.title,
                this.id,
                tvType
            ) {
                this.posterUrl = provider.imageBaseUrl + this.poster_path
            }
        }
    }

    private suspend fun VideoDetailResponse.toLoadResponse(provider: StreamhubProvider): LoadResponse {
        return if (this.type == "movie") {
            // Dla filmów
            provider.newMovieLoadResponse(
                this.title,
                this.id,
                TvType.Movie,
                this.id
            ) {
                this.plot = description
                this.posterUrl = provider.imageBaseUrl + poster_path
            }
        } else {
            // Dla seriali
            provider.newTvSeriesLoadResponse(
                this.title,
                this.id,
                TvType.TvSeries,
                this.seasons?.flatMap { season ->
                    season.episodes?.map { episode ->
                        CSEpisode(
                            "${this.id}_${episode.id}",
                            episode.name,
                            season.season,
                            episode.episode
                        )
                    } ?: emptyList()
                } ?: emptyList()
            ) {
                this.plot = description
                this.posterUrl = provider.imageBaseUrl + poster_path
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isEpisode = data.contains("_")

            // Pobierz szablony hostingów
            val hostsResponse = makeApiRequest("/hosts.json")
            val hosts = tryParseJson<HostsResponse>(hostsResponse)?.hosts ?: return false
            val hostMap = hosts.associateBy { it.i }

        if (isEpisode) {
            // Format ID: "serialId_sXeY" gdzie X to numer sezonu, Y to numer odcinka
            val parts = data.split("_")
            val seriesId = parts[0]
            val episodeId = parts[1] // format "sXeY"

            // Wyciągnij numer sezonu i odcinka z episodeId
            val seasonNumber = episodeId.substringAfter("s").substringBefore("e").toIntOrNull() ?: 1
            val episodeNumber = episodeId.substringAfter("e").toIntOrNull() ?: 1

            val response = makeApiRequest("/data/$seriesId.json")
            val seriesDetail = tryParseJson<VideoDetailResponse>(response) ?: return false

            // Znajdź odpowiedni odcinek
            val episode = seriesDetail.seasons
                ?.find { it.season == seasonNumber }
                ?.episodes
                ?.find { it.episode == episodeNumber }
                ?: return false


            // Ładuj linki dla odcinka
            episode.streams?.forEach { stream ->
                val host = hostMap[stream.i] ?: return@forEach
                val url = host.t.replace("{hashid}", stream.h)
                loadExtractor(url, subtitleCallback, callback)
            }
        } else {
            // Dla filmów - obecna implementacja
            val response = makeApiRequest("/data/$data.json")
            val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: return false

            // Ładuj linki dla filmu
            videoDetail.streams?.forEach { stream ->
                val host = hostMap[stream.i] ?: return@forEach
                val url = host.t.replace("{hashid}", stream.h)
                loadExtractor(url, subtitleCallback, callback)
            }
        }

        return true
    }
}