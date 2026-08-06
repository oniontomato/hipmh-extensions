package eu.kanade.tachiyomi.extension.zh.copymanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Source
abstract class CopyManga : KeiSource() {

    private val apiBaseUrl: String
        get() {
            val host = baseUrl.toHttpUrlOrNull()?.host ?: return baseUrl
            return "https://api.$host"
        }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("region", "1")
        set("platform", "1")
        set("version", VERSION)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = PAGE_SIZE * (page - 1)
        val url = apiUrl("recs")
            .addQueryParameter("pos", "3200102")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val list = client.get(url).parseAs<ResultDto<ListDto<MangaDto>>>().results
        return parseMangaPage(list)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val offset = PAGE_SIZE * (page - 1)
        val url = apiUrl("update/newest")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val list = client.get(url).parseAs<ResultDto<ListDto<MangaDto>>>().results
        return parseMangaPage(list)
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val offset = PAGE_SIZE * (page - 1)
        val builder = apiBaseUrl.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegments("api/v3")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
        if (query.isNotBlank()) {
            builder.addPathSegments("search/comic").addQueryParameter("q", query)
        } else {
            builder.addPathSegments("comics")
        }
        val list = client.get(builder.build()).parseAs<ResultDto<ListDto<MangaDto>>>().results
        return parseMangaPage(list)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "comic") return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        return mangaDetails(slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.removePrefix(MangaDto.URL_PREFIX)
        val detail = client.get(apiUrl("comic2/$slug").build())
            .parseAs<ResultDto<MangaWrapperDto>>().results
        val updatedManga = if (fetchDetails) detail.toSMangaDetails() else manga
        val updatedChapters = if (fetchChapters) {
            val result = mutableListOf<SChapter>()
            result += fetchChapterGroup(slug, "default", "")
            for ((key, group) in detail.groups.orEmpty()) {
                if (key != "default") result += fetchChapterGroup(slug, key, group.name)
            }
            result
        } else {
            chapters
        }
        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = apiBaseUrl + "/api/v3" + chapter.url
        val result = client.get(url).parseAs<ResultDto<ChapterPageListWrapperDto>>().results
        if (result.show_app) {
            throw Exception("访问受限，请尝试在源设置中切换域名")
        }
        return result.chapter.contents.mapIndexed { index, it -> Page(index, imageUrl = it.url) }
    }

    private suspend fun mangaDetails(slug: String): SManga = client.get(apiUrl("comic2/$slug").build())
        .parseAs<ResultDto<MangaWrapperDto>>().results.toSMangaDetails()

    private suspend fun fetchChapterGroup(slug: String, key: String, name: String): List<SChapter> {
        val result = mutableListOf<SChapter>()
        var offset = 0
        var hasNextPage = true
        while (hasNextPage) {
            val url = apiUrl("comic/$slug/group/$key/chapters")
                .addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
            val page = client.get(url).parseAs<ResultDto<ListDto<ChapterDto>>>().results
            result += page.list.map { it.toSChapter(name) }
            offset += CHAPTER_PAGE_SIZE
            hasNextPage = offset < page.total
        }
        return result.asReversed()
    }

    private fun parseMangaPage(list: ListDto<MangaDto>): MangasPage = MangasPage(list.list.map { it.toSManga() }, list.offset + list.limit < list.total)

    private fun apiUrl(path: String) = apiBaseUrl.toHttpUrlOrNull()!!.newBuilder()
        .addPathSegments("api/v3")
        .addPathSegments(path)

    companion object {
        private const val PAGE_SIZE = 20
        private const val CHAPTER_PAGE_SIZE = 500
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        private const val VERSION = "2022.06.29"
    }
}
