package com.example.translator

import android.content.Context
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer

class OnDeviceTranslator(context: Context) {
    private val session: OrtSession
    private val tokenizer: SimpleTokenizer

    init {
        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions()
        sessionOptions.addCPU(true)
        
        val modelBytes = context.assets.open("model/decoder_model.onnx").readBytes()
        session = env.createSession(modelBytes, sessionOptions)
        
        tokenizer = SimpleTokenizer(context)
    }

    suspend fun translate(text: String): String = withContext(Dispatchers.Default) {
        try {
            // 1. Токенизация
            val inputIds = tokenizer.encode(text)
            
            // 2. Создание тензора
            val inputShape = longArrayOf(1, inputIds.size.toLong())
            val inputBuffer = LongBuffer.wrap(inputIds)
            val inputTensor = OnnxTensor.createTensor(
                session.environment, 
                inputBuffer, 
                inputShape
            )
            
            // 3. Запуск модели
            val inputs = mapOf("input_ids" to inputTensor)
            val results = session.run(inputs)
            
            // 4. Получение результата
            val outputIds = extractOutputIds(results)
            
            // 5. Декодирование
            tokenizer.decode(outputIds)
        } catch (e: Exception) {
            "Ошибка перевода: ${e.message}"
        }
    }
    
    private fun extractOutputIds(results: OrtSession.Result): List<Long> {
        val output = results.get(0).value as Array<*>
        return output.flatten().filterIsInstance<Long>()
    }
    
    fun close() {
        session.close()
    }
}
