package com.example.voicetranslator

import android.util.Log
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WhisperApi(private val apiKey: String) {

    companion object {
        private const val TAG = "WhisperApi"
        private const val BASE_URL = "https://api.openai.com/v1/"
        private const val TRANSLATE_URL = "https://api.mymemory.translated.net/get"

        private val TEXT_PLAIN = "text/plain".toMediaType()
    }

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val api: WhisperApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WhisperApiService::class.java)

    /**
     * Транскрибация через OpenAI Whisper API.
     * Если language == "auto" — не передаём параметр, API определит сам.
     */
    suspend fun transcribe(
        audioFile: File,
        language: String = "auto"
    ): ApiTranscriptionResponse = withContext(Dispatchers.IO) {
        val mime = when (audioFile.extension.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }.toMediaType()

        val audioBody = audioFile.asRequestBody(mime)
        val filePart = MultipartBody.Part.createFormData(
            "file", audioFile.name, audioBody
        )

        val modelPart = "whisper-1".toRequestBody(TEXT_PLAIN)
        val formatPart = "json".toRequestBody(TEXT_PLAIN)
        val languagePart = if (language.isNotBlank() && language != "auto") {
            language.toRequestBody(TEXT_PLAIN)
        } else null

        try {
            val response = api.transcribe(
                auth = "Bearer $apiKey",
                file = filePart,
                model = modelPart,
                language = languagePart,
                responseFormat = formatPart
            )
            Log.d(TAG, "API response: ${response.text.take(100)}")
            response
        } catch (e: Exception) {
            Log.e(TAG, "API error: ${e.message}", e)
            throw e
        }
    }

    /**
     * Перевод через MyMemory. sourceLang обязателен — жёстко забивать "en" нельзя.
     * Используется как fallback; в ON_DEVICE режиме лучше использовать ML Kit.
     */
    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String = "ru"
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank() || sourceLang == targetLang) return@withContext text

        val q = URLEncoder.encode(text, "UTF-8")
        val pair = "$sourceLang|$targetLang"
        val request = Request.Builder()
            .url("$TRANSLATE_URL?q=$q&langpair=$pair")
            .build()

        okHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Translate HTTP ${response.code}")
                return@withContext text
            }
            val body = response.body?.string().orEmpty()
            parseTranslation(body) ?: text
        }
    }

    private fun parseTranslation(json: String): String? = try {
        val root = JsonParser.parseString(json).asJsonObject
        root.getAsJsonObject("responseData")
            ?.get("translatedText")
            ?.asString
    } catch (e: Exception) {
        Log.e(TAG, "Translation parse error: ${e.message}")
        null
    }

    interface WhisperApiService {
        @Multipart
        @POST("audio/transcriptions")
        suspend fun transcribe(
            @Header("Authorization") auth: String,
            @Part file: MultipartBody.Part,
            @Part("model") model: okhttp3.RequestBody,
            @Part("language") language: okhttp3.RequestBody?,
            @Part("response_format") responseFormat: okhttp3.RequestBody
        ): ApiTranscriptionResponse
    }

    data class ApiTranscriptionResponse(
        @SerializedName("text") val text: String,
        @SerializedName("language") val detectedLanguage: String? = null
    )
}
