from hashlib import sha256
import json

from pydantic import BaseModel, ConfigDict

from .model import DIMENSION, MAX_TOKENS, MODEL_ID, MODEL_REVISION, QUERY_PREFIX

SPLITTER_VERSION = "bge-offset-window-v1"
CHUNK_TOKENS = 320
OVERLAP_TOKENS = 48
MAX_BODY_TOKENS = 510
MAX_CHUNK_BYTES = 65_535


class EmbeddingProfile(BaseModel):
    model_config = ConfigDict(frozen=True)

    fingerprint: str
    modelId: str
    revision: str
    dimension: int
    queryPrefix: str
    normalizeL2: bool
    splitterVersion: str
    chunkTokens: int
    overlapTokens: int
    maxBodyTokens: int
    maxTokens: int
    maxChunkBytes: int


class Chunk(BaseModel):
    ordinal: int
    content: str
    contentHash: str
    startOffset: int
    endOffset: int
    startToken: int
    endToken: int
    bodyTokens: int
    tokenCount: int


def content_hash(text: str) -> str:
    return sha256(text.encode("utf-8")).hexdigest()


def _profile() -> EmbeddingProfile:
    fields = {
        "modelId": MODEL_ID,
        "revision": MODEL_REVISION,
        "dimension": DIMENSION,
        "queryPrefix": QUERY_PREFIX,
        "normalizeL2": True,
        "splitterVersion": SPLITTER_VERSION,
        "chunkTokens": CHUNK_TOKENS,
        "overlapTokens": OVERLAP_TOKENS,
        "maxBodyTokens": MAX_BODY_TOKENS,
        "maxTokens": MAX_TOKENS,
        "maxChunkBytes": MAX_CHUNK_BYTES,
    }
    canonical = json.dumps(fields, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return EmbeddingProfile(**fields, fingerprint=content_hash(canonical))


PROFILE = _profile()


def split_content(content: str, tokenizer) -> list[Chunk]:
    offsets = tokenizer(
        content, add_special_tokens=False, truncation=False, return_offsets_mapping=True, verbose=False
    )["offset_mapping"]
    if not offsets:
        raise ValueError("Knowledge content has no tokens")

    chunks: list[Chunk] = []
    total = len(offsets)
    special_tokens = tokenizer.num_special_tokens_to_add(pair=False)
    body_limit = min(CHUNK_TOKENS, MAX_BODY_TOKENS)
    start = 0
    while start < total:
        start_offset = 0 if start == 0 else offsets[start][0]
        end = min(start + CHUNK_TOKENS, total)

        # A slice can tokenize differently when its first piece starts inside a word.
        # Recount the exact original slice before admitting it to an embedding batch.
        while end > start:
            end_offset = len(content) if end == total else offsets[end][0]
            part = content[start_offset:end_offset]
            token_count = len(tokenizer(part, add_special_tokens=True, truncation=False, verbose=False)["input_ids"])
            body_count = token_count - special_tokens
            if 0 < body_count <= body_limit and token_count <= MAX_TOKENS and len(part.encode("utf-8")) <= MAX_CHUNK_BYTES:
                break
            end -= 1
        if end == start or (end < total and end - start <= OVERLAP_TOKENS):
            raise ValueError("Cannot build a bounded knowledge chunk")

        chunks.append(Chunk(
            ordinal=len(chunks), content=part, contentHash=content_hash(part),
            startOffset=start_offset, endOffset=end_offset, startToken=start, endToken=end,
            bodyTokens=body_count, tokenCount=token_count,
        ))
        if end == total:
            break
        start = end - OVERLAP_TOKENS

    return chunks
