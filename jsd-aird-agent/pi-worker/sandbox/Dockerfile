FROM python:3.12.14-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       libreoffice-writer libreoffice-calc poppler-utils fontconfig fonts-wqy-zenhei fonts-dejavu-core \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir numpy==2.3.5 pandas==2.3.3 scipy==1.16.3 openpyxl==3.1.5 python-docx==1.2.0 \
       reportlab==4.4.5 pypdf==6.1.1 \
    && groupadd --gid 10001 analysis && useradd --uid 10001 --gid 10001 --no-create-home analysis \
    && mkdir -p /work/input /work/source-scratch /work/scratch /work/output
COPY runner.py /opt/jsd/runner.py
COPY aird_files.py /opt/jsd/aird_files.py
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 MPLCONFIGDIR=/tmp/mpl XDG_CACHE_HOME=/tmp/cache PYTHONPATH=/opt/jsd LANG=C.UTF-8
ENTRYPOINT ["python", "/opt/jsd/runner.py"]
