package eu.kanade.tachiyomi.extension.zh.hipmh

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SearchResponse(val code: Int, val data: SearchData)

// /v1/mangas 列表/最新接口返回的作品 shape（與搜尋用的 MangaItem 不同）
@Serializable
class MangaListResponse(val code: Int, val data: MangaListData)

@Serializable
class MangaListData(
    val items: List<ApiMangaItem> = emptyList(),
    val page: Int = 1,
    val per_page: Int = 0,
    val total: Long = 0,
    val total_pages: Int = 1,
)

@Serializable
class ApiMangaItem(
    val mid: String = "",
    val title: String = "",
    val vertical_image_url: String = "",
    val author_names: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val content_rating: Int = 1,
)

@Serializable
class SearchData(
    val data: List<MangaItem>,
    val page: Int = 1,
    val page_size: Int = 0,
    val total: Long = 0,
    val total_pages: Int = 1,
)

@Serializable
class MangaItem(
    val id: String = "",
    val title: String = "",
    val slug: String = "",
    val vertical_image_url: String = "",
    val description: String = "",
    val status: String = "",
    val authors: List<LdAuthor> = emptyList(),
    val genres: List<LdGenre> = emptyList(),
)

@Serializable
class LdAuthor(val name: String = "")

@Serializable
class LdGenre(val name: String = "")

@Serializable
class ChaptersResponse(val code: Int, val data: ChaptersData)

@Serializable
class ChaptersData(
    val items: List<ChapterItem>,
    val page: Int = 1,
    val total: Int = 0,
    val total_pages: Int = 1,
)

@Serializable
class ChapterItem(
    val hid: String = "",
    val chapter_number: Int = 0,
    val title: String = "",
    val cover_image_url: String = "",
    val updated_at: String = "",
)

@Serializable
class ChapterImagesResponse(val code: Int, val data: ChapterImagesData)

@Serializable
class ChapterImagesData(val images: String = "")

@Serializable
class LdJsonRoot(@SerialName("@graph") val graph: List<LdSeries> = emptyList())

@Serializable
class LdSeries(

// 分類 dropdown filter（國漫/韓漫）：搜尋 UI 會渲染，選中後 state=index 注入 getSearchManga
open class CategoryFilter(
    displayName: String,
    private val vals: Array<Pair<String, String>>,
) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

// 狀態 dropdown：API /v1/mangas 接受 status 參數 server-side 篩選（ongoing/completed/unknown）
open class StatusFilter(
    displayName: String,
    private val vals: Array<Pair<String, String>>,
) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

// 排序 dropdown：API /v1/mangas 接受 sort 參數 server-side 排序（updated/popular/latest）
open class SortFilter(
    displayName: String,
    private val vals: Array<String>,
) : Filter.Sort(displayName, vals) {
    fun toUriPart() = state?.let { vals[it.index] } ?: vals[0]
}
    val name: String = "",
    val description: String = "",
    val image: String = "",
    val author: LdAuthor = LdAuthor(),
)
