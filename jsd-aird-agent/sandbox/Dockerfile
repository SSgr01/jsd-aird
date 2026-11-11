FROM node:24.11.1-bookworm-slim AS native-office
COPY office/package.json office/package-lock.json /opt/dsh-office/
RUN npm ci --prefix /opt/dsh-office --omit=dev

FROM python:3.12.14-slim
COPY --from=native-office /usr/local/bin/node /usr/local/bin/node
COPY --from=native-office /opt/dsh-office /opt/dsh-office
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
       libreoffice-writer libreoffice-calc poppler-utils fontconfig fonts-wqy-zenhei fonts-dejavu-core \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*
ARG SANDBOX_PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --no-cache-dir --index-url "${SANDBOX_PIP_INDEX_URL}" numpy==2.3.5 pandas==2.3.3 scipy==1.16.3 openpyxl==3.1.5 python-docx==1.2.0 \
       reportlab==4.4.5 pypdf==6.1.1 \
    && groupadd --gid 10001 analysis && useradd --uid 10001 --gid 10001 --no-create-home analysis \
    && mkdir -p /work/input /work/source-scratch /work/scratch /work/output \
    && ln -s /work/scratch/native /workspaces
COPY runner.py /opt/jsd/runner.py
COPY aird_files.py /opt/jsd/aird_files.py
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 MPLCONFIGDIR=/tmp/mpl XDG_CACHE_HOME=/tmp/cache PYTHONPATH=/opt/jsd LANG=C.UTF-8
ENTRYPOINT ["python", "/opt/jsd/runner.py"]
