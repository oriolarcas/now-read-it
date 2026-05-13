package cat.oriol.nowreadit.data.remote

import android.util.Log
import cat.oriol.nowreadit.data.TextChunker
import cat.oriol.nowreadit.data.TtsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class OpenAiTtsClient(
    private val httpClient: OkHttpClient,
) {
    private val tag = "NowReadItTts"

    suspend fun synthesizeToFile(
        settings: TtsSettings,
        text: String,
        outputFile: File,
        onProgress: suspend (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val chunks = TextChunker.chunk(text)
        if (chunks.isEmpty()) error("There is no text to synthesize.")

        Log.i(tag, "Prepared ${chunks.size} TTS chunk(s) for model=${settings.model} voice=${settings.voice}")

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        FileOutputStream(outputFile, true).use { output ->
            chunks.forEachIndexed { index, chunk ->
                Log.i(tag, "Requesting TTS chunk ${index + 1}/${chunks.size} length=${chunk.length}")
                val body = JSONObject()
                    .put("model", settings.model)
                    .put("voice", settings.voice)
                    .put("input", chunk)
                    .put("response_format", "mp3")
                    .put("speed", settings.speed.toDouble())
                    .toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/audio/speech")
                    .header("Authorization", "Bearer ${settings.apiKey}")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string().orEmpty()
                        Log.e(tag, "TTS chunk ${index + 1}/${chunks.size} failed: HTTP ${response.code} $errorBody")
                        error("TTS request failed: HTTP ${response.code} $errorBody")
                    }

                    val bytes = response.body?.bytes() ?: error("TTS response was empty.")
                    output.write(bytes)
                    output.flush()
                    Log.i(tag, "Completed TTS chunk ${index + 1}/${chunks.size} bytes=${bytes.size}")
                }

                onProgress(((index + 1) * 100) / chunks.size)
            }
        }
    }
}
