import math

import pytest
from fastapi.testclient import TestClient

from embedding.app import app
from embedding.model import DIMENSION, MAX_TOKENS, MODEL_ID, MODEL_REVISION, QUERY_PREFIX

TOKEN = "m1103-local-test-fixture"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


@pytest.fixture(scope="module")
def client():
    with pytest.MonkeyPatch.context() as env:
        env.setenv("EMBEDDING_INTERNAL_TOKEN", TOKEN)
        with TestClient(app) as active:
            yield active


def request(client, endpoint, mode, inputs):
    return client.post(f"/internal/{endpoint}", headers=AUTH, json={"mode": mode, "inputs": inputs})


def test_health_and_inference_endpoints_require_internal_token(client):
    assert client.get("/internal/health").status_code == 401
    assert client.get("/internal/health", headers={"Authorization": "Bearer different-value"}).status_code == 401
    payload = {"mode": "query", "inputs": ["宠物健康"]}
    for endpoint in ("tokenize", "embeddings"):
        assert client.post(f"/internal/{endpoint}", json=payload).status_code == 401
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path, headers=AUTH).status_code == 404

    response = client.get("/internal/health", headers=AUTH)
    assert response.status_code == 200
    assert response.json() == {
        "modelId": MODEL_ID, "revision": MODEL_REVISION, "dimension": DIMENSION,
        "maxTokens": MAX_TOKENS, "status": "ready", "device": "cpu",
    }
    assert client.app.state.model.device.type == "cpu"


def test_real_vectors_have_unit_length_and_keep_one_loaded_model(client):
    model = client.app.state.model
    text = "如何记录猫的日常饮水量？"
    query = request(client, "embeddings", "query", [text])
    passages = request(client, "embeddings", "passages", [text, "定期清洁饮水容器。"])
    assert query.status_code == passages.status_code == 200

    vectors = query.json()["vectors"] + passages.json()["vectors"]
    assert len(vectors) == 3
    for vector in vectors:
        assert len(vector) == DIMENSION
        assert all(math.isfinite(value) for value in vector)
        assert math.sqrt(sum(value * value for value in vector)) == pytest.approx(1, abs=1e-5)
    assert vectors[0] != pytest.approx(vectors[1], abs=1e-5)
    assert client.app.state.model is model


def test_token_ids_prove_query_prefix_and_passage_offsets(client):
    tokenizer = client.app.state.model.tokenizer
    text = "给犬更换干净的水"
    for mode, prefix in (("query", QUERY_PREFIX), ("passages", "")):
        response = request(client, "tokenize", mode, [text])
        assert response.status_code == 200
        result = response.json()
        expected = tokenizer(prefix + text, add_special_tokens=True, return_offsets_mapping=True)
        assert result["prefixLength"] == len(prefix)
        assert result["items"] == [{
            "tokenIds": expected["input_ids"],
            "offsets": [list(offset) for offset in expected["offset_mapping"]],
            "tokenCount": len(expected["input_ids"]),
        }]


def test_over_limit_is_rejected_before_inference_without_silent_truncation(client):
    limit = "a " * (MAX_TOKENS - 2)
    response = request(client, "tokenize", "passages", [limit])
    assert response.status_code == 200
    assert response.json()["items"][0]["tokenCount"] == MAX_TOKENS
    assert request(client, "embeddings", "passages", [limit]).status_code == 200
    for endpoint in ("tokenize", "embeddings"):
        assert request(client, endpoint, "passages", [limit + "a"]).status_code == 422
        assert request(client, endpoint, "query", [limit]).status_code == 422
        assert request(client, endpoint, "passages", ["正常", "   "]).status_code == 422
    response = client.post(
        "/internal/embeddings", headers=AUTH, json={"mode": "query", "inputs": ["文本"], "model": "other"}
    )
    assert response.status_code == 422
    assert response.json() == {"detail": "Invalid embedding request"}
