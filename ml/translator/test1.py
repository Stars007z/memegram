pip install transformers torch sentencepiece protobuf

!pip install -U transformers torch sentencepiece

from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
import torch

model_name = "Helsinki-NLP/opus-mt-en-ru"
device = "cuda" if torch.cuda.is_available() else "cpu"

print(f" Загрузка модели на {device.upper()}...")

tokenizer = AutoTokenizer.from_pretrained(model_name)
model = AutoModelForSeq2SeqLM.from_pretrained(model_name)
model.to(device)
model.eval()

def translate(text):
    inputs = tokenizer(text, return_tensors="pt", padding=True).to(device)
    
    with torch.no_grad():
        outputs = model.generate(
            **inputs,
            max_length=512,
            num_beams=4, # качество перевода
            early_stopping=True
        )
    
    translation = tokenizer.decode(outputs[0], skip_special_tokens=True)
    return translation

texts = [
    "Hello, how are you?",
    "This application works offline.",
    "Right click to translate this message."
]

print("\n Модель готова. Перевод:\n")
for text in texts:
    result = translate(text)
    print(f"🇬🇧 EN: {text}")
    print(f"🇷🇺 RU: {result}")
    print("-" * 40)
