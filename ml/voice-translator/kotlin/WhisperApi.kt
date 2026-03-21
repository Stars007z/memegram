package com.example.voicetranslator

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.http.*
import java.io.File

class WhisperApi(private val apiKey: String) {
    
    companion object {
        private const val TAG = "WhisperApi"
        private const val BASE_URL = "https://api.openai.com/v1/"
    }
    
    private val api = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(kotlinx.serialization.retrofit.ContentNegotiation.create())
        .build()
        .create(WhisperApiService::class.java)
    
    suspend fun transcribe(audioFile: File, language: String = "ru"): ApiTranscriptionResponse = 
        withContext(Dispatchers.IO) {
            try {
                val audioBody = audioFile.asRequestBody("audio/wav".toMediaType())
                val request = MultipartBody.Part.createFormData(
                    "file",
                    audioFile.name,
                    audioBody
                )
                
                val response = api.transcribe(
                    file = request,
                    model = "whisper-1",
                    language = language,
                    responseFormat = "json"
                )
                
                Log.d(TAG, "API Response: ${response.text}")
                response
            } catch (e: Exception) {
                Log.e(TAG, "API Error: ${e.message}")
                throw e
            }
        }
    
    suspend fun translate(text: String, targetLang: String = "ru"): String = 
        withContext(Dispatchers.IO) {
            val url = "https://api.mymemory.translated.net/get"
            val client = okhttp3.OkHttpClient.Builder().build()
            
            val request = okhttp3.Request.Builder()
                .url("$url?q=${java.net.URLEncoder.encode(text, "UTF-8")}&langpair=en|${targetLang}")
                .build()
            
            client.newCall(request).execute().use { response ->
                val jsonString = response.body?.string() ?: ""
                val regex = "\"translatedText\":\"([^\"]+)\"".toRegex()
                regex.find(jsonString)?.groupValues?.get(1) ?: text
            }
        }
    
    interface WhisperApiService {
        @Multipart
        @POST("audio/transcriptions")
        suspend fun transcribe(
            @Part file: MultipartBody.Part,
            @Part("model") model: String,
            @Part("language") language: String,
            @Part("responseFormat") responseFormat: String
        ): ApiTranscriptionResponse
    }
    
    data class ApiTranscriptionResponse(
        val text: String
    )
}
