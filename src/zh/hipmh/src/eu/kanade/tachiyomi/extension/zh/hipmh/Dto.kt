package eu.kanade.tachiyomi.extension.zh.hipmh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SearchResponse(
    val code: Int = 0,
    val message: String = "",
    val data: SearchData = SearchData(),
)

@Serializable
class SearchData(
    val items: List<MangaItem> = emptyList(),
    @SerialName("data") val legacyData: List<MangaItem>? = null,
    val page: Int = 1,
    val page_size: Int = 0,
    val per_page: Int = 0,
    val total: Long = 0,
    val total_pages: Int = 1,
) {
    val mangaItems: List<MangaItem>
        get() = items.ifEmpty { legacyData.orEmpty() }
}

@Serializable
class MangaItem(
    @SerialName("mid") val mid: String = "",
    @SerialName("id") val id: String? = null,
    val title: String = "",
    val slug: String = "",
    val vertical_image_url: String = "",
    val description: String = "",
    val status: String = "",
    val content_rating: Int = 0,
    val cover_color: String? = null,
    val cover_image_url: String = "",
    val author_names: List<String> = emptyList(),
    val authors: List<LdAuthor> = emptyList(),
    val genres: List<String> = emptyList(),
    val genre_names: List<String> = emptyList(),
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
