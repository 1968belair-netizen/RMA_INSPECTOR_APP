package com.bitzer.rmainspector.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.io.File

/** Where the code lists come from — the app server in the app, a fake in unit tests. */
fun interface CodeListSource {
    suspend fun fetch(baseUrl: String, etag: String?): ApiResult<CodeListFetch>
}

fun ApiClient.asCodeListSource(): CodeListSource = CodeListSource { url, etag -> codeListsConditional(url, etag) }

/** What is kept on the device: the `/codelists` body exactly as received, plus where and when it came from. */
@Serializable
data class CachedCodeLists(
    @SerialName("server_url") val serverUrl: String,
    val etag: String? = null,
    @SerialName("fetched_at_ms") val fetchedAtMillis: Long,
    /** Raw JSON body from the server — never re-encoded, so every code round-trips byte for byte. */
    val raw: String,
)

interface CodeListCache {
    fun read(): CachedCodeLists?
    fun write(entry: CachedCodeLists)
    fun clear()
}

/** One JSON file in the app's private storage (no secrets, fake data in tests). */
class FileCodeListCache(private val file: File) : CodeListCache {
    override fun read(): CachedCodeLists? = runCatching {
        if (!file.exists()) null else ApiClient.defaultJson.decodeFromString(CachedCodeLists.serializer(), file.readText())
    }.getOrNull()

    override fun write(entry: CachedCodeLists) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ApiClient.writeJson.encodeToString(CachedCodeLists.serializer(), entry))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    override fun clear() { file.delete() }
}

data class CodeListsState(
    val lists: CodeLists? = null,
    val fetchedAtMillis: Long? = null,
    val refreshing: Boolean = false,
    /** Last refresh problem (safe to show). Cached lists stay usable when this is set. */
    val error: String? = null,
) {
    val available: Boolean get() = lists != null
}

enum class RefreshOutcome { UPDATED, NOT_MODIFIED, SKIPPED_FRESH, FAILED }

/**
 * Code lists offline (spec §4.3, api §4): download once, keep on the device, refresh with `If-None-Match`
 * on sign-in / app start and every few hours. A `304` only bumps the fetch time. A failed refresh keeps the
 * cached lists. Lists from a different server address are not used.
 */
class CodeListRepository(
    private val source: CodeListSource,
    private val cache: CodeListCache,
    private val clock: () -> Long = System::currentTimeMillis,
    val maxAgeMillis: Long = DEFAULT_MAX_AGE_MS,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(CodeListsState())
    val state: StateFlow<CodeListsState> = _state.asStateFlow()
    private var cached: CachedCodeLists? = null
    private var loaded = false

    /** Loads the device copy (once). Call before reading [state] offline. */
    suspend fun loadCached(serverUrl: String? = null) = mutex.withLock { loadLocked(serverUrl) }

    private suspend fun loadLocked(serverUrl: String?) {
        if (!loaded) {
            cached = withContext(Dispatchers.IO) { cache.read() }
            loaded = true
        }
        val c = cached?.takeIf { serverUrl == null || it.serverUrl == serverUrl }
        val lists = c?.let { parse(it.raw) }
        _state.update { it.copy(lists = lists, fetchedAtMillis = if (lists != null) c.fetchedAtMillis else null) }
    }

    /**
     * Refreshes from the server. [force] = ignore the age check (sign-in, pull to refresh).
     * Never throws; problems end up in [CodeListsState.error].
     */
    suspend fun refresh(serverUrl: String, force: Boolean = false): RefreshOutcome = mutex.withLock {
        loadLocked(serverUrl)
        val current = cached?.takeIf { it.serverUrl == serverUrl && parse(it.raw) != null }
        if (!force && current != null && clock() - current.fetchedAtMillis < maxAgeMillis) {
            return@withLock RefreshOutcome.SKIPPED_FRESH
        }
        _state.update { it.copy(refreshing = true) }
        when (val r = source.fetch(serverUrl, current?.etag)) {
            is ApiResult.Failure -> {
                _state.update { it.copy(refreshing = false, error = r.message) }
                RefreshOutcome.FAILED
            }
            is ApiResult.Ok -> when (val f = r.value) {
                CodeListFetch.NotModified -> {
                    if (current == null) {
                        // 304 without a copy to keep (shouldn't happen) → ask again without the tag.
                        _state.update { it.copy(refreshing = false) }
                        return@withLock fetchFull(serverUrl)
                    }
                    store(current.copy(fetchedAtMillis = clock()))
                    RefreshOutcome.NOT_MODIFIED
                }
                is CodeListFetch.Fresh -> {
                    store(CachedCodeLists(serverUrl, f.etag, clock(), f.raw))
                    RefreshOutcome.UPDATED
                }
            }
        }
    }

    /** Refresh only when the device copy is older than [maxAgeMillis] (or missing). */
    suspend fun refreshIfStale(serverUrl: String): RefreshOutcome = refresh(serverUrl, force = false)

    private suspend fun fetchFull(serverUrl: String): RefreshOutcome {
        _state.update { it.copy(refreshing = true) }
        return when (val r = source.fetch(serverUrl, null)) {
            is ApiResult.Ok -> {
                val f = r.value
                if (f is CodeListFetch.Fresh) {
                    store(CachedCodeLists(serverUrl, f.etag, clock(), f.raw)); RefreshOutcome.UPDATED
                } else {
                    _state.update { it.copy(refreshing = false, error = "The server didn't send the code lists.") }
                    RefreshOutcome.FAILED
                }
            }
            is ApiResult.Failure -> {
                _state.update { it.copy(refreshing = false, error = r.message) }; RefreshOutcome.FAILED
            }
        }
    }

    private suspend fun store(entry: CachedCodeLists) {
        withContext(Dispatchers.IO) { cache.write(entry) }
        cached = entry
        _state.value = CodeListsState(lists = parse(entry.raw), fetchedAtMillis = entry.fetchedAtMillis)
    }

    private fun parse(raw: String): CodeLists? =
        runCatching { ApiClient.defaultJson.decodeFromString(CodeLists.serializer(), raw) }.getOrNull()

    companion object {
        /** "Every few hours" (spec §4.3): 4 h. */
        const val DEFAULT_MAX_AGE_MS: Long = 4L * 60 * 60 * 1000
        /** How often the app checks whether a refresh is due. */
        const val CHECK_INTERVAL_MS: Long = 15L * 60 * 1000
    }
}

/**
 * "Recently used" codes per picker (e.g. `parts`, `issues`), newest first, kept on the device. Stores code keys
 * exactly as received.
 */
class RecentCodesStore(private val file: File, private val max: Int = 8) {
    @Serializable
    private data class Data(val lists: Map<String, List<CodeKey>> = emptyMap())

    private val lock = Any()
    private var data: Data? = null

    private fun load(): Data = data ?: runCatching {
        if (file.exists()) ApiClient.defaultJson.decodeFromString(Data.serializer(), file.readText()) else Data()
    }.getOrDefault(Data()).also { data = it }

    fun get(list: String): List<CodeKey> = synchronized(lock) { load().lists[list].orEmpty() }

    /** Forgets every recent code (emulator tests only, so one test's picks don't reorder another's pickers). */
    fun clearAll() = synchronized(lock) { data = Data(); file.delete(); Unit }

    fun record(list: String, key: CodeKey) = synchronized(lock) {
        val d = load()
        val next = (listOf(key) + d.lists[list].orEmpty().filter { it != key }).take(max)
        val nd = d.copy(lists = d.lists + (list to next))
        data = nd
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(ApiClient.writeJson.encodeToString(Data.serializer(), nd))
        }
        Unit
    }
}
