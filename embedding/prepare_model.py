from huggingface_hub import snapshot_download

from .model import MODEL_ID, MODEL_REVISION, model_path

MODEL_FILES = (
    "model.safetensors",
    "config.json",
    "config_sentence_transformers.json",
    "modules.json",
    "sentence_bert_config.json",
    "special_tokens_map.json",
    "tokenizer.json",
    "tokenizer_config.json",
    "vocab.txt",
    "1_Pooling/config.json",
)


def main() -> None:
    path = model_path()
    snapshot_download(
        repo_id=MODEL_ID,
        revision=MODEL_REVISION,
        local_dir=path,
        allow_patterns=MODEL_FILES,
        token=False,
    )
    if any(not (path / filename).is_file() for filename in MODEL_FILES):
        raise RuntimeError("固定版本模型缓存不完整")
    print(f"Embedding model cached: {MODEL_ID}@{MODEL_REVISION}")


if __name__ == "__main__":
    main()
