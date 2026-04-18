"""
Скрипт для запуска сервера тестирования модерации.
Запуск: python run.py
"""
import subprocess
import sys
import os

def check_deps():
    """Проверяет и устанавливает зависимости."""
    reqs_path = os.path.join(os.path.dirname(__file__), "requirements.txt")
    print("[Setup] Installing dependencies (this may take a few minutes)...")
    sys.stdout.flush()
    ret = subprocess.call(
        [sys.executable, "-m", "pip", "install", "-r", reqs_path],
    )
    if ret != 0:
        print("[ERROR] Failed to install dependencies.")
        print("Try manually: pip install -r requirements.txt")
        sys.exit(1)
    print("[Setup] Dependencies OK")

def main():
    check_deps()

    print()
    print("=" * 60)
    print("  Content Moderation Tester")
    print("  Open http://localhost:8000 in your browser")
    print("  Press Ctrl+C to stop")
    print("=" * 60)
    print()

    import uvicorn
    os.chdir(os.path.dirname(__file__))
    uvicorn.run("app:app", host="0.0.0.0", port=8000, reload=False)

if __name__ == "__main__":
    main()
