package cat.oriol.nowreadit

import android.app.Application
import cat.oriol.nowreadit.data.AppContainer

class NowReadItApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }
}
