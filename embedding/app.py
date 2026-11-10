from contextlib import asynccontextmanager
from hmac import compare_digest
import os
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, HTTPException, Request, Security
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from .model import DIMENSION, MAX_TOKENS, MODEL_ID, MODEL_REVISION, QUERY_PREFIX, Mode, load_model
from .splitting import PROFILE, Chunk, EmbeddingProfile, split_content

TextInput = Annotated[str, StringConstraints(strict=True, min_length=1, max_length=100_000)]
bearer = HTTPBearer(auto_error=False)


class BatchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Mode
    inputs: Annotated[list[TextInput], Field(min_length=1, max_length=32)]


class ModelInfo(BaseModel):
    modelId: str
    revision: str
    dimension: int
    maxTokens: int


class HealthResponse(ModelInfo):
    status: Literal["ready"]
    device: Literal["cpu"]


class TokenizedInput(BaseModel):
    tokenIds: list[int]
    offsets: list[tuple[int, int]]
    tokenCount: int


class TokenizeResponse(ModelInfo):
    mode: Mode
    prefixLength: int
    items: list[TokenizedInput]


class EmbeddingResponse(ModelInfo):
    mode: Mode
    vectors: list[list[float]]


class ChunkRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    content: TextInput


class ChunkResponse(BaseModel):
    profile: EmbeddingProfile
    chunks: list[Chunk]


class ErrorResponse(BaseModel):
    detail: str


MODEL_INFO = ModelInfo(modelId=MODEL_ID, revision=MODEL_REVISION, dimension=DIMENSION, maxTokens=MAX_TOKENS)


@asynccontextmanager
async def lifespan(app: FastAPI):
    token = os.environ.get("EMBEDDING_INTERNAL_TOKEN", "")
    if len(token) < 16:
        raise RuntimeError("须配置至少 16 字符的 EMBEDDING_INTERNAL_TOKEN")
    app.state.auth_token = token.encode("utf-8")
    app.state.model = load_model()
    try:
        yield
    finally:
        del app.state.model
        del app.state.auth_token


def require_auth(
    request: Request, credentials: Annotated[HTTPAuthorizationCredentials | None, Security(bearer)]
) -> None:
    if credentials is None or not compare_digest(credentials.credentials.encode("utf-8"), request.app.state.auth_token):
        raise HTTPException(status_code=401, detail="Unauthorized", headers={"WWW-Authenticate": "Bearer"})


app = FastAPI(
    title="Pet Hospital Internal Embedding",
    version="1.0",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    dependencies=[Depends(require_auth)],
    responses={401: {"model": ErrorResponse}, 422: {"model": ErrorResponse}},
)


@app.exception_handler(RequestValidationError)
async def invalid_request(_request: Request, _error: RequestValidationError):
    return JSONResponse(status_code=422, content={"detail": "Invalid embedding request"})


@app.get("/internal/health")
def health() -> HealthResponse:
    return HealthResponse(**MODEL_INFO.model_dump(), status="ready", device="cpu")


def prepare_batch(batch: BatchRequest, tokenizer, *, offsets: bool = False):
    prefix = QUERY_PREFIX if batch.mode == "query" else ""
    if any(not text.strip() for text in batch.inputs):
        raise HTTPException(status_code=422, detail="Embedding inputs must not be blank")
    prepared = [prefix + text for text in batch.inputs]
    encoded = tokenizer(
        prepared, add_special_tokens=True, truncation=False, return_offsets_mapping=offsets, verbose=False
    )
    if any(len(ids) > MAX_TOKENS for ids in encoded["input_ids"]):
        raise HTTPException(status_code=422, detail="Embedding input exceeds 512 tokens including special tokens")
    return prepared, encoded, len(prefix)


@app.post("/internal/tokenize")
def tokenize(batch: BatchRequest, request: Request) -> TokenizeResponse:
    _, encoded, prefix_length = prepare_batch(batch, request.app.state.model.tokenizer, offsets=True)
    items = [
        TokenizedInput(tokenIds=ids, offsets=offsets, tokenCount=len(ids))
        for ids, offsets in zip(encoded["input_ids"], encoded["offset_mapping"], strict=True)
    ]
    return TokenizeResponse(**MODEL_INFO.model_dump(), mode=batch.mode, prefixLength=prefix_length, items=items)


@app.post("/internal/embeddings")
def embeddings(batch: BatchRequest, request: Request) -> EmbeddingResponse:
    model = request.app.state.model
    prepared, _, _ = prepare_batch(batch, model.tokenizer)
    vectors = model.encode(
        prepared, prompt="", normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False
    )
    return EmbeddingResponse(**MODEL_INFO.model_dump(), mode=batch.mode, vectors=vectors.tolist())


@app.post("/internal/chunks")
def chunks(payload: ChunkRequest, request: Request) -> ChunkResponse:
    try:
        items = split_content(payload.content, request.app.state.model.tokenizer)
    except ValueError as invalid:
        raise HTTPException(status_code=422, detail=str(invalid)) from None
    return ChunkResponse(profile=PROFILE, chunks=items)
