package cat.oriol.nowreadit.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TtsSettings(
    val apiKey: String = "",
    val model: String = "gpt-4o-mini-tts",
    val voice: String = "alloy",
    val speed: Float = 1.0f,
)

class SettingsStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val preferences = EncryptedSharedPreferences.create(
        context,
        "now-read-it-settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    suspend fun load(): TtsSettings = withContext(Dispatchers.IO) {
        TtsSettings(
            apiKey = preferences.getString(KEY_API, "").orEmpty(),
            model = preferences.getString(KEY_MODEL, "gpt-4o-mini-tts").orEmpty(),
            voice = preferences.getString(KEY_VOICE, "alloy").orEmpty(),
            speed = preferences.getFloat(KEY_SPEED, 1.0f),
        )
    }

    suspend fun save(settings: TtsSettings) = withContext(Dispatchers.IO) {
        preferences.edit()
            .putString(KEY_API, settings.apiKey.trim())
            .putString(KEY_MODEL, settings.model.trim())
            .putString(KEY_VOICE, settings.voice.trim())
            .putFloat(KEY_SPEED, settings.speed)
            .apply()
    }

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_VOICE = "voice"
        private const val KEY_SPEED = "speed"
    }
}
