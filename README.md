# Hipmh Extensions (lite)

Minimal [tachiyomi-extensions](https://github.com/yuzono/tachiyomi-extensions) repository containing a single source:

| Source | Module | Base URL |
|--------|--------|----------|
| Hipmh (嬉皮漫畫) | `src/zh/hipmh` | https://m.hipmh.com (custom URL) |

Compatible with Komikku / Mihon / Tachiyomi forks.

## Build

```bash
./gradlew :src:zh:hipmh:assembleRelease
```

APKs are uploaded as GitHub Actions artifacts.
