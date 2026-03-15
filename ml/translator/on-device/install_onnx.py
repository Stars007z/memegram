!pip install optimum[onnxruntime] transformers torch sentencepiece

from optimum.onnxruntime import ORTModelForSeq2SeqLM
from transformers import AutoTokenizer

model_id = "Helsinki-NLP/opus-mt-en-ru"
output_dir = "onnx_model"

print("Конвертация модели в ONNX...")

model = ORTModelForSeq2SeqLM.from_pretrained(
    model_id,
    from_transformers=True,
    export=True
)
tokenizer = AutoTokenizer.from_pretrained(model_id)

model.save_pretrained(output_dir)
tokenizer.save_pretrained(output_dir)

import os
total_size = sum(
    os.path.getsize(os.path.join(dirpath, f))
    for dirpath, _, filenames in os.walk(output_dir)
    for f in filenames
)
print(f"Размер: {total_size / 1024 / 1024:.2f} МБ")
print(f"Папка: {output_dir}")
