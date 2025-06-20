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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.Episode as CSEpisode
import kotlin.math.absoluteValue

class StreamhubProvider : MainAPI() {

data class IPTVChannel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val language: String? = null,
    val tvgId: String? = null
)

data class ChannelMetadata(
    val id: String,
    val name: String,
    val country: String,
    val languages: List<String>,
    val categories: List<String>,
    val logo: String?,
    val website: String?
)

    data class IPTVResponse(
        val channels: List<IPTVChannel>
    )

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

    override var mainUrl = "https://raw.githubusercontent.com/ggcenter/streamhub/refs/heads/main/public/github"
    override var name = "Streamhub"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val imageBaseUrl = "https://image.tmdb.org/t/p/original"
    private val imageDefaultUrl = "https://motivatevalmorgan.com/wp-content/uploads/2016/06/default-movie-1-3-476x700.jpg"
    private val randomString = this.getRandomString()

    override var lang = "pl"

    override val hasMainPage = true

    private var searchCache: List<VideoItem>? = null


    private suspend fun makeApiRequest(url: String): String {
        try {
            val fullUrl = "$mainUrl/$url"
            println("StreamhubProvider: Wykonywanie żądania do: $fullUrl")

            val response = app.get(
                fullUrl,
                headers = mapOf("Authorization" to "Bearer $randomString")
            )

            // Sprawdź status HTTP
            if (!response.isSuccessful) {
                println("StreamhubProvider: Błąd HTTP: ${response.code}")
                return ""
            }

            return response.text
        } catch (e: Exception) {
            println("StreamhubProvider: Błąd podczas wykonywania żądania API: ${e.message}")
            return ""
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

    // Dodaj kanały IPTV jako osobną sekcję
    val iptvChannels = parseM3UPlaylist()
    val homePageLists = mutableListOf<HomePageList>()

    // Dodaj istniejące listy
    homePageLists.addAll(
        toplists.map {
            HomePageList(it.name, it.titles?.map { it.toSearchResponse(this) } ?: emptyList(), false)
        }
    )

    // Dodaj wszystkie kanały IPTV w jednej grupie
    if (iptvChannels.isNotEmpty()) {
        homePageLists.add(
            HomePageList(
                "IPTV Polska",
                iptvChannels.map { it.toSearchResponse(this) },
                false
            )
        )
    }

    return newHomePageResponse(homePageLists, false)
}




    override suspend fun load(url: String): LoadResponse? {

        if (url.startsWith("http") && (url.contains(".m3u8") || url.contains("stream") || url.contains("live"))) {
            // To jest kanał IPTV
            val channels = parseM3UPlaylist()
            val channel = channels.find { it.url == url }

            return channel?.let {
                newMovieLoadResponse(
                    it.name,
                    it.url,
                    TvType.Live,
                    it.url
                ) {
                    this.posterUrl = it.logo ?: imageDefaultUrl
                    this.plot = "Kanał telewizyjny na żywo${it.group?.let { group -> " z kategorii: $group" } ?: ""}"
                }
            }
        }

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

private fun IPTVChannel.toSearchResponse(provider: StreamhubProvider): SearchResponse {
    return provider.newMovieSearchResponse(
        this.name,
        this.url,
        TvType.Live
    ) {
        this.posterUrl = this@toSearchResponse.logo ?: provider.imageDefaultUrl
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
                        CSEpisode(
                            "${this.id}_${season.number}_${episode.number}",
                            episode.name,
                            season.number,
                            episode.number
                        )
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
    if (data.startsWith("http") && (data.contains(".m3u8") || data.contains("stream") || data.contains("live"))) {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "IPTV Stream",
                url = data
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    val isEpisode = data.contains("_")
    val hostsResponse = makeApiRequest("hosts.json")
    val hosts = tryParseJson<HostsResponse>(hostsResponse)?.hosts ?: return false
    val hostMap = hosts.associateBy { it.i }

    if (isEpisode) {
        val parts = data.split("_")
        val seriesId = parts[0]
        val seasonNumber = parts[1].toIntOrNull() ?: 1
        val episodeNumber = parts[2].toIntOrNull() ?: 1

        val response = makeApiRequest("data/$seriesId.json")
        val seriesDetail = tryParseJson<VideoDetailResponse>(response) ?: return false

        val episode = seriesDetail.seasons
            ?.find { it.number == seasonNumber }
            ?.episodes
            ?.find { it.number == episodeNumber }
            ?: return false

        episode.sources?.forEach { stream ->
            val host = hostMap[stream.i] ?: return@forEach
            val streamUrl = host.t.replace("{hashid}", stream.h) // Zmieniona nazwa zmiennej
            loadExtractor(streamUrl, subtitleCallback, callback)
        }
    } else {
        val response = makeApiRequest("data/$data.json")
        val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: return false

        videoDetail.sources?.forEach { stream ->
            val host = hostMap[stream.i] ?: return@forEach
            val streamUrl = host.t.replace("{hashid}", stream.h) // Zmieniona nazwa zmiennej
            loadExtractor(streamUrl, subtitleCallback, callback)
        }
    }

    return true
}



private suspend fun parseM3UPlaylist(): List<IPTVChannel> {
    try {
        val response = app.get("https://raw.githubusercontent.com/iptv-org/iptv/refs/heads/master/streams/pl.m3u")
        if (!response.isSuccessful) {
            println("StreamhubProvider: Błąd pobierania playlisty M3U: ${response.code}")
            return emptyList()
        }

        val content = response.text
        val channels = mutableListOf<IPTVChannel>()
        val lines = content.lines()

        // Pobierz metadane kanałów z API
        val channelsMetadata = fetchChannelsMetadata()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val metadata = line.substringAfter("#EXTINF:")
                val name = metadata.substringAfterLast(",").trim()

                // Wyciągnij tvg-id i usuń suffix po @
                val tvgId = if (metadata.contains("tvg-id=")) {
                    val fullTvgId = metadata.substringAfter("tvg-id=\"").substringBefore("\"")
                    // Usuń suffix po @ (np. "4FunTV.pl@SD" -> "4FunTV.pl")
                    if (fullTvgId.contains("@")) {
                        fullTvgId.substringBefore("@")
                    } else {
                        fullTvgId
                    }
                } else null

                // Znajdź metadane na podstawie oczyszczonego tvg-id
                val channelMeta = channelsMetadata.find { it.id == tvgId }

                // Generuj placeholder logo z randomowym kolorem i nazwą kanału
                val placeholderLogo = generatePlaceholderLogo(tvgId.substringBefore("."))

                if (i + 1 < lines.size) {
                    val url = lines[i + 1].trim()
                    if (url.isNotEmpty() && !url.startsWith("#")) {
                        channels.add(
                            IPTVChannel(
                                name = channelMeta?.name ?: name,
                                url = url,
                                logo = channelMeta?.logo ?: placeholderLogo,
                                group = "IPTV", // Wszystkie kanały w jednej grupie
                                language = "pl",
                                tvgId = tvgId
                            )
                        )
                    }
                }
                i += 2
            } else {
                i++
            }
        }

        println("StreamhubProvider: Załadowano ${channels.size} kanałów IPTV")
        return channels
    } catch (e: Exception) {
        println("StreamhubProvider: Błąd parsowania M3U: ${e.message}")
        return emptyList()
    }
}


private suspend fun fetchChannelsMetadata(): List<ChannelMetadata> {
    try {
        println("StreamhubProvider: Pobieranie metadanych kanałów...")
        val channelsResponse = app.get("https://iptv-org.github.io/api/channels.json")
        if (!channelsResponse.isSuccessful) {
            println("StreamhubProvider: Błąd HTTP przy pobieraniu channels.json: ${channelsResponse.code}")
            return emptyList()
        }

        val channelsData = tryParseJson<List<Map<String, Any>>>(channelsResponse.text)
        if (channelsData == null) {
            println("StreamhubProvider: Nie udało się sparsować channels.json")
            return emptyList()
        }

        println("StreamhubProvider: Znaleziono ${channelsData.size} kanałów w API")

        val metadata = channelsData.mapNotNull { channel ->
            try {
                val id = channel["id"] as? String
                val name = channel["name"] as? String
                val country = channel["country"] as? String
                val languages = (channel["languages"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                val categories = (channel["categories"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                val logo = channel["logo"] as? String
                val website = channel["website"] as? String

                if (id != null && name != null) {
                    println("StreamhubProvider: Dodano metadane dla kanału: $id -> $name (logo: ${logo != null})")
                    ChannelMetadata(id, name, country ?: "", languages, categories, logo, website)
                } else {
                    println("StreamhubProvider: Pominięto kanał bez ID lub nazwy: $channel")
                    null
                }
            } catch (e: Exception) {
                println("StreamhubProvider: Błąd parsowania kanału: ${e.message}")
                null
            }
        }

        println("StreamhubProvider: Pomyślnie załadowano ${metadata.size} metadanych kanałów")
        return metadata

    } catch (e: Exception) {
        println("StreamhubProvider: Błąd pobierania metadanych kanałów: ${e.message}")
        e.printStackTrace()
        return emptyList()
    }
}

private fun generatePlaceholderLogo(channelName: String): String {
    // Lista kolorów do losowego wyboru
    val colors = listOf(
        "ff5722", "e91e63", "9c27b0", "673ab7", "3f51b5",
        "2196f3", "03a9f4", "00bcd4", "009688", "4caf50",
        "8bc34a", "cddc39", "ffc107", "ff9800", "795548"
    )

    // Wybierz losowy kolor na podstawie nazwy kanału (żeby był konsystentny)
    val colorIndex = channelName.hashCode().absoluteValue % colors.size
    val backgroundColor = colors[colorIndex]

    // Oczyść nazwę kanału z niepotrzebnych znaków i skróć
    val cleanName = channelName
        .replace(Regex("[^a-zA-Z0-9\\s]"), "") // Usuń znaki specjalne
        .replace("\\s+".toRegex(), "+") // Zamień spacje na +
        .take(8) // Maksymalnie 8 znaków
        .uppercase()

    // Jeśli nazwa jest pusta po czyszczeniu, użyj "TV"
    val finalName = if (cleanName.isBlank()) "TV" else cleanName

    // Użyj placehold.co zamiast via.placeholder.com
    return "https://placehold.co/200x200/$backgroundColor/ffffff?text=$finalName"
}
}