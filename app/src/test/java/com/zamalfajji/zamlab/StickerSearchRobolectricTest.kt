package com.zamalfajji.zamlab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.EndpointRequestException
import com.zamalfajji.zamlab.editor.HttpStatusException
import com.zamalfajji.zamlab.editor.OnlineStickerResult
import com.zamalfajji.zamlab.editor.OnlineStickerSource
import com.zamalfajji.zamlab.editor.StickerHttpFetcher
import com.zamalfajji.zamlab.editor.StickerSearchFilter
import com.zamalfajji.zamlab.editor.StickerSearchRepository
import com.zamalfajji.zamlab.editor.StudioTab
import com.zamalfajji.zamlab.ui.EditorScreen
import com.zamalfajji.zamlab.ui.theme.ZamLabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerSearchRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()

    private val sampleOpenMojiJson = """
        [
          {
            "hexcode": "1F4F7",
            "annotation": "camera",
            "tags": "camera, photo, video",
            "openmoji_tags": "photography, lens",
            "group": "objects",
            "subgroups": "light-video",
            "skintone": ""
          },
          {
            "hexcode": "1F4F8",
            "annotation": "camera with flash",
            "tags": "camera, flash, video",
            "openmoji_tags": "snapshot",
            "group": "objects",
            "subgroups": "light-video",
            "skintone": ""
          },
          {
            "hexcode": "1F525",
            "annotation": "fire",
            "tags": "fire, flame, hot",
            "openmoji_tags": "lit",
            "group": "travel-places",
            "subgroups": "sky-weather",
            "skintone": ""
          },
          {
            "hexcode": "1F44D-1F3FD",
            "annotation": "thumbs up: medium skin tone",
            "tags": "hand, camera",
            "openmoji_tags": "",
            "group": "people-body",
            "subgroups": "hand-fingers-closed",
            "skintone": "4"
          }
        ]
    """.trimIndent()

    private val sampleWikimediaJson = """
        {
          "batchcomplete": "",
          "query": {
            "pages": {
              "98765": {
                "pageid": 98765,
                "ns": 6,
                "title": "File:Vintage_Camera_Cutout.png",
                "index": 1,
                "imageinfo": [
                  {
                    "url": "https://upload.wikimedia.org/wikipedia/commons/a/ab/Vintage_Camera_Cutout.png",
                    "thumburl": "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Vintage_Camera_Cutout.png/512px-Vintage_Camera_Cutout.png",
                    "width": 1200,
                    "height": 900
                  }
                ]
              },
              "98766": {
                "pageid": 98766,
                "ns": 6,
                "title": "File:Camera_Photo.jpg",
                "index": 2,
                "imageinfo": [
                  {
                    "url": "https://upload.wikimedia.org/wikipedia/commons/b/bc/Camera_Photo.jpg",
                    "thumburl": "https://upload.wikimedia.org/wikipedia/commons/thumb/b/bc/Camera_Photo.jpg/512px-Camera_Photo.jpg",
                    "width": 1200,
                    "height": 900
                  }
                ]
              }
            }
          }
        }
    """.trimIndent()

    private fun createSamplePngBytes(color: Int = Color.argb(255, 245, 166, 35)): ByteArray {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        for (y in 8 until 40) {
            for (x in 8 until 40) {
                bmp.setPixel(x, y, color)
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        StickerSearchRepository.resetCachesForTesting()
        File(app.cacheDir, "stickers").deleteRecursively()
    }

    @After
    fun tearDown() {
        StickerSearchRepository.resetCachesForTesting()
        Dispatchers.resetMain()
    }

    private fun awaitCondition(timeoutMs: Long = 10_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for condition")
    }

    @Test
    fun `Trending online shelf provides 24 curated OpenMoji 618x618 transparent PNGs immediately`() {
        val trending = StickerSearchRepository.featuredTrendingStickers()
        assertEquals(24, trending.size)
        trending.forEach { item ->
            assertEquals(OnlineStickerSource.OPENMOJI, item.source)
            assertTrue(
                "Trending URL must use OpenMoji 618x618 CDN: ${item.downloadUrl}",
                item.downloadUrl.startsWith("https://cdn.jsdelivr.net/npm/openmoji@15.1.0/color/618x618/")
            )
            assertTrue(item.downloadUrl.endsWith(".png"))
        }
        val titles = trending.map { it.title.lowercase() }
        listOf("fire", "crown", "lightning", "sparkle", "camera", "heart", "sunglasses", "trophy", "rocket").forEach { kw ->
            assertTrue("Trending list should include $kw", titles.any { it.contains(kw) })
        }
    }

    @Test
    fun `Wikimedia 256px SVG and PNG search URL, Iconify search URL, OpenMoji synonyms, Noto 512px, and Fluent 3D & Twemoji search work cleanly`() {
        val url = StickerSearchRepository.buildWikimediaSearchUrl("golden crown")
        assertTrue(url.startsWith("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search"))
        assertTrue(url.contains("gsrnamespace=6"))
        assertTrue(url.contains("gsrsearch=filetype:bitmap+golden+crown+png"))
        assertTrue(url.contains("gsrlimit=24"))
        assertTrue(url.contains("prop=imageinfo&iiprop=url%7Csize&iiurlwidth=256"))
        assertTrue("Wikimedia search URL must pass isSafeHttpsUrl allowlist", StickerSearchRepository.isSafeHttpsUrl(url))

        val iconifyUrl = StickerSearchRepository.buildIconifySearchUrl("golden crown")
        assertEquals(
            "https://api.iconify.design/search?query=golden+crown&limit=32&prefixes=noto,twemoji,openmoji,fluent-emoji-flat,fluent-emoji",
            iconifyUrl
        )
        assertTrue("Iconify search URL must pass isSafeHttpsUrl allowlist", StickerSearchRepository.isSafeHttpsUrl(iconifyUrl))

        val allImagesUrl = StickerSearchRepository.buildWikimediaAllImagesUrl("golden crown")
        assertTrue(allImagesUrl.contains("list=allimages"))
        assertTrue(allImagesUrl.contains("aiprefix=Golden"))
        assertTrue(allImagesUrl.contains("aimime=image%2Fpng"))
        assertTrue("Wikimedia allimages URL must pass isSafeHttpsUrl allowlist", StickerSearchRepository.isSafeHttpsUrl(allImagesUrl))

        // OpenMoji parses, excludes skintones, and matches keyword synonyms (e.g. "flame" -> fire)
        val openMojiEntries = StickerSearchRepository.parseOpenMojiJson(sampleOpenMojiJson)
        assertEquals(4, openMojiEntries.size)
        val filtered = StickerSearchRepository.filterOpenMojiCatalog(openMojiEntries, "camera")
        assertEquals(2, filtered.size)
        assertEquals("Camera", filtered[0].title)
        assertEquals(
            "https://cdn.jsdelivr.net/npm/openmoji@15.1.0/color/618x618/1F4F7.png",
            filtered[0].downloadUrl
        )
        val synonymMatch = StickerSearchRepository.filterOpenMojiCatalog(openMojiEntries, "flame")
        assertTrue("Synonym 'flame' should match fire", synonymMatch.any { it.title == "Fire" })

        // Google Noto Color Emoji 512px PNG source
        val notoResults = StickerSearchRepository.searchNotoEmoji("camera", openMojiEntries)
        assertEquals(2, notoResults.size)
        assertEquals(OnlineStickerSource.NOTO_EMOJI, notoResults[0].source)
        assertEquals(
            "https://raw.githubusercontent.com/googlefonts/noto-emoji/main/png/512/emoji_u1f4f7.png",
            notoResults[0].downloadUrl
        )
        assertEquals("1f525", StickerSearchRepository.toNotoEmojiHexcode("1F525"))
        assertEquals("2764", StickerSearchRepository.toNotoEmojiHexcode("2764-FE0F"))
        assertEquals("1f469_200d_1f680", StickerSearchRepository.toNotoEmojiHexcode("1F469-200D-1F680"))
        assertEquals(null, StickerSearchRepository.toNotoEmojiHexcode("E042"))

        // Fluent 3D & Twemoji CDN URLs
        val fluentResults = StickerSearchRepository.searchFluentAndTwemoji("camera", openMojiEntries)
        assertTrue(fluentResults.isNotEmpty())
        assertTrue(fluentResults.all { it.source == OnlineStickerSource.FLUENT_TWEMOJI })
        assertTrue(fluentResults.any { it.downloadUrl.startsWith(StickerSearchRepository.FLUENT_3D_BASE_URL) && it.downloadUrl.endsWith("_3d.png") })
        assertEquals("1f525", StickerSearchRepository.toTwemojiHexcode("1F525"))
        assertEquals("2764", StickerSearchRepository.toTwemojiHexcode("2764-FE0F"))
        assertEquals(null, StickerSearchRepository.toTwemojiHexcode("E042"))

        // Wikimedia filtering accepts PNGs and SVG->512px PNG thumbnails while rejecting JPGs and raw SVGs
        val wikiResults = StickerSearchRepository.parseWikimediaSearchJson(sampleWikimediaJson)
        assertEquals(1, wikiResults.size)
        assertEquals("Vintage Camera Cutout", wikiResults[0].title)
        assertEquals(OnlineStickerSource.WIKIMEDIA, wikiResults[0].source)
        assertTrue(wikiResults[0].downloadUrl.endsWith(".png"))
    }

    @Test
    fun `fuzzy multi-word stemming and Wikimedia SVG-to-PNG rasterization match plurals, phrases, and vector cutouts`() {
        val extendedOpenMojiJson = """
            [
              {
                "hexcode": "1FABD",
                "annotation": "wing",
                "tags": "angelic, aviation, bird, flying, mythology, wing",
                "openmoji_tags": "angel, feather",
                "group": "animals-nature",
                "subgroups": "animal-bird",
                "skintone": ""
              },
              {
                "hexcode": "1F3CE",
                "annotation": "racing car",
                "tags": "car, racing",
                "openmoji_tags": "speed, motorsport",
                "group": "travel-places",
                "subgroups": "transport-ground",
                "skintone": ""
              },
              {
                "hexcode": "1F98B",
                "annotation": "butterfly",
                "tags": "butterfly, insect, pretty",
                "openmoji_tags": "nature",
                "group": "animals-nature",
                "subgroups": "animal-bug",
                "skintone": ""
              }
            ]
        """.trimIndent()

        val entries = StickerSearchRepository.parseOpenMojiJson(extendedOpenMojiJson)

        // Singular/plural stemming: "wings" -> "wing", "cars" -> "car", "butterflies" -> "butterfly"
        assertTrue(StickerSearchRepository.stemWordVariants("wings").contains("wing"))
        assertTrue(StickerSearchRepository.stemWordVariants("cars").contains("car"))
        assertTrue(StickerSearchRepository.stemWordVariants("butterflies").contains("butterfly"))
        assertEquals(listOf("golden", "angel", "wings"), StickerSearchRepository.normalizeQueryTokens("  Golden & Angel of Wings! "))

        val wingsMatches = StickerSearchRepository.filterOpenMojiCatalog(entries, "wings")
        assertTrue("Plural 'wings' must match 'Wing'", wingsMatches.any { it.title == "Wing" })

        val carsMatches = StickerSearchRepository.filterOpenMojiCatalog(entries, "cars")
        assertTrue("Plural 'cars' must match 'Racing car'", carsMatches.any { it.title == "Racing car" })

        val butterfliesMatches = StickerSearchRepository.filterOpenMojiCatalog(entries, "butterflies")
        assertTrue("Plural 'butterflies' must match 'Butterfly'", butterfliesMatches.any { it.title == "Butterfly" })

        // Multi-word casual phrases hit relevant OpenMoji & Noto stickers via keyword splitting + stemming
        val phraseMatches = StickerSearchRepository.filterOpenMojiCatalog(entries, "cool fast racing cars")
        assertTrue("Multi-word phrase should match 'Racing car'", phraseMatches.any { it.title == "Racing car" })

        val notoWingMatches = StickerSearchRepository.searchNotoEmoji("golden angel wings", entries)
        assertTrue("Noto 512px should match 'Wing' for multi-word plural phrase", notoWingMatches.any { it.id == "noto_1fabd" })

        // Wikimedia Commons SVG + PNG parsing: accepts .svg.png thumburl and .png, rejects .jpg and raw .svg without PNG thumburl
        val svgAndPngWikiJson = """
            {
              "query": {
                "pages": {
                  "101": {
                    "pageid": 101,
                    "title": "File:Angel_Wing_Icon.svg",
                    "index": 1,
                    "imageinfo": [
                      {
                        "url": "https://upload.wikimedia.org/wikipedia/commons/1/11/Angel_Wing_Icon.svg",
                        "thumburl": "https://upload.wikimedia.org/wikipedia/commons/thumb/1/11/Angel_Wing_Icon.svg/512px-Angel_Wing_Icon.svg.png"
                      }
                    ]
                  },
                  "102": {
                    "pageid": 102,
                    "title": "File:Broken_No_Thumb.svg",
                    "index": 2,
                    "imageinfo": [
                      {
                        "url": "https://upload.wikimedia.org/wikipedia/commons/2/22/Broken_No_Thumb.svg"
                      }
                    ]
                  },
                  "103": {
                    "pageid": 103,
                    "title": "File:Wing_Photo.jpg",
                    "index": 3,
                    "imageinfo": [
                      {
                        "url": "https://upload.wikimedia.org/wikipedia/commons/3/33/Wing_Photo.jpg",
                        "thumburl": "https://upload.wikimedia.org/wikipedia/commons/thumb/3/33/Wing_Photo.jpg/512px-Wing_Photo.jpg"
                      }
                    ]
                  },
                  "104": {
                    "pageid": 104,
                    "title": "File:Wing_Sticker.png",
                    "index": 4,
                    "imageinfo": [
                      {
                        "url": "https://upload.wikimedia.org/wikipedia/commons/4/44/Wing_Sticker.png",
                        "thumburl": "https://upload.wikimedia.org/wikipedia/commons/thumb/4/44/Wing_Sticker.png/512px-Wing_Sticker.png"
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val parsedWiki = StickerSearchRepository.parseWikimediaSearchJson(svgAndPngWikiJson)
        assertEquals(2, parsedWiki.size)
        assertEquals("Angel Wing Icon", parsedWiki[0].title)
        assertTrue("SVG must use MediaWiki 512px .svg.png thumburl", parsedWiki[0].downloadUrl.endsWith(".svg.png"))
        assertEquals("Wing Sticker", parsedWiki[1].title)
        assertTrue(parsedWiki[1].downloadUrl.endsWith(".png"))
    }

    @Test
    fun `Iconify keyless search resolves noto, twemoji, openmoji, and fluent-emoji slugs directly to pre-rendered PNG CDNs with OpenMoji fallback`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pngBytes = createSamplePngBytes()
        val sampleIconifyJson = """
            {
              "icons": [
                "openmoji:camera",
                "noto:fire",
                "twemoji:camera-with-flash",
                "fluent-emoji:fire",
                "fluent-emoji-flat:sparkles",
                "noto:thumbs-up-medium-skin-tone"
              ],
              "total": 6
            }
        """.trimIndent()

        val openMojiEntries = StickerSearchRepository.parseOpenMojiJson(sampleOpenMojiJson)
        val parsedIds = StickerSearchRepository.parseIconifySearchJson(sampleIconifyJson)
        assertEquals(6, parsedIds.size)

        val resolved = StickerSearchRepository.resolveIconifyIconsToPngResults(parsedIds, openMojiEntries)
        // Skin-tone variant is skipped; all 5 standard icons resolve strictly to pre-rendered PNG URLs
        assertEquals(5, resolved.size)
        resolved.forEach { item ->
            assertTrue("Resolved Iconify sticker must use strict PNG URL: ${item.downloadUrl}", StickerSearchRepository.isStrictPngUrl(item.downloadUrl))
            assertFalse("Resolved Iconify sticker must never pass api.iconify.design SVG to BitmapFactory", item.downloadUrl.contains("api.iconify.design"))
        }

        val openMojiCamera = resolved.first { it.id == "openmoji_1F4F7" }
        assertEquals("https://cdn.jsdelivr.net/npm/openmoji@15.1.0/color/618x618/1F4F7.png", openMojiCamera.downloadUrl)

        val notoFire = resolved.first { it.id == "noto_1f525" }
        assertEquals("https://raw.githubusercontent.com/googlefonts/noto-emoji/main/png/512/emoji_u1f525.png", notoFire.downloadUrl)
        assertTrue(notoFire.fallbackUrls.contains("https://cdn.jsdelivr.net/npm/openmoji@15.1.0/color/618x618/1F525.png"))

        val twemojiFlash = resolved.first { it.id == "twemoji_1f4f8" }
        assertEquals("https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72/1f4f8.png", twemojiFlash.downloadUrl)
        assertTrue(twemojiFlash.fallbackUrls.contains("https://cdn.jsdelivr.net/npm/openmoji@15.1.0/color/618x618/1F4F8.png"))

        val fluentSparkles = resolved.first { it.id == "fluent3d_sparkles" }
        assertTrue(fluentSparkles.downloadUrl.startsWith(StickerSearchRepository.FLUENT_3D_BASE_URL))
        assertTrue(fluentSparkles.downloadUrl.endsWith("_3d.png"))
        assertTrue(fluentSparkles.fallbackUrls.contains("https://cdn.jsdelivr.net/npm/openmoji@15.1.0/color/618x618/2728.png"))

        // Verify Fluent 3D 404 seamlessly falls back to guaranteed OpenMoji 618x618 PNG
        val requestedUrls = mutableListOf<String>()
        StickerSearchRepository.downloadRetryBackoffMs = 5L
        StickerSearchRepository.httpFetcher = StickerHttpFetcher { url, _ ->
            requestedUrls.add(url)
            if (url.startsWith(StickerSearchRepository.OPENMOJI_PNG_BASE_URL)) {
                pngBytes
            } else {
                throw HttpStatusException(404, url, "HTTP 404 Not Found")
            }
        }
        val fallbackAsset = StickerSearchRepository.downloadAndCacheSticker(app, fluentSparkles)
        assertNotNull("Fluent PNG 404 should fall back to OpenMoji 618x618 PNG", fallbackAsset)
        assertTrue(requestedUrls.any { it == "https://cdn.jsdelivr.net/npm/openmoji@15.1.0/color/618x618/2728.png" })
        fallbackAsset!!.bitmap.recycle()
    }

    @Test
    fun `Wikimedia downloads enforce max-2 concurrency, pacing delay, and automatic 1-retry on HTTP 429 and 503`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pngBytes = createSamplePngBytes()
        StickerSearchRepository.downloadRetryBackoffMs = 10L
        StickerSearchRepository.wikimediaDownloadDelayMs = 20L

        // 1. Verify 1-retry backoff recovers from HTTP 429 rate limit
        val retry429Attempts = AtomicInteger(0)
        StickerSearchRepository.httpFetcher = StickerHttpFetcher { url, _ ->
            if (retry429Attempts.incrementAndGet() == 1) {
                throw HttpStatusException(429, url, "HTTP 429 Too Many Requests")
            }
            pngBytes
        }
        val wiki429Sticker = OnlineStickerResult(
            id = "wiki_retry_429",
            title = "Angel Wing Icon",
            source = OnlineStickerSource.WIKIMEDIA,
            thumbnailUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/11/Angel_Wing_Icon.svg/256px-Angel_Wing_Icon.svg.png",
            downloadUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/11/Angel_Wing_Icon.svg/256px-Angel_Wing_Icon.svg.png",
            cacheKey = "wiki_retry_429"
        )
        val retried429Bitmap = StickerSearchRepository.loadStickerThumbnail(app, wiki429Sticker)
        assertNotNull("Transient HTTP 429 should succeed on 1-retry backoff", retried429Bitmap)
        assertEquals(2, retry429Attempts.get())
        retried429Bitmap!!.recycle()

        // 2. Verify 1-retry backoff also recovers from HTTP 503
        val retry503Attempts = AtomicInteger(0)
        StickerSearchRepository.httpFetcher = StickerHttpFetcher { url, _ ->
            if (retry503Attempts.incrementAndGet() == 1) {
                throw HttpStatusException(503, url, "HTTP 503 Service Unavailable")
            }
            pngBytes
        }
        val wiki503Sticker = OnlineStickerResult(
            id = "wiki_retry_503",
            title = "Wing Sticker",
            source = OnlineStickerSource.WIKIMEDIA,
            thumbnailUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/44/Wing_Sticker.png/256px-Wing_Sticker.png",
            downloadUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/44/Wing_Sticker.png/256px-Wing_Sticker.png",
            cacheKey = "wiki_retry_503"
        )
        val retried503Bitmap = StickerSearchRepository.loadStickerThumbnail(app, wiki503Sticker)
        assertNotNull("Transient HTTP 503 should succeed on 1-retry backoff", retried503Bitmap)
        assertEquals(2, retry503Attempts.get())
        retried503Bitmap!!.recycle()

        // 3. Verify upload.wikimedia.org concurrent downloads never exceed 2 in flight
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        val totalDownloads = AtomicInteger(0)
        StickerSearchRepository.httpFetcher = StickerHttpFetcher { _, _ ->
            val current = inFlight.incrementAndGet()
            peakInFlight.accumulateAndGet(current, Math::max)
            totalDownloads.incrementAndGet()
            try {
                Thread.sleep(45L)
                pngBytes
            } finally {
                inFlight.decrementAndGet()
            }
        }

        val batchStickers = (1..8).map { idx ->
            OnlineStickerResult(
                id = "wiki_batch_$idx",
                title = "Sticker $idx",
                source = OnlineStickerSource.WIKIMEDIA,
                thumbnailUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a$idx/Icon_$idx.svg/256px-Icon_$idx.svg.png",
                downloadUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a$idx/Icon_$idx.svg/256px-Icon_$idx.svg.png",
                cacheKey = "wiki_batch_$idx"
            )
        }
        val loadedBitmaps = batchStickers.map { sticker ->
            async(Dispatchers.Default) {
                StickerSearchRepository.loadStickerThumbnail(app, sticker)
            }
        }.awaitAll()

        assertEquals(8, totalDownloads.get())
        assertTrue(
            "Peak concurrent upload.wikimedia.org downloads (${peakInFlight.get()}) must not exceed ${StickerSearchRepository.MAX_CONCURRENT_WIKIMEDIA_DOWNLOADS}",
            peakInFlight.get() <= StickerSearchRepository.MAX_CONCURRENT_WIKIMEDIA_DOWNLOADS
        )
        assertEquals(2, StickerSearchRepository.MAX_CONCURRENT_WIKIMEDIA_DOWNLOADS)
        loadedBitmaps.forEach { bmp ->
            assertNotNull(bmp)
            bmp!!.recycle()
        }
    }

    @Test
    fun `parallel Iconify, OpenMoji, and Wikimedia search respects 3 filter modes and caches PNGs in cacheDir stickers`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pngBytes = createSamplePngBytes()
        val catalogFetches = AtomicInteger(0)
        val iconifyFetches = AtomicInteger(0)
        val wikimediaFetches = AtomicInteger(0)
        val imageFetches = AtomicInteger(0)
        val sampleIconifyJson = """{"icons":["openmoji:camera","noto:camera","twemoji:camera-with-flash","fluent-emoji:camera"]}"""

        StickerSearchRepository.httpFetcher = StickerHttpFetcher { url, _ ->
            when {
                url == StickerSearchRepository.OPENMOJI_CATALOG_URL -> {
                    catalogFetches.incrementAndGet()
                    sampleOpenMojiJson.toByteArray(Charsets.UTF_8)
                }
                url.startsWith(StickerSearchRepository.ICONIFY_SEARCH_API_BASE_URL) -> {
                    iconifyFetches.incrementAndGet()
                    sampleIconifyJson.toByteArray(Charsets.UTF_8)
                }
                url.startsWith(StickerSearchRepository.WIKIMEDIA_API_BASE_URL) -> {
                    wikimediaFetches.incrementAndGet()
                    sampleWikimediaJson.toByteArray(Charsets.UTF_8)
                }
                url.endsWith(".png") -> {
                    imageFetches.incrementAndGet()
                    pngBytes
                }
                else -> throw IOException("Unexpected test URL: $url")
            }
        }

        // 1. ALL filter queries Iconify + OpenMoji + Wikimedia in parallel and interleaves all sources cleanly
        val first = StickerSearchRepository.searchStickers(app, "camera", StickerSearchFilter.ALL)
        assertEquals(1, catalogFetches.get())
        assertEquals(1, iconifyFetches.get())
        assertEquals(1, wikimediaFetches.get())
        assertTrue(first.results.any { it.source == OnlineStickerSource.OPENMOJI })
        assertTrue(first.results.any { it.source == OnlineStickerSource.NOTO_EMOJI })
        assertTrue(first.results.any { it.source == OnlineStickerSource.FLUENT_TWEMOJI })
        assertTrue(first.results.any { it.source == OnlineStickerSource.WIKIMEDIA })

        // 2. STICKERS_AND_EMOJI filter reuses cached openmoji.json and skips Wikimedia completely
        val emojiOnly = StickerSearchRepository.searchStickers(app, "snapshot", StickerSearchFilter.STICKERS_AND_EMOJI)
        assertEquals(1, catalogFetches.get())
        assertEquals(1, wikimediaFetches.get())
        assertTrue(emojiOnly.results.isNotEmpty())
        assertTrue(emojiOnly.results.none { it.source == OnlineStickerSource.WIKIMEDIA })

        // 3. WEB_CUTOUTS filter queries only Wikimedia Commons
        val webOnly = StickerSearchRepository.searchStickers(app, "camera", StickerSearchFilter.WEB_CUTOUTS)
        assertEquals(2, wikimediaFetches.get())
        assertTrue(webOnly.results.isNotEmpty())
        assertTrue(webOnly.results.all { it.source == OnlineStickerSource.WIKIMEDIA })

        // 4. Download & cache sticker PNG in cacheDir/stickers/ with zero redundant downloads on repeat tap
        val sticker = first.results.first()
        val firstAsset = StickerSearchRepository.downloadAndCacheSticker(app, sticker)
        assertNotNull(firstAsset)
        assertEquals(1, imageFetches.get())
        assertTrue(firstAsset!!.cacheFile.exists())
        assertEquals(File(app.cacheDir, "stickers").canonicalPath, firstAsset.cacheFile.parentFile?.canonicalPath)
        firstAsset.bitmap.recycle()

        val cachedAsset = StickerSearchRepository.downloadAndCacheSticker(app, sticker)
        assertNotNull(cachedAsset)
        assertEquals(1, imageFetches.get())
        cachedAsset!!.bitmap.recycle()
    }

    @Test
    fun `HTTP client configures policy-compliant Wikimedia User-Agent, 10s timeouts, and allowlists Iconify and unpkg mirrors`() {
        val expectedUa = "ZEffectPhotoEditor/1.3 (https://github.com/zamelalfajji-droid/ZamLab; Android)"
        assertEquals(expectedUa, StickerSearchRepository.USER_AGENT)
        assertEquals(10_000, StickerSearchRepository.CONNECT_TIMEOUT_MS)
        assertEquals(10_000, StickerSearchRepository.READ_TIMEOUT_MS)
        assertEquals(
            "https://unpkg.com/openmoji@15.1.0/data/openmoji.json",
            StickerSearchRepository.OPENMOJI_CATALOG_FALLBACK_URL
        )

        val conn = StickerSearchRepository.openConfiguredConnection(StickerSearchRepository.WIKIMEDIA_API_BASE_URL)
        try {
            assertEquals(expectedUa, conn.getRequestProperty("User-Agent"))
            assertEquals("application/json", conn.getRequestProperty("Accept"))
            assertEquals(10_000, conn.connectTimeout)
            assertEquals(10_000, conn.readTimeout)
            assertFalse(conn.instanceFollowRedirects)
        } finally {
            conn.disconnect()
        }

        val uploadConn = StickerSearchRepository.openConfiguredConnection(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Test.png/256px-Test.png"
        )
        try {
            assertEquals("image/png, image/*", uploadConn.getRequestProperty("Accept"))
            assertEquals(expectedUa, uploadConn.getRequestProperty("User-Agent"))
        } finally {
            uploadConn.disconnect()
        }

        assertTrue(StickerSearchRepository.isSafeHttpsUrl(StickerSearchRepository.ICONIFY_SEARCH_API_BASE_URL))
        assertTrue(StickerSearchRepository.isSafeHttpsUrl(StickerSearchRepository.OPENMOJI_CATALOG_URL))
        assertTrue(StickerSearchRepository.isSafeHttpsUrl(StickerSearchRepository.OPENMOJI_CATALOG_FALLBACK_URL))
        assertTrue(StickerSearchRepository.isSafeHttpsUrl(StickerSearchRepository.OPENMOJI_CATALOG_GITHUB_URL))
        assertTrue(StickerSearchRepository.isSafeHttpsUrl(StickerSearchRepository.WIKIMEDIA_API_BASE_URL))
        assertTrue(StickerSearchRepository.isSafeHttpsUrl("https://upload.wikimedia.org/wikipedia/commons/a/ab/Test.png"))
        assertFalse(StickerSearchRepository.isSafeHttpsUrl("http://cdn.jsdelivr.net/npm/openmoji@15.1.0/data/openmoji.json"))

        // Verify network_security_config.xml trusts system & user CAs and is wired in AndroidManifest.xml
        val nscFile = File("src/main/res/xml/network_security_config.xml")
            .takeIf { it.exists() }
            ?: File("app/src/main/res/xml/network_security_config.xml")
        if (nscFile.exists()) {
            val nscXml = nscFile.readText()
            assertTrue(nscXml.contains("""<certificates src="system""""))
            assertTrue(nscXml.contains("""<certificates src="user""""))
            assertTrue(nscXml.contains("""cleartextTrafficPermitted="false""""))
        }
        val manifestFile = File("src/main/AndroidManifest.xml")
            .takeIf { it.exists() }
            ?: File("app/src/main/AndroidManifest.xml")
        if (manifestFile.exists()) {
            assertTrue(manifestFile.readText().contains("""android:networkSecurityConfig="@xml/network_security_config""""))
        }
    }

    @Test
    fun `jsDelivr failure falls back to unpkg mirror and per-source failures never block other sources`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pngBytes = createSamplePngBytes()
        val requestedUrls = mutableListOf<String>()

        StickerSearchRepository.httpFetcher = StickerHttpFetcher { url, _ ->
            requestedUrls.add(url)
            when {
                url == StickerSearchRepository.OPENMOJI_CATALOG_URL ->
                    throw HttpStatusException(503, url, "HTTP 503 Service Unavailable")
                url == StickerSearchRepository.OPENMOJI_CATALOG_FALLBACK_URL ->
                    sampleOpenMojiJson.toByteArray(Charsets.UTF_8)
                url.startsWith(StickerSearchRepository.WIKIMEDIA_API_BASE_URL) ->
                    throw HttpStatusException(403, url, "HTTP 403 Forbidden")
                url.startsWith(StickerSearchRepository.OPENMOJI_PNG_FALLBACK_BASE_URL) ->
                    pngBytes
                url.startsWith(StickerSearchRepository.OPENMOJI_PNG_BASE_URL) ->
                    throw IOException("jsDelivr blocked")
                else -> throw IOException("Unexpected URL: $url")
            }
        }

        val response = StickerSearchRepository.searchStickers(app, "camera")
        assertFalse(response.isOffline)
        assertFalse(response.usedLocalFallback)
        assertEquals(null, response.errorMessage)
        assertTrue("Primary jsDelivr should be attempted first", StickerSearchRepository.OPENMOJI_CATALOG_URL in requestedUrls)
        assertTrue("Fallback unpkg mirror should be attempted when jsDelivr fails", StickerSearchRepository.OPENMOJI_CATALOG_FALLBACK_URL in requestedUrls)
        assertTrue("OpenMoji results should still load via unpkg mirror even if Wikimedia fails", response.results.any { it.source == OnlineStickerSource.OPENMOJI })

        // Downloading OpenMoji PNG should also succeed via unpkg fallback mirror
        val openMojiSticker = response.results.first { it.source == OnlineStickerSource.OPENMOJI }
        val downloaded = StickerSearchRepository.downloadAndCacheSticker(app, openMojiSticker)
        assertNotNull(downloaded)
        assertTrue(requestedUrls.any { it.startsWith(StickerSearchRepository.OPENMOJI_PNG_FALLBACK_BASE_URL) })
        downloaded!!.bitmap.recycle()
    }

    @Test
    fun `GitHub raw OpenMoji and lightweight Wikimedia allimages fallbacks succeed when CDNs and CirrusSearch fail`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sampleAllImagesJson = """
            {
              "query": {
                "allimages": [
                  {
                    "name": "Camera_Icon_Cutout.png",
                    "title": "File:Camera_Icon_Cutout.png",
                    "url": "https://upload.wikimedia.org/wikipedia/commons/c/cd/Camera_Icon_Cutout.png",
                    "width": 512,
                    "height": 512
                  }
                ]
              }
            }
        """.trimIndent()
        val requestedUrls = mutableListOf<String>()

        StickerSearchRepository.httpFetcher = StickerHttpFetcher { url, _ ->
            requestedUrls.add(url)
            when {
                url == StickerSearchRepository.OPENMOJI_CATALOG_URL ->
                    throw EndpointRequestException(url, SSLHandshakeException("Blocked CDN"))
                url == StickerSearchRepository.OPENMOJI_CATALOG_FALLBACK_URL ->
                    throw HttpStatusException(502, url, "HTTP 502 Bad Gateway")
                url == StickerSearchRepository.OPENMOJI_CATALOG_GITHUB_URL ->
                    sampleOpenMojiJson.toByteArray(Charsets.UTF_8)
                url.contains("generator=search") ->
                    throw HttpStatusException(429, url, "HTTP 429 Too Many Requests")
                url.contains("list=allimages") ->
                    sampleAllImagesJson.toByteArray(Charsets.UTF_8)
                else -> throw IOException("Unexpected URL: $url")
            }
        }

        val response = StickerSearchRepository.searchStickers(app, "camera")
        assertFalse(response.isOffline)
        assertEquals(null, response.errorMessage)
        assertTrue(StickerSearchRepository.OPENMOJI_CATALOG_GITHUB_URL in requestedUrls)
        assertTrue(requestedUrls.any { it.contains("list=allimages") })
        assertTrue(response.results.any { it.source == OnlineStickerSource.OPENMOJI })
        assertTrue(response.results.any { it.source == OnlineStickerSource.WIKIMEDIA && it.title == "Camera Icon Cutout" })
    }

    @Test
    fun `all-network-failure searches attempt HTTP directly, surface exact exception and host diagnostics, and fall back to local StickerLibrary`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val attemptedRequests = AtomicInteger(0)

        StickerSearchRepository.httpFetcher = StickerHttpFetcher { url, _ ->
            attemptedRequests.incrementAndGet()
            when {
                url.contains("commons.wikimedia.org") ->
                    throw HttpStatusException(403, url, "HTTP 403 Forbidden")
                else ->
                    throw EndpointRequestException(url, SSLHandshakeException("Trust anchor not found"))
            }
        }

        // 1. Matching query ("sparkle") falls back to matching local StickerLibrary stickers AND surfaces exact failure diagnostics
        val matchedFallback = StickerSearchRepository.searchStickers(app, "sparkle")
        assertTrue("Must always attempt HTTP directly without connectivity pre-check aborts", attemptedRequests.get() >= 4)
        assertTrue(matchedFallback.isOffline)
        assertTrue(matchedFallback.usedLocalFallback)
        val matchedErr = matchedFallback.errorMessage
        assertNotNull(matchedErr)
        assertTrue(
            "Expected SSLHandshakeException on cdn.jsdelivr.net in '$matchedErr'",
            matchedErr!!.contains("SSLHandshakeException on cdn.jsdelivr.net")
        )
        assertTrue(
            "Expected HTTP 403 on commons.wikimedia.org in '$matchedErr'",
            matchedErr.contains("HTTP 403 on commons.wikimedia.org")
        )
        assertTrue(matchedFallback.results.isNotEmpty())
        assertTrue(matchedFallback.results.all { it.builtInStickerId != null })
        assertTrue(matchedFallback.results.any { it.builtInStickerId == "symbol_sparkles" })

        // Local fallback thumbnail & download render directly without network
        val thumb = StickerSearchRepository.loadStickerThumbnail(app, matchedFallback.results.first())
        assertNotNull(thumb)
        thumb!!.recycle()
        val asset = StickerSearchRepository.downloadAndCacheSticker(app, matchedFallback.results.first())
        assertNotNull(asset)
        assertTrue(asset!!.cacheFile.exists())
        asset.bitmap.recycle()

        // 2. Unmatched query when all network calls fail returns empty results with the exact diagnostic reason
        val unmatchedFallback = StickerSearchRepository.searchStickers(app, "xyzzy_unmatched_qzx")
        assertTrue(unmatchedFallback.isOffline)
        assertFalse(unmatchedFallback.usedLocalFallback)
        assertTrue(unmatchedFallback.results.isEmpty())
        val unmatchedErr = unmatchedFallback.errorMessage
        assertNotNull(unmatchedErr)
        assertTrue(unmatchedErr!!.contains("SSLHandshakeException on cdn.jsdelivr.net"))
        assertTrue(unmatchedErr.contains("HTTP 403 on commons.wikimedia.org"))
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `STUDIO tab shows instant Trending OpenMoji shelf, 3 clean filter chips, and drops tapped PNG onto canvas as ImageLayer`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pngBytes = createSamplePngBytes()
        val sampleIconifyJson = """{"icons":["openmoji:camera","noto:camera","twemoji:camera-with-flash","fluent-emoji:camera"]}"""
        StickerSearchRepository.wikimediaDownloadDelayMs = 5L
        StickerSearchRepository.httpFetcher = StickerHttpFetcher { url, _ ->
            when {
                url == StickerSearchRepository.OPENMOJI_CATALOG_URL -> sampleOpenMojiJson.toByteArray(Charsets.UTF_8)
                url.startsWith(StickerSearchRepository.ICONIFY_SEARCH_API_BASE_URL) -> sampleIconifyJson.toByteArray(Charsets.UTF_8)
                url.startsWith(StickerSearchRepository.WIKIMEDIA_API_BASE_URL) -> sampleWikimediaJson.toByteArray(Charsets.UTF_8)
                url.endsWith(".png") -> pngBytes
                else -> throw IOException("Unexpected URL: $url")
            }
        }

        val vm = EditorViewModel(app)
        try {
            awaitCondition { vm.uiState.value.fullWidth > 0 && vm.uiState.value.layers.isNotEmpty() }

            composeTestRule.setContent {
                ZamLabTheme {
                    EditorScreen(viewModel = vm)
                }
            }
            composeTestRule.waitForIdle()

            // 1. Open STUDIO tab -> CDN probe succeeds -> Instant 24-sticker Trending online shelf renders
            composeTestRule.onNodeWithTag("tab_studio").performClick()
            testDispatcher.scheduler.advanceUntilIdle()
            composeTestRule.waitForIdle()
            awaitCondition {
                StickerSearchRepository.cachedTrendingCdnReachability() == true
            }
            composeTestRule.waitForIdle()
            assertEquals(StudioTab.STUDIO, vm.uiState.value.activeTab)
            composeTestRule.onNodeWithTag("trending_online_stickers_grid").assertIsDisplayed()
            composeTestRule.onNodeWithTag("online_sticker_item_openmoji_1F525").assertIsDisplayed()

            // 2. Type "camera" into the Online Sticker Search bar -> 3 clean source chips & results render
            composeTestRule.onNodeWithTag("online_sticker_search_input").assertIsDisplayed()
            composeTestRule.onNodeWithTag("online_sticker_search_input").performTextInput("camera")
            testDispatcher.scheduler.advanceTimeBy(450L)
            awaitCondition {
                !vm.uiState.value.isSearchingOnlineStickers &&
                    vm.uiState.value.stickerSearchResults.any { it.source == OnlineStickerSource.OPENMOJI } &&
                    vm.uiState.value.stickerSearchResults.any { it.source == OnlineStickerSource.NOTO_EMOJI } &&
                    vm.uiState.value.stickerSearchResults.any { it.source == OnlineStickerSource.FLUENT_TWEMOJI } &&
                    vm.uiState.value.stickerSearchResults.any { it.source == OnlineStickerSource.WIKIMEDIA }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("online_source_all").assertIsDisplayed()
            composeTestRule.onNodeWithTag("online_source_stickers_emoji").assertIsDisplayed()
            composeTestRule.onNodeWithTag("online_source_web_cutouts").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("online_sticker_item_openmoji_1F4F7").assertIsDisplayed()

            // 2b. Tapping "Web Cutouts" filters down strictly to Wikimedia cutouts
            composeTestRule.onNodeWithTag("online_source_web_cutouts").performClick()
            awaitCondition {
                !vm.uiState.value.isSearchingOnlineStickers &&
                    vm.uiState.value.stickerSearchSourceFilter == StickerSearchFilter.WEB_CUTOUTS &&
                    vm.uiState.value.stickerSearchResults.isNotEmpty() &&
                    vm.uiState.value.stickerSearchResults.all { it.source == OnlineStickerSource.WIKIMEDIA }
            }
            composeTestRule.waitForIdle()

            // 2c. Tapping "Stickers & Emoji" filters to OpenMoji + Noto + Twemoji/Fluent + Iconify
            composeTestRule.onNodeWithTag("online_source_stickers_emoji").performClick()
            awaitCondition {
                !vm.uiState.value.isSearchingOnlineStickers &&
                    vm.uiState.value.stickerSearchSourceFilter == StickerSearchFilter.STICKERS_AND_EMOJI &&
                    vm.uiState.value.stickerSearchResults.isNotEmpty() &&
                    vm.uiState.value.stickerSearchResults.none { it.source == OnlineStickerSource.WIKIMEDIA }
            }
            composeTestRule.waitForIdle()

            // 3. Tap an online sticker tile -> downloads/caches to cacheDir/stickers/ and drops onto canvas as ImageLayer
            val layersBefore = vm.uiState.value.layers.size
            composeTestRule.onNodeWithTag("online_sticker_item_openmoji_1F4F7").performClick()
            awaitCondition { vm.uiState.value.layers.size == layersBefore + 1 }
            val activeLayer = vm.uiState.value.activeLayer!!
            assertEquals("Camera", activeLayer.name)
            assertFalse(activeLayer.isBackground)

            // 3b. Tap the star/bookmark button on an online sticker -> permanently saves into "My Cutouts" without clearing search
            val cutoutsBefore = vm.uiState.value.savedCutouts.size
            composeTestRule.onNodeWithTag("save_online_sticker_openmoji_1F4F7").performClick()
            awaitCondition {
                vm.uiState.value.savedCutouts.size == cutoutsBefore + 1 &&
                    "openmoji_1F4F7" in vm.uiState.value.savedOnlineStickerIds
            }
            assertEquals("camera", vm.uiState.value.stickerSearchQuery)

            // 3c. Tapping a one-tap ONLINE search tag pill ("Fire") immediately runs a live online search
            composeTestRule.onNodeWithTag("online_tag_fire").performScrollTo().performClick()
            awaitCondition {
                vm.uiState.value.stickerSearchQuery == "Fire" &&
                    !vm.uiState.value.isSearchingOnlineStickers &&
                    vm.uiState.value.stickerSearchResults.any { it.title.equals("Fire", ignoreCase = true) }
            }
            composeTestRule.waitForIdle()

            // 4. Tapping the "Trending" tag pill returns smoothly to the Trending online shelf & offline categories
            composeTestRule.onNodeWithTag("online_tag_trending").performScrollTo().performClick()
            testDispatcher.scheduler.advanceUntilIdle()
            composeTestRule.waitForIdle()
            assertEquals("", vm.uiState.value.stickerSearchQuery)
            composeTestRule.onNodeWithTag("trending_online_stickers_grid").assertIsDisplayed()
            composeTestRule.onNodeWithTag("sticker_category_shapes").performScrollTo().assertIsDisplayed()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `Trending tab automatically falls back to curated local StickerLibrary stickers when CDN is blocked without offline banner`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Simulate AI Studio web preview sandbox / offline device where outbound CDN is blocked
        StickerSearchRepository.httpFetcher = StickerHttpFetcher { _, _ ->
            throw IOException("Blocked external CDN in sandbox")
        }

        val curatedFallback = StickerSearchRepository.curatedLocalTrendingFallbackStickers()
        assertEquals(24, curatedFallback.size)

        val vm = EditorViewModel(app)
        try {
            awaitCondition { vm.uiState.value.fullWidth > 0 && vm.uiState.value.layers.isNotEmpty() }

            composeTestRule.setContent {
                ZamLabTheme {
                    EditorScreen(viewModel = vm)
                }
            }
            composeTestRule.waitForIdle()

            // Open STUDIO tab -> CDN fails -> Trending shows curated locally rendered StickerLibrary grid with zero offline banner
            composeTestRule.onNodeWithTag("tab_studio").performClick()
            testDispatcher.scheduler.advanceUntilIdle()
            composeTestRule.waitForIdle()
            awaitCondition {
                StickerSearchRepository.cachedTrendingCdnReachability() == false
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("trending_local_fallback_grid").assertIsDisplayed()
            composeTestRule.onNodeWithTag("trending_online_stickers_grid").assertDoesNotExist()
            composeTestRule.onNodeWithTag("trending_offline_banner").assertDoesNotExist()
            composeTestRule.onNodeWithTag("sticker_item_symbol_sparkles").assertIsDisplayed()

            // Tapping a local curated Trending sticker drops it straight onto the canvas as an ImageLayer
            val layersBefore = vm.uiState.value.layers.size
            composeTestRule.onNodeWithTag("sticker_item_symbol_sparkles").performClick()
            awaitCondition { vm.uiState.value.layers.size == layersBefore + 1 }
            assertEquals("Sparkles", vm.uiState.value.activeLayer?.name)
            assertFalse(vm.uiState.value.activeLayer!!.isBackground)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `STUDIO sticker status banner displays exact exception class, HTTP status code, and failing host when search fails`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        StickerSearchRepository.httpFetcher = StickerHttpFetcher { url, _ ->
            when {
                url.contains("commons.wikimedia.org") ->
                    throw HttpStatusException(403, url, "HTTP 403 Forbidden")
                else ->
                    throw EndpointRequestException(url, SSLHandshakeException("Handshake failed"))
            }
        }

        val vm = EditorViewModel(app)
        try {
            awaitCondition { vm.uiState.value.fullWidth > 0 && vm.uiState.value.layers.isNotEmpty() }

            composeTestRule.setContent {
                ZamLabTheme {
                    EditorScreen(viewModel = vm)
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("tab_studio").performClick()
            testDispatcher.scheduler.advanceUntilIdle()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("online_sticker_search_input").performTextInput("sparkle")
            testDispatcher.scheduler.advanceTimeBy(450L)
            awaitCondition {
                !vm.uiState.value.isSearchingOnlineStickers &&
                    vm.uiState.value.stickerSearchError != null
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("studio_sticker_status_banner")
                .assertIsDisplayed()
                .assertTextContains("SSLHandshakeException on cdn.jsdelivr.net", substring = true)
                .assertTextContains("HTTP 403 on commons.wikimedia.org", substring = true)
            // Matching local fallback stickers still render cleanly below the diagnostic banner
            composeTestRule.onNodeWithTag("online_sticker_results_grid").assertIsDisplayed()
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
