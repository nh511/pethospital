# 本地 Embedding 服务

服务使用固定 revision 的 BGE-small 中文模型，在 CPU 上生成 512 维、L2 归一化向量。查询模式添加模型发布方的检索前缀，文档模式保留输入原文。[模型文件](https://huggingface.co/BAAI/bge-small-zh-v1.5/tree/7999e1d3359715c523056ef9478215996d62a620) · [模型发布方的前缀说明](https://github.com/FlagOpen/FlagEmbedding#model-list)

## 准备依赖和权重

在项目根目录用 PowerShell 执行。依赖缓存、模型缓存和测试报告放在工作区的忽略目录中。镜像使用版本锁中的 Python 3.11.14 Linux amd64 基础镜像。

```powershell
$env:UV_CACHE_DIR = Join-Path $PWD '.tools\uv-embedding'
uv --system-certs pip sync embedding/requirements.lock --python-version 3.11.14 --python-platform x86_64-manylinux_2_28 --target .local/embedding/site --require-hashes --only-binary ':all:'
$deps = (Resolve-Path .local/embedding/site).Path
docker build --pull=false --network none --build-context "embedding_deps=$deps" -t pet-hospital-embedding:m1104 ./embedding
$env:HF_HOME = Join-Path $PWD '.local\embedding\hf-host'
$env:HF_HUB_DISABLE_IMPLICIT_TOKEN = '1'
$env:HF_HUB_DISABLE_XET = '1'
uv --system-certs run --no-project --with 'huggingface-hub==2.4.0' --with 'socksio==1.0.0' -- python -m embedding.prepare_model
```

预下载命令按固定 revision 缓存 safetensors 和分词器。推理启动只读本地模型文件，并在加载完成后提供健康探针。

## 运行与验证

运行前在当前进程配置 `EMBEDDING_INTERNAL_TOKEN`。本机调用通过 Docker 的回环端口。全部接口都要求相同的 Bearer 凭据。

```powershell
$models = (Resolve-Path .local/embedding/models).Path
docker run --rm -p 127.0.0.1:8188:8188 --mount "type=bind,source=$models,target=/models,readonly" -e EMBEDDING_CACHE_DIR=/models -e EMBEDDING_INTERNAL_TOKEN -e HF_HUB_OFFLINE=1 pet-hospital-embedding:m1104
```

```powershell
$tests = (Resolve-Path embedding/tests).Path
docker run --rm --network none --mount "type=bind,source=$models,target=/models,readonly" --mount "type=bind,source=$tests,target=/tests,readonly" -e EMBEDDING_CACHE_DIR=/models -e HF_HUB_OFFLINE=1 pet-hospital-embedding:m1104 python -m pytest -q -p no:cacheprovider /tests/test_m1103.py /tests/test_m1104.py
```

内部契约见 [openapi.json](openapi.json)。`GET /internal/health` 返回模型和 CPU 状态。`POST /internal/tokenize` 和 `POST /internal/embeddings` 接收 `mode` 与 `inputs`，长度上限包含特殊 token 和查询前缀。分词偏移量以实际参与分词的文本为准，查询响应会给出前缀的字符数。

`POST /internal/chunks` 接收 `content`，按 320 个正文 token 和 48 token 重叠返回原文片段。结果含字符和 token 范围、分块 SHA-256 及固定 profile。片段会重新分词并核对 MySQL `TEXT` 的 65,535 字节边界。
