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
            mirrors("https://www.copymanga.org", "https://www.copymanga.info", "https://www.copymanga.net")
        }
    }
}
