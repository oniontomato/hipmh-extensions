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
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.format.DateTimeFormatter

@Source
abstract class Hipmh : KeiSource() {

    private val apiBaseUrl = "https://hipapi1.s3file.top"
    private val coverBaseUrl = "https://cover.s3imgs.top"
    private val readerBaseUrl = "https://reader.hipmh.top"

    // 用 API 取熱門（前端 /popularity 是 client-side route，SSR 不到卡片；API sort=popular 更準）
    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaListApiPage(
        apiUrl("mangas")
            .addQueryParameter("category", "1") // 1 = 韓漫
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "18")
            .build(),
    )

    // 用 API 取最新（server-side sort=updated，比前端 new-releases 頁面更準、不會不同步）
    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaListApiPage(
        apiUrl("mangas")
            .addQueryParameter("category", "1") // 1 = 韓漫
            .addQueryParameter("sort", "updated")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "18")
            .build(),
    )

    // 宣報搜尋 UI filter：分類（國漫/韓漫）+ 狀態 dropdown，選中後注入 getSearchManga
    override fun getFilterList(): FilterList = FilterList(
        CategoryFilter("分類", arrayOf("國漫" to "2", "韓漫" to "1")),
        StatusFilter("狀態", arrayOf("連載中" to "ongoing", "完結" to "completed", "未更新" to "unknown")),
        SortFilter("排序", arrayOf("updated", "popular", "latest")),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val category = when (filters.firstOrNull { it is CategoryFilter }?.toUriPart()) {
            "2" -> 2 // 國漫
            "1" -> 1 // 韓漫
            else -> null
        }
        val status = when (filters.firstOrNull { it is StatusFilter }?.toUriPart()) {
            "ongoing" -> "ongoing"
            "completed" -> "completed"
            "unknown" -> "unknown"
            else -> null
        }
        val sort = when (filters.firstOrNull { it is SortFilter }?.toUriPart()) {
            "updated" -> "updated"
            "popular" -> "popular"
            "latest" -> "latest"
            else -> null
        }
        // 有選分類/狀態/排序 → API browse（server-side filter + sort，可同時）
        if (category != null || status != null || sort != null) {
            val items = searchCategoryStatus(query, category, status, sort)
            return MangasPage(items, hasNext = false)
        }
        // 冇選任何 filter → 維持全域搜尋
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

    // API JSON 列表解析（/v1/mangas 返回 shape，與前端 HTML 的 parseMangaListPage 不同）
    private suspend fun parseMangaListApiPage(url: String): MangasPage {
        val response = client.get(url).parseAs<MangaListResponse>()
        val items = response.data.items.map { it.toSManga() }
        return MangasPage(items, hasNext = response.data.page < response.data.total_pages)
    }

    // 分類+狀態搜尋：API 忽略 query，故抓多頁去重後 client-side 按 query 篩 title
    private suspend fun searchCategoryStatus(query: String, category: Int?, status: String?, sort: String?): List<SManga> {
        val seen = HashSet<String>()
        val items = mutableListOf<SManga>()
        var page = 1
        // 空白 query（browse）→ 只抓 page 1 即時；有 query → 多頁去重後 client-side 篩
        val maxPages = query.isBlank() ? 1 : 20
        while (page <= maxPages && items.size < 400) {
            val url = apiUrl("mangas")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", "18")
            if (category != null) url.addQueryParameter("category", category.toString())
            if (status != null) url.addQueryParameter("status", status)
            if (sort != null) url.addQueryParameter("sort", sort)

            val pageItems = parseMangaListApiPage(url).items
            for (item in pageItems) {
                if (item.url.isNotBlank() && seen.add(item.url)) items += item
            }
            if (pageItems.isEmpty()) break
            page++
        }
        return items.filter { it.title.contains(query, ignoreCase = true) }
    }

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
            val chapterUrl = apiBaseUrl.toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("v1")
                .addPathSegment("manga")
                .addPathSegment("chapters")
                .addQueryParameter("mid", mid)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", "100")
                .addQueryParameter("order", "desc")
                .build()
            val data = client.get(chapterUrl).parseAs<ChaptersResponse>().data
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

    private fun ApiMangaItem.toSManga(): SManga = SManga.create().apply {
        url = "/works/$mid"
        title = this@toSManga.title
        thumbnail_url = vertical_image_url.takeIf { it.isNotBlank() }
            ?.let { coverBaseUrl + it }
        author = author_names.joinToString(", ")
        genre = genres.joinToString(", ")
        status = SManga.UNKNOWN
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
