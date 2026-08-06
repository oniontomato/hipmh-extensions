package eu.kanade.tachiyomi.extension.zh.hipmh

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant

@Source
class Hipmh : KeiSource() {

    private val apiBaseUrl = "https://hipapi1.s3file.top"
    private val coverBaseUrl = "https://cover.s3imgs.top"
    private val readerBaseUrl = "https://reader.hipmh.top"

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaListPage("$baseUrl/popularity?page=$page")

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaListPage("$baseUrl/new-releases?page=$page")

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
        val items = data.data.map { it.toSManga() }
        return MangasPage(items, page < data.total_pages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "works") return null
        val manga = mangaFromDetailsPage(client.get(url.toString()).asJsoup())
        manga.url = "/${url.pathSegments.joinToString("/")}"
        return manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get("$baseUrl${manga.url}").asJsoup()
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
        val doc = client.get("$readerBaseUrl/chapter/$frontendHid").asJsoup()
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
            client.get(url).parseAs<ChapterImagesResponse>().data.images,
        )
        return images.mapIndexed { index, path -> Page(index, imageUrl = imgBase + path) }
    }

    private fun apiUrl(path: String) = apiBaseUrl.toHttpUrlOrNull()!!.newBuilder()
        .addPathSegment("v1")
        .addPathSegment(path)

    private suspend fun parseMangaListPage(url: String): MangasPage {
        val doc = client.get(url).asJsoup()
        val items = doc.select("a.manga-card-link").map { it.toSManga() }
        val next = doc.selectFirst("a.pagination-next")
        val hasNext = next != null && next.attr("aria-disabled") != "true"
        return MangasPage(items, hasNext)
    }

    private suspend fun fetchChapters(mid: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val url = apiBaseUrl.toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("v1")
                .addPathSegment("manga")
                .addPathSegment("chapters")
                .addQueryParameter("mid", mid)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", "100")
                .addQueryParameter("order", "desc")
                .build()
            val data = client.get(url).parseAs<ChaptersResponse>().data
            if (data.items.isEmpty()) break
            data.items.forEach { item ->
                chapters += SChapter.create().apply {
                    url = "/chapter/${item.hid}"
                    name = item.title.ifBlank { "第 ${item.chapter_number} 话" }
                    date_upload = Instant.tryParse(item.updated_at)
                }
            }
            if (page >= data.total_pages) break
            page++
        }
        return chapters.asReversed()
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
        url = "/works/$id"
        title = this@toSManga.title
        thumbnail_url = vertical_image_url.takeIf { it.isNotBlank() }
            ?.let { coverBaseUrl + it }
        description = this@toSManga.description
        author = authors.joinToString(", ") { it.name }
        genre = genres.joinToString(", ") { it.name }
        status = when (this@toSManga.status) {
            "completed" -> SManga.COMPLETED
            "ongoing" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }
}
