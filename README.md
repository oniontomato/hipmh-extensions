# Hipmh Extensions (lite)

Minimal [tachiyomi-extensions](https://github.com/yuzono/tachiyomi-extensions) repository containing only 5 sources:

| Source | Module | Base URL |
|--------|--------|----------|
| Goda | `src/en/goda` | https://manhuascans.org |
| Hipmh (嬉皮漫畫) | `src/zh/hipmh` | https://m.hipmh.com (custom URL) |
| Jinman Tiantang (禁漫天堂) | `src/zh/jinmantiantang` | https://18comic.vip |
| Komiic | `src/zh/komiic` | https://komiic.com (mirrors) |
| Tencent Comics (腾讯动漫) | `src/zh/tencentcomics` | https://m.ac.qq.com |

Compatible with Komikku / Mihon / Tachiyomi forks.

## Build

```bash
./gradlew :src:en:goda:assembleRelease \
  :src:zh:hipmh:assembleRelease \
  :src:zh:jinmantiantang:assembleRelease \
  :src:zh:komiic:assembleRelease \
  :src:zh:tencentcomics:assembleRelease
```

APKs are uploaded as GitHub Actions artifacts.
