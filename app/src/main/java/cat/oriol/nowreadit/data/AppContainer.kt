package cat.oriol.nowreadit.data

import android.content.Context
import androidx.work.WorkManager
import cat.oriol.nowreadit.data.local.LibraryStore
import cat.oriol.nowreadit.data.remote.OpenAiTtsClient
import cat.oriol.nowreadit.data.remote.PageContentExtractor
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val httpClient = OkHttpClient()
    private val libraryStore = LibraryStore(appContext)
    val settingsStore = SettingsStore(appContext)
    private val pageContentExtractor = PageContentExtractor(httpClient)
    private val ttsClient = OpenAiTtsClient(httpClient)
    val workManager: WorkManager = WorkManager.getInstance(appContext)

    val libraryRepository = LibraryRepository(
        appContext = appContext,
        libraryStore = libraryStore,
        pageContentExtractor = pageContentExtractor,
        settingsStore = settingsStore,
        workManager = workManager,
        ttsClient = ttsClient,
    )
}
