from hashlib import sha256
import json

import pytest
from fastapi.testclient import TestClient

from embedding.app import app
from embedding.model import DIMENSION, MODEL_ID, MODEL_REVISION, QUERY_PREFIX
from embedding.splitting import CHUNK_TOKENS, MAX_CHUNK_BYTES, OVERLAP_TOKENS, SPLITTER_VERSION

TOKEN = "m1104-local-test-fixture"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


@pytest.fixture(scope="module")
def client():
    with pytest.MonkeyPatch.context() as env:
        env.setenv("EMBEDDING_INTERNAL_TOKEN", TOKEN)
        with TestClient(app) as active:
            yield active


def chunks(client, text):
    return client.post("/internal/chunks", headers=AUTH, json={"content": text})


def test_mixed_paragraphs_reconstruct_original_and_keep_stable_overlap(client):
    paragraph = "猫的饮水量变化。Record 200 mL and check again. Café 🐈 e\u0301.\r\n"
    text = "\t \n" + (paragraph + "\r\n下一段保留间距。\n\n") * 65 + "  \n尾注结束。\t "
    response = chunks(client, text)
    assert response.status_code == 200
    items = response.json()["chunks"]
    assert len(items) > 3
    assert items[0]["startOffset"] == items[0]["startToken"] == 0
    assert items[-1]["endOffset"] == len(text)

    reconstructed = ""
    last_offset = 0
    for ordinal, item in enumerate(items):
        start, end = item["startOffset"], item["endOffset"]
        assert item["ordinal"] == ordinal
        assert start <= last_offset < end
        assert item["content"] == text[start:end]
        assert item["contentHash"] == sha256(item["content"].encode("utf-8")).hexdigest()
        assert 0 < item["bodyTokens"] <= CHUNK_TOKENS
        assert item["tokenCount"] == item["bodyTokens"] + 2 <= 512
        assert len(item["content"].encode("utf-8")) <= MAX_CHUNK_BYTES
        if ordinal:
            assert items[ordinal - 1]["endToken"] - item["startToken"] == OVERLAP_TOKENS
        reconstructed += item["content"][last_offset - start:]
        last_offset = end
    assert reconstructed == text

    tokenized = client.post(
        "/internal/tokenize", headers=AUTH,
        json={"mode": "passages", "inputs": [item["content"] for item in items]},
    )
    assert tokenized.status_code == 200
    assert [item["tokenCount"] for item in tokenized.json()["items"]] == [item["tokenCount"] for item in items]


def test_exact_window_and_overlap_are_measured_in_source_tokens(client):
    text = "a " * 640
    response = chunks(client, text)
    assert response.status_code == 200
    items = response.json()["chunks"]
    assert [(item["startToken"], item["endToken"], item["bodyTokens"]) for item in items] == [
        (0, 320, 320), (272, 592, 320), (544, 640, 96)
    ]


def test_profile_and_chunks_are_deterministic_and_the_splitter_cannot_be_overridden(client):
    text = "犬科知识和 water intake。" * 75
    original = chunks(client, text)
    repeated = chunks(client, text)
    assert original.status_code == repeated.status_code == 200
    assert original.json() == repeated.json()

    profile = original.json()["profile"].copy()
    fingerprint = profile.pop("fingerprint")
    assert profile == {
        "modelId": MODEL_ID, "revision": MODEL_REVISION, "dimension": DIMENSION,
        "queryPrefix": QUERY_PREFIX, "normalizeL2": True, "splitterVersion": SPLITTER_VERSION,
        "chunkTokens": 320, "overlapTokens": 48, "maxBodyTokens": 510, "maxTokens": 512,
        "maxChunkBytes": 65535,
    }
    canonical = json.dumps(profile, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    assert fingerprint == sha256(canonical.encode("utf-8")).hexdigest()

    assert client.post("/internal/chunks", headers=AUTH, json={"content": text, "splitterVersion": "other"}).status_code == 422
    assert client.post("/internal/chunks", json={"content": text}).status_code == 401


def test_unsplittable_text_is_rejected_and_raw_embedding_still_has_a_hard_limit(client):
    assert chunks(client, " \t\n ").status_code == 422
    assert chunks(client, "a" * (MAX_CHUNK_BYTES + 1)).status_code == 422
    text = "a " * 700
    direct = client.post("/internal/embeddings", headers=AUTH, json={"mode": "passages", "inputs": [text]})
    assert direct.status_code == 422
    response = chunks(client, text)
    assert response.status_code == 200
    batch = [item["content"] for item in response.json()["chunks"]]
    inferred = client.post("/internal/embeddings", headers=AUTH, json={"mode": "passages", "inputs": batch})
    assert inferred.status_code == 200
    assert len(inferred.json()["vectors"]) == len(batch)
    assert all(len(vector) == DIMENSION for vector in inferred.json()["vectors"])
