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
import com.lagradost.cloudstream3.newEpisode
import android.util.Log

class StreamhubProvider : MainAPI() {

    data class Host(
        val i: Int,
        val t: String
    )

    data class Toplist(
        val name: String,
        val titles: List<VideoItem>? = null,
    )
    data class ToplistsResponse(
        val toplists: List<Toplist>
    )

    data class HostsResponse(
        val hosts: List<Host>
    )

    data class Season(
        val number: Int,
        val episodes: List<Episode>
    )

    data class Episode(
        val number: Int,
        val name: String? = null,
        val sources: List<Stream>? = null
    )

data class Stream(
    val i: Int,
    val h: String,
    val l: String? = null
)

    data class VideoItem(
        val id: String,
        val name: String,
        val type: String,
        val year: Int? = null,
        @Suppress("PropertyName")
        val poster_path: String?
    )

    data class VideoSearchResponse(
        val list: List<VideoItem>
    )

    data class VideoDetailResponse(
        val id: String,
        val name: String,
        val type: String,
        val year: Int? = null,
        val overview: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val sources: List<Stream>? = null,
        val seasons: List<Season>? = null
    )

    override var mainUrl = "https://streamhub.m-dev.pl/storage/github"
    override var name = "Streamhub"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val imageBaseUrl = "https://image.tmdb.org/t/p/original"
    private val imageDefaultUrl = "https://motivatevalmorgan.com/wp-content/uploads/2016/06/default-movie-1-3-476x700.jpg"
    private val randomString = this.getRandomString()

    override var lang = "pl"

    override val hasMainPage = true

    private var searchCache: List<VideoItem>? = null


private suspend fun makeApiRequest(url: String): String {
    return try {


        val fullUrl = if (url.startsWith("http")) url else "$mainUrl/${url.trimStart('/')}"

        Log.d("StreamhubProvider", "Wykonywanie żądania do: $fullUrl")

        val response = app.get(
            fullUrl
        )

        if (!response.isSuccessful) {
            Log.e("StreamhubProvider", "Błąd HTTP ${response.code} dla URL: $fullUrl")
            return ""
        }

        val responseText = response.text
        Log.d("StreamhubProvider", "Odpowiedź API (długość: ${responseText.length})")
        responseText
    } catch (e: Exception) {
        Log.e("StreamhubProvider", "Błąd żądania API dla $url: ${e.message}", e)
        ""
    }
}

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            // Ładowanie danych do pamięci podręcznej, jeśli jeszcze nie załadowane
            if (searchCache == null) {
                val response = makeApiRequest("search_catalog.json")

                // Sprawdź czy odpowiedź nie jest pusta
                if (response.isBlank()) {
                    println("StreamhubProvider: Pusta odpowiedź z API")
                    return emptyList()
                }

                // Logowanie długości odpowiedzi
                println("StreamhubProvider: Długość odpowiedzi API: ${response.length}")

                // Sprawdź czy odpowiedź jest prawidłowym JSON
                val parsedResponse = tryParseJson<VideoSearchResponse>(response)
                if (parsedResponse == null) {
                    println("StreamhubProvider: Nie udało się sparsować JSON")
                    return emptyList()
                }

                // Sprawdź, czy lista nie jest pusta
                if (parsedResponse.list.isNullOrEmpty()) {
                    println("StreamhubProvider: Lista elementów jest pusta")
                    return emptyList()
                }

                searchCache = parsedResponse.list
                println("StreamhubProvider: Pomyślnie załadowano ${searchCache!!.size} elementów")
            }

            // Upewnij się, że searchCache nie jest null przed filtrowaniem
            if (searchCache == null) {
                return emptyList()
            }

            // Filtrowanie tytułów zawierających zapytanie
            val filteredList = searchCache!!.filter { it.name.contains(query, ignoreCase = true) }
            println("StreamhubProvider: Znaleziono ${filteredList.size} pasujących elementów")

            return filteredList.map { it.toSearchResponse(this) }
        } catch (e: Exception) {
            println("StreamhubProvider: Błąd w funkcji search: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }


    private fun getRandomString(): String {
        val parts = arrayOf("github", "pat", "11AB5WZ2Q0UffVewF5EqS8", "Qh3Kx5zLYlH7umIUqunXc9io9vhAYAqKVvpVCKkujwHAWIMJQFZ8J0DObwo")
        return parts.joinToString("_")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = makeApiRequest("main_page.json")
        val toplists = tryParseJson<ToplistsResponse>(response)?.toplists ?: emptyList()

        return newHomePageResponse(
            toplists.map{HomePageList(it.name, it.titles?.map { it.toSearchResponse(this) } ?: emptyList(), false)},
            false
        )
    }


    override suspend fun load(url: String): LoadResponse? {

        val response = makeApiRequest(url)
        val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: return null

        return videoDetail.toLoadResponse(this)
    }

    private fun VideoItem.toSearchResponse(provider: StreamhubProvider): SearchResponse {
        val tvType = if (this.type == "movie") TvType.Movie else TvType.TvSeries
        return if (this.type == "movie") {
            provider.newMovieSearchResponse(
                this.name,
                "/data/${this.id}.json",
                tvType
            ) {
                this.posterUrl = this@toSearchResponse.poster_path?.let {
                    provider.imageBaseUrl + it
                } ?: provider.imageDefaultUrl
                this.year = this@toSearchResponse.year?.let {
                    it
                }
            }
        } else {
            provider.newTvSeriesSearchResponse(
                this.name,
                "/data/${this.id}.json",
                tvType
            ) {
                 this.posterUrl = this@toSearchResponse.poster_path?.let {
                     provider.imageBaseUrl + it
                 } ?: provider.imageDefaultUrl
                 this.year = this@toSearchResponse.year?.let {
                     it
                 }
            }
        }
    }


    private suspend fun VideoDetailResponse.toLoadResponse(provider: StreamhubProvider): LoadResponse {
        return if (this.type == "movie") {
            // Dla filmów
            provider.newMovieLoadResponse(
                this.name,
                "/data/${this.id}.json",
                TvType.Movie,
                "/data/${this.id}.json"
            ) {
                this.posterUrl = this@toLoadResponse.poster_path?.let {
                    provider.imageBaseUrl + it
                } ?: provider.imageDefaultUrl
                this.backgroundPosterUrl = this@toLoadResponse.backdrop_path?.let {
                    provider.imageBaseUrl + it
                } ?: provider.imageDefaultUrl
                 this.year = this@toLoadResponse.year?.let {
                     it
                 }
                this.plot = this@toLoadResponse.overview?.let {
                     it
                }
            }
        } else {
            // Dla seriali
            provider.newTvSeriesLoadResponse(
                this.name,
                "/data/${this.id}.json",
                TvType.TvSeries,
                this.seasons?.flatMap { season ->
                    season.episodes?.map { episode ->
                        newEpisode("/data/${this.id}.json?season=${season.number}&episode=${episode.number}") {
                            this.name = episode.name
                            this.season = season.number
                            this.episode = episode.number
                        }
                    } ?: emptyList()
                } ?: emptyList()
            ) {
                this.posterUrl = this@toLoadResponse.poster_path?.let {
                    provider.imageBaseUrl + it
                } ?: provider.imageDefaultUrl
                this.backgroundPosterUrl = this@toLoadResponse.backdrop_path?.let {
                    provider.imageBaseUrl + it
                } ?: provider.imageDefaultUrl
                 this.year = this@toLoadResponse.year?.let {
                     it
                 }
                this.plot = this@toLoadResponse.overview?.let {
                     it
                }
            }
        }
    }


override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // 1) Pobierz i zmapuj hosty
    val hostsResponse = makeApiRequest("hosts.json")
    val hosts = tryParseJson<HostsResponse>(hostsResponse)?.hosts ?: run {
        Log.e("StreamhubProvider", "Brak/niepoprawne hosts.json")
        return false
    }
    val hostMap = hosts.associateBy { it.i }

    var emitted = 0
    val isEpisode = data.contains("season=") && data.contains("episode=")

    if (isEpisode) {
        // data: "/data/SERIES_ID.json?season=X&episode=Y"
        val pathPart = data.substringBefore("?").ifBlank { data }
        val queryPart = data.substringAfter("?", "")

        val params = queryPart.split("&")
            .mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) kv[0] to kv[1] else null
            }.toMap()

        val seasonNumber = params["season"]?.toIntOrNull()
        val episodeNumber = params["episode"]?.toIntOrNull()
        if (seasonNumber == null || episodeNumber == null) {
            Log.e("StreamhubProvider", "Brak/niepoprawne parametry season/episode")
            return false
        }

        // 2) Pobieramy czysty JSON bez query
        val response = makeApiRequest(pathPart)
        val seriesDetail = tryParseJson<VideoDetailResponse>(response) ?: run {
            Log.e("StreamhubProvider", "Nie udało się sparsować JSON dla $pathPart")
            return false
        }

        val episode = seriesDetail.seasons
            ?.find { it.number == seasonNumber }
            ?.episodes
            ?.find { it.number == episodeNumber }

        if (episode?.sources.isNullOrEmpty()) {
            Log.w("StreamhubProvider", "Brak źródeł dla S$seasonNumber E$episodeNumber")
            return false
        }

        episode!!.sources!!.forEach { stream ->
            val host = hostMap[stream.i] ?: run {
                Log.w("StreamhubProvider", "Nieznany host index=${stream.i}")
                return@forEach
            }
            // 3) WAŻNE: kodujemy hashid
            val url = host.t.replace("{hashid}", stream.h)
            if (loadExtractor(url, subtitleCallback, callback)) {
                emitted++
            } else {
                Log.w("StreamhubProvider", "Extractor nie zwrócił linków dla $url")
            }
        }
    } else {
        // Film
        val response = makeApiRequest(data.substringBefore("?"))
        val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: run {
            Log.e("StreamhubProvider", "Nie udało się sparsować JSON dla ${data.substringBefore("?")}")
            return false
        }

        if (videoDetail.sources.isNullOrEmpty()) {
            Log.w("StreamhubProvider", "Brak źródeł w JSON (movie)")
            return false
        }

        videoDetail.sources!!.forEach { stream ->
            val host = hostMap[stream.i] ?: run {
                Log.w("StreamhubProvider", "Nieznany host index=${stream.i}")
                return@forEach
            }
            val url = host.t.replace("{hashid}", stream.h)
            if (loadExtractor(url, subtitleCallback, callback)) {
                emitted++
            } else {
                Log.w("StreamhubProvider", "Extractor nie zwrócił linków dla $url")
            }
        }
    }

    // Zwracamy prawdę tylko jeśli faktycznie coś wyemitowaliśmy
    return emitted > 0
}


}
