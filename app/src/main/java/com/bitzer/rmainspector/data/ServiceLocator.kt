package com.bitzer.rmainspector.data

import android.content.Context
import com.bitzer.rmainspector.BuildConfig
import java.io.File

/** One settings store, one session store and one HTTP client for the whole app. */
object ServiceLocator {
    @Volatile private var store: SettingsStore? = null
    @Volatile private var authStore: AuthStore? = null

    /** Sends the signed-in token on every call; a 401 for that token returns the app to sign-in. */
    val api: ApiClient by lazy {
        ApiClient(
            tokenProvider = { authStore?.token },
            onUnauthorized = { rejected -> authStore?.expire(rejected) },
            appVersion = BuildConfig.VERSION_NAME,
            deviceIdProvider = { authStore?.deviceId },
        )
    }

    @Volatile private var codeListRepo: CodeListRepository? = null
    @Volatile private var recent: RecentCodesStore? = null

    /** Code lists kept on the device (spec §4.3). */
    fun codeLists(context: Context): CodeListRepository =
        codeListRepo ?: synchronized(this) {
            codeListRepo ?: CodeListRepository(api.asCodeListSource(),
                FileCodeListCache(File(context.applicationContext.filesDir, "codelists/codelists.json"))).also { codeListRepo = it }
        }

    /** "Recently used" codes per picker. */
    fun recentCodes(context: Context): RecentCodesStore =
        recent ?: synchronized(this) {
            recent ?: RecentCodesStore(File(context.applicationContext.filesDir, "codelists/recent.json")).also { recent = it }
        }

    @Volatile private var draftStore: DraftStore? = null

    /** Local inspection drafts, one file per inspector and RMA (spec §4.3, §8). */
    fun drafts(context: Context): DraftStore =
        draftStore ?: synchronized(this) {
            draftStore ?: DraftStore(File(context.applicationContext.filesDir, "drafts")).also { draftStore = it }
        }

    @Volatile private var photoStore: PhotoStore? = null

    /** Photos taken on this tablet, stored before anything is sent (spec §6.3). */
    fun photos(context: Context): PhotoStore =
        photoStore ?: synchronized(this) {
            photoStore ?: PhotoStore(File(context.applicationContext.filesDir, "photos")).also { photoStore = it }
        }

    @Volatile private var guidedStore: GuidedProgressStore? = null

    /** Guided inspection progress per inspector and RMA (spec §5). */
    fun guidedProgress(context: Context): GuidedProgressStore =
        guidedStore ?: synchronized(this) {
            guidedStore ?: GuidedProgressStore(File(context.applicationContext.filesDir, "guided")).also { guidedStore = it }
        }

    @Volatile private var reportDraftStore: ReportDraftStore? = null

    /** Report tab drafts (defect code, summary, by-fields, report-photo picks) per RMA. */
    fun reportDrafts(context: Context): ReportDraftStore =
        reportDraftStore ?: synchronized(this) {
            reportDraftStore ?: ReportDraftStore(File(context.applicationContext.filesDir, "report_drafts/report_drafts.json")).also { reportDraftStore = it }
        }

    fun settings(context: Context): SettingsStore =
        store ?: synchronized(this) {
            store ?: SettingsStore(context.applicationContext, BuildConfig.DEFAULT_SERVER_URL).also { store = it }
        }

    fun auth(context: Context): AuthStore =
        authStore ?: synchronized(this) {
            authStore ?: AuthStore.create(context.applicationContext).also { authStore = it }
        }
}
