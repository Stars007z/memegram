"""
Рекурсивно удаляет комментарии (начинающиеся с #) из всех .py файлов
в текущей папке и во всех вложенных папках.

Использование:
    python remove_comments.py           # обработать текущую папку
    python remove_comments.py path/to   # обработать указанную папку
    python remove_comments.py . --dry   # показать, что изменится, но не писать

Корректно обрабатывает:
  - # внутри строк ('...', "...", '''...''', \"\"\"...\"\"\") — не трогает
  - f-строки, r-строки, b-строки
  - экранированные кавычки
  - shebang (#!/usr/bin/env python) в первой строке — сохраняется
  - многострочные строки
Удаляет:
  - строки, состоящие только из комментария (полностью убирает строку)
  - inline-комментарии в конце строки кода (обрезает хвост, trailing пробелы чистит)
"""

from __future__ import annotations

import io
import sys
import tokenize
from pathlib import Path


def strip_comments(source: str, keep_shebang: bool = True) -> str:
    """Удаляет #-комментарии из python-исходника с помощью tokenize."""
    # tokenize требует bytes-поток или readline, возвращающий str.
    readline = io.StringIO(source).readline

    try:
        tokens = list(tokenize.generate_tokens(readline))
    except tokenize.TokenizeError:
        # Файл с синтаксической ошибкой — не трогаем.
        return source

    result_tokens = []
    for tok in tokens:
        if tok.type == tokenize.COMMENT:
            # Сохраняем shebang в самой первой строке.
            if keep_shebang and tok.start == (1, 0) and tok.string.startswith("#!"):
                result_tokens.append(tok)
            continue
        result_tokens.append(tok)

    try:
        untok = tokenize.untokenize(result_tokens)
    except ValueError:
        return source

    # После untokenize могут остаться строки только из пробелов — почистим хвосты.
    cleaned_lines = []
    for line in untok.splitlines():
        cleaned_lines.append(line.rstrip())

    # Удаляем подряд идущие пустые строки, появившиеся на месте комментариев,
    # оставляя максимум одну пустую строку подряд.
    collapsed = []
    prev_blank = False
    for line in cleaned_lines:
        is_blank = line == ""
        if is_blank and prev_blank:
            continue
        collapsed.append(line)
        prev_blank = is_blank

    # Финальный перевод строки, если исходник его имел.
    text = "\n".join(collapsed)
    if source.endswith("\n"):
        text += "\n"
    return text


def process_file(path: Path, dry_run: bool = False) -> bool:
    """Обрабатывает один файл. Возвращает True, если файл был изменён."""
    try:
        original = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError) as e:
        print(f"  [skip] {path}: {e}")
        return False

    new = strip_comments(original)
    if new == original:
        return False

    if dry_run:
        print(f"  [would change] {path}")
    else:
        path.write_text(new, encoding="utf-8")
        print(f"  [changed] {path}")
    return True


SKIP_DIRS = {".git", ".venv", "venv", "env", "__pycache__", ".idea", ".mypy_cache",
             ".pytest_cache", "node_modules", "build", "dist", ".tox"}


def iter_py_files(root: Path):
    for p in root.rglob("*.py"):
        # Пропускаем служебные директории.
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        # Не трогаем сам скрипт.
        if p.resolve() == Path(__file__).resolve():
            continue
        yield p


def main(argv: list[str]) -> int:
    dry_run = "--dry" in argv
    args = [a for a in argv if a != "--dry"]
    root = Path(args[0]).resolve() if args else Path.cwd()

    if not root.exists():
        print(f"Путь не существует: {root}")
        return 1

    print(f"Корень: {root}")
    print(f"Режим:  {'dry-run (без записи)' if dry_run else 'запись в файлы'}")
    print("-" * 60)

    total = 0
    changed = 0
    for py_file in iter_py_files(root):
        total += 1
        if process_file(py_file, dry_run=dry_run):
            changed += 1

    print("-" * 60)
    print(f"Всего .py файлов: {total}")
    print(f"Изменено:        {changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
