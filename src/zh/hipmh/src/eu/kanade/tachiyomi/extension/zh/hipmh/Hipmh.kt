package eu.kanade.tachiyomi.extension.zh.hipmh

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Hipmh : KeiSource() {

    companion object {
        private val browserUserAgents = listOf(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )
    }

    private val browserUserAgent = browserUserAgents.random()

    private val apiBaseUrl = "https://hipapi1.s3file.top"
    private val coverBaseUrl = "https://cover.s3imgs.top"
    private val readerBaseUrl = "https://reader.hipmh.top"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        connectTimeout(20, TimeUnit.SECONDS)
        readTimeout(25, TimeUnit.SECONDS)
        writeTimeout(20, TimeUnit.SECONDS)
        callTimeout(40, TimeUnit.SECONDS)
        rateLimit(3, 1.seconds, 600.milliseconds) { url ->
            url.host in setOf("m.hipmh.com", "hipapi1.s3file.top", "reader.hipmh.top", "cover.s3imgs.top")
        }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        // Keep one stable browser identity across the whole session. Randomizing the User-Agent per
        // request makes the same source look like a different client every time and can trigger bot
        // detection on sites that check request consistency.
        set("User-Agent", browserUserAgent)
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        set("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Accept-Encoding", "gzip, deflate, br, zstd")
        set("Cache-Control", "no-cache")
        set("Pragma", "no-cache")
        set("DNT", "1")
        set("Upgrade-Insecure-Requests", "1")
    }

    private suspend fun fetchHtml(url: String): Document {
        delay(Random.nextLong(350, 1200))
        return client.get(url).asJsoup()
    }

    private suspend inline fun <reified T> fetchJson(url: HttpUrl): T {
        delay(Random.nextLong(350, 1200))
        return client.get(url).parseAs<T>()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaListPage("$baseUrl/popularity?page=$page")

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseApiMangaListPage(page)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isBlank()) {
            return parseMangaListPage("$baseUrl/popularity?page=$page")
        }
        val url = apiUrl("search")
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "24")
            .build()
        val data = client.get(url).parseAs<SearchResponse>().data
        val items = data.mangaItems.map { it.toSManga() }
        return MangasPage(items, page < data.total_pages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "works") return null
        val manga = mangaFromDetailsPage(fetchHtml(url.toString()))
        manga.url = "/${url.pathSegments.joinToString("/")}"
        return manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = fetchHtml("$baseUrl${manga.url}")
        val updatedManga = if (fetchDetails) mangaFromDetailsPage(doc, manga) else manga
        val updatedChapters = if (fetchChapters) {
            val mid = doc.selectFirst("#chapters-config")?.attr("data-mid")
                ?: throw Exception("mid not found")
            fetchChapters(mid)
        } else {
            chapters
        }
        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val frontendHid = chapter.url.substringAfterLast("/")
        val doc = fetchHtml("$readerBaseUrl/chapter/$frontendHid")
        val content = doc.selectFirst("#chapcontent")
            ?: throw Exception("reader content not found")
        val apiHid = content.attr("data-api-hid")
        require(apiHid.isNotBlank()) { "api hid not found" }
        val imgBase = content.attr("data-chapter-img-base-line1")
            .ifBlank { content.attr("data-chapter-img-base") }
        val url = apiBaseUrl.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("v2")
            .addPathSegment("chapter")
            .addQueryParameter("hid", apiHid)
            .build()
        val images = HipmhImagesDecoder.decode(
            fetchJson<ChapterImagesResponse>(url).data.images,
        )
        return images.mapIndexed { index, path -> Page(index, imageUrl = imgBase + path) }
    }

    private fun apiUrl(path: String) = apiBaseUrl.toHttpUrlOrNull()!!.newBuilder()
        .addPathSegment("v1")
        .addPathSegment(path)

    private suspend fun parseMangaListPage(url: String): MangasPage {
        val doc = fetchHtml(url)
        val items = doc.select("a.manga-card-link").map { it.toSManga() }
        val next = doc.selectFirst("a.pagination-next")
        val hasNext = next != null && next.attr("aria-disabled") != "true"
        return MangasPage(items, hasNext)
    }

    private suspend fun parseApiMangaListPage(page: Int): MangasPage {
        val url = apiUrl("mangas")
            .addQueryParameter("category", "1")
            .addQueryParameter("sort", "updated")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "18")
            .build()
        val data = client.get(url).parseAs<SearchResponse>().data
        val items = data.mangaItems.map { it.toSManga() }
        return MangasPage(items, page < data.total_pages)
    }

    private suspend fun fetchChapters(mid: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val chapterUrl = apiBaseUrl.toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("v1")
                .addPathSegment("manga")
                .addPathSegment("chapters")
                .addQueryParameter("mid", mid)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", "100")
                .addQueryParameter("order", "desc")
                .build()
            val data = fetchJson<ChaptersResponse>(chapterUrl).data
            if (data.items.isEmpty()) break
            data.items.forEach { item ->
                chapters += SChapter.create().apply {
                    this.url = "/chapter/${item.hid}"
                    name = item.title.ifBlank { "第 ${item.chapter_number} 话" }
                    date_upload = runCatching {
                        DateTimeFormatter.ISO_INSTANT.parse(item.updated_at, Instant::from).toEpochMilli()
                    }.getOrDefault(0L)
                }
            }
            if (page >= data.total_pages) break
            page++
        }
        return chapters
    }

    private fun mangaFromDetailsPage(doc: Document, manga: SManga = SManga.create()): SManga {
        val ld = doc.selectFirst("script[type='application/ld+json']")?.data()
            ?.let { runCatching { it.parseAs<LdJsonRoot>() }.getOrNull() }
        val series = ld?.graph?.firstOrNull { it.name.isNotBlank() }
        return manga.apply {
            title = series?.name ?: doc.selectFirst("h1")?.text().orEmpty()
            author = series?.author?.name.orEmpty()
            description = series?.description.orEmpty()
            thumbnail_url = series?.image ?: doc.selectFirst("img[class*='cover']")?.attr("src")
            genre = doc.select("a[href^='/genre/']").joinToString(", ") { it.text() }
            status = when {
                doc.selectFirst("a[href='/completed']") != null -> SManga.COMPLETED
                doc.selectFirst("a[href='/ongoing']") != null -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun Element.toSManga(): SManga = SManga.create().apply {
        url = attr("href")
        title = selectFirst("h3.manga-card-title")?.text().orEmpty()
            .ifBlank { attr("aria-label") }
        thumbnail_url = selectFirst("img.manga-card-image")?.attr("src")
    }

    private fun MangaItem.toSManga(): SManga = SManga.create().apply {
        val actualId = mid.ifBlank { id.orEmpty() }
        url = "/works/$actualId"
        title = this@toSManga.title
        thumbnail_url = vertical_image_url.takeIf { it.isNotBlank() }
            ?.let { coverBaseUrl + it }
            ?: cover_image_url.takeIf { it.isNotBlank() }
                ?.let { coverBaseUrl + it }
        description = this@toSManga.description
        author = author_names.ifEmpty { authors.map { it.name } }.joinToString(", ")
        genre = genres.ifEmpty { genre_names }.joinToString(", ")
        status = when (this@toSManga.status.lowercase()) {
            "completed" -> SManga.COMPLETED
            "ongoing" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }
}
