FROM python:3.12.14-slim
RUN pip install --no-cache-dir numpy==2.3.5 pandas==2.3.3 scipy==1.16.3 openpyxl==3.1.5 python-docx==1.2.0 \
    && groupadd --gid 10001 analysis && useradd --uid 10001 --gid 10001 --no-create-home analysis \
    && mkdir -p /work/input /work/source-scratch /work/scratch /work/output
COPY runner.py /opt/jsd/runner.py
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 MPLCONFIGDIR=/tmp/mpl XDG_CACHE_HOME=/tmp/cache
ENTRYPOINT ["python", "/opt/jsd/runner.py"]
