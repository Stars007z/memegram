#!/usr/bin/env python3
"""Test if Viterbi (unigram-style) decoding with SPM piece scores matches SPM output."""
import sys
sys.stdout.reconfigure(encoding='utf-8')

from sentencepiece import sentencepiece_model_pb2
import sentencepiece as spm
import json

# Load SPM model
model = sentencepiece_model_pb2.ModelProto()
with open('exported_models/opus-mt-en-ru/source.spm', 'rb') as f:
    model.ParseFromString(f.read())

# Build piece -> score mapping
piece_scores = {}
max_piece_len = 0
for p in model.pieces:
    if p.type == 1:  # NORMAL
        piece_scores[p.piece] = p.score
        max_piece_len = max(max_piece_len, len(p.piece))

# Also add special tokens
for p in model.pieces:
    if p.type in (2, 3):  # CONTROL, USER_DEFINED
        piece_scores[p.piece] = 0.0

print(f'Pieces: {len(piece_scores)}, max piece length: {max_piece_len}')

UNK_SCORE = -100.0  # penalty for unknown characters


def viterbi_encode(text_with_prefix):
    """Segment text using Viterbi (dynamic programming) to maximize total score."""
    n = len(text_with_prefix)
    # dp[i] = (best_score, best_piece_end) for text[0:i]
    dp = [None] * (n + 1)
    dp[0] = (0.0, 0, '')  # (score, start, piece)
    
    for i in range(1, n + 1):
        best = None
        # Try all possible pieces ending at position i
        for length in range(1, min(i, max_piece_len) + 1):
            j = i - length
            if dp[j] is None:
                continue
            piece = text_with_prefix[j:i]
            score = piece_scores.get(piece, None)
            if score is not None:
                total = dp[j][0] + score
                if best is None or total > best[0]:
                    best = (total, j, piece)
            elif length == 1:
                # Single unknown character fallback
                total = dp[j][0] + UNK_SCORE
                if best is None or total > best[0]:
                    best = (total, j, piece)
        dp[i] = best
    
    # Backtrace
    pieces = []
    i = n
    while i > 0:
        if dp[i] is None:
            break
        _, j, piece = dp[i]
        pieces.append(piece)
        i = j
    pieces.reverse()
    return pieces


# Load vocab for ID mapping
with open('exported_models/opus-mt-en-ru/vocab.json', 'r', encoding='utf-8') as f:
    vocab = json.load(f)

# Load SPM for reference
sp = spm.SentencePieceProcessor()
sp.Load('exported_models/opus-mt-en-ru/source.spm')

# Load HF tokenizer
from transformers import MarianTokenizer
tok = MarianTokenizer.from_pretrained('Helsinki-NLP/opus-mt-en-ru')

PREFIX = '\u2581'

test_texts = [
    'Hello, how are you?',
    'The quick brown fox',
    'Machine learning is great',
    'I love programming',
    'This is a test sentence',
    'The United Nations announced new climate policies',
    'She said hello to everyone',
]

all_match = True
for text in test_texts:
    # Our Viterbi encoding
    words = text.strip().split()
    our_tokens = []
    for w in words:
        our_tokens.extend(viterbi_encode(PREFIX + w))
    our_ids = [vocab.get(t, 0) for t in our_tokens]
    
    # SPM encoding
    spm_pieces = sp.EncodeAsPieces(text)
    
    # HF encoding
    hf_ids = tok.encode(text)
    
    pieces_match = our_tokens == spm_pieces
    ids_match = our_ids == hf_ids[:-1]  # HF appends EOS
    
    print(f'Text: {text}')
    print(f'  Viterbi: {our_tokens}')
    print(f'  SPM:     {spm_pieces}')
    print(f'  Pieces match: {pieces_match}')
    print(f'  Our IDs: {our_ids}')
    print(f'  HF IDs:  {hf_ids}')
    print(f'  IDs match: {ids_match}')
    if not ids_match:
        all_match = False
    print()

print(f'ALL MATCH: {all_match}')
