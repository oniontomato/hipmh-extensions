package eu.kanade.tachiyomi.extension.zh.hipmh

import eu.kanade.tachiyomi.source.model.Filter
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

    override suspend fun getPopularManga(page: Int): MangasPage = parseApiMangaListPage(
        page = page,
        category = 1,
        sort = "popular",
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseApiMangaListPage(page)

    override fun getFilterList(): FilterList = FilterList(
        CategoryFilter("分類", arrayOf("國漫" to "2", "韓漫" to "1")),
        StatusFilter("狀態", arrayOf("連載中" to "ongoing", "完結" to "completed", "全部" to "all")),
        SortFilter("排序", arrayOf("updated", "popular", "latest")),
    )

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val category = filters.firstOrNull { it is CategoryFilter }?.let { (it as CategoryFilter).toUriPart()?.toIntOrNull() }
        val status = filters.firstOrNull { it is StatusFilter }?.let { (it as StatusFilter).toUriPart() }
        val sort = filters.firstOrNull { it is SortFilter }?.let { (it as SortFilter).toUriPart() }

        if (category != null || status != null || sort != null) {
            val items = searchCategoryStatus(query, category, status, sort)
            return MangasPage(items, hasNext = false)
        }

        if (query.isBlank()) {
            return parseApiMangaListPage(
                page = page,
                category = 1,
                sort = "updated",
            )
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

    private class CategoryFilter(name: String, values: Array<Pair<String, String>>) : Filter.Select<String>(
        name,
        values.map { it.first }.toTypedArray(),
        0,
    ) {
        private val valueMap = values.toMap()
        fun toUriPart(): String? = valueMap[values[selected].first]
    }

    private class StatusFilter(name: String, values: Array<Pair<String, String>>) : Filter.Select<String>(
        name,
        values.map { it.first }.toTypedArray(),
        0,
    ) {
        private val valueMap = values.toMap()
        fun toUriPart(): String? = valueMap[values[selected].first]
    }

    private class SortFilter(name: String, values: Array<String>) : Filter.Select<String>(
        name,
        values,
        0,
    ) {
        fun toUriPart(): String? = values.getOrNull(selected)
    }

    private suspend fun parseMangaListPage(url: String): MangasPage {
        val doc = client.get(url).asJsoup()
        val items = doc.select("a.manga-card-link").map { it.toSManga() }
        val next = doc.selectFirst("a.pagination-next")
        val hasNext = next != null && next.attr("aria-disabled") != "true"
        return MangasPage(items, hasNext)
    }

    private suspend fun parseMangaListApiPage(url: HttpUrl): MangasPage {
        val data = client.get(url).parseAs<SearchResponse>().data
        val items = data.mangaItems.map { it.toSManga() }
        return MangasPage(items, page = data.page < data.total_pages)
    }

    private suspend fun searchCategoryStatus(
        query: String,
        category: Int?,
        status: String?,
        sort: String?,
    ): List<SManga> {
        val seen = HashSet<String>()
        val items = mutableListOf<SManga>()
        var page = 1
        while (page <= 20 && items.size < 400) {
            val pageItems = parseMangaListApiPage(
                apiUrl("mangas")
                    .apply {
                        if (category != null) addQueryParameter("category", category.toString())
                        if (status != null && status != "all") addQueryParameter("status", status)
                        if (sort != null) addQueryParameter("sort", sort)
                        addQueryParameter("page", page.toString())
                        addQueryParameter("per_page", "18")
                    }
                    .build(),
            ).items
            for (item in pageItems) {
                if (item.url.isNotBlank() && seen.add(item.url)) items += item
            }
            if (pageItems.isEmpty()) break
            page++
        }
        return items.filter { it.title.contains(query, ignoreCase = true) }
    }

    private suspend fun parseApiMangaListPage(
        page: Int,
        category: Int = 1,
        sort: String = "updated",
        status: String? = null,
    ): MangasPage {
        val url = apiUrl("mangas")
            .addQueryParameter("category", category.toString())
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "18")
            .apply {
                if (status != null && status != "all") addQueryParameter("status", status)
            }
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
