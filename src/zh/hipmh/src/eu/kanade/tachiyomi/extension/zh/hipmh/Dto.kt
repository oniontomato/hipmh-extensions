package eu.kanade.tachiyomi.extension.zh.hipmh

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
    val name: String = "",
    val description: String = "",
    val image: String = "",
    val author: LdAuthor = LdAuthor(),
)
