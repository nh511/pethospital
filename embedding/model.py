import os
from pathlib import Path
from typing import Literal

MODEL_ID = "BAAI/bge-small-zh-v1.5"
MODEL_REVISION = "7999e1d3359715c523056ef9478215996d62a620"
DIMENSION = 512
MAX_TOKENS = 512
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
Mode = Literal["query", "passages"]


def model_path() -> Path:
    cache = Path(os.environ.get("EMBEDDING_CACHE_DIR", ".local/embedding/models"))
    return cache / MODEL_REVISION


def load_model():
    from sentence_transformers import SentenceTransformer

    path = model_path()
    if not (path / "model.safetensors").is_file():
        raise RuntimeError("缺少固定版本模型权重，请先执行 embedding.prepare_model")
    model = SentenceTransformer(
        str(path), device="cpu", local_files_only=True, trust_remote_code=False,
        model_kwargs={"use_safetensors": True},
    )
    if model.get_embedding_dimension() != DIMENSION or model.max_seq_length != MAX_TOKENS:
        raise RuntimeError("模型的维度或长度与冻结配置不符")
    if not model.tokenizer.is_fast:
        raise RuntimeError("固定模型需要支持偏移量的 fast tokenizer")
    return model
