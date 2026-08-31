import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "hipmh manga"
    versionCode = 4
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "嬉皮漫畫"
        lang = "zh"
        baseUrl {
            custom("https://m.hipmh.com")
        }
    }
}
