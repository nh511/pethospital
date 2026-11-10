FROM python:3.11.14-slim-bookworm@sha256:65a93d69fa75478d554f4ad27c85c1e69fa184956261b4301ebaf6dbb0a3543d
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONPATH=/opt/embedding-deps
COPY --from=embedding_deps . /opt/embedding-deps/
COPY __init__.py __main__.py app.py model.py prepare_model.py splitting.py /app/embedding/
CMD ["python", "-m", "uvicorn", "embedding.app:app", "--host", "0.0.0.0", "--port", "8188", "--no-access-log"]
