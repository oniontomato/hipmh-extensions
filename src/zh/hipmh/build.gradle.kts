import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hipmh"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "Hipmh"
        lang = "zh"
        baseUrl {
            custom("https://m.hipmh.com")
        }
    }
}
