FROM python:3.12-slim

WORKDIR /app
RUN groupadd --gid 10001 manga && useradd --uid 10001 --gid 10001 --no-create-home manga
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY manga/ ./manga/
USER 10001:10001
EXPOSE 8080
HEALTHCHECK CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/sync/healthz', timeout=5).read()"]
CMD ["uvicorn", "manga.sync.api:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--timeout-keep-alive", "15"]
