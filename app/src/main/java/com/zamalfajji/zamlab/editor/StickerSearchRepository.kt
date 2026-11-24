package com.zamalfajji.zamlab.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.max

/**
 * Free, keyless public sources powering Z-Effect's online sticker search inside STUDIO.
 */
enum class OnlineStickerSource(val label: String, val badge: String) {
    OPENMOJI("OpenMoji", "Emoji"),
    NOTO_EMOJI("Noto Emoji", "Noto"),
    FLUENT_TWEMOJI("3D & Gloss", "3D"),
    WIKIMEDIA("Web Cutouts", "Wiki")
}

/**
 * Curated visual styles for Z-Effect's 100% free, keyless Pollinations.ai AI Sticker Generator.
 */
enum class AiStickerStyle(val label: String, val promptTag: String) {
    DIE_CUT_VECTOR("Die-Cut Vector", "die-cut vector"),
    GLOSSY_3D("3D Glossy", "3D glossy"),
    NEON_GLOW("Neon Glow", "neon glow"),
    RETRO_BADGE("Retro Badge", "retro badge"),
    ANIME("Anime", "anime")
}

/**
 * Top-level filter chips displayed under the STUDIO online sticker search bar:
 * - [ALL]: Combines all sources (Iconify + OpenMoji + Noto + Fluent/Twemoji + Wikimedia).
 * - [STICKERS_AND_EMOJI]: Direct pre-rendered transparent PNG stickers & emoji (Iconify + OpenMoji + Noto + Fluent/Twemoji).
 * - [WEB_CUTOUTS]: Wikimedia Commons transparent web cutouts.
 */
enum class StickerSearchFilter(val label: String) {
    ALL("All"),
    STICKERS_AND_EMOJI("Stickers & Emoji"),
    WEB_CUTOUTS("Web Cutouts")
}

/**
 * Single transparent PNG sticker returned from Iconify-resolved PNG CDNs (OpenMoji, Noto,
 * Fluent 3D / Twemoji), Wikimedia Commons, or a matching local [StickerLibrary] fallback.
 */
data class OnlineStickerResult(
    val id: String,
    val title: String,
    val source: OnlineStickerSource,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val cacheKey: String,
    val builtInStickerId: String? = null,
    val fallbackUrls: List<String> = emptyList()
)

/**
 * Aggregated result from querying online sticker sources.
 */
data class StickerSearchResponse(
    val results: List<OnlineStickerResult>,
    val errorMessage: String? = null,
    val isOffline: Boolean = false,
    val usedLocalFallback: Boolean = false
)

/**
 * Typed HTTP failure preserving exact status code and URL for debug diagnostics.
 */
class HttpStatusException(
    val statusCode: Int,
    val url: String,
    message: String
) : IOException(message)

/**
 * Wraps a per-endpoint network failure with its failing URL so exact exception class and host
 * (e.g., `SSLHandshakeException on cdn.jsdelivr.net`) can be surfaced in logs and UI banners.
 */
class EndpointRequestException(
    val url: String,
    cause: Throwable
) : IOException(cause.message ?: cause.javaClass.simpleName, cause)

/**
 * Aggregates ordered per-endpoint failure summaries when every mirror of a source failed.
 */
internal class MultiEndpointFailureException(
    val endpointReasons: List<String>,
    cause: Throwable
) : IOException(endpointReasons.joinToString(" • "), cause)

/**
 * Cached download outcome ready to drop onto the canvas as an independent [ImageLayer].
 */
data class DownloadedStickerAsset(
    val bitmap: Bitmap,
    val cacheFile: File
)

/**
 * Lightweight HTTP transport abstraction so unit tests can inject deterministic responses.
 */
internal fun interface StickerHttpFetcher {
    @Throws(IOException::class)
    fun fetchBytes(url: String, maxBytes: Int): ByteArray
}

/**
 * Triple keyless public online sticker search & disk-caching repository for Z-Effect:
 *
 * 1. **Instant Featured / Trending Shelf (24 OpenMoji 618x618 PNGs)**:
 *    Immediately surfaces 24 curated popular transparent PNG stickers (fire, crown, lightning,
 *    sparkle, camera, lens, neon, heart, star, sunglasses, trophy, rocket, etc.) before typing.
 * 2. **Source 1 — OpenMoji Full Catalog + Keyword Synonyms (jsDelivr CDN)**:
 *    Fetches & caches `openmoji.json` in memory and `context.cacheDir/stickers/` so repeat
 *    searches execute in <10ms across `annotation`, `tags`, `openmoji_tags`, `group`, and `subgroups`.
 * 3. **Source 2 — Fluent Emoji 3D & Twemoji Flat Transparent PNGs (jsDelivr CDN)**:
 *    Matches glossy 3D Microsoft Fluent Emoji PNGs plus crisp Twemoji PNG cutouts via jsDelivr.
 * 4. **Source 3 — Wikimedia Commons Transparent PNG Cutouts**:
 *    Queries `gsrnamespace=6`, `filetype:bitmap+<query>+transparent+png`, `gsrlimit=24`,
 *    `iiurlwidth=512`, and strictly filters for valid `.png` results.
 * 5. **Local Disk Cache (`context.cacheDir/stickers/`)**:
 *    Persists downloaded sticker PNGs so tapped stickers load once and drop instantaneously.
 */
object StickerSearchRepository {

    private const val TAG = "StickerSearchRepo"

    const val OPENMOJI_CATALOG_URL =
        "https://cdn.jsdelivr.net/npm/openmoji@15.1.0/data/openmoji.json"
    const val OPENMOJI_CATALOG_FALLBACK_URL =
        "https://unpkg.com/openmoji@15.1.0/data/openmoji.json"
    const val OPENMOJI_CATALOG_GITHUB_URL =
        "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/data/openmoji.json"
    const val OPENMOJI_PNG_BASE_URL =
        "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/"
    const val OPENMOJI_PNG_FALLBACK_BASE_URL =
        "https://cdn.jsdelivr.net/gh/hfg-gmuend/openmoji@master/color/618x618/"
    const val OPENMOJI_PNG_GITHUB_BASE_URL =
        "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/"
    const val FLUENT_3D_BASE_URL =
        "https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@main/assets/"
    const val FLUENT_3D_GITHUB_BASE_URL =
        "https://raw.githubusercontent.com/microsoft/fluentui-emoji/main/assets/"
    const val TWEMOJI_PNG_BASE_URL =
        "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72/"
    const val TWEMOJI_PNG_GITHUB_BASE_URL =
        "https://raw.githubusercontent.com/jdecked/twemoji/v15.1.0/assets/72x72/"
    const val NOTO_EMOJI_PNG_BASE_URL =
        "https://raw.githubusercontent.com/googlefonts/noto-emoji/main/png/128/emoji_u"
    const val NOTO_EMOJI_PNG_JSDELIVR_BASE_URL =
        "https://cdn.jsdelivr.net/gh/googlefonts/noto-emoji@main/png/128/emoji_u"
    const val ICONIFY_SEARCH_API_BASE_URL =
        "https://api.iconify.design/search"
    const val ICONIFY_SEARCH_PREFIXES =
        "noto,twemoji,openmoji,fluent-emoji-flat,fluent-emoji"
    const val WIKIMEDIA_API_BASE_URL =
        "https://commons.wikimedia.org/w/api.php"
    const val POLLINATIONS_IMAGE_BASE_URL =
        "https://image.pollinations.ai/prompt/"

    private const val STICKERS_CACHE_DIR = "stickers"
    private const val OPENMOJI_CATALOG_CACHE_FILE = "openmoji_catalog_15_1_0.json"
    const val USER_AGENT =
        "ZEffectPhotoEditor/1.3 (https://github.com/zamelalfajji-droid/ZamLab; Android)"
    const val ACCEPT_JSON = "application/json"
    const val ACCEPT_IMAGE = "image/png, image/*"
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 10_000
    const val AI_STICKER_TIMEOUT_MS = 25_000
    const val MAX_CONCURRENT_THUMBNAIL_DOWNLOADS = 4
    const val MAX_CONCURRENT_WIKIMEDIA_DOWNLOADS = 2
    const val WIKIMEDIA_THUMB_WIDTH_PX = 256
    private const val DEFAULT_DOWNLOAD_RETRY_BACKOFF_MS = 350L
    private const val DEFAULT_WIKIMEDIA_DOWNLOAD_DELAY_MS = 150L
    private const val MAX_REDIRECTS = 5
    private const val CDN_PROBE_TIMEOUT_MS = 10_000L
    private const val CDN_PROBE_CACHE_TTL_MS = 45_000L
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024 // openmoji.json is ~4MB
    private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    private const val ICONIFY_MAX_RESULTS = 32
    private const val OPENMOJI_MAX_RESULTS = 24
    private const val NOTO_EMOJI_MAX_RESULTS = 24
    private const val FLUENT_TWEMOJI_MAX_RESULTS = 24
    private const val WIKIMEDIA_MAX_RESULTS = 24

    private val ALLOWED_HOSTS = setOf(
        "api.iconify.design",
        "cdn.jsdelivr.net",
        "unpkg.com",
        "raw.githubusercontent.com",
        "commons.wikimedia.org",
        "upload.wikimedia.org",
        "image.pollinations.ai"
    )

    internal data class OpenMojiEntry(
        val hexcode: String,
        val annotation: String,
        val searchHaystack: String,
        val hasSkintone: Boolean
    )

    private data class Fluent3dStickerSpec(
        val slug: String,
        val title: String,
        val assetFolder: String,
        val fileSlug: String,
        val keywords: String
    ) {
        val searchHaystack: String = "${title.lowercase()} ${keywords.lowercase()}"

        fun toOnlineResult(): OnlineStickerResult {
            val encodedFolder = assetFolder.replace(" ", "%20")
            val pngUrl = "$FLUENT_3D_BASE_URL$encodedFolder/3D/${fileSlug}_3d.png"
            return OnlineStickerResult(
                id = "fluent3d_$slug",
                title = title,
                source = OnlineStickerSource.FLUENT_TWEMOJI,
                thumbnailUrl = pngUrl,
                downloadUrl = pngUrl,
                cacheKey = "fluent3d_$slug"
            )
        }
    }

    /**
     * 24 curated trending OpenMoji 618x618 transparent PNG stickers shown immediately when the
     * user opens STUDIO -> Stickers with an empty search box.
     */
    val FEATURED_TRENDING_STICKERS: List<OnlineStickerResult> = listOf(
        trendingOpenMoji("1F525", "Fire"),
        trendingOpenMoji("1F451", "Crown"),
        trendingOpenMoji("26A1", "Lightning"),
        trendingOpenMoji("2728", "Sparkle"),
        trendingOpenMoji("1F4F7", "Camera"),
        trendingOpenMoji("1F4F8", "Lens Flash"),
        trendingOpenMoji("1F31F", "Neon Star"),
        trendingOpenMoji("2764", "Heart"),
        trendingOpenMoji("2B50", "Gold Star"),
        trendingOpenMoji("1F576", "Sunglasses"),
        trendingOpenMoji("1F3C6", "Trophy"),
        trendingOpenMoji("1F680", "Rocket"),
        trendingOpenMoji("1F496", "Neon Heart"),
        trendingOpenMoji("1F48E", "Diamond"),
        trendingOpenMoji("1F4AB", "Glow Orbit"),
        trendingOpenMoji("1F308", "Rainbow"),
        trendingOpenMoji("1F98B", "Butterfly"),
        trendingOpenMoji("1F338", "Blossom"),
        trendingOpenMoji("1F3AC", "Cinema"),
        trendingOpenMoji("1F39E", "Film Lens"),
        trendingOpenMoji("1F47E", "Retro Neon"),
        trendingOpenMoji("1F389", "Confetti"),
        trendingOpenMoji("1F4AF", "100%"),
        trendingOpenMoji("1F60E", "Cool Shades")
    )

    fun featuredTrendingStickers(): List<OnlineStickerResult> = FEATURED_TRENDING_STICKERS

    /**
     * Rich curated set of 24 locally rendered transparent stickers from [StickerLibrary] used as an
     * automatic, zero-latency fallback for the Trending tab whenever external CDN requests fail,
     * time out, or are blocked (e.g., offline mode or sandboxed preview environments).
     */
    private val CURATED_LOCAL_TRENDING_IDS: List<String> = listOf(
        "symbol_sparkles",
        "symbol_crown",
        "symbol_lightning",
        "symbol_heart",
        "symbol_aperture",
        "symbol_north_star",
        "flare_golden_sunburst",
        "flare_anamorphic_cyan",
        "flare_star_glint",
        "flare_prism_leak",
        "flare_bokeh_orbs",
        "flare_amber_halo",
        "shape_gold_ring",
        "shape_starburst",
        "shape_diamond_prism",
        "shape_neon_capsule",
        "badge_pro_gold",
        "badge_raw_stamp",
        "badge_film_35mm",
        "badge_rec_live",
        "frame_polaroid",
        "frame_viewfinder",
        "frame_film_strip",
        "arrow_curved_swoosh"
    )

    fun curatedLocalTrendingFallbackStickers(): List<BuiltInSticker> {
        val byId = StickerLibrary.allBuiltInStickers.associateBy { it.id }
        val curated = CURATED_LOCAL_TRENDING_IDS.mapNotNull { byId[it] }
        return if (curated.isNotEmpty()) curated else StickerLibrary.allBuiltInStickers.take(24)
    }

    private fun trendingOpenMoji(hexcode: String, title: String): OnlineStickerResult {
        val url = "$OPENMOJI_PNG_BASE_URL$hexcode.png"
        return OnlineStickerResult(
            id = "openmoji_$hexcode",
            title = title,
            source = OnlineStickerSource.OPENMOJI,
            thumbnailUrl = url,
            downloadUrl = url,
            cacheKey = "openmoji_$hexcode"
        )
    }

    /**
     * Curated Microsoft Fluent Emoji 3D glossy transparent PNG catalog hosted on jsDelivr CDN.
     */
    private val FLUENT_3D_CATALOG: List<Fluent3dStickerSpec> = listOf(
        Fluent3dStickerSpec("fire", "Fire 3D", "Fire", "fire", "fire flame hot lit burn glow spark"),
        Fluent3dStickerSpec("crown", "Crown 3D", "Crown", "crown", "crown king queen royal gold tiara vip"),
        Fluent3dStickerSpec("lightning", "Lightning 3D", "High voltage", "high_voltage", "lightning bolt electric zap energy neon thunder voltage"),
        Fluent3dStickerSpec("sparkles", "Sparkle 3D", "Sparkles", "sparkles", "sparkle sparkles magic glitter shine neon glow star"),
        Fluent3dStickerSpec("camera", "Camera 3D", "Camera", "camera", "camera photo lens photography shutter snapshot"),
        Fluent3dStickerSpec("camera_flash", "Flash Camera 3D", "Camera with flash", "camera_with_flash", "camera flash lens photo snapshot studio"),
        Fluent3dStickerSpec("movie_camera", "Cinema Cam 3D", "Movie camera", "movie_camera", "camera cinema film movie lens video"),
        Fluent3dStickerSpec("film_frames", "Film Strip 3D", "Film frames", "film_frames", "film cinema lens photo retro strip"),
        Fluent3dStickerSpec("magnifier", "Lens Glass 3D", "Magnifying glass tilted left", "magnifying_glass_tilted_left", "lens zoom glass search focus optic"),
        Fluent3dStickerSpec("glowing_star", "Neon Star 3D", "Glowing star", "glowing_star", "star neon glow bright sparkle shine"),
        Fluent3dStickerSpec("star", "Gold Star 3D", "Star", "star", "star gold favorite rating bright"),
        Fluent3dStickerSpec("shooting_star", "Shooting Star 3D", "Shooting star", "shooting_star", "star neon comet night magic trail"),
        Fluent3dStickerSpec("red_heart", "Heart 3D", "Red heart", "red_heart", "heart love red romance passion"),
        Fluent3dStickerSpec("sparkling_heart", "Neon Heart 3D", "Sparkling heart", "sparkling_heart", "heart neon pink sparkle love glow"),
        Fluent3dStickerSpec("heart_on_fire", "Fire Heart 3D", "Heart on fire", "heart_on_fire", "heart fire flame passion hot love"),
        Fluent3dStickerSpec("sunglasses", "Sunglasses 3D", "Sunglasses", "sunglasses", "sunglasses shades cool summer eyewear glasses"),
        Fluent3dStickerSpec("cool_face", "Cool Shades 3D", "Smiling face with sunglasses", "smiling_face_with_sunglasses", "sunglasses shades cool face smile"),
        Fluent3dStickerSpec("trophy", "Trophy 3D", "Trophy", "trophy", "trophy award winner gold cup prize champion"),
        Fluent3dStickerSpec("medal", "Gold Medal 3D", "1st place medal", "1st_place_medal", "trophy medal gold winner award first"),
        Fluent3dStickerSpec("rocket", "Rocket 3D", "Rocket", "rocket", "rocket space launch ship fast boost fire"),
        Fluent3dStickerSpec("gem_stone", "Diamond 3D", "Gem stone", "gem_stone", "diamond gem crystal jewel luxury neon cyan"),
        Fluent3dStickerSpec("rainbow", "Rainbow 3D", "Rainbow", "rainbow", "rainbow color pride sky vibrant neon"),
        Fluent3dStickerSpec("butterfly", "Butterfly 3D", "Butterfly", "butterfly", "butterfly wings nature aesthetic aesthetic blue"),
        Fluent3dStickerSpec("cherry_blossom", "Blossom 3D", "Cherry blossom", "cherry_blossom", "flower blossom sakura pink spring floral"),
        Fluent3dStickerSpec("rose", "Rose 3D", "Rose", "rose", "flower rose red floral love plant"),
        Fluent3dStickerSpec("sunflower", "Sunflower 3D", "Sunflower", "sunflower", "flower sunflower yellow summer nature"),
        Fluent3dStickerSpec("clapper_board", "Clapper 3D", "Clapper board", "clapper_board", "cinema film movie action studio director"),
        Fluent3dStickerSpec("party_popper", "Confetti 3D", "Party popper", "party_popper", "party confetti celebration fun birthday"),
        Fluent3dStickerSpec("hundred_points", "100% 3D", "Hundred points", "hundred_points", "100 score perfect badge stamp red"),
        Fluent3dStickerSpec("collision", "Boom 3D", "Collision", "collision", "boom comic burst pow explode flash neon"),
        Fluent3dStickerSpec("dizzy", "Glow Orbit 3D", "Dizzy", "dizzy", "neon glow star orbit swirl sparkle"),
        Fluent3dStickerSpec("sun", "Sun 3D", "Sun", "sun", "sun sunshine summer bright warm flare"),
        Fluent3dStickerSpec("crescent_moon", "Moon 3D", "Crescent moon", "crescent_moon", "moon night sky aesthetic crescent"),
        Fluent3dStickerSpec("magic_wand", "Magic Wand 3D", "Magic wand", "magic_wand", "magic wand sparkle star neon wizard"),
        Fluent3dStickerSpec("crystal_ball", "Crystal Ball 3D", "Crystal ball", "crystal_ball", "crystal neon magic purple orb glow"),
        Fluent3dStickerSpec("alien_monster", "Retro Neon 3D", "Alien monster", "alien_monster", "neon retro arcade gaming pixel alien purple"),
        Fluent3dStickerSpec("video_game", "Gamepad 3D", "Video game", "video_game", "game gaming neon controller arcade play"),
        Fluent3dStickerSpec("musical_notes", "Music Notes 3D", "Musical notes", "musical_notes", "music note audio song rhythm sound"),
        Fluent3dStickerSpec("headphone", "Headphones 3D", "Headphone", "headphone", "music headphones audio sound dj beat"),
        Fluent3dStickerSpec("artist_palette", "Palette 3D", "Artist palette", "artist_palette", "art paint color creative studio draw"),
        Fluent3dStickerSpec("light_bulb", "Idea Bulb 3D", "Light bulb", "light_bulb", "light bulb idea bright neon glow"),
        Fluent3dStickerSpec("framed_picture", "Art Frame 3D", "Framed picture", "framed_picture", "photo picture frame art gallery"),
        Fluent3dStickerSpec("balloon", "Balloon 3D", "Balloon", "balloon", "balloon party celebration birthday red"),
        Fluent3dStickerSpec("wrapped_gift", "Gift Box 3D", "Wrapped gift", "wrapped_gift", "gift present party birthday box"),
        Fluent3dStickerSpec("birthday_cake", "Cake 3D", "Birthday cake", "birthday_cake", "cake birthday party celebration sweet"),
        Fluent3dStickerSpec("hot_beverage", "Coffee 3D", "Hot beverage", "hot_beverage", "coffee tea cafe drink morning cup"),
        Fluent3dStickerSpec("pizza", "Pizza 3D", "Pizza", "pizza", "pizza food slice party cheese"),
        Fluent3dStickerSpec("airplane", "Airplane 3D", "Airplane", "airplane", "travel plane flight trip vacation sky"),
        Fluent3dStickerSpec("palm_tree", "Palm Tree 3D", "Palm tree", "palm_tree", "summer beach tropical palm vacation"),
        Fluent3dStickerSpec("snowflake", "Snowflake 3D", "Snowflake", "snowflake", "winter snow ice cold frost crystal"),
        Fluent3dStickerSpec("water_wave", "Ocean Wave 3D", "Water wave", "water_wave", "wave ocean sea water blue surf"),
        Fluent3dStickerSpec("cat_face", "Cat 3D", "Cat face", "cat_face", "cat kitten pet cute animal meow"),
        Fluent3dStickerSpec("dog_face", "Dog 3D", "Dog face", "dog_face", "dog puppy pet cute animal woof"),
        Fluent3dStickerSpec("unicorn", "Unicorn 3D", "Unicorn", "unicorn", "unicorn magic rainbow fantasy neon horse"),
        Fluent3dStickerSpec("dragon", "Dragon 3D", "Dragon", "dragon", "dragon fire fantasy mythic green"),
        Fluent3dStickerSpec("check_mark", "Verified 3D", "Check mark button", "check_mark_button", "check verified badge ok green tick"),
        Fluent3dStickerSpec("speech_balloon", "Speech Bubble 3D", "Speech balloon", "speech_balloon", "speech chat text bubble comic talk")
    )

    /**
     * Domain-aware keyword synonyms so creative/photo search terms match OpenMoji & Fluent 3D catalogs richly.
     */
    private val QUERY_SYNONYMS: Map<String, List<String>> = mapOf(
        "lens" to listOf("camera", "magnifying", "photo", "film", "optical", "telescope"),
        "neon" to listOf("glowing", "sparkle", "sparkles", "lightning", "electric", "star", "alien", "crystal"),
        "fire" to listOf("flame", "burn", "hot", "spark", "explosion"),
        "flame" to listOf("fire", "burn", "hot"),
        "crown" to listOf("king", "queen", "royal", "tiara", "gold"),
        "lightning" to listOf("high voltage", "bolt", "zap", "thunder", "electric", "storm"),
        "bolt" to listOf("high voltage", "lightning", "electric"),
        "sparkle" to listOf("sparkles", "glitter", "star", "glowing", "magic", "shine"),
        "camera" to listOf("photo", "flash", "film", "movie", "snapshot", "lens"),
        "photo" to listOf("camera", "picture", "frame", "film", "snapshot"),
        "heart" to listOf("love", "romance", "valentine", "sparkling"),
        "love" to listOf("heart", "kiss", "romance", "cupid"),
        "star" to listOf("sparkle", "glowing", "shooting", "comet", "gold"),
        "sunglasses" to listOf("shades", "cool", "glasses", "eyewear"),
        "shades" to listOf("sunglasses", "cool", "glasses"),
        "trophy" to listOf("award", "medal", "winner", "prize", "cup", "champion"),
        "rocket" to listOf("space", "spaceship", "launch", "comet", "galaxy"),
        "magic" to listOf("wand", "sparkles", "crystal", "fairy", "unicorn"),
        "flower" to listOf("blossom", "rose", "sunflower", "tulip", "floral", "hibiscus"),
        "music" to listOf("musical", "note", "headphone", "guitar", "microphone"),
        "party" to listOf("confetti", "popper", "balloon", "cake", "celebration"),
        "summer" to listOf("sun", "palm", "beach", "sunglasses", "wave"),
        "winter" to listOf("snow", "snowflake", "ice", "cold"),
        "cool" to listOf("sunglasses", "fire", "crown", "100"),
        "badge" to listOf("medal", "trophy", "check", "hundred", "crown"),
        "cat" to listOf("kitten", "feline", "paw"),
        "dog" to listOf("puppy", "paw", "canine"),
        "wings" to listOf("butterfly", "angel", "feather", "bird", "dove", "wing"),
        "wing" to listOf("wings", "angel", "feather", "bird", "butterfly"),
        "glasses" to listOf("sunglasses", "eyeglasses", "shades", "eyewear", "cool", "goggles"),
        "car" to listOf("automobile", "racing", "sport", "vehicle", "taxi", "police"),
        "smoke" to listOf("dashing", "fog", "cloud", "fire", "wind", "vapor", "mist")
    )

    /**
     * Keyword tags for built-in [StickerLibrary] stickers so local fallback search returns
     * relevant studio stickers whenever all online endpoints are unreachable.
     */
    private val LOCAL_STICKER_KEYWORDS: Map<String, String> = mapOf(
        "shape_gold_ring" to "ring circle gold halo shape round frame neon",
        "shape_amber_blob" to "blob organic amber gold splash paint orange warm fire flame",
        "shape_hexagon" to "hexagon hex polygon geometric shape tech badge crystal",
        "shape_diamond_prism" to "diamond prism gem crystal jewel shape neon sparkle",
        "shape_neon_capsule" to "neon capsule pill cyan glow futuristic cyber bar",
        "shape_starburst" to "star starburst burst explosion comic boom gold badge sparkle sun",
        "arrow_bold_right" to "arrow pointer right direction next sign",
        "arrow_curved_swoosh" to "arrow curved swoosh swirl point callout hand",
        "arrow_double_chevron" to "arrow chevron fast forward boost speed play",
        "arrow_studio_pointer" to "arrow pointer cursor click studio callout",
        "arrow_loop_arc" to "arrow loop arc curly doodle swirl",
        "arrow_drop_pin" to "pin focus location map target drop marker arrow",
        "flare_anamorphic_cyan" to "flare light anamorphic cyan neon lens cinema glow laser streak blue",
        "flare_golden_sunburst" to "flare sun sunburst sunshine gold golden warm summer sunset fire glow light",
        "flare_prism_leak" to "flare prism leak rainbow color light film retro vintage aesthetic",
        "flare_bokeh_orbs" to "bokeh orbs circles blur lights night magic sparkle party aesthetic glow",
        "flare_star_glint" to "star glint sparkle twinkle shine diamond bright glow flare",
        "flare_amber_halo" to "halo amber warm sunset sun glow ring golden fire flare",
        "badge_raw_stamp" to "badge raw photo camera stamp pro photography dng",
        "badge_film_35mm" to "badge 35mm film camera analog retro vintage cinema photo lens",
        "badge_pro_gold" to "badge pro gold vip premium award trophy winner 100",
        "badge_new_pill" to "badge new fresh pill tag label hot drop",
        "badge_rec_live" to "badge rec live record video camera movie cinema red stream",
        "badge_hdr_plus" to "badge hdr hd photo camera bright crisp pro lens",
        "frame_polaroid" to "frame polaroid instant photo picture retro vintage border gallery",
        "frame_viewfinder" to "frame viewfinder camera focus lens shutter photo target rec",
        "frame_gold_hairline" to "frame gold hairline trim border luxury elegant minimal",
        "frame_film_strip" to "frame film strip 35mm cinema movie analog retro photo",
        "frame_dashed_trim" to "frame dashed crop trim border stamp stitch outline",
        "frame_gallery_matte" to "frame matte gallery museum white border art photo",
        "symbol_sparkles" to "sparkle sparkles magic glitter star shine glow neon twinkle",
        "symbol_heart" to "heart love romance like pink red valentine cute",
        "symbol_lightning" to "lightning bolt electric zap energy thunder storm neon flash power",
        "symbol_crown" to "crown king queen royal gold tiara vip winner trophy",
        "symbol_aperture" to "aperture camera lens shutter iris photo photography optical focus",
        "symbol_north_star" to "star north sparkle compass night sky magic gold bright"
    )

    private val catalogMutex = Mutex()
    private val thumbnailDownloadSemaphore = Semaphore(MAX_CONCURRENT_THUMBNAIL_DOWNLOADS)
    private val wikimediaDownloadSemaphore = Semaphore(MAX_CONCURRENT_WIKIMEDIA_DOWNLOADS)
    @Volatile
    private var cachedOpenMojiCatalog: List<OpenMojiEntry>? = null
    @Volatile
    private var preferUnpkgOpenMojiMirror: Boolean = false
    @Volatile
    private var lastTrendingCdnReachable: Boolean? = null
    @Volatile
    private var lastTrendingCdnProbeAtMs: Long = 0L

    @VisibleForTesting
    internal var downloadRetryBackoffMs: Long = DEFAULT_DOWNLOAD_RETRY_BACKOFF_MS

    @VisibleForTesting
    internal var wikimediaDownloadDelayMs: Long = DEFAULT_WIKIMEDIA_DOWNLOAD_DELAY_MS

    @VisibleForTesting
    internal var httpFetcher: StickerHttpFetcher = StickerHttpFetcher { url, maxBytes ->
        defaultHttpGet(url, maxBytes)
    }

    @VisibleForTesting
    internal var aiStickerHttpFetcher: StickerHttpFetcher = StickerHttpFetcher { url, maxBytes ->
        defaultHttpGet(
            urlString = url,
            maxBytes = maxBytes,
            connectTimeoutMs = AI_STICKER_TIMEOUT_MS,
            readTimeoutMs = AI_STICKER_TIMEOUT_MS
        )
    }

    @VisibleForTesting
    internal fun resetCachesForTesting() {
        cachedOpenMojiCatalog = null
        preferUnpkgOpenMojiMirror = false
        lastTrendingCdnReachable = null
        lastTrendingCdnProbeAtMs = 0L
        downloadRetryBackoffMs = DEFAULT_DOWNLOAD_RETRY_BACKOFF_MS
        wikimediaDownloadDelayMs = DEFAULT_WIKIMEDIA_DOWNLOAD_DELAY_MS
        httpFetcher = StickerHttpFetcher { url, maxBytes -> defaultHttpGet(url, maxBytes) }
        aiStickerHttpFetcher = StickerHttpFetcher { url, maxBytes ->
            defaultHttpGet(
                urlString = url,
                maxBytes = maxBytes,
                connectTimeoutMs = AI_STICKER_TIMEOUT_MS,
                readTimeoutMs = AI_STICKER_TIMEOUT_MS
            )
        }
    }

    fun hasCachedTrendingCdnPng(context: Context): Boolean =
        FEATURED_TRENDING_STICKERS.any { sticker ->
            val f = cacheFileForSticker(context, sticker)
            f.exists() && f.length() > 0L
        }

    fun cachedTrendingCdnReachability(): Boolean? {
        val cached = lastTrendingCdnReachable ?: return null
        val ageMs = System.currentTimeMillis() - lastTrendingCdnProbeAtMs
        return if (ageMs in 0..CDN_PROBE_CACHE_TTL_MS) cached else null
    }

    fun markTrendingCdnUnreachable() {
        lastTrendingCdnReachable = false
        lastTrendingCdnProbeAtMs = System.currentTimeMillis()
    }

    /**
     * Verifies whether external OpenMoji PNG CDNs (jsDelivr, unpkg, or GitHub raw) are reachable
     * by attempting a direct HTTP fetch without strict ConnectivityManager pre-checks.
     * Returns `false` cleanly on timeout or network failure so Trending falls back to local
     * [StickerLibrary] stickers without blank placeholders or offline banners.
     */
    suspend fun probeTrendingCdnAvailability(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (hasCachedTrendingCdnPng(context)) {
            lastTrendingCdnReachable = true
            lastTrendingCdnProbeAtMs = System.currentTimeMillis()
            return@withContext true
        }
        cachedTrendingCdnReachability()?.let { return@withContext it }

        val probeSticker = FEATURED_TRENDING_STICKERS.first()
        val fetched = withTimeoutOrNull(CDN_PROBE_TIMEOUT_MS) {
            ensureStickerFileCached(context, probeSticker, maxRetries = 0)
        }
        val reachable = fetched != null && fetched.exists() && fetched.length() > 0L
        if (!reachable) {
            Log.d(TAG, "Trending CDN probe did not succeed for ${probeSticker.downloadUrl}; using local StickerLibrary fallback")
        }
        lastTrendingCdnReachable = reachable
        lastTrendingCdnProbeAtMs = System.currentTimeMillis()
        reachable
    }

    fun stickersCacheDir(context: Context): File =
        File(context.cacheDir, STICKERS_CACHE_DIR).apply { mkdirs() }

    /**
     * Matches built-in locally rendered [StickerLibrary] stickers for a query (including synonyms).
     * Used automatically when all online sticker endpoints fail so users still get usable stickers.
     */
    fun searchLocalStickerLibrary(
        rawQuery: String,
        limit: Int = OPENMOJI_MAX_RESULTS
    ): List<OnlineStickerResult> {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val tokens = normalizeQueryTokens(q)
        if (tokens.isEmpty()) return emptyList()
        val tokenStemSets = tokens.map { stemWordVariants(it) }
        val synonyms = expandQuerySynonyms(q)

        val scored = ArrayList<Pair<Int, BuiltInSticker>>()
        for (sticker in StickerLibrary.allBuiltInStickers) {
            val nameLower = sticker.name.lowercase()
            val categoryLower = sticker.category.label.lowercase()
            val extraKeywords = LOCAL_STICKER_KEYWORDS[sticker.id].orEmpty()
            val haystack = "$nameLower $categoryLower ${sticker.id.replace('_', ' ')} $extraKeywords"

            val matchedCount = tokenStemSets.count { variants -> variants.any { v -> haystack.contains(v) } }
            val allTokensMatch = matchedCount == tokens.size
            val score = if (allTokensMatch) {
                when {
                    nameLower == q -> 100
                    nameLower.startsWith(q) -> 85
                    nameLower.contains(q) -> 70
                    categoryLower.contains(q) -> 55
                    else -> 40
                }
            } else if (tokens.size >= 2 && matchedCount > 0) {
                val nameHits = tokenStemSets.count { variants -> variants.any { v -> nameLower.contains(v) } }
                if (nameHits > 0) 28 + (10 * matchedCount) / tokens.size else 20 + (8 * matchedCount) / tokens.size
            } else if (synonyms.isNotEmpty()) {
                val synHit = synonyms.firstOrNull { syn -> haystack.contains(syn) }
                if (synHit != null) {
                    if (nameLower.contains(synHit)) 30 else 20
                } else {
                    0
                }
            } else {
                0
            }

            if (score > 0) {
                scored.add(score to sticker)
            }
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<Int, BuiltInSticker>> { it.first }
                    .thenBy { it.second.name.length }
            )
            .take(limit)
            .map { (_, sticker) ->
                OnlineStickerResult(
                    id = "local_${sticker.id}",
                    title = sticker.name,
                    source = OnlineStickerSource.OPENMOJI,
                    thumbnailUrl = "local://sticker/${sticker.id}",
                    downloadUrl = "local://sticker/${sticker.id}",
                    cacheKey = "local_${sticker.id}",
                    builtInStickerId = sticker.id
                )
            }
    }

    /**
     * Built-in Unicode hex lookup for popular Iconify emoji slugs so Iconify search matches
     * resolve directly to pre-rendered transparent PNG CDN URLs even if `openmoji.json` is not
     * yet cached or fails to download.
     */
    private val BUILTIN_SLUG_TO_HEX: Map<String, String> = mapOf(
        "fire" to "1F525",
        "flame" to "1F525",
        "crown" to "1F451",
        "high-voltage" to "26A1",
        "lightning" to "26A1",
        "sparkles" to "2728",
        "sparkle" to "2728",
        "camera" to "1F4F7",
        "camera-with-flash" to "1F4F8",
        "glowing-star" to "1F31F",
        "red-heart" to "2764",
        "heart" to "2764",
        "star" to "2B50",
        "shooting-star" to "1F320",
        "sunglasses" to "1F576",
        "smiling-face-with-sunglasses" to "1F60E",
        "trophy" to "1F3C6",
        "1st-place-medal" to "1F947",
        "rocket" to "1F680",
        "sparkling-heart" to "1F496",
        "heart-on-fire" to "2764-FE0F-200D-1F525",
        "gem-stone" to "1F48E",
        "diamond" to "1F48E",
        "dizzy" to "1F4AB",
        "rainbow" to "1F308",
        "butterfly" to "1F98B",
        "cherry-blossom" to "1F338",
        "rose" to "1F339",
        "sunflower" to "1F33B",
        "clapper-board" to "1F3AC",
        "movie-camera" to "1F3A5",
        "film-frames" to "1F39E",
        "magnifying-glass-tilted-left" to "1F50D",
        "alien-monster" to "1F47E",
        "party-popper" to "1F389",
        "hundred-points" to "1F4AF",
        "collision" to "1F4A5",
        "sun" to "2600",
        "crescent-moon" to "1F319",
        "magic-wand" to "1FA84",
        "crystal-ball" to "1F52E",
        "video-game" to "1F3AE",
        "musical-notes" to "1F3B6",
        "headphone" to "1F3A7",
        "artist-palette" to "1F3A8",
        "light-bulb" to "1F4A1",
        "framed-picture" to "1F5BC",
        "balloon" to "1F388",
        "wrapped-gift" to "1F381",
        "birthday-cake" to "1F382",
        "hot-beverage" to "2615",
        "pizza" to "1F355",
        "airplane" to "2708",
        "palm-tree" to "1F334",
        "snowflake" to "2744",
        "water-wave" to "1F30A",
        "cat-face" to "1F431",
        "dog-face" to "1F436",
        "unicorn" to "1F984",
        "dragon" to "1F409",
        "check-mark-button" to "2705",
        "speech-balloon" to "1F4AC",
        "wing" to "1FABD",
        "feather" to "1FAB6",
        "glasses" to "1F453",
        "eyeglasses" to "1F453",
        "goggles" to "1F97D",
        "dashing-away" to "1F4A8",
        "smoke" to "1F4A8",
        "fog" to "1F32B",
        "cloud" to "2601",
        "racing-car" to "1F3CE",
        "automobile" to "1F697",
        "sport-utility-vehicle" to "1F699",
        "car" to "1F697"
    )

    /**
     * Searches all free keyless sources in parallel according to [filter]:
     * - [StickerSearchFilter.ALL]: Iconify search API + OpenMoji catalog (OpenMoji, Noto 512px,
     *   Fluent 3D/Twemoji PNG CDNs) + Wikimedia Commons 256px transparent PNG/SVG cutouts.
     * - [StickerSearchFilter.STICKERS_AND_EMOJI]: Iconify + OpenMoji + Noto + Fluent/Twemoji PNGs only.
     * - [StickerSearchFilter.WEB_CUTOUTS]: Wikimedia Commons cutouts only.
     *
     * Never aborts early based on `ConnectivityManager` / `NET_CAPABILITY_VALIDATED` — always
     * attempts the HTTP requests directly with 10s timeouts, isolates failures per source, logs
     * exact HTTP status or exception reasons, and falls back to matching local [StickerLibrary]
     * stickers if every requested network source fails.
     */
    suspend fun searchStickers(
        context: Context,
        rawQuery: String,
        filter: StickerSearchFilter = StickerSearchFilter.ALL
    ): StickerSearchResponse = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            return@withContext StickerSearchResponse(results = emptyList())
        }

        val wantsStickersAndEmoji =
            filter == StickerSearchFilter.ALL || filter == StickerSearchFilter.STICKERS_AND_EMOJI
        val wantsWebCutouts =
            filter == StickerSearchFilter.ALL || filter == StickerSearchFilter.WEB_CUTOUTS

        coroutineScope {
            val openMojiCatalogDeferred = if (wantsStickersAndEmoji) {
                async {
                    try {
                        Result.success(getOrFetchOpenMojiCatalog(context))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "OpenMoji search failed for '$query': ${describeFailure(t)}", t)
                        Result.failure(t)
                    }
                }
            } else {
                null
            }
            val iconifyDeferred = if (wantsStickersAndEmoji) {
                async {
                    try {
                        Result.success(searchIconify(query))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "Iconify search failed for '$query': ${describeFailure(t)}", t)
                        Result.failure(t)
                    }
                }
            } else {
                null
            }
            val wikimediaDeferred = if (wantsWebCutouts) {
                async {
                    try {
                        Result.success(searchWikimediaCommons(query))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "Wikimedia Commons search failed for '$query': ${describeFailure(t)}", t)
                        Result.failure(t)
                    }
                }
            } else {
                null
            }

            val openMojiCatalogResult = openMojiCatalogDeferred?.await()
            val catalog = openMojiCatalogResult?.getOrNull()
            val iconifyResult = iconifyDeferred?.await()
            val iconifyIcons = iconifyResult?.getOrNull().orEmpty()
            val wikimediaResult = wikimediaDeferred?.await()
            val wikimediaItems = wikimediaResult?.getOrNull().orEmpty()

            val catalogFailed = wantsStickersAndEmoji && (openMojiCatalogResult?.isFailure == true)
            val iconifyFailed = wantsStickersAndEmoji && (iconifyResult?.isFailure == true)
            val stickersGroupFailed = wantsStickersAndEmoji && catalogFailed && iconifyFailed
            val wikimediaFailed = wantsWebCutouts && (wikimediaResult?.isFailure == true)

            val allNetworkSourcesFailed = when (filter) {
                StickerSearchFilter.STICKERS_AND_EMOJI -> stickersGroupFailed
                StickerSearchFilter.WEB_CUTOUTS -> wikimediaFailed
                StickerSearchFilter.ALL -> stickersGroupFailed && wikimediaFailed
            }

            if (allNetworkSourcesFailed) {
                val diagnosticReasons = LinkedHashSet<String>()
                openMojiCatalogResult?.exceptionOrNull()?.let { err ->
                    when (err) {
                        is MultiEndpointFailureException -> err.endpointReasons.firstOrNull()?.let { diagnosticReasons.add(it) }
                        else -> diagnosticReasons.add(formatFailureReason(err, OPENMOJI_CATALOG_URL))
                    }
                }
                iconifyResult?.exceptionOrNull()?.let { err ->
                    diagnosticReasons.add(formatFailureReason(err, ICONIFY_SEARCH_API_BASE_URL))
                }
                wikimediaResult?.exceptionOrNull()?.let { err ->
                    when (err) {
                        is MultiEndpointFailureException -> err.endpointReasons.firstOrNull()?.let { diagnosticReasons.add(it) }
                        else -> diagnosticReasons.add(formatFailureReason(err, WIKIMEDIA_API_BASE_URL))
                    }
                }
                val summary = diagnosticReasons
                    .take(3)
                    .joinToString(" • ")
                    .ifEmpty { "Network error on sticker servers" }

                Log.w(TAG, "All online sticker sources failed for '$query' ($summary); falling back to local StickerLibrary")
                val localFallback = searchLocalStickerLibrary(query)
                return@coroutineScope if (localFallback.isNotEmpty()) {
                    StickerSearchResponse(
                        results = localFallback,
                        errorMessage = summary,
                        isOffline = true,
                        usedLocalFallback = true
                    )
                } else {
                    StickerSearchResponse(
                        results = emptyList(),
                        errorMessage = summary,
                        isOffline = true,
                        usedLocalFallback = false
                    )
                }
            }

            val iconifyResolvedItems = if (wantsStickersAndEmoji && iconifyIcons.isNotEmpty()) {
                resolveIconifyIconsToPngResults(iconifyIcons, catalog)
            } else {
                emptyList()
            }

            val openMojiItems = if (wantsStickersAndEmoji && catalog != null) {
                filterOpenMojiCatalog(catalog, query)
            } else {
                emptyList()
            }

            val notoEmojiItems = if (wantsStickersAndEmoji && catalog != null) {
                searchNotoEmoji(query, catalog)
            } else {
                emptyList()
            }

            val fluentTwemojiItems = if (wantsStickersAndEmoji) {
                searchFluentAndTwemoji(query, catalog)
            } else {
                emptyList()
            }

            val combined = when (filter) {
                StickerSearchFilter.STICKERS_AND_EMOJI -> interleaveSources(
                    iconifyResolvedItems,
                    fluentTwemojiItems,
                    notoEmojiItems,
                    openMojiItems
                )
                StickerSearchFilter.WEB_CUTOUTS -> wikimediaItems
                StickerSearchFilter.ALL -> interleaveSources(
                    iconifyResolvedItems,
                    fluentTwemojiItems,
                    notoEmojiItems,
                    openMojiItems,
                    wikimediaItems
                )
            }

            StickerSearchResponse(
                results = combined,
                errorMessage = null,
                isOffline = false,
                usedLocalFallback = false
            )
        }
    }

    /**
     * Builds the keyless Iconify public search API URL across Noto, Twemoji, OpenMoji,
     * Fluent Emoji Flat, and Fluent Emoji icon sets.
     */
    fun buildIconifySearchUrl(rawQuery: String): String {
        val encodedQuery = URLEncoder.encode(rawQuery.trim(), "UTF-8")
        return "$ICONIFY_SEARCH_API_BASE_URL?query=$encodedQuery&limit=$ICONIFY_MAX_RESULTS&prefixes=$ICONIFY_SEARCH_PREFIXES"
    }

    /**
     * Queries Iconify's free keyless search API on [Dispatchers.IO] and returns raw `"prefix:slug"` hits.
     */
    internal suspend fun searchIconify(query: String): List<String> = withContext(Dispatchers.IO) {
        val url = buildIconifySearchUrl(query)
        val bytes = httpFetcher.fetchBytes(url, MAX_JSON_BYTES)
        parseIconifySearchJson(bytes.toString(Charsets.UTF_8))
    }

    internal fun parseIconifySearchJson(jsonText: String): List<String> {
        val root = JSONObject(jsonText)
        val iconsArr = root.optJSONArray("icons") ?: return emptyList()
        val out = ArrayList<String>(iconsArr.length())
        for (i in 0 until iconsArr.length()) {
            val raw = iconsArr.optString(i).trim()
            if (raw.contains(':')) {
                out.add(raw)
            }
        }
        return out
    }

    /**
     * Maps Iconify `"prefix:slug"` search hits directly to pre-rendered transparent PNG CDN URLs
     * (OpenMoji 618x618, Noto 512px, Twemoji 72x72, and Microsoft Fluent Emoji 3D PNGs) so Android's
     * [BitmapFactory] decodes every sticker cleanly without ever requesting SVG payloads.
     */
    internal fun resolveIconifyIconsToPngResults(
        iconIds: List<String>,
        openMojiCatalog: List<OpenMojiEntry>? = null,
        limit: Int = ICONIFY_MAX_RESULTS
    ): List<OnlineStickerResult> {
        if (iconIds.isEmpty()) return emptyList()

        // Build O(1) lookup indexes from OpenMoji catalog & Fluent 3D catalog
        val exactSlugToHex = HashMap<String, String>()
        if (!openMojiCatalog.isNullOrEmpty()) {
            for (entry in openMojiCatalog) {
                if (entry.hasSkintone) continue
                val normSlug = normalizeToIconifySlug(entry.annotation)
                if (normSlug.isNotEmpty() && !exactSlugToHex.containsKey(normSlug)) {
                    exactSlugToHex[normSlug] = entry.hexcode
                }
            }
        }
        val fluentBySlug = HashMap<String, Fluent3dStickerSpec>()
        for (spec in FLUENT_3D_CATALOG) {
            fluentBySlug[normalizeToIconifySlug(spec.slug)] = spec
            fluentBySlug[normalizeToIconifySlug(spec.assetFolder)] = spec
            fluentBySlug[normalizeToIconifySlug(spec.fileSlug)] = spec
        }

        fun resolveHexForSlug(slug: String): String? {
            exactSlugToHex[slug]?.let { return it }
            BUILTIN_SLUG_TO_HEX[slug]?.let { return it }
            if (openMojiCatalog.isNullOrEmpty()) return null
            val slugWords = normalizeQueryTokens(slug.replace('-', ' '))
            if (slugWords.isEmpty()) return null
            val stemSets = slugWords.map { stemWordVariants(it) }
            var bestEntry: OpenMojiEntry? = null
            var bestLen = Int.MAX_VALUE
            for (entry in openMojiCatalog) {
                if (entry.hasSkintone) continue
                val annLower = entry.annotation.lowercase()
                val allInAnn = stemSets.all { variants -> variants.any { v -> annLower.contains(v) } }
                if (allInAnn && annLower.length < bestLen) {
                    bestEntry = entry
                    bestLen = annLower.length
                }
            }
            return bestEntry?.hexcode
        }

        val results = ArrayList<OnlineStickerResult>(minOf(iconIds.size, limit))
        val seenKeys = HashSet<String>()

        for (rawIconId in iconIds) {
            if (results.size >= limit) break
            val colonIdx = rawIconId.indexOf(':')
            if (colonIdx <= 0 || colonIdx >= rawIconId.lastIndex) continue
            val prefix = rawIconId.substring(0, colonIdx).trim().lowercase()
            val rawSlug = rawIconId.substring(colonIdx + 1).trim().lowercase()
            if (rawSlug.isEmpty() || rawSlug.contains("skin-tone")) continue

            val normSlug = normalizeToIconifySlug(rawSlug)
            if (normSlug.isEmpty()) continue
            val prettyTitle = normSlug
                .split('-')
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                .ifEmpty { "Sticker" }

            val resolved: OnlineStickerResult? = when (prefix) {
                "openmoji" -> {
                    val hex = resolveHexForSlug(normSlug)
                    if (hex != null) {
                        val pngUrl = "$OPENMOJI_PNG_BASE_URL$hex.png"
                        OnlineStickerResult(
                            id = "openmoji_$hex",
                            title = prettyTitle,
                            source = OnlineStickerSource.OPENMOJI,
                            thumbnailUrl = pngUrl,
                            downloadUrl = pngUrl,
                            cacheKey = "openmoji_$hex"
                        )
                    } else {
                        null
                    }
                }

                "noto" -> {
                    val hex = resolveHexForSlug(normSlug)
                    if (hex != null) {
                        val notoHex = toNotoEmojiHexcode(hex)
                        val openMojiFallback = "$OPENMOJI_PNG_BASE_URL$hex.png"
                        if (notoHex != null) {
                            val primaryUrl = "${NOTO_EMOJI_PNG_BASE_URL}${notoHex}.png"
                            OnlineStickerResult(
                                id = "noto_$notoHex",
                                title = prettyTitle,
                                source = OnlineStickerSource.NOTO_EMOJI,
                                thumbnailUrl = primaryUrl,
                                downloadUrl = primaryUrl,
                                cacheKey = "noto_$notoHex",
                                fallbackUrls = listOf(
                                    "${NOTO_EMOJI_PNG_JSDELIVR_BASE_URL}${notoHex}.png",
                                    openMojiFallback
                                )
                            )
                        } else {
                            OnlineStickerResult(
                                id = "openmoji_$hex",
                                title = prettyTitle,
                                source = OnlineStickerSource.OPENMOJI,
                                thumbnailUrl = openMojiFallback,
                                downloadUrl = openMojiFallback,
                                cacheKey = "openmoji_$hex"
                            )
                        }
                    } else {
                        null
                    }
                }

                "twemoji" -> {
                    val hex = resolveHexForSlug(normSlug)
                    if (hex != null) {
                        val twemojiHex = toTwemojiHexcode(hex)
                        val openMojiFallback = "$OPENMOJI_PNG_BASE_URL$hex.png"
                        if (twemojiHex != null) {
                            val primaryUrl = "$TWEMOJI_PNG_BASE_URL$twemojiHex.png"
                            OnlineStickerResult(
                                id = "twemoji_$twemojiHex",
                                title = "$prettyTitle Flat",
                                source = OnlineStickerSource.FLUENT_TWEMOJI,
                                thumbnailUrl = primaryUrl,
                                downloadUrl = primaryUrl,
                                cacheKey = "twemoji_$twemojiHex",
                                fallbackUrls = listOf(
                                    "$TWEMOJI_PNG_GITHUB_BASE_URL$twemojiHex.png",
                                    openMojiFallback
                                )
                            )
                        } else {
                            OnlineStickerResult(
                                id = "openmoji_$hex",
                                title = prettyTitle,
                                source = OnlineStickerSource.OPENMOJI,
                                thumbnailUrl = openMojiFallback,
                                downloadUrl = openMojiFallback,
                                cacheKey = "openmoji_$hex"
                            )
                        }
                    } else {
                        null
                    }
                }

                "fluent-emoji", "fluent-emoji-flat" -> {
                    val hex = resolveHexForSlug(normSlug)
                    val openMojiFallback = hex?.let { "$OPENMOJI_PNG_BASE_URL$it.png" }
                    val curatedSpec = fluentBySlug[normSlug]
                    if (curatedSpec != null) {
                        val base = curatedSpec.toOnlineResult()
                        base.copy(
                            fallbackUrls = buildList {
                                addAll(base.fallbackUrls)
                                if (openMojiFallback != null) add(openMojiFallback)
                            }.distinct()
                        )
                    } else {
                        val folderName = normSlug
                            .replace('-', ' ')
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        val encodedFolder = folderName.replace(" ", "%20")
                        val fileSlug = normSlug.replace('-', '_')
                        val primaryUrl = "$FLUENT_3D_BASE_URL$encodedFolder/3D/${fileSlug}_3d.png"
                        val githubMirror = "$FLUENT_3D_GITHUB_BASE_URL$encodedFolder/3D/${fileSlug}_3d.png"
                        val fallbacks = buildList {
                            add(githubMirror)
                            if (openMojiFallback != null) add(openMojiFallback)
                        }
                        OnlineStickerResult(
                            id = "fluent3d_$fileSlug",
                            title = "$prettyTitle 3D",
                            source = OnlineStickerSource.FLUENT_TWEMOJI,
                            thumbnailUrl = primaryUrl,
                            downloadUrl = primaryUrl,
                            cacheKey = "fluent3d_$fileSlug",
                            fallbackUrls = fallbacks
                        )
                    }
                }

                else -> null
            }

            if (resolved != null &&
                isSafeHttpsUrl(resolved.downloadUrl) &&
                isStrictPngUrl(resolved.downloadUrl) &&
                seenKeys.add("${resolved.id}|${resolved.downloadUrl}")
            ) {
                results.add(resolved)
            }
        }
        return results
    }

    private fun normalizeToIconifySlug(raw: String): String =
        raw.trim()
            .lowercase()
            .replace('&', ' ')
            .replace(Regex("[^a-z0-9\\s_-]"), "")
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')

    /**
     * Source 1: OpenMoji 618x618 transparent PNG search backed by cached `openmoji.json` + synonyms.
     */
    internal suspend fun searchOpenMoji(
        context: Context,
        query: String
    ): List<OnlineStickerResult> = withContext(Dispatchers.IO) {
        val catalog = getOrFetchOpenMojiCatalog(context)
        filterOpenMojiCatalog(catalog, query)
    }

    private suspend fun getOrFetchOpenMojiCatalog(context: Context): List<OpenMojiEntry> =
        withContext(Dispatchers.IO) {
            cachedOpenMojiCatalog?.let { return@withContext it }
            catalogMutex.withLock {
                cachedOpenMojiCatalog?.let { return@withLock it }
                // 1. Try disk cache first (<10ms warm start)
                val diskCached = loadCachedOpenMojiCatalogOnly(context)
                if (diskCached != null) {
                    cachedOpenMojiCatalog = diskCached
                    return@withLock diskCached
                }
                // 2. Fetch from jsDelivr CDN -> unpkg.com mirror -> GitHub raw fallback
                val endpoints = listOf(
                    OPENMOJI_CATALOG_URL,
                    OPENMOJI_CATALOG_FALLBACK_URL,
                    OPENMOJI_CATALOG_GITHUB_URL
                )
                val endpointReasons = ArrayList<String>(endpoints.size)
                var lastError: Throwable? = null
                for ((index, endpoint) in endpoints.withIndex()) {
                    try {
                        val bytes = httpFetcher.fetchBytes(endpoint, MAX_JSON_BYTES)
                        val jsonText = bytes.toString(Charsets.UTF_8)
                        val parsed = parseOpenMojiJson(jsonText)
                        if (parsed.isNotEmpty()) {
                            writeBytesAtomically(
                                File(stickersCacheDir(context), OPENMOJI_CATALOG_CACHE_FILE),
                                bytes
                            )
                            cachedOpenMojiCatalog = parsed
                            preferUnpkgOpenMojiMirror = (index > 0)
                            return@withLock parsed
                        }
                        throw IOException("Empty OpenMoji catalog returned from $endpoint")
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        lastError = t
                        val reason = formatFailureReason(t, endpoint)
                        endpointReasons.add(reason)
                        val nextEndpoint = endpoints.getOrNull(index + 1)
                        Log.w(
                            TAG,
                            "OpenMoji catalog fetch failed ($reason)" +
                                if (nextEndpoint != null) "; trying fallback $nextEndpoint" else "",
                            t
                        )
                    }
                }
                throw MultiEndpointFailureException(
                    endpointReasons = endpointReasons,
                    cause = lastError ?: IOException("Failed to fetch OpenMoji catalog from all endpoints")
                )
            }
        }

    private fun loadCachedOpenMojiCatalogOnly(context: Context): List<OpenMojiEntry>? {
        cachedOpenMojiCatalog?.let { return it }
        val file = File(stickersCacheDir(context), OPENMOJI_CATALOG_CACHE_FILE)
        if (!file.exists() || file.length() <= 16L) return null
        return runCatching {
            val parsed = parseOpenMojiJson(file.readText(Charsets.UTF_8))
            if (parsed.isNotEmpty()) {
                cachedOpenMojiCatalog = parsed
                parsed
            } else {
                null
            }
        }.getOrNull()
    }

    internal fun parseOpenMojiJson(jsonText: String): List<OpenMojiEntry> {
        val array = JSONArray(jsonText)
        val entries = ArrayList<OpenMojiEntry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val hex = obj.optString("hexcode").trim()
            if (hex.isEmpty()) continue
            val annotation = obj.optString("annotation").trim()
            val tags = obj.optString("tags").trim()
            val openMojiTags = obj.optString("openmoji_tags").trim()
            val group = obj.optString("group").trim()
            val subgroups = obj.optString("subgroups").trim()
            val skintone = obj.optString("skintone").trim()
            val haystack = buildString {
                append(annotation.lowercase())
                if (tags.isNotEmpty()) {
                    append(' ')
                    append(tags.lowercase())
                }
                if (openMojiTags.isNotEmpty()) {
                    append(' ')
                    append(openMojiTags.lowercase())
                }
                if (group.isNotEmpty()) {
                    append(' ')
                    append(group.lowercase())
                }
                if (subgroups.isNotEmpty()) {
                    append(' ')
                    append(subgroups.lowercase())
                }
            }
            entries.add(
                OpenMojiEntry(
                    hexcode = hex,
                    annotation = annotation.ifEmpty { hex },
                    searchHaystack = haystack,
                    hasSkintone = skintone.isNotEmpty()
                )
            )
        }
        return entries
    }

    private val QUERY_STOP_WORDS = setOf("a", "an", "the", "of", "with", "and", "or", "for", "in", "on", "to")

    /**
     * Normalizes a casual user search phrase by trimming, lowercasing, stripping punctuation,
     * splitting multi-word queries into individual keywords, and dropping filler stop-words when
     * meaningful keywords are present.
     */
    internal fun normalizeQueryTokens(rawQuery: String): List<String> {
        val cleaned = rawQuery.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
        val allTokens = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (allTokens.size <= 1) return allTokens
        val filtered = allTokens.filter { it !in QUERY_STOP_WORDS }
        return filtered.ifEmpty { allTokens }
    }

    /**
     * Generates singular/plural stem variants for a normalized keyword token so casual plurals
     * (e.g., `"wings"` -> `"wing"`, `"cars"` -> `"car"`, `"butterflies"` -> `"butterfly"`,
     * `"flashes"` -> `"flash"`) match OpenMoji, Noto, Fluent 3D, and local catalogs reliably.
     */
    internal fun stemWordVariants(token: String): Set<String> {
        val t = token.trim().lowercase()
        if (t.isEmpty()) return emptySet()
        val variants = LinkedHashSet<String>(4)
        variants.add(t)

        // Plural -> singular stems
        when {
            t.length > 4 && t.endsWith("ies") -> {
                variants.add(t.dropLast(3) + "y")
            }
            t.length > 4 && t.endsWith("ves") -> {
                variants.add(t.dropLast(3) + "f")
                variants.add(t.dropLast(3) + "fe")
            }
            t.length > 4 && (
                t.endsWith("ches") || t.endsWith("shes") ||
                    t.endsWith("xes") || t.endsWith("zes") || t.endsWith("sses")
                ) -> {
                variants.add(t.dropLast(2))
            }
            t.length > 4 && t.endsWith("oes") -> {
                variants.add(t.dropLast(2))
            }
            t.length > 3 && t.endsWith("s") &&
                !t.endsWith("ss") && !t.endsWith("us") && !t.endsWith("is") -> {
                variants.add(t.dropLast(1))
            }
        }
        if (t.length > 5 && t.endsWith("ing")) {
            val base = t.dropLast(3)
            variants.add(base)
            variants.add(base + "e")
        }

        // Singular -> plural stems (for matching plural-only tags)
        if (t.length >= 3 && !t.endsWith("s")) {
            when {
                t.endsWith("y") && t[t.length - 2] !in "aeiou" ->
                    variants.add(t.dropLast(1) + "ies")
                t.endsWith("x") || t.endsWith("z") || t.endsWith("ch") || t.endsWith("sh") ->
                    variants.add(t + "es")
                else ->
                    variants.add(t + "s")
            }
        }
        return variants
    }

    internal fun expandQuerySynonyms(rawQuery: String): Set<String> {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return emptySet()
        val expanded = LinkedHashSet<String>()
        QUERY_SYNONYMS[q]?.let { expanded.addAll(it) }
        val tokens = normalizeQueryTokens(q)
        for (token in tokens) {
            for (stem in stemWordVariants(token)) {
                QUERY_SYNONYMS[stem]?.let { expanded.addAll(it) }
            }
        }
        expanded.remove(q)
        return expanded
    }

    internal fun filterOpenMojiCatalog(
        catalog: List<OpenMojiEntry>,
        rawQuery: String,
        limit: Int = OPENMOJI_MAX_RESULTS
    ): List<OnlineStickerResult> {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val tokens = normalizeQueryTokens(q)
        if (tokens.isEmpty()) return emptyList()
        val tokenStemSets = tokens.map { stemWordVariants(it) }
        val primaryStemPhrase = tokens.joinToString(" ") { stemWordVariants(it).lastOrNull() ?: it }
        val synonyms = expandQuerySynonyms(q)

        val scored = ArrayList<Pair<Int, OpenMojiEntry>>()
        for (entry in catalog) {
            // Skip skin-tone modifier variants so search results stay crisp and non-repetitive
            if (entry.hasSkintone) continue
            val annLower = entry.annotation.lowercase()
            val matchedTokenCount = tokenStemSets.count { variants ->
                variants.any { v -> entry.searchHaystack.contains(v) }
            }
            val allTokensMatch = matchedTokenCount == tokens.size

            val score = if (allTokensMatch) {
                val allInAnn = tokenStemSets.all { variants -> variants.any { v -> annLower.contains(v) } }
                when {
                    annLower == q || tokenStemSets.size == 1 && tokenStemSets[0].contains(annLower) -> 100
                    annLower.startsWith(q) || (tokenStemSets.size == 1 && tokenStemSets[0].any { annLower.startsWith(it) }) -> 82
                    annLower.contains(q) || annLower.contains(primaryStemPhrase) -> 65
                    allInAnn -> 54
                    else -> 36
                }
            } else if (tokens.size >= 2 && matchedTokenCount > 0) {
                // Multi-word fuzzy match: reward entries matching one or more stemmed keywords
                val annHits = tokenStemSets.count { variants -> variants.any { v -> annLower.contains(v) } }
                if (annHits > 0) {
                    26 + (14 * matchedTokenCount) / tokens.size
                } else {
                    18 + (10 * matchedTokenCount) / tokens.size
                }
            } else if (synonyms.isNotEmpty()) {
                val synonymHit = synonyms.firstOrNull { syn -> entry.searchHaystack.contains(syn) }
                if (synonymHit != null) {
                    if (annLower.contains(synonymHit)) 24 else 16
                } else {
                    0
                }
            } else {
                0
            }

            if (score > 0) {
                scored.add(score to entry)
            }
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<Int, OpenMojiEntry>> { it.first }
                    .thenBy { it.second.annotation.length }
            )
            .take(limit)
            .map { (_, entry) ->
                val pngUrl = "$OPENMOJI_PNG_BASE_URL${entry.hexcode}.png"
                val prettyTitle = entry.annotation
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                OnlineStickerResult(
                    id = "openmoji_${entry.hexcode}",
                    title = prettyTitle,
                    source = OnlineStickerSource.OPENMOJI,
                    thumbnailUrl = pngUrl,
                    downloadUrl = pngUrl,
                    cacheKey = "openmoji_${entry.hexcode}"
                )
            }
    }

    /**
     * Source 2: Google Noto Color Emoji 512px transparent PNGs hosted on GitHub Raw (with jsDelivr mirror).
     */
    internal fun searchNotoEmoji(
        rawQuery: String,
        openMojiCatalog: List<OpenMojiEntry>,
        limit: Int = NOTO_EMOJI_MAX_RESULTS
    ): List<OnlineStickerResult> {
        val matches = filterOpenMojiCatalog(openMojiCatalog, rawQuery, limit = limit * 2)
        val out = ArrayList<OnlineStickerResult>(limit)
        val seenHex = HashSet<String>()
        for (om in matches) {
            if (out.size >= limit) break
            val hex = om.cacheKey.removePrefix("openmoji_")
            val notoHex = toNotoEmojiHexcode(hex) ?: continue
            if (!seenHex.add(notoHex)) continue
            val pngUrl = "${NOTO_EMOJI_PNG_BASE_URL}${notoHex}.png"
            out.add(
                OnlineStickerResult(
                    id = "noto_$notoHex",
                    title = om.title,
                    source = OnlineStickerSource.NOTO_EMOJI,
                    thumbnailUrl = pngUrl,
                    downloadUrl = pngUrl,
                    cacheKey = "noto_$notoHex"
                )
            )
        }
        return out
    }

    /**
     * Converts an OpenMoji uppercase hexcode (e.g., `1F525`, `2764-FE0F`, `1F469-200D-1F680`) into
     * Google Noto Color Emoji's canonical filename suffix (`1f525`, `2764`, `1f469_200d_1f680`),
     * skipping OpenMoji private-use codepoints (`E000`..`F8FF`).
     */
    internal fun toNotoEmojiHexcode(openMojiHex: String): String? {
        val parts = openMojiHex.trim().uppercase().split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val parsed = parts.map { part ->
            part.toIntOrNull(16) ?: return null
        }
        if (parsed.any { cp -> cp in 0xE000..0xF8FF || cp >= 0xF0000 }) return null
        val normalized = parsed
            .filter { cp -> cp != 0xFE0F }
            .map { cp -> cp.toString(16).lowercase().padStart(4, '0') }
        if (normalized.isEmpty()) return null
        return normalized.joinToString("_")
    }

    /**
     * Source 3: Microsoft Fluent Emoji 3D glossy PNGs + Twemoji crisp flat PNGs via jsDelivr / GitHub Raw.
     */
    internal fun searchFluentAndTwemoji(
        rawQuery: String,
        openMojiCatalog: List<OpenMojiEntry>? = null,
        limit: Int = FLUENT_TWEMOJI_MAX_RESULTS
    ): List<OnlineStickerResult> {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val tokens = normalizeQueryTokens(q)
        if (tokens.isEmpty()) return emptyList()
        val tokenStemSets = tokens.map { stemWordVariants(it) }
        val synonyms = expandQuerySynonyms(q)

        // 1. Score curated 3D glossy Microsoft Fluent Emoji PNGs first
        val scoredFluent = ArrayList<Pair<Int, Fluent3dStickerSpec>>()
        for (spec in FLUENT_3D_CATALOG) {
            val titleLower = spec.title.lowercase()
            val matchedCount = tokenStemSets.count { variants ->
                variants.any { v -> spec.searchHaystack.contains(v) }
            }
            val allMatch = matchedCount == tokens.size
            val score = if (allMatch) {
                when {
                    titleLower == "$q 3d" || titleLower == q -> 100
                    titleLower.startsWith(q) || (tokenStemSets.size == 1 && tokenStemSets[0].any { titleLower.startsWith(it) }) -> 85
                    titleLower.contains(q) -> 68
                    else -> 45
                }
            } else if (tokens.size >= 2 && matchedCount > 0) {
                val titleHits = tokenStemSets.count { variants -> variants.any { v -> titleLower.contains(v) } }
                if (titleHits > 0) 30 + (12 * matchedCount) / tokens.size else 20 + (8 * matchedCount) / tokens.size
            } else if (synonyms.isNotEmpty()) {
                val synHit = synonyms.firstOrNull { spec.searchHaystack.contains(it) }
                if (synHit != null) {
                    if (titleLower.contains(synHit)) 28 else 20
                } else {
                    0
                }
            } else {
                0
            }
            if (score > 0) {
                scoredFluent.add(score to spec)
            }
        }

        val out = ArrayList<OnlineStickerResult>(limit)
        val seenTitles = HashSet<String>()
        scoredFluent
            .sortedWith(compareByDescending<Pair<Int, Fluent3dStickerSpec>> { it.first }.thenBy { it.second.title.length })
            .take(limit)
            .forEach { (_, spec) ->
                out.add(spec.toOnlineResult())
                seenTitles.add(spec.title.removeSuffix(" 3D").lowercase())
            }

        // 2. Supplement with Twemoji crisp flat transparent PNGs mapped from cached OpenMoji matches
        if (out.size < limit && !openMojiCatalog.isNullOrEmpty()) {
            val openMojiMatches = filterOpenMojiCatalog(openMojiCatalog, q, limit = limit)
            for (om in openMojiMatches) {
                if (out.size >= limit) break
                val hex = om.cacheKey.removePrefix("openmoji_")
                val twemojiHex = toTwemojiHexcode(hex) ?: continue
                if (seenTitles.contains(om.title.lowercase())) continue
                seenTitles.add(om.title.lowercase())
                val pngUrl = "$TWEMOJI_PNG_BASE_URL$twemojiHex.png"
                out.add(
                    OnlineStickerResult(
                        id = "twemoji_$twemojiHex",
                        title = "${om.title} Flat",
                        source = OnlineStickerSource.FLUENT_TWEMOJI,
                        thumbnailUrl = pngUrl,
                        downloadUrl = pngUrl,
                        cacheKey = "twemoji_$twemojiHex"
                    )
                )
            }
        }

        return out.take(limit)
    }

    /**
     * Converts an OpenMoji uppercase hexcode (e.g., `1F525`, `2764-FE0F`, `00A9`) into Twemoji's
     * canonical lowercase filename hexcode (e.g., `1f525`, `2764`, `a9`), skipping OpenMoji-private
     * codepoints (`E000`..`F8FF`) that do not exist in Unicode / Twemoji.
     */
    internal fun toTwemojiHexcode(openMojiHex: String): String? {
        val parts = openMojiHex.trim().uppercase().split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val parsed = parts.map { part ->
            part.toIntOrNull(16) ?: return null
        }
        // Skip OpenMoji private-use glyphs (not part of Unicode / Twemoji)
        if (parsed.any { cp -> cp in 0xE000..0xF8FF || cp >= 0xF0000 }) return null
        val hasZwj = parsed.contains(0x200D)
        val normalized = parsed
            .filter { cp -> hasZwj || cp != 0xFE0F }
            .map { cp -> cp.toString(16).lowercase() }
        if (normalized.isEmpty()) return null
        return normalized.joinToString("-")
    }

    /**
     * Builds the Wikimedia Commons public API search URL matching transparent bitmap PNG cutouts
     * (`gsrsearch=filetype:bitmap+<encoded_query>+png`), requesting MediaWiki's standard cached
     * 256px transparent PNG thumbnail renderer (`iiurlwidth=256`) to stay rate-limit friendly.
     */
    fun buildWikimediaSearchUrl(rawQuery: String): String {
        val encodedQuery = URLEncoder.encode(rawQuery.trim(), "UTF-8")
        return "$WIKIMEDIA_API_BASE_URL?action=query&format=json&generator=search" +
            "&gsrnamespace=6&gsrsearch=filetype:bitmap+$encodedQuery+png" +
            "&gsrlimit=$WIKIMEDIA_MAX_RESULTS&prop=imageinfo&iiprop=url%7Csize&iiurlwidth=$WIKIMEDIA_THUMB_WIDTH_PX"
    }

    /**
     * Builds a lightweight, fast-responding Wikimedia Commons `list=allimages` PNG prefix query
     * as a fallback if full-text `generator=search` fails or returns zero results.
     */
    fun buildWikimediaAllImagesUrl(rawQuery: String): String {
        val firstToken = normalizeQueryTokens(rawQuery)
            .firstOrNull()
            .orEmpty()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val encodedPrefix = URLEncoder.encode(firstToken, "UTF-8")
        return "$WIKIMEDIA_API_BASE_URL?action=query&format=json&list=allimages" +
            "&aiprefix=$encodedPrefix&aimime=image%2Fpng&ailimit=$WIKIMEDIA_MAX_RESULTS&aiprop=url%7Csize"
    }

    /**
     * Source 4: Wikimedia Commons SVG & PNG cutout search on [Dispatchers.IO].
     * Automatically falls back to the lightweight `list=allimages` PNG API if `generator=search`
     * fails or yields zero PNG/SVG cutouts.
     */
    internal suspend fun searchWikimediaCommons(query: String): List<OnlineStickerResult> =
        withContext(Dispatchers.IO) {
            val primaryUrl = buildWikimediaSearchUrl(query)
            var primaryError: Throwable? = null
            val primaryResults = try {
                val bytes = httpFetcher.fetchBytes(primaryUrl, MAX_JSON_BYTES)
                parseWikimediaSearchJson(bytes.toString(Charsets.UTF_8))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                primaryError = t
                Log.w(TAG, "Wikimedia generator=search failed (${formatFailureReason(t, primaryUrl)}); trying lightweight allimages fallback", t)
                null
            }

            if (!primaryResults.isNullOrEmpty()) {
                return@withContext primaryResults
            }

            val fallbackUrl = buildWikimediaAllImagesUrl(query)
            try {
                val fallbackBytes = httpFetcher.fetchBytes(fallbackUrl, MAX_JSON_BYTES)
                val fallbackResults = parseWikimediaAllImagesJson(fallbackBytes.toString(Charsets.UTF_8))
                if (fallbackResults.isNotEmpty() || primaryError == null) {
                    return@withContext fallbackResults
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (fallbackErr: Throwable) {
                Log.w(TAG, "Wikimedia allimages fallback failed (${formatFailureReason(fallbackErr, fallbackUrl)})", fallbackErr)
                if (primaryError == null) {
                    return@withContext emptyList()
                }
                val reasons = listOf(
                    formatFailureReason(primaryError, primaryUrl),
                    formatFailureReason(fallbackErr, fallbackUrl)
                ).distinct()
                throw MultiEndpointFailureException(reasons, primaryError)
            }

            throw EndpointRequestException(primaryUrl, primaryError)
        }

    internal fun parseWikimediaAllImagesJson(jsonText: String): List<OnlineStickerResult> {
        val root = JSONObject(jsonText)
        val queryObj = root.optJSONObject("query") ?: return emptyList()
        val imagesArr = queryObj.optJSONArray("allimages") ?: return emptyList()
        val results = ArrayList<OnlineStickerResult>(imagesArr.length())
        for (i in 0 until imagesArr.length()) {
            val item = imagesArr.optJSONObject(i) ?: continue
            val rawTitle = item.optString("title").ifBlank { item.optString("name") }.trim()
            val fullUrl = item.optString("url").trim()
            if (!isSafeHttpsUrl(fullUrl) || !isStrictPngUrl(fullUrl)) continue
            val cleanTitle = cleanWikimediaTitle(rawTitle)
            val idKey = sha256Hex(fullUrl).take(12)
            results.add(
                OnlineStickerResult(
                    id = "wiki_ai_$idKey",
                    title = cleanTitle,
                    source = OnlineStickerSource.WIKIMEDIA,
                    thumbnailUrl = fullUrl,
                    downloadUrl = fullUrl,
                    cacheKey = "wiki_ai_$idKey"
                )
            )
        }
        return results
    }

    internal fun parseWikimediaSearchJson(jsonText: String): List<OnlineStickerResult> {
        val root = JSONObject(jsonText)
        val queryObj = root.optJSONObject("query") ?: return emptyList()
        val pagesObj = queryObj.optJSONObject("pages") ?: return emptyList()
        val results = ArrayList<Pair<Int, OnlineStickerResult>>()
        val keys = pagesObj.keys()
        while (keys.hasNext()) {
            val pageKey = keys.next()
            val page = pagesObj.optJSONObject(pageKey) ?: continue
            val pageId = page.optLong("pageid", pageKey.toLongOrNull() ?: 0L)
            val index = page.optInt("index", Int.MAX_VALUE)
            val rawTitle = page.optString("title").trim()
            val imageInfoArr = page.optJSONArray("imageinfo") ?: continue
            if (imageInfoArr.length() == 0) continue
            val info = imageInfoArr.optJSONObject(0) ?: continue

            val thumbUrl = info.optString("thumburl").trim()
            val fullUrl = info.optString("url").trim()

            // Accept both transparent PNG files AND SVG vector icons rasterized to 512px PNG (.svg.png);
            // reject opaque raster formats like JPG/JPEG/GIF/WebP/PDF.
            val titleLower = rawTitle.lowercase()
            val sourceIsPng = isStrictPngUrl(fullUrl) || titleLower.endsWith(".png")
            val sourceIsSvg = isSvgUrl(fullUrl) || titleLower.endsWith(".svg")
            if (!sourceIsPng && !sourceIsSvg) continue

            val preferredUrl = when {
                // Prefer MediaWiki's 512px PNG thumbnail (includes .svg.png rasterized transparent PNGs!)
                isSafeHttpsUrl(thumbUrl) && isStrictPngUrl(thumbUrl) -> thumbUrl
                // Only fall back to fullUrl when source itself is already a PNG (never raw .svg XML)
                sourceIsPng && isSafeHttpsUrl(fullUrl) && isStrictPngUrl(fullUrl) -> fullUrl
                else -> continue
            }

            val cleanTitle = cleanWikimediaTitle(rawTitle)
            results.add(
                index to OnlineStickerResult(
                    id = "wiki_$pageId",
                    title = cleanTitle,
                    source = OnlineStickerSource.WIKIMEDIA,
                    thumbnailUrl = preferredUrl,
                    downloadUrl = preferredUrl,
                    cacheKey = "wiki_$pageId"
                )
            )
        }
        return results.sortedBy { it.first }.map { it.second }
    }

    internal fun isStrictPngUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val path = runCatching { URL(url).path }.getOrNull() ?: url.substringBefore('?')
        return path.lowercase().endsWith(".png")
    }

    internal fun isSvgUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val path = runCatching { URL(url).path }.getOrNull() ?: url.substringBefore('?')
        return path.lowercase().endsWith(".svg")
    }

    private fun cleanWikimediaTitle(rawTitle: String): String {
        val withoutPrefix = rawTitle.removePrefix("File:").removePrefix("file:")
        val withoutExt = withoutPrefix.substringBeforeLast('.')
        return withoutExt
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "Sticker" }
            .take(36)
    }

    private fun interleaveSources(vararg lists: List<OnlineStickerResult>): List<OnlineStickerResult> {
        val total = lists.sumOf { it.size }
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        val out = ArrayList<OnlineStickerResult>(total)
        val seenIds = HashSet<String>(total)
        val seenUrls = HashSet<String>(total)
        for (i in 0 until maxLen) {
            for (list in lists) {
                if (i < list.size) {
                    val item = list[i]
                    if (seenIds.add(item.id) && seenUrls.add(item.downloadUrl)) {
                        out.add(item)
                    }
                }
            }
        }
        return out
    }

    // --- Disk Caching & Decoding (`context.cacheDir/stickers/`) ---

    fun cacheFileForSticker(context: Context, sticker: OnlineStickerResult): File {
        val safeHash = sha256Hex("${sticker.source.name}:${sticker.cacheKey}:${sticker.downloadUrl}").take(24)
        val safePrefix = sticker.cacheKey
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(28)
            .ifEmpty { "sticker" }
        return File(stickersCacheDir(context), "${safePrefix}_${safeHash}.png")
    }

    /**
     * Loads a compact preview thumbnail for an online sticker card (or renders a local
     * [StickerLibrary] fallback sticker directly), reusing `cacheDir/stickers/` automatically so
     * tapping the sticker later requires zero re-downloading.
     *
     * Throttles `upload.wikimedia.org` downloads to at most [MAX_CONCURRENT_WIKIMEDIA_DOWNLOADS] (2)
     * in flight with a 150ms pacing delay, and caps general CDN thumbnail downloads to
     * [MAX_CONCURRENT_THUMBNAIL_DOWNLOADS] (4) at a time.
     */
    suspend fun loadStickerThumbnail(
        context: Context,
        sticker: OnlineStickerResult,
        maxDimension: Int = StickerLibrary.THUMBNAIL_SIZE_PX
    ): Bitmap? = withContext(Dispatchers.IO) {
        sticker.builtInStickerId?.let { builtInId ->
            val builtIn = StickerLibrary.allBuiltInStickers.firstOrNull { it.id == builtInId }
                ?: return@withContext null
            return@withContext StickerLibrary.renderBuiltInSticker(builtIn, maxDimension)
        }
        val cachedFile = cacheFileForSticker(context, sticker)
        if (cachedFile.exists() && cachedFile.length() > 0L) {
            return@withContext decodeBoundedBitmap(cachedFile, maxDimension)
        }
        val file = if (isWikimediaUploadUrl(sticker.downloadUrl)) {
            ensureStickerFileCached(context, sticker, maxRetries = 1)
        } else {
            thumbnailDownloadSemaphore.withPermit {
                ensureStickerFileCached(context, sticker, maxRetries = 1)
            }
        } ?: return@withContext null
        decodeBoundedBitmap(file, maxDimension)
    }

    /**
     * Downloads (if not already cached) and decodes a full-resolution transparent sticker PNG
     * from `context.cacheDir/stickers/` ready to drop onto the canvas as an [ImageLayer].
     * For local [StickerLibrary] fallback results, renders and caches the transparent PNG locally.
     */
    suspend fun downloadAndCacheSticker(
        context: Context,
        sticker: OnlineStickerResult,
        maxDimension: Int = LayerCompositor.OVERLAY_PREVIEW_MAX_DIM
    ): DownloadedStickerAsset? = withContext(Dispatchers.IO) {
        sticker.builtInStickerId?.let { builtInId ->
            val builtIn = StickerLibrary.allBuiltInStickers.firstOrNull { it.id == builtInId }
                ?: return@withContext null
            val bmp = StickerLibrary.renderBuiltInSticker(builtIn, builtIn.renderSizePx)
            val target = cacheFileForSticker(context, sticker)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            writeBytesAtomically(target, out.toByteArray())
            return@withContext DownloadedStickerAsset(bitmap = bmp, cacheFile = target)
        }
        val file = ensureStickerFileCached(context, sticker, maxRetries = 1) ?: return@withContext null
        val bmp = decodeBoundedBitmap(file, maxDimension) ?: return@withContext null
        DownloadedStickerAsset(bitmap = bmp, cacheFile = file)
    }

    internal fun isWikimediaUploadUrl(url: String): Boolean =
        extractHost(url) == "upload.wikimedia.org"

    internal fun candidateDownloadUrlsFor(sticker: OnlineStickerResult): List<String> {
        val primary = sticker.downloadUrl
        val mirrored = when {
            primary.startsWith(OPENMOJI_PNG_BASE_URL) -> {
                val suffix = primary.removePrefix(OPENMOJI_PNG_BASE_URL)
                val jsDelivrGhMirror = OPENMOJI_PNG_FALLBACK_BASE_URL + suffix
                if (preferUnpkgOpenMojiMirror) {
                    listOf(jsDelivrGhMirror, primary)
                } else {
                    listOf(primary, jsDelivrGhMirror)
                }
            }
            primary.startsWith(OPENMOJI_PNG_FALLBACK_BASE_URL) -> {
                val suffix = primary.removePrefix(OPENMOJI_PNG_FALLBACK_BASE_URL)
                listOf(primary, OPENMOJI_PNG_BASE_URL + suffix)
            }
            primary.startsWith(NOTO_EMOJI_PNG_BASE_URL) -> {
                val suffix = primary.removePrefix(NOTO_EMOJI_PNG_BASE_URL)
                listOf(primary, NOTO_EMOJI_PNG_JSDELIVR_BASE_URL + suffix)
            }
            primary.startsWith(FLUENT_3D_BASE_URL) -> {
                val suffix = primary.removePrefix(FLUENT_3D_BASE_URL)
                listOf(primary, FLUENT_3D_GITHUB_BASE_URL + suffix)
            }
            primary.startsWith(TWEMOJI_PNG_BASE_URL) -> {
                val suffix = primary.removePrefix(TWEMOJI_PNG_BASE_URL)
                listOf(primary, TWEMOJI_PNG_GITHUB_BASE_URL + suffix)
            }
            else -> listOf(primary)
        }
        if (sticker.fallbackUrls.isEmpty()) return mirrored
        return (mirrored + sticker.fallbackUrls).distinct()
    }

    private fun isRetryableDownloadFailure(err: Throwable): Boolean {
        val root = if (err is EndpointRequestException) err.cause ?: err else err
        return when (root) {
            is HttpStatusException -> root.statusCode == 429 || root.statusCode == 503 || root.statusCode == 502 || root.statusCode == 504
            else -> true
        }
    }

    private suspend fun ensureStickerFileCached(
        context: Context,
        sticker: OnlineStickerResult,
        maxRetries: Int = 1
    ): File? {
        val target = cacheFileForSticker(context, sticker)
        if (target.exists() && target.length() > 0L) {
            return target
        }

        val candidates = candidateDownloadUrlsFor(sticker).filter { isSafeHttpsUrl(it) }
        if (candidates.isEmpty()) return null

        for (candidateUrl in candidates) {
            val isWikiUpload = isWikimediaUploadUrl(candidateUrl)
            for (attempt in 0..maxRetries) {
                if (attempt > 0) {
                    val backoffMs = if (isWikiUpload) {
                        max(downloadRetryBackoffMs, wikimediaDownloadDelayMs)
                    } else {
                        downloadRetryBackoffMs
                    }
                    if (backoffMs > 0L) delay(backoffMs)
                }

                var shouldRetryCandidate = false
                val result = try {
                    val bytes = if (isWikiUpload) {
                        wikimediaDownloadSemaphore.withPermit {
                            if (attempt == 0 && wikimediaDownloadDelayMs > 0L) {
                                delay(wikimediaDownloadDelayMs)
                            }
                            httpFetcher.fetchBytes(candidateUrl, MAX_IMAGE_BYTES)
                        }
                    } else {
                        httpFetcher.fetchBytes(candidateUrl, MAX_IMAGE_BYTES)
                    }
                    if (bytes.isEmpty()) {
                        throw IOException("Empty image payload from $candidateUrl")
                    }
                    // Verify bytes decode cleanly as an image before committing to disk cache
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        throw IOException("Invalid image payload from $candidateUrl")
                    }
                    writeBytesAtomically(target, bytes)
                    target
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Throwable) {
                    shouldRetryCandidate = isRetryableDownloadFailure(err)
                    val root = if (err is EndpointRequestException) err.cause ?: err else err
                    val isExpected404 = root is HttpStatusException && root.statusCode == HttpURLConnection.HTTP_NOT_FOUND
                    if (isExpected404) {
                        // Do not log noisy stack traces for expected 404 misses on individual hex codes
                        Log.d(TAG, "Sticker hex miss (${formatFailureReason(err, candidateUrl)}) for ${sticker.id}")
                    } else if (root is HttpStatusException) {
                        Log.w(
                            TAG,
                            "Sticker download ${sticker.id} attempt ${attempt + 1}/${maxRetries + 1} failed (${formatFailureReason(err, candidateUrl)})"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Failed downloading sticker ${sticker.id} attempt ${attempt + 1}/${maxRetries + 1} (${formatFailureReason(err, candidateUrl)})",
                            err
                        )
                    }
                    null
                }
                if (result != null) {
                    return result
                }
                if (!shouldRetryCandidate) {
                    break
                }
            }
        }
        return null
    }

    private fun decodeBoundedBitmap(file: File, maxDimension: Int): Bitmap? {
        if (!file.exists() || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val maxSide = max(bounds.outWidth, bounds.outHeight)
        while (maxSide / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    // --- Keyless AI Sticker Generator (Pollinations.ai + Edge Flood-Fill White Background Removal) ---

    private fun encodePathSegment(raw: String): String =
        URLEncoder.encode(raw, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")

    /**
     * Builds the keyless 512x512 Pollinations.ai sticker prompt URL requesting a centered die-cut
     * sticker isolated on solid pure white background with no shadow so our edge flood-fill color
     * key strips the background cleanly into true alpha transparency.
     */
    fun buildPollinationsStickerUrl(
        rawPrompt: String,
        style: AiStickerStyle = AiStickerStyle.DIE_CUT_VECTOR,
        seed: Int = kotlin.random.Random.nextInt(1, 999_999)
    ): String {
        val cleanedPrompt = rawPrompt.trim().replace(Regex("\\s+"), " ")
        val fullPrompt = "$cleanedPrompt, ${style.promptTag} die-cut sticker, isolated on solid pure white background, centered, no shadow"
        val encodedPath = encodePathSegment(fullPrompt)
        return "${POLLINATIONS_IMAGE_BASE_URL}${encodedPath}?width=512&height=512&nologo=true&seed=${seed.coerceAtLeast(1)}"
    }

    /**
     * Fetches a 512x512 generated sticker from Pollinations.ai on [Dispatchers.IO] (25s timeout,
     * zero API keys), removes the connected pure-white background from the edges using a fast
     * flood-fill color key with anti-aliased edge feathering, and returns a crisp transparent
     * `ARGB_8888` [Bitmap] ready to save into `"My Cutouts"` and drop onto the canvas.
     */
    suspend fun generateAiSticker(
        rawPrompt: String,
        style: AiStickerStyle = AiStickerStyle.DIE_CUT_VECTOR,
        seed: Int = kotlin.random.Random.nextInt(1, 999_999)
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        val cleaned = rawPrompt.trim()
        if (cleaned.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Describe a sticker first"))
        }
        val url = buildPollinationsStickerUrl(cleaned, style, seed)
        try {
            val bytes = aiStickerHttpFetcher.fetchBytes(url, MAX_IMAGE_BYTES)
            if (bytes.isEmpty()) {
                return@withContext Result.failure(IOException("Empty image received from image.pollinations.ai"))
            }
            val decoded = StickerLibrary.decodeAndDownscaleStickerBytes(bytes, maxDimensionPx = 1024)
                ?: return@withContext Result.failure(IOException("Could not decode generated sticker image"))
            val transparent = try {
                stripWhiteBackgroundFloodFill(decoded)
            } finally {
                if (!decoded.isRecycled) decoded.recycle()
            }
            val trimmed = StickerLibrary.trimTransparentPadding(transparent, padPx = 8)
            if (trimmed !== transparent && !transparent.isRecycled) {
                transparent.recycle()
            }
            Result.success(trimmed)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "AI sticker generation failed (${formatFailureReason(t, url)})")
            Result.failure(IOException(formatFailureReason(t, url), t))
        }
    }

    /**
     * Strips the connected solid white background starting from all 4 image edges via a fast
     * primitive-queue BFS flood-fill color key, preserving internal white highlights and crisp
     * die-cut sticker outlines, followed by a gentle 1px anti-alias fringe softening pass.
     */
    fun stripWhiteBackgroundFloodFill(
        source: Bitmap,
        whiteThreshold: Int = 236,
        maxChroma: Int = 24,
        softEdgeRange: Int = 28
    ): Bitmap {
        val w = source.width
        val h = source.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (w <= 0 || h <= 0) return out

        val total = w * h
        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val visited = BooleanArray(total)
        val queue = IntArray(total)
        var head = 0
        var tail = 0

        fun isWhiteBackgroundPixel(argb: Int, minChannel: Int): Boolean {
            val a = (argb ushr 24) and 0xFF
            if (a <= 16) return true
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val minC = minOf(r, g, b)
            val maxC = maxOf(r, g, b)
            return minC >= minChannel && (maxC - minC) <= maxChroma
        }

        fun enqueueIfBg(idx: Int, minChannel: Int) {
            if (!visited[idx] && isWhiteBackgroundPixel(pixels[idx], minChannel)) {
                visited[idx] = true
                queue[tail++] = idx
            }
        }

        val borderThreshold = (whiteThreshold - 8).coerceAtLeast(200)
        // Seed BFS from all 4 edges
        for (x in 0 until w) {
            enqueueIfBg(x, borderThreshold)
            enqueueIfBg((h - 1) * w + x, borderThreshold)
        }
        for (y in 1 until h - 1) {
            enqueueIfBg(y * w, borderThreshold)
            enqueueIfBg(y * w + (w - 1), borderThreshold)
        }

        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            val y = idx / w
            if (x > 0) enqueueIfBg(idx - 1, whiteThreshold)
            if (x < w - 1) enqueueIfBg(idx + 1, whiteThreshold)
            if (y > 0) enqueueIfBg(idx - w, whiteThreshold)
            if (y < h - 1) enqueueIfBg(idx + w, whiteThreshold)
        }

        // Clear all connected background pixels to full alpha = 0
        for (i in 0 until total) {
            if (visited[i]) {
                pixels[i] = 0x00000000
            }
        }

        // Crisp 1px anti-aliased edge feathering on near-white halo pixels directly touching background
        val softFloor = (whiteThreshold - softEdgeRange).coerceAtLeast(160)
        if (softEdgeRange > 0) {
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val idx = rowOff + x
                    if (visited[idx]) continue
                    val touchesBg = (x > 0 && visited[idx - 1]) ||
                        (x < w - 1 && visited[idx + 1]) ||
                        (y > 0 && visited[idx - w]) ||
                        (y < h - 1 && visited[idx + w])
                    if (!touchesBg) continue
                    val argb = pixels[idx]
                    val a = (argb ushr 24) and 0xFF
                    if (a == 0) continue
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    val minC = minOf(r, g, b)
                    val maxC = maxOf(r, g, b)
                    if (minC >= softFloor && (maxC - minC) <= maxChroma + 10) {
                        val lum = (r + g + b) / 3f
                        val keepFraction = ((255f - lum) / (255f - softFloor).coerceAtLeast(1f))
                            .coerceIn(0f, 1f)
                        val newA = (a * keepFraction).toInt().coerceIn(0, 255)
                        pixels[idx] = (newA shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }

        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    internal fun extractHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { URL(url).host?.lowercase()?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    internal fun isSafeHttpsUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        if (!parsed.protocol.equals("https", ignoreCase = true)) return false
        val host = parsed.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return host in ALLOWED_HOSTS ||
            host.endsWith(".iconify.design") ||
            host.endsWith(".wikimedia.org") ||
            host.endsWith(".jsdelivr.net") ||
            host.endsWith(".unpkg.com") ||
            host.endsWith(".githubusercontent.com") ||
            host.endsWith(".pollinations.ai")
    }

    internal fun expectedAcceptHeaderFor(urlString: String): String {
        val parsed = runCatching { URL(urlString) }.getOrNull()
        val host = parsed?.host?.lowercase().orEmpty()
        if (host == "image.pollinations.ai" || host.endsWith(".pollinations.ai")) {
            return ACCEPT_IMAGE
        }
        val path = parsed?.path?.lowercase().orEmpty()
        return if (path.endsWith(".png") || path.endsWith(".webp") || path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            ACCEPT_IMAGE
        } else {
            ACCEPT_JSON
        }
    }

    internal fun openConfiguredConnection(
        urlString: String,
        acceptHeader: String = expectedAcceptHeaderFor(urlString),
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): HttpURLConnection =
        (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", acceptHeader)
        }

    /**
     * Produces a concise diagnostic label showing exact HTTP status code or exception class + failing host,
     * e.g. `"HTTP 403 on commons.wikimedia.org"` or `"SSLHandshakeException on cdn.jsdelivr.net"`.
     */
    internal fun formatFailureReason(err: Throwable, fallbackUrl: String? = null): String {
        val (effectiveUrl, root) = when (err) {
            is EndpointRequestException -> err.url to (err.cause ?: err)
            is HttpStatusException -> err.url to err
            else -> fallbackUrl to err
        }
        val host = extractHost(effectiveUrl)
        return when (root) {
            is HttpStatusException -> {
                val h = extractHost(root.url) ?: host
                if (h != null) "HTTP ${root.statusCode} on $h" else "HTTP ${root.statusCode}"
            }
            else -> {
                val cls = root.javaClass.simpleName.ifBlank { "IOException" }
                if (host != null) "$cls on $host" else "$cls: ${root.message ?: "network error"}"
            }
        }
    }

    private fun describeFailure(err: Throwable): String = formatFailureReason(err)

    @Throws(IOException::class)
    private fun defaultHttpGet(
        urlString: String,
        maxBytes: Int,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): ByteArray {
        var currentUrl = urlString
        var redirects = 0
        while (redirects <= MAX_REDIRECTS) {
            if (!isSafeHttpsUrl(currentUrl)) {
                throw EndpointRequestException(
                    currentUrl,
                    IOException("Blocked non-allowlisted sticker URL: $currentUrl")
                )
            }
            val conn = openConfiguredConnection(
                urlString = currentUrl,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs
            )
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 ||
                    code == 308
                ) {
                    val location = conn.getHeaderField("Location")
                        ?: throw HttpStatusException(code, currentUrl, "HTTP $code redirect without Location header for $currentUrl")
                    val resolved = URL(URL(currentUrl), location).toString()
                    Log.d(TAG, "Following HTTP $code redirect: $currentUrl -> $resolved")
                    currentUrl = resolved
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    val errorSnippet = runCatching {
                        conn.errorStream?.bufferedReader()?.use { it.readText().take(200).trim() }
                    }.getOrNull().orEmpty()
                    val statusReason = conn.responseMessage?.takeIf { it.isNotBlank() } ?: "Error"
                    val detail = if (errorSnippet.isNotEmpty()) " — $errorSnippet" else ""
                    val msg = "HTTP $code ($statusReason) while fetching $currentUrl$detail"
                    if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                        Log.d(TAG, "HTTP 404 miss for $currentUrl")
                    } else {
                        Log.w(TAG, msg)
                    }
                    throw HttpStatusException(code, currentUrl, msg)
                }
                val contentLength = conn.contentLengthLong
                if (contentLength > maxBytes) {
                    throw EndpointRequestException(
                        currentUrl,
                        IOException("Response exceeds max allowed size ($contentLength > $maxBytes)")
                    )
                }
                return conn.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    val out = ByteArrayOutputStream()
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) {
                            throw EndpointRequestException(
                                currentUrl,
                                IOException("Stream exceeded max allowed size ($maxBytes)")
                            )
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            } catch (ioe: IOException) {
                if (ioe is HttpStatusException || ioe is EndpointRequestException) {
                    throw ioe
                }
                Log.w(TAG, "HTTP request error (${formatFailureReason(ioe, currentUrl)}): ${ioe.message}")
                throw EndpointRequestException(currentUrl, ioe)
            } finally {
                conn.disconnect()
            }
        }
        throw EndpointRequestException(
            urlString,
            IOException("Too many redirects ($MAX_REDIRECTS) while fetching $urlString")
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0F])
            }
        }
    }
}
