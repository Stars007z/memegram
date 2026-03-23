# 1. Клонируем whisper.cpp
cd app/src/main/cpp
git submodule add https://github.com/ggerganov/whisper.cpp.git

# 2. Скачиваем модель
cd whisper.cpp
./models/download-ggml-model.sh small-q5_1

# 3. Копируем модель в assets
cp models/ggml-small-q5_1.bin ../../../src/main/assets/

# 4. Собираем проект
./gradlew assembleDebug

# 5. Запускаем тесты
./gradlew connectedAndroidTest
