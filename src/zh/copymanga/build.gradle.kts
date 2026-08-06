import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "CopyManga"
    versionCode = 31
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "拷贝漫画"
        lang = "zh"
        baseUrl {
            mirrors("https://www.copy3000.com", "https://2025copy.com", "https://www.copymanga.site")
        }
    }
}
