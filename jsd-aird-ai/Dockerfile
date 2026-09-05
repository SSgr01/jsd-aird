FROM python:3.12.11-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    JSD_AIRD_AI_HOST=0.0.0.0 \
    JSD_AIRD_AI_PORT=8090 \
    JSD_AIRD_AI_TMP=/tmp/jsd-aird-ai

RUN groupadd --system --gid 10001 jsdai \
    && useradd --system --uid 10001 --gid jsdai --home-dir /nonexistent --shell /usr/sbin/nologin jsdai \
    && mkdir -p /app /tmp/jsd-aird-ai \
    && chown -R jsdai:jsdai /app /tmp/jsd-aird-ai

WORKDIR /app
COPY requirements.lock.txt ./requirements.lock.txt
RUN python -m pip install -r requirements.lock.txt

COPY pyproject.toml README.md ./
COPY src ./src
RUN python -m pip install --no-build-isolation --no-deps .

USER 10001:10001
EXPOSE 8090

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=4 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/internal/v1/health/ready', timeout=2)"]

ENTRYPOINT ["python", "-m", "jsd_aird_ai.main"]
