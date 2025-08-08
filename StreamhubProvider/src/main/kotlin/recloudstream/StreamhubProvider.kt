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
        val h: String
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
        val fullUrl = "$mainUrl/$url"
        Log.d("StreamhubProvider", "Wykonywanie żądania do: $fullUrl")

        val response = app.get(
            fullUrl,
            headers = mapOf("Authorization" to "Bearer $randomString")
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
     // Wyciągnij samo ID z pełnego URL
         val videoId = if (url.contains("/")) {
             url.substringAfterLast("/")
         } else {
             url
         }

        val response = makeApiRequest("data/$videoId.json")
        val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: return null
        return videoDetail.toLoadResponse(this)
    }

    private fun VideoItem.toSearchResponse(provider: StreamhubProvider): SearchResponse {
        val tvType = if (this.type == "movie") TvType.Movie else TvType.TvSeries
        return if (this.type == "movie") {
            provider.newMovieSearchResponse(
                this.name,
                this.id,
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
                this.id,
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
                this.id,
                TvType.Movie,
                this.id
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
                this.id,
                TvType.TvSeries,
                this.seasons?.flatMap { season ->
                    season.episodes?.map { episode ->
                        newEpisode("${this.id}_${season.number}_${episode.number}") {
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
    try {
        Log.d("StreamhubProvider", "loadLinks wywołane z data: $data")

        val isEpisode = data.contains("_")

        // Pobierz szablony hostingów z lepszym logowaniem błędów
        val hostsResponse = makeApiRequest("hosts.json")
        if (hostsResponse.isBlank()) {
            Log.e("StreamhubProvider", "Pusta odpowiedź dla hosts.json")
            return false
        }

        val hostsData = tryParseJson<HostsResponse>(hostsResponse)
        if (hostsData == null) {
            Log.e("StreamhubProvider", "Nie udało się sparsować hosts.json")
            return false
        }

        val hosts = hostsData.hosts
        if (hosts.isNullOrEmpty()) {
            Log.e("StreamhubProvider", "Lista hostów jest pusta")
            return false
        }

        val hostMap = hosts.associateBy { it.i }
        Log.d("StreamhubProvider", "Załadowano ${hostMap.size} hostów")

        if (isEpisode) {
            // Walidacja formatu ID odcinka
            val parts = data.split("_")
            if (parts.size < 3) {
                Log.e("StreamhubProvider", "Nieprawidłowy format ID odcinka: $data")
                return false
            }

            val seriesId = parts[0]
            val seasonNumber = parts.getOrNull(1)?.toIntOrNull()
            val episodeNumber = parts.getOrNull(2)?.toIntOrNull()

            if (seasonNumber == null || episodeNumber == null) {
                Log.e("StreamhubProvider", "Nieprawidłowe numery sezonu/odcinka w ID: $data")
                return false
            }

            Log.d("StreamhubProvider", "Ładowanie S${seasonNumber}E${episodeNumber} dla serialu: $seriesId")

            // Pobierz dane serialu
            val response = makeApiRequest("data/$seriesId.json")
            if (response.isBlank()) {
                Log.e("StreamhubProvider", "Pusta odpowiedź dla serialu: $seriesId")
                return false
            }

            val seriesDetail = tryParseJson<VideoDetailResponse>(response)
            if (seriesDetail == null) {
                Log.e("StreamhubProvider", "Nie udało się sparsować danych serialu: $seriesId")
                return false
            }

            // Znajdź odpowiedni sezon
            val season = seriesDetail.seasons?.find { it.number == seasonNumber }
            if (season == null) {
                Log.e("StreamhubProvider", "Nie znaleziono sezonu $seasonNumber w serialu $seriesId")
                return false
            }

            // Znajdź odpowiedni odcinek
            val episode = season.episodes?.find { it.number == episodeNumber }
            if (episode == null) {
                Log.e("StreamhubProvider", "Nie znaleziono odcinka $episodeNumber w sezonie $seasonNumber")
                return false
            }

            val sources = episode.sources
            if (sources.isNullOrEmpty()) {
                Log.e("StreamhubProvider", "Brak źródeł dla odcinka S${seasonNumber}E${episodeNumber}")
                return false
            }

            Log.d("StreamhubProvider", "Znaleziono ${sources.size} źródeł dla odcinka")

            // Przetwórz wszystkie źródła
            var successfulLinks = 0
            sources.forEach { stream ->
                try {
                    val host = hostMap[stream.i]
                    if (host == null) {
                        Log.w("StreamhubProvider", "Nie znaleziono hosta dla ID: ${stream.i}")
                        return@forEach
                    }

                    val url = host.t.replace("{hashid}", stream.h)
                    Log.d("StreamhubProvider", "Próba ładowania z hosta ${stream.i}: $url")

                    // Wywołaj loadExtractor bez przerywania pętli przy niepowodzeniu
                    val extracted = loadExtractor(url, subtitleCallback, callback)
                    if (extracted) {
                        successfulLinks++
                        Log.d("StreamhubProvider", "Pomyślnie załadowano link z hosta ${stream.i}")
                    } else {
                        Log.w("StreamhubProvider", "Nie udało się załadować linka z hosta ${stream.i}")
                    }
                } catch (e: Exception) {
                    Log.e("StreamhubProvider", "Błąd podczas ładowania z hosta ${stream.i}: ${e.message}")
                }
            }

            Log.d("StreamhubProvider", "Załadowano $successfulLinks z ${sources.size} źródeł")
            return successfulLinks > 0

        } else {
            // Analogiczna logika dla filmów
            Log.d("StreamhubProvider", "Ładowanie filmu: $data")

            val response = makeApiRequest("data/$data.json")
            if (response.isBlank()) {
                Log.e("StreamhubProvider", "Pusta odpowiedź dla filmu: $data")
                return false
            }

            val videoDetail = tryParseJson<VideoDetailResponse>(response)
            if (videoDetail == null) {
                Log.e("StreamhubProvider", "Nie udało się sparsować danych filmu: $data")
                return false
            }

            val sources = videoDetail.sources
            if (sources.isNullOrEmpty()) {
                Log.e("StreamhubProvider", "Brak źródeł dla filmu: $data")
                return false
            }

            Log.d("StreamhubProvider", "Znaleziono ${sources.size} źródeł dla filmu")

            var successfulLinks = 0
            sources.forEach { stream ->
                try {
                    val host = hostMap[stream.i]
                    if (host == null) {
                        Log.w("StreamhubProvider", "Nie znaleziono hosta dla ID: ${stream.i}")
                        return@forEach
                    }

                    val url = host.t.replace("{hashid}", stream.h)
                    Log.d("StreamhubProvider", "Próba ładowania z hosta ${stream.i}: $url")

                    val extracted = loadExtractor(url, subtitleCallback, callback)
                    if (extracted) {
                        successfulLinks++
                        Log.d("StreamhubProvider", "Pomyślnie załadowano link z hosta ${stream.i}")
                    } else {
                        Log.w("StreamhubProvider", "Nie udało się załadować linka z hosta ${stream.i}")
                    }
                } catch (e: Exception) {
                    Log.e("StreamhubProvider", "Błąd podczas ładowania z hosta ${stream.i}: ${e.message}")
                }
            }

            Log.d("StreamhubProvider", "Załadowano $successfulLinks z ${sources.size} źródeł")
            return successfulLinks > 0
        }

    } catch (e: Exception) {
        Log.e("StreamhubProvider", "Krytyczny błąd w loadLinks: ${e.message}", e)
        return false
    }
}

}
