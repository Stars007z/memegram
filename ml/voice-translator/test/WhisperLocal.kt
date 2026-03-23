class WhisperLocal(private val context: Context) {
    
    init {
        System.loadLibrary("whisperjni")
    }
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        // 1. Копируем модель из assets
        val modelFile = copyModelFromAssets("ggml-small-q5_1.bin")
        
        // 2. Проверяем размер файла (должен быть ~200MB)
        if (modelFile.length() < 100_000_000) {
            Log.e("Whisper", "Model file too small!")
            return@withContext false
        }
        
        // 3. Инициализируем нативную библиотеку
        return@withContext initModel(modelFile.absolutePath)
    }
    
    private external fun initModel(modelPath: String): Boolean
    private external fun transcribeFile(audioPath: String, language: String): String
    private external fun releaseModel()
}
