package io.stamethyst.backend.workshop

import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import top.apricityx.workshop.steam.protocol.STEAM_LANGUAGE_ENGLISH
import top.apricityx.workshop.steam.protocol.STEAM_LANGUAGE_SIMPLIFIED_CHINESE
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAuthenticationClient
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient

enum class SteamLanguagePreference(
    val storageValue: String,
    val requestValue: String,
    val acceptLanguageValue: String,
    val protocolLanguage: Int,
    val displayName: String,
) {
    SimplifiedChinese(
        storageValue = "schinese",
        requestValue = "schinese",
        acceptLanguageValue = "zh-CN,zh;q=0.9",
        protocolLanguage = STEAM_LANGUAGE_SIMPLIFIED_CHINESE,
        displayName = "简体中文",
    ),
    English(
        storageValue = "english",
        requestValue = "english",
        acceptLanguageValue = "en-US,en;q=0.9",
        protocolLanguage = STEAM_LANGUAGE_ENGLISH,
        displayName = "English",
    );

    companion object {
        fun fromStorageValue(value: String?): SteamLanguagePreference =
            entries.firstOrNull { it.storageValue == value?.trim() } ?: SimplifiedChinese
    }
}

internal class WorkshopSteamWebSession(
    private val baseClient: OkHttpClient,
    private val identity: WorkshopSteamClientIdentity,
) {
    val cookieJar: SteamWebSessionCookieJar = SteamWebSessionCookieJar(
        projectedCookiesProvider = ::projectedCookiesFor,
        sessionScopeProvider = { currentScope },
    )

    private val lock = Any()
    private var currentScope: String? = null
    private var primedScope: String? = null
    private var webLoginContext: SteamWebLoginContext? = null

    suspend fun ensurePrimed(
        account: SteamAccountSession?,
        client: OkHttpClient,
        languagePreference: SteamLanguagePreference,
    ) {
        if (account == null) {
            synchronized(lock) {
                currentScope = null
                primedScope = null
                webLoginContext = null
            }
            return
        }
        val scope = "${account.steamId}:${account.refreshToken.hashCode()}"
        synchronized(lock) {
            if (primedScope == scope && webLoginContext != null) return
        }

        val accessToken = withContext(Dispatchers.IO) {
            SteamAuthenticationClient(
                directoryClient = SteamDirectoryClient(baseClient),
                sessionFactory = { identity.createSession(baseClient) },
        ).generateAccessTokenForApp(
            account = account,
            allowRenewal = false,
        ).accessToken
        }

        synchronized(lock) {
            currentScope = scope
            webLoginContext = SteamWebLoginContext(
                steamId = account.steamId,
                accessToken = accessToken,
                sessionId = generateSteamWebSessionId(),
            )
        }

        withContext(Dispatchers.IO) {
            listOf(
                "https://store.steampowered.com/account/preferences/",
                "https://steamcommunity.com/login/home/?goto=workshop%2F",
            ).forEach { url ->
                runCatching { primeUrl(client, url, languagePreference) }
            }
        }
        synchronized(lock) {
            primedScope = scope
        }
    }

    private fun projectedCookiesFor(url: HttpUrl): List<Cookie> {
        if (!url.host.isSteamDomain()) return emptyList()
        val context = synchronized(lock) { webLoginContext } ?: return emptyList()
        val domain = when {
            url.host == "steamcommunity.com" || url.host.endsWith(".steamcommunity.com") -> "steamcommunity.com"
            url.host == "steampowered.com" || url.host.endsWith(".steampowered.com") -> "steampowered.com"
            else -> url.host
        }
        return listOf(
            Cookie.Builder()
                .name("steamLoginSecure")
                .value("${context.steamId}||${context.accessToken}")
                .domain(domain)
                .path("/")
                .build(),
            Cookie.Builder()
                .name("sessionid")
                .value(context.sessionId)
                .domain(domain)
                .path("/")
                .build(),
        )
    }

    private fun primeUrl(
        client: OkHttpClient,
        url: String,
        languagePreference: SteamLanguagePreference,
    ) {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept-Language", languagePreference.acceptLanguageValue)
                .header("User-Agent", STEAM_WEB_SESSION_USER_AGENT)
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("Steam web session prime failed: ${response.code}")
        }
    }
}

internal class SteamWebSessionCookieJar(
    private val projectedCookiesProvider: (HttpUrl) -> List<Cookie> = { emptyList() },
    private val sessionScopeProvider: (() -> String?)? = null,
) : CookieJar {
    private val lock = Any()
    private val cookies = linkedMapOf<StoredCookieKey, Cookie>()
    private var isScopeInitialized = false
    private var currentScope: String? = null

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.host.isSteamDomain()) return
        syncScope()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            cookies.forEach { cookie ->
                val key = cookie.storageKey()
                if (cookie.expiresAt <= now) {
                    this.cookies.remove(key)
                } else {
                    this.cookies[key] = cookie
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.isSteamDomain()) return emptyList()
        syncScope()
        val persistedCookies = synchronized(lock) {
            val now = System.currentTimeMillis()
            val expiredKeys = mutableListOf<StoredCookieKey>()
            val matchingCookies = mutableListOf<Cookie>()
            cookies.forEach { (key, cookie) ->
                when {
                    cookie.expiresAt <= now -> expiredKeys += key
                    cookie.matches(url) -> matchingCookies += cookie
                }
            }
            expiredKeys.forEach(cookies::remove)
            matchingCookies
        }
        if (persistedCookies.isEmpty()) return projectedCookiesProvider(url)
        val merged = linkedMapOf<StoredCookieKey, Cookie>()
        persistedCookies.forEach { cookie -> merged[cookie.storageKey()] = cookie }
        projectedCookiesProvider(url).forEach { cookie -> merged[cookie.storageKey()] = cookie }
        return merged.values.toList()
    }

    private fun syncScope() {
        val provider = sessionScopeProvider ?: return
        val nextScope = provider()
        synchronized(lock) {
            if (!isScopeInitialized || currentScope != nextScope) {
                cookies.clear()
                currentScope = nextScope
                isScopeInitialized = true
            }
        }
    }
}

internal class SteamLanguageInterceptor(
    private val languagePreferenceProvider: () -> SteamLanguagePreference,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.isSteamDomain()) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Accept-Language", languagePreferenceProvider().acceptLanguageValue)
                .build()
        )
    }
}

private data class StoredCookieKey(
    val name: String,
    val domain: String,
    val path: String,
)

private fun Cookie.storageKey(): StoredCookieKey = StoredCookieKey(
    name = name.lowercase(),
    domain = domain,
    path = path,
)

private data class SteamWebLoginContext(
    val steamId: Long,
    val accessToken: String,
    val sessionId: String,
)

internal fun String.isSteamDomain(): Boolean {
    val normalizedHost = lowercase()
    return normalizedHost == "steamcommunity.com" ||
        normalizedHost.endsWith(".steamcommunity.com") ||
        normalizedHost == "steampowered.com" ||
        normalizedHost.endsWith(".steampowered.com")
}

private fun generateSteamWebSessionId(): String {
    val bytes = ByteArray(12)
    steamWebSessionRandom.nextBytes(bytes)
    val result = StringBuilder(bytes.size * 2)
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xFF
        result.append(HEX_CHARS[value ushr 4])
        result.append(HEX_CHARS[value and 0x0F])
    }
    return result.toString()
}

private val steamWebSessionRandom = SecureRandom()
private const val STEAM_WEB_SESSION_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
