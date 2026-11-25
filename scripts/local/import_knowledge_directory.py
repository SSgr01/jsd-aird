#!/usr/bin/env python3
"""Import a server directory through the existing knowledge document APIs.

Requires Python 3.10+ and curl. The script creates ordinary knowledge documents,
so every file appears in the normal knowledge list. Metadata enrichment uses the
shared backend task APIs; the script does not create upload-task records. Progress
and resume state stay in a local JSONL manifest beside the selected source directory
(or --manifest), with body, metadata and vector status tracked separately.
Only public papers are eligible for external bibliography lookup. Recognized
business documents use local metadata; optional local review does not block import.

Example (Linux):
  export JSD_AIRD_URL=http://127.0.0.1:8080
  export JSD_AIRD_USERNAME=admin JSD_AIRD_PASSWORD='...'
  python3.12 import_knowledge_directory.py /srv/kb/import --max-in-flight 4

Publication requires an approved workspace review. Automatic submission and
approval are enabled by default (--auto-approve), using an account with
knowledge.view.submit and knowledge.view.approve permissions through the normal
audited APIs. Use --no-auto-approve to review in the application and then resume
with the same manifest.

Use --rebuild-vectors --embedding-model MODEL to rebuild vectors of existing
published, AI-authorized documents without source files, uploads or OCR. This mode
requires the matching API/Worker update, paginates all documents, verifies stored
model/dimension counts, and persists resumable progress in a separate JSONL manifest.
It updates the existing published chunks' vectors, preserving their IDs and BM25.

Rebuild example (model name and dimension must match the deployed service):
  python3.12 import_knowledge_directory.py --rebuild-vectors \
      --embedding-model Qwen/Qwen3-Embedding-0.6B --embedding-dimension 1024 --dry-run
Remove --dry-run to submit work. Repeat the same command and --manifest to resume;
use --retry-failed to retry terminal failures. Update both API and Worker first.
--max-in-flight controls document concurrency (1–128). Import defaults to 16;
vector rebuild defaults to 8. Worker coalesces compatible files into real MinerU
batch requests; batch size/concurrency and OCR policy belong to server configuration.
Workers reuse one login with isolated cookie jars; never run other scripts or a
browser session under the same account while this script runs. HTTP 401 stops the
run immediately, preserving submitted jobs and the manifest for resume. Uploads
and category resolution remain ordered; publication waits complete concurrently.
Metadata backfill is dispatched by the server; submitting concurrent backfill
tasks does not increase its Worker capacity and may duplicate external lookups.

By default, the first relative directory selects/creates a category, deeper
directories become tags, and the full relative file path becomes the source
description. Supplying --category-id retains the single-category workflow.
"""

from __future__ import annotations

import argparse
import builtins
import re
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import copy
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unicodedata
import urllib.parse
import uuid
from pathlib import Path
from typing import Any


OUTPUT = None


class ConsoleOutput:
    def __init__(self, verbose=False):
        self.verbose = verbose
        self.lock = threading.Lock()
        self.last_queue = 0.0

    def write(self, message, file=None):
        with self.lock:
            now = time.strftime('%H:%M:%S')
            if self.verbose:
                builtins.print(f'[{now}] {message}', file=file, flush=True)
                return
            if (message.startswith(('进度 ', '[处理] 已发布', '等待发布：', '分类：',
                                    '完整清点 ', '最多同时跟踪 ', '补全任务：', '预览：', '可补全 '))
                    or any(word in message for word in ('已加入处理队列', '已建文档并加入解析队列', '已存在，继续处理'))
                    or re.match(r'^\[\d+/\d+\]\s+(?:REBUILT|CURRENT)\b', message)):
                return
            queued = any(word in message for word in ('已加入处理队列', '已建文档并加入解析队列', '已存在，继续处理'))
            if queued:
                if time.monotonic() - self.last_queue < 10: return
                self.last_queue = time.monotonic()
                message = message.split('：', 1)[0] + '（处理中）'
            if message.startswith('[处理] 已发布'): return
            if ' — ' in message:
                subject, reason = message.split(' — ', 1)
                # Keep the database cause rather than filling the terminal with SQL.
                causes = re.findall(r'(?:错误[:：]|ERROR:|Caused by:)\s*([^\r\n]+)', reason)
                if causes: reason = causes[-1]
                reason = ' '.join(reason.split())
                if 'PreparedStatementCallback' in reason and not causes:
                    reason = '数据库操作失败；加 --verbose 查看完整错误'
                prefix, separator, name = subject.partition('：')
                if separator:
                    subject = prefix + '：' + name.replace('\\', '/').rsplit('/', 1)[-1]
                message = subject[:100] + ' — ' + reason[:180]
            elif message.startswith('[') and '：' in message:
                prefix, text = message.split('：', 1)
                message = prefix + '：' + text.replace('\\', '/').rsplit('/', 1)[-1][:90]
            message = ' '.join(message.split())
            builtins.print(f'[{now}] {message}', file=file, flush=True)


def emit(*values, sep=' ', file=None, flush=False):
    message = sep.join(str(value) for value in values)
    if OUTPUT is None:
        builtins.print(message, file=file, flush=True)
    else:
        OUTPUT.write(message, file=file)


DOCUMENT_STATE_FIELDS = (
    "status", "scanStatus", "aiStatus", "lifecycleStatus", "reviewStatus",
    "reviewRevisionStatus", "currentPublicationId", "currentPublicationNo", "parseError",
    "title", "libraryScope", "categoryId", "categoryName", "tags", "sourceInfo",
)
DOCUMENT_STATUSES = {"QUEUED", "PROCESSING", "READY", "FAILED", "REJECTED", "PENDING_PROVIDER"}


class ImportErrorWithMessage(Exception):
    pass


class ApiHttpError(ImportErrorWithMessage):
    def __init__(self, status: int, code: str | None, message: str):
        super().__init__(f"HTTP {status}: {message}")
        self.status = status
        self.code = code


class SessionExpired(ApiHttpError):
    def __init__(self):
        super().__init__(401, None, "登录已失效，已停止本次脚本；请勿使用同账号同时登录浏览器或其他脚本，保留 manifest 后续跑")


def require_session(error):
    if isinstance(error, ApiHttpError) and error.status == 401:
        raise SessionExpired() from error


def pause(api, seconds):
    event = getattr(api, 'cancelled', None)
    if isinstance(event, threading.Event):
        if event.wait(seconds):
            raise SessionExpired()
    else:
        time.sleep(seconds)


MANIFEST_LOCK = threading.Lock()


class SessionThreadPool(ThreadPoolExecutor):
    api = None

    def __exit__(self, exception_type, exception, traceback):
        if exception_type is not None and self.api is not None:
            self.api.cancelled.set()
        self.shutdown(wait=True, cancel_futures=exception_type is not None)
        return False


def parallel_items(api, items, workers, operation):
    """Bounded in-flight work; one login, separate cookie jars per worker thread."""
    local = threading.local()

    def run(item):
        if not hasattr(local, 'api'):
            local.api = api.fork()
        return operation(local.api, item)

    iterator = iter(items)
    executor = ThreadPoolExecutor(max_workers=workers)
    pending = {}
    try:
        def fill():
            while len(pending) < workers:
                try:
                    item = next(iterator)
                except StopIteration:
                    break
                pending[executor.submit(run, item)] = item
        fill()
        while pending:
            done, _ = wait(pending, return_when=FIRST_COMPLETED)
            results = [(pending.pop(future), future.result()) for future in done]
            for result in results:
                yield result
            fill()
    finally:
        if pending:
            api.cancelled.set()
            for future in pending:
                future.cancel()
        executor.shutdown(wait=True, cancel_futures=True)


class ApiClient:
    def __init__(self, base_url: str, cookie_file: Path, curl: str):
        self.base_url = base_url.rstrip("/")
        self.cookie_file = cookie_file
        self.cookie_file.touch(exist_ok=True)
        self.curl = curl
        self.csrf_token: str | None = None
        self.cancelled = threading.Event()

    def fork(self):
        child = copy.copy(self)
        child.cookie_file = self.cookie_file.with_name(f"cookies-{uuid.uuid4().hex}.txt")
        child.cookie_file.write_bytes(self.session_cookies)
        return child

    def _run(self, args: list[str], body: bytes | None = None) -> Any:
        if self.cancelled.is_set():
            raise SessionExpired()
        command = [
            self.curl, "--silent", "--show-error", "--connect-timeout", "10", "--max-time", "300", "--cookie", str(self.cookie_file),
            "--cookie-jar", str(self.cookie_file), "--write-out", "\n%{http_code}", *args,
        ]
        result = subprocess.run(command, input=body, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            message = result.stderr.decode("utf-8", errors="replace").strip()
            raise ImportErrorWithMessage(message or f"curl 执行失败（{result.returncode}）")
        raw = result.stdout
        try:
            response_body, status_text = raw.rsplit(b"\n", 1)
            status = int(status_text.strip())
        except (ValueError, TypeError):
            raise ImportErrorWithMessage("服务器响应格式异常") from None
        try:
            payload = json.loads(response_body) if response_body else None
        except json.JSONDecodeError:
            payload = response_body.decode("utf-8", errors="replace")
        if not 200 <= status < 300:
            if status == 401:
                self.cancelled.set()
                raise SessionExpired()
            if isinstance(payload, dict):
                message = payload.get("message") or payload.get("error") or str(payload)
                code = payload.get("code")
            else:
                message = str(payload or f"HTTP {status}")
                code = None
            raise ApiHttpError(status, code, message)
        if isinstance(payload, dict) and "data" in payload:
            return payload["data"]
        return payload

    def _url(self, path: str) -> str:
        return self.base_url + path

    def json(self, method: str, path: str, value: dict[str, Any] | None = None) -> Any:
        args = ["--request", method, "--header", "Accept: application/json"]
        body = None
        if value is not None:
            args += ["--header", "Content-Type: application/json"]
            if self.csrf_token:
                args += ["--header", f"X-XSRF-TOKEN: {self.csrf_token}"]
            args += ["--data-binary", "@-"]
            body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        args.append(self._url(path))
        for attempt in range(4):
            try:
                return self._run(args, body)
            except ApiHttpError as error:
                # Reads are safe to repeat; write responses can be ambiguous after a timeout.
                if method == "GET" and error.status in {503, 504} and attempt < 3:
                    pause(self, min(8, 2 ** (attempt + 1)))
                    continue
                error.args = (f"{method} {path.split('?', 1)[0]} — {error}",)
                raise

    def login(self, username: str, password: str) -> None:
        csrf = self._run(["--request", "GET", self._url("/api/v1/auth/csrf")])
        self.csrf_token = csrf.get("token") if isinstance(csrf, dict) else None
        if not self.csrf_token:
            raise ImportErrorWithMessage("获取登录安全令牌失败")
        self.json("POST", "/api/v1/auth/login", {
            "username": username,
            "password": password,
            "rememberMe": False,
        })
        self.session_cookies = self.cookie_file.read_bytes()

    def stage_file(self, file_path: Path) -> dict[str, Any]:
        # curl parses commas/semicolons inside --form itself, independently of
        # shell quoting. Quote both form values and escape only quote/backslash.
        source = file_path.resolve().as_posix()
        name = file_path.name
        def quote(value: str) -> str:
            return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
        form = f'file=@{quote(source)};filename={quote(name)}'
        query = urllib.parse.urlencode({"kind": "KNOWLEDGE"})
        return self._run([
            "--request", "POST", "--header", "Accept: application/json",
            "--header", f"X-XSRF-TOKEN: {self.csrf_token}",
            "--form", form,
            self._url(f"/api/v1/files/staged?{query}"),
        ])


def append_manifest(path: Path, record: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with MANIFEST_LOCK, path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, ensure_ascii=False) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def read_manifest(path: Path) -> dict[tuple[str, str], dict[str, Any]]:
    latest: dict[tuple[str, str], dict[str, Any]] = {}
    if not path.exists():
        return latest
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            try:
                item = json.loads(line)
                latest[(item["relativePath"], item["sha256"])] = item
            except (json.JSONDecodeError, KeyError, TypeError):
                continue
    return latest


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(4 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def java_length(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def unique_tags(values: list[str]) -> list[str]:
    tags: dict[str, str] = {}
    for value in values:
        normalized = unicodedata.normalize("NFKC", value.strip())
        if normalized:
            if java_length(normalized) > 100:
                raise ImportErrorWithMessage(f"目录标签超过 100 字：{value}")
            tags.setdefault(normalized.lower(), normalized)
    if len(tags) > 50:
        raise ImportErrorWithMessage("目录标签与已有标签合计超过 50 个")
    return list(tags.values())


def file_metadata(relative: Path, category_mode: str, fixed_category_id: str | None) -> dict[str, Any]:
    directories = relative.parts[:-1]
    folder_category = category_mode == "first-directory" and bool(directories)
    category_name = directories[0].strip() if folder_category else None
    if category_mode == "first-directory" and not directories and not fixed_category_id:
        category_name = "未分类"
    if category_name is not None and (not category_name or java_length(category_name) > 120):
        raise ImportErrorWithMessage(f"一级目录无法映射为分类，名称须为 1～120 字：{category_name}")
    path = relative.as_posix()
    tags = unique_tags(list(directories[1:] if folder_category else directories))
    return {
        "categoryName": category_name,
        "categoryId": None if category_name is not None else fixed_category_id,
        "tags": tags,
        "sourceInfo": {
            "description": path,
            "originalName": path,
            "relativePath": path,
            "relativeDirectory": relative.parent.as_posix() if directories else "",
            "relativePaths": [path],
        },
    }


class CategoryResolver:
    def __init__(self, api: ApiClient, scope: str):
        self.api = api
        self.scope = scope
        self.categories: list[dict[str, Any]] | None = None

    def reload(self) -> None:
        self.categories = self.api.json("GET", "/api/v1/knowledge/categories?" + urllib.parse.urlencode({"scope": self.scope}))

    def resolve(self, metadata: dict[str, Any]) -> dict[str, Any]:
        if self.categories is None:
            self.reload()
        name, category_id = metadata["categoryName"], metadata["categoryId"]

        def find() -> dict[str, Any] | None:
            return next((item for item in self.categories or []
                         if (item["name"] == name if name is not None else item["id"] == category_id)), None)

        category = find()
        if category is not None:
            return category
        if name is None:
            raise ImportErrorWithMessage(f"指定分类不存在或不属于 {self.scope} 资料范围：{category_id}")
        try:
            category = self.api.json("POST", "/api/v1/knowledge/categories", {"name": name, "scope": self.scope})
        except ApiHttpError as error:
            if error.status != 409:
                raise
            self.reload()
            category = find()
            if category is None:
                raise
        assert self.categories is not None
        if not any(item["id"] == category["id"] for item in self.categories):
            self.categories.append(category)
        emit(f"分类：{name} → {category['id']}", flush=True)
        return category


def api_path_document(document_id: str) -> str:
    return f"/api/v1/knowledge/documents/{document_id}"


def copy_document_state(record: dict[str, Any], document: dict[str, Any]) -> None:
    """Copy the same state fields returned to the knowledge list into the manifest."""
    for field in DOCUMENT_STATE_FIELDS:
        if field in document:
            record[field] = document[field]
        else:
            record.pop(field, None)


def refresh_document_state(api: ApiClient, record: dict[str, Any], document_id: str) -> dict[str, Any]:
    document = api.json("GET", api_path_document(document_id))
    copy_document_state(record, document)
    return document


def document_location(api: ApiClient, document: dict[str, Any]) -> str:
    scope = "内部资料" if document.get("libraryScope") == "INTERNAL" else "外部资料"
    category = document.get("categoryName") or document.get("categoryId") or "未分类"
    url = f"{api.base_url}/knowledge/documents/{document['id']}"
    return f"{scope} / {category} / {document.get('title') or document['id']}（{url}）"


def require_document_target(api: ApiClient, document: dict[str, Any], record: dict[str, Any]) -> None:
    location = document_location(api, document)
    if (document.get("categoryId") != record["targetCategoryId"]
            or document.get("libraryScope") != record["targetScope"]):
        raise ImportErrorWithMessage(
            f"归类冲突：同内容文档位于 {location}；目标为 {record['targetScope']} / "
            f"{record['targetCategoryName']}。未移动已有文档，请在页面确认归属后重跑")
    if document.get("sha256") != record["sha256"]:
        raise ImportErrorWithMessage(f"文件内容只匹配历史版本，当前文件版本已变化：{location}；请人工核对版本")
    if document.get("lifecycleStatus") != "ACTIVE":
        raise ImportErrorWithMessage(f"已有文档已停用：{location}；请在页面恢复后重跑")


def merge_source_info(existing: dict[str, Any], incoming: dict[str, Any]) -> dict[str, Any]:
    source = dict(existing)
    existing_paths = source.get("relativePaths")
    paths = [value for value in existing_paths if isinstance(value, str)] if isinstance(existing_paths, list) else []
    if isinstance(source.get("relativePath"), str):
        paths.append(source["relativePath"])
    if source.get("description") == "服务器目录批量导入" and isinstance(source.get("originalName"), str):
        paths.append(source["originalName"])
    paths = list(dict.fromkeys([*paths, *incoming["relativePaths"]]))
    description = source.get("description") or ""
    notes = [] if description == "服务器目录批量导入" else description.splitlines()
    source.update({
        "description": "\n".join(dict.fromkeys([*notes, *paths])),
        "relativePath": source.get("relativePath") or incoming["relativePath"],
        "relativeDirectory": source.get("relativeDirectory", incoming["relativeDirectory"]),
        "relativePaths": paths,
    })
    source.setdefault("originalName", incoming["originalName"])
    return source


def sync_document_metadata(api: ApiClient, record: dict[str, Any]) -> dict[str, Any]:
    for attempt in range(3):
        document = refresh_document_state(api, record, record["documentId"])
        require_document_target(api, document, record)
        record["versionId"] = document["currentVersionId"]
        tags = unique_tags([*(document.get("tags") or []), *record["directoryTags"]])
        source = merge_source_info(document.get("sourceInfo") or {}, record["directorySourceInfo"])
        if tags == document.get("tags", []) and source == document.get("sourceInfo", {}):
            return document
        try:
            return api.json("PUT", f"{api_path_document(document['id'])}/metadata", {
                "revision": document["revision"], "title": document["title"],
                "libraryScope": document["libraryScope"], "categoryId": document["categoryId"],
                "tags": tags, "sourceInfo": source,
            })
        except ApiHttpError as error:
            if error.status != 409 or attempt == 2:
                raise
    raise ImportErrorWithMessage("文档元数据持续发生冲突，请稍后重跑")


def duplicate_document(api: ApiClient, matches: list[dict[str, Any]], record: dict[str, Any]) -> dict[str, Any]:
    candidates: dict[str, dict[str, Any]] = {}
    for match in matches:
        document_id = match.get("documentId")
        if not document_id:
            continue
        if document_id not in candidates:
            candidates[document_id] = api.json("GET", api_path_document(document_id))
        document = candidates[document_id]
        if (document.get("categoryId") == record["targetCategoryId"]
                and document.get("libraryScope") == record["targetScope"]
                and document.get("sha256") == record["sha256"]):
            return document
    if not candidates:
        raise ImportErrorWithMessage("系统确认文件重复，但重复记录缺少可用的知识文档编号")
    return next(iter(candidates.values()))


def upload_capabilities(api: ApiClient) -> dict[str, Any]:
    capabilities = api.json("GET", "/api/v1/knowledge/upload-capabilities")
    if not isinstance(capabilities, dict) or not isinstance(capabilities.get("queue"), dict):
        raise ImportErrorWithMessage("服务器没有返回有效的知识库上传能力")
    return capabilities


def queue_api_call(api: ApiClient, operation: Any, timeout_seconds: int,
                   required_slots: int = 1) -> Any:
    deadline = time.monotonic() + timeout_seconds
    delay = 2
    while True:
        try:
            capabilities = upload_capabilities(api)
            queue = capabilities["queue"]
            organization_limit = max(1, int(queue.get("organizationJobLimit") or 1))
            total_limit = max(1, int(queue.get("totalJobLimit") or 1))
            needed = min(max(1, required_slots), organization_limit, total_limit)
            if int(queue.get("availableSlots") or 0) >= needed:
                return operation()
            retry_after = max(1, int(queue.get("retryAfterSeconds") or 2))
        except ApiHttpError as error:
            if error.status != 503 or error.code not in {"SERVER_BUSY", "DATABASE_BUSY"}:
                raise
            retry_after = delay
            delay = min(30, delay * 2)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ImportErrorWithMessage("等待知识库任务队列容量超时")
        pause(api, min(retry_after, remaining))


def wait_for_parse(api: ApiClient, document_id: str, version_id: str, timeout_seconds: int) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        state = api.json("GET", f"{api_path_document(document_id)}/processing")
        if state.get("versionId") != version_id:
            raise ImportErrorWithMessage("等待解析时文档文件版本发生变化，请核对后重跑")
        status = state.get("parseRunStatus")
        if status == "SUCCEEDED" and state.get("documentStatus") == "READY":
            return state
        # A newly queued reparse can still expose the preceding failed parse run.
        parsing_active = state.get("jobStatus") in {"READY", "RUNNING"} and not state.get("terminal")
        failed_parse = (status in {"FAILED", "REJECTED"}
                        or state.get("documentStatus") in {"FAILED", "REJECTED", "PENDING_PROVIDER"})
        failed_parse = failed_parse and not parsing_active and state.get("documentStatus") not in {"QUEUED", "PROCESSING"}
        if failed_parse or (state.get("terminal") and status != "SUCCEEDED"):
            reason = state.get("parseRunError") or state.get("lastError") or "文件解析失败"
            raise ImportErrorWithMessage(reason)
        pause(api, 10)
    raise ImportErrorWithMessage(f"解析等待超时，文档 {document_id} 可在知识库查看后续状态")


def ensure_publication_review(api: ApiClient, version_id: str, *, auto_approve: bool,
                              retry_failed: bool) -> None:
    path = f"/api/v1/workspace-reviews/KNOWLEDGE_VERSION/{version_id}"
    state = api.json("GET", path)
    status = state.get("status")
    if status == "APPROVED":
        return
    if not auto_approve:
        raise ImportErrorWithMessage(
            f"当前版本须审核通过后才能发布（{status}）。请在页面完成审核后用原清单续跑；"
            "如需按当前账号权限自动提交并审核，通过 --auto-approve 启用")
    if status == "REJECTED" and not retry_failed:
        raise ImportErrorWithMessage("当前版本已被驳回，请核对内容；确需重新提交时使用 --retry-failed --auto-approve")
    if status in {"NOT_SUBMITTED", "REJECTED"}:
        state = api.json("POST", path + "/submit", {})
        status = state.get("status")
    if status == "SUBMITTED":
        state = api.json("POST", path + "/approve", {})
        status = state.get("status")
    if status != "APPROVED":
        raise ImportErrorWithMessage(f"版本审核尚未通过（{status}），未提交发布，请核对后续跑")


def finish_document(api: ApiClient, document_id: str, version_id: str, timeout_seconds: int,
                    retry_failed: bool, queue_timeout_seconds: int, *,
                    auto_approve: bool = False, publish_timeout_seconds: int | None = None) -> dict[str, Any]:
    document = api.json("GET", api_path_document(document_id))
    if document.get("currentVersionId") != version_id:
        raise ImportErrorWithMessage("文档的当前文件版本已变化，请核对后重跑")
    if not document.get("currentPublicationId") or document.get("reviewStatus") != "PUBLISHED":
        processing = api.json("GET", f"{api_path_document(document_id)}/processing")
        parsing_active = processing.get("jobStatus") in {"READY", "RUNNING"} and not processing.get("terminal")
        parse_failed = (processing.get("parseRunStatus") in {"FAILED", "REJECTED"}
                        or processing.get("documentStatus") in {"FAILED", "REJECTED", "PENDING_PROVIDER"}
                        or (processing.get("terminal")
                            and processing.get("parseRunStatus") != "SUCCEEDED"))
        if parse_failed and not parsing_active:
            if not retry_failed:
                raise ImportErrorWithMessage(processing.get("parseRunError") or processing.get("lastError")
                                             or "文件解析失败；可使用 --retry-failed 重试")
            failed_review = api.json("GET", f"{api_path_document(document_id)}/versions/{version_id}/review-status")
            failed_revision = failed_review.get("reviewRevision") or {}
            queue_api_call(api, lambda: api.json(
                "POST", f"/api/v1/knowledge/documents/{document_id}/versions/{version_id}/reparse", {
                    "reviewRevisionId": failed_revision.get("id"),
                    "lockVersion": failed_revision.get("lockVersion"),
                }
            ), queue_timeout_seconds)
        wait_for_parse(api, document_id, version_id, timeout_seconds)
        review = api.json("GET", f"/api/v1/knowledge/documents/{document_id}/versions/{version_id}/review-status")
        revision = review.get("reviewRevision")
        if not revision:
            raise ImportErrorWithMessage("解析完成，但没有生成待校对内容")
        if revision.get("status") == "FAILED" and not retry_failed:
            raise ImportErrorWithMessage((revision.get("failureReason") or "发布或索引生成失败")
                                         + "；修复原因后可使用 --retry-failed 重试发布")
        if revision.get("status") in {"DRAFT", "FAILED"}:
            ensure_publication_review(api, version_id, auto_approve=auto_approve, retry_failed=retry_failed)
            # Approval may update the revision lock; publish the reviewed state.
            refreshed_review = api.json("GET", f"/api/v1/knowledge/documents/{document_id}/versions/{version_id}/review-status")
            refreshed_revision = refreshed_review.get("reviewRevision") or {}
            if refreshed_revision.get("id") != revision.get("id"):
                raise ImportErrorWithMessage("审核期间校对版本发生变化，请核对后续跑")
            revision = refreshed_revision
            queue_api_call(api, lambda: api.json(
                "POST", f"/api/v1/knowledge/documents/{document_id}/versions/{version_id}/publish", {
                    "reviewRevisionId": revision["id"],
                    "lockVersion": revision["lockVersion"],
                    "basePublicationId": revision.get("basePublicationId"),
                    "authorizeAi": True,
                }
            ), queue_timeout_seconds, 2)

        deadline = time.monotonic() + (publish_timeout_seconds if publish_timeout_seconds is not None else timeout_seconds)
        next_report = time.monotonic() + 30
        while time.monotonic() < deadline:
            document = api.json("GET", api_path_document(document_id))
            if document.get("currentVersionId") != version_id:
                raise ImportErrorWithMessage("等待发布时文档文件版本发生变化，请核对后重跑")
            if document.get("currentPublicationId") and document.get("reviewStatus") == "PUBLISHED":
                break
            latest_review = api.json("GET", f"/api/v1/knowledge/documents/{document_id}/versions/{version_id}/review-status")
            latest_revision = latest_review.get("reviewRevision") or {}
            if latest_revision.get("status") == "FAILED":
                raise ImportErrorWithMessage(latest_revision.get("failureReason") or "发布任务失败")
            if time.monotonic() >= next_report:
                emit(f"等待发布：{document_id}；状态 {latest_revision.get('status', 'UNKNOWN')}；剩余约 {max(0, int(deadline-time.monotonic()))} 秒", flush=True)
                next_report = time.monotonic() + 30
            pause(api, 3)
        else:
            raise ImportErrorWithMessage("发布任务已提交，尚未看到发布版本；可在知识库继续查看")

    if document.get("aiStatus") != "APPROVED":
        document = api.json("GET", api_path_document(document_id))
        document = queue_api_call(api, lambda: api.json(
            "POST", f"{api_path_document(document_id)}/ai-grant", {
                "revision": document["revision"],
                "action": "APPROVE",
                "reason": "服务器目录批量导入自动授权",
            }
        ), queue_timeout_seconds)
    if document.get("aiStatus") != "APPROVED":
        raise ImportErrorWithMessage("文件已发布，但 AI 授权未生效")
    return document


METADATA_FINAL = {"ACCEPTED", "NEEDS_REVIEW", "NO_MATCH", "MISSING_PARSE", "FAILED", "STALE"}


def local_metadata_ready(record: dict[str, Any]) -> bool:
    """Optional source metadata review is not missing paper bibliography."""
    return record.get("documentType") == "INTERNAL" and record.get("metadataStatus") in {"ACCEPTED", "NEEDS_REVIEW"}


def sync_enrichment(api: ApiClient, record: dict[str, Any], *, retry_failed: bool = False) -> None:
    """Never upload, parse, publish, or fabricate vector completion in this phase."""
    base = f"{api_path_document(record['documentId'])}/versions/{record['versionId']}"
    try:
        bibliography = api.json("GET", base + "/bibliography")
        record["metadataStatus"] = bibliography["status"]
        record["documentType"] = bibliography.get("documentType", "UNKNOWN")
        record["metadataMode"] = "LOCAL_ONLY" if record["documentType"] == "INTERNAL" else (
            "BIBLIOGRAPHIC" if record["documentType"] in {"PAPER", "PATENT"} else "UNCLASSIFIED")
        record["metadataRevision"] = bibliography["revision"]
        record["metadataError"] = bibliography.get("message")
        if bibliography["status"] not in {"ACCEPTED", "NEEDS_REVIEW", "NO_MATCH"}:
            if not record.get("metadataTaskId"):
                task = api.json("POST", "/api/v1/knowledge/bibliography-tasks", {
                    "documentIds": [record["documentId"]], "requestKey": "directory-import:" + record["versionId"],
                })
                record["metadataTaskId"] = task["id"]
            task = api.json("GET", f"/api/v1/knowledge/bibliography-tasks/{record['metadataTaskId']}")
            item = next((v for v in task["items"] if v["versionId"] == record["versionId"]), None)
            if not item:
                raise ImportErrorWithMessage("补全任务中没有当前文件版本，请核对权限与文件版本")
            record["metadataStatus"] = item["status"]
            record["metadataError"] = item.get("errorCode") or bibliography.get("message")
            record["metadataRetryAt"] = item.get("retryAt")
            if retry_failed and item["status"] in {"FAILED", "MISSING_PARSE", "STALE"}:
                api.json("POST", f"/api/v1/knowledge/bibliography-tasks/{task['id']}/items/{item['id']}/retry", {})
                record["metadataStatus"] = "PENDING"
    except Exception as error:
        require_session(error)
        record["metadataStatus"] = "CHECK_FAILED"
        record["metadataError"] = str(error)
    try:
        vector = api.json("GET", base + "/vector-status")
        record["vectorIndexStatus"] = vector.get("autoBuild", vector)["status"]
        record["vectorIndexDetail"] = vector
        record.pop("vectorIndexError", None)
    except Exception as error:
        require_session(error)
        record["vectorIndexStatus"] = "CHECK_FAILED"
        record["vectorIndexError"] = str(error)
    # Bibliography is optional enrichment for a mixed knowledge library. Keep its
    # status visible without making missing paper metadata a failed body import.
    # PRESENT describes stored vectors, not compatibility with a new model.
    record["importStatus"] = ("COMPLETED" if record.get("bodyImportStatus") == "COMPLETED"
                              and record.get("vectorIndexStatus") == "PRESENT" else "PARTIAL")


def summarize(records: list[dict[str, Any]]) -> dict[str, int]:
    return {
        "bodyCompleted": sum(r.get("bodyImportStatus") == "COMPLETED" for r in records),
        "metadataAccepted": sum(r.get("metadataStatus") == "ACCEPTED" for r in records),
        "metadataLocalOnly": sum(local_metadata_ready(r) for r in records),
        "metadataNeedsReview": sum(r.get("metadataStatus") in {"NEEDS_REVIEW", "NO_MATCH"} and not local_metadata_ready(r) for r in records),
        "metadataMissingParse": sum(r.get("metadataStatus") == "MISSING_PARSE" for r in records),
        "metadataFailed": sum(r.get("metadataStatus") in {"FAILED", "CHECK_FAILED", "STALE"} for r in records),
        "metadataPending": sum(r.get("metadataStatus") in {"PENDING", "WAITING_PROVIDER"} for r in records),
        "vectorIncomplete": sum(r.get("vectorIndexStatus") != "PRESENT" for r in records),
    }


def enrich_existing(args: argparse.Namespace) -> int:
    """Server-side backfill: no source PDFs, re-upload, or OCR required."""
    username = args.username or os.environ.get("JSD_AIRD_USERNAME")
    password = os.environ.get("JSD_AIRD_PASSWORD")
    curl = shutil.which("curl")
    if not username or not password or not curl:
        raise ImportErrorWithMessage("需要 curl、JSD_AIRD_USERNAME 和 JSD_AIRD_PASSWORD")
    with tempfile.TemporaryDirectory(prefix="jsd-kb-enrich-") as temp_dir:
        api = ApiClient(args.base_url, Path(temp_dir) / "cookies.txt", curl)
        api.login(username, password)
        selection = {"categoryId": args.category_id, "requestKey": args.request_key or str(uuid.uuid4())}
        try:
            preview = api.json("POST", "/api/v1/knowledge/bibliography-tasks/preview", selection)
            emit(f"可补全 {preview['total']} 份已入库资料（预览不访问外部书目服务）。")
            if args.dry_run:
                return 0
            task = api.json("POST", "/api/v1/knowledge/bibliography-tasks", selection)
            emit(f"补全任务：{task['id']}；重复提交请沿用 --request-key {selection['requestKey']}")
            deadline = time.monotonic() + args.metadata_wait_seconds
            while True:
                result = api.json("GET", f"/api/v1/knowledge/bibliography-tasks/{task['id']}?limit=1")
                if result['finished'] or time.monotonic() >= deadline:
                    emit('补全统计：' + json.dumps(result['counts'], ensure_ascii=False))
                    return 1 if result['counts'].get('FAILED') else 0
                pause(api, min(3, max(0, deadline - time.monotonic())))
        finally:
            api.json("POST", "/api/v1/auth/logout", {})


def collect_files(source: Path, manifest: Path, included: list[str] | None = None) -> list[Path]:
    """An explicit selection stays inside the source directory and never follows links."""
    def importable(path: Path) -> bool:
        return (not path.name.startswith("~$")
                and path.suffix.lower() not in {".crdownload", ".part", ".partial", ".tmp", ".short"}
                and not (path.name.startswith(".knowledge-import") and path.suffix.lower() == ".jsonl"))

    if included is None:
        return sorted(path for path in source.rglob("*")
                      if path.is_file() and not path.is_symlink() and path.resolve() != manifest.resolve() and importable(path))
    selected: set[Path] = set()
    for name in included:
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts:
            raise ImportErrorWithMessage("选择的文件必须是来源目录内的相对路径")
        path = source / relative
        if any(part.is_symlink() for part in (path, *path.parents) if part != source and source in part.parents):
            raise ImportErrorWithMessage("选择的文件不能经过符号链接")
        resolved = path.resolve(strict=True)
        if not resolved.is_relative_to(source) or not resolved.is_file() or resolved == manifest.resolve():
            raise ImportErrorWithMessage("选择的文件不是来源目录内的普通文件")
        if importable(path):
            selected.add(path)
    return sorted(selected)


def create_or_resolve_duplicate(api, record, create):
    try:
        return create()
    except ApiHttpError as error:
        if error.status != 409:
            raise
        latest = api.json('POST', '/api/v1/knowledge/uploads/preflight', {
            'fileId': record['fileId'], 'categoryId': record['targetCategoryId']})
        if latest.get('decision') != 'EXACT_DUPLICATE' or not latest.get('exactMatches'):
            raise error
        document = duplicate_document(api, latest['exactMatches'], record)
        require_document_target(api, document, record)
        record['duplicate'] = True
        return document


def import_directory(args: argparse.Namespace) -> int:
    source = Path(args.source_dir).expanduser().resolve(strict=True)
    if not source.is_dir():
        raise ImportErrorWithMessage("来源路径不是文件夹")
    manifest = Path(args.manifest).expanduser().resolve() if args.manifest else source / ".knowledge-import.jsonl"
    previous = read_manifest(manifest)
    files = collect_files(source, manifest, args.include)
    category_mode = args.category_mode or ("fixed" if args.category_id else "first-directory")
    if category_mode == "fixed" and not args.category_id:
        raise ImportErrorWithMessage("固定分类模式需要 --category-id")
    if args.dry_run:
        total_bytes = sum(path.stat().st_size for path in files)
        emit(f"扫描到 {len(files)} 个文件，总计 {total_bytes / (1024 ** 3):.2f} GB。"
              f"dry-run 不登录，因此未执行服务端格式校验。清单：{manifest}")
        counts: dict[str, int] = {}
        errors = 0
        for index, path in enumerate(files):
            relative = path.relative_to(source)
            try:
                metadata = file_metadata(relative, category_mode, args.category_id)
                category = metadata["categoryName"] or f"UUID {metadata['categoryId']}"
                counts[category] = counts.get(category, 0) + 1
                if index < 20:
                    emit(f"预览：{relative.as_posix()} → 分类：{category}；标签：{', '.join(metadata['tags']) or '无'}；"
                          f"来源：{metadata['sourceInfo']['description']}")
            except ImportErrorWithMessage as error:
                errors += 1
                emit(f"目录映射失败：{relative.as_posix()} — {error}", file=sys.stderr)
        for category, count in sorted(counts.items()):
            emit(f"分类统计：{category}，{count} 个文件")
        return 1 if errors else 0

    username = args.username or os.environ.get("JSD_AIRD_USERNAME")
    password = os.environ.get("JSD_AIRD_PASSWORD")
    if not username or not password:
        raise ImportErrorWithMessage("请设置 JSD_AIRD_USERNAME 和 JSD_AIRD_PASSWORD 环境变量")
    curl = shutil.which("curl")
    if not curl:
        raise ImportErrorWithMessage("需要在服务器安装 curl")
    completed = 0
    failed = 0
    pending = set()
    finished_records: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="jsd-kb-import-") as temp_dir, SessionThreadPool(max_workers=args.max_in_flight) as executor:
        api = ApiClient(args.base_url, Path(temp_dir) / "cookies.txt", curl)
        executor.api = api
        api.login(username, password)
        categories = CategoryResolver(api, args.scope)
        capabilities = upload_capabilities(api)
        max_file_bytes = int(capabilities.get("maxFileBytes") or 0)
        supported_suffixes = {str(value).lower() for value in capabilities.get("supportedExtensions") or []}
        if max_file_bytes <= 0 or not supported_suffixes:
            raise ImportErrorWithMessage("服务器返回的文件大小或格式限制无效")
        organization_limit = max(1, int(capabilities["queue"].get("organizationJobLimit") or 1))
        effective_max_in_flight = min(args.max_in_flight, max(1, organization_limit // 2))
        queue_timeout_seconds = args.queue_wait_timeout_minutes * 60
        processed_pending = 0
        thread_client = threading.local()
        document_locks = {}

        def finish_record(record, worker_api):
            api = worker_api
            relative = record["relativePath"]
            document_id = record.get("documentId")
            try:
                if not document_id or not record.get("versionId"):
                    raise ImportErrorWithMessage("断点记录缺少知识文档或版本编号")
                sync_document_metadata(api, record)
                document = finish_document(api, document_id, record["versionId"],
                                           args.parse_timeout_minutes * 60, args.retry_failed,
                                           queue_timeout_seconds, auto_approve=args.auto_approve,
                                           publish_timeout_seconds=getattr(args, 'publish_timeout_minutes', 120) * 60)
                copy_document_state(record, document)
                record["message"] = "已发布并授权 AI" + ("（关联已有文档）" if record.get("duplicate") else "")
                record["bodyImportStatus"] = "COMPLETED"
                capture_parse_provenance(api, record)
                sync_enrichment(api, record, retry_failed=args.retry_failed)
                record["documentUrl"] = f"{api.base_url}/knowledge/documents/{document_id}"
                record["updatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
                append_manifest(manifest, record)
                emit(f"[处理] {record['message']}：{relative}；"
                      f"分类：{document.get('categoryName')}；文档：{document.get('title')}；{record['documentUrl']}")
                return True, record
            except Exception as error:
                require_session(error)
                record.update({"sha256": record.get("sha256", ""), "message": str(error), "importStatus": "FAILED",
                               "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")})
                if document_id:
                    try:
                        refresh_document_state(api, record, document_id)
                    except Exception as refresh_error:
                        require_session(refresh_error)
                        for field in DOCUMENT_STATE_FIELDS:
                            record.pop(field, None)
                append_manifest(manifest, record)
                emit(f"[处理] 失败：{relative} — {error}", file=sys.stderr)
                return False, record

        category_lock = threading.Lock()
        document_locks_guard = threading.Lock()

        def finish_serialized(record, worker_api):
            # Duplicate paths can resolve to one document. Serialize its writes only.
            with document_locks_guard:
                lock = document_locks.setdefault(record.get('documentId'), threading.Lock())
            with lock:
                return finish_record(record.copy(), worker_api)

        def finish_next():
            nonlocal completed, failed, processed_pending
            done, _ = wait(pending, return_when=FIRST_COMPLETED)
            for future in done:
                pending.remove(future)
                success, record = future.result()
                processed_pending += 1
                if success:
                    completed += 1
                    finished_records.append(record)
                else:
                    failed += 1
                emit(f"进度 {completed + failed}/{len(files)} | 成功 {completed} | 失败/待处理 {failed} | 在途 {len(pending)} | {Path(record['relativePath']).name[:60]}")

        parent_api = api

        def import_file(index, file_path):
            if not hasattr(thread_client, 'api'):
                thread_client.api = parent_api.fork()
            api = thread_client.api
            relative = file_path.relative_to(source).as_posix()
            digest = ""
            prior = None
            record: dict[str, Any] = {"relativePath": relative}
            try:
                size = file_path.stat().st_size
                digest = sha256_file(file_path)
                prior = previous.get((relative, digest))
                record = {
                    **(prior or {}),
                    "relativePath": relative,
                    "sha256": digest,
                    "size": size,
                    "importStatus": "PENDING",
                    "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                }
                if record.get("status") not in DOCUMENT_STATUSES:
                    record.pop("status", None)
                if size > max_file_bytes:
                    record["importStatus"] = "FAILED"
                    record["message"] = f"文件超过 {max_file_bytes} 字节限制"
                    append_manifest(manifest, record)
                    emit(f"[{index}/{len(files)}] 超过限制：{relative}")
                    return False, record
                if file_path.suffix.lower() not in supported_suffixes:
                    record["importStatus"] = "FAILED"
                    record["message"] = "文件格式不在知识库导入支持范围内"
                    append_manifest(manifest, record)
                    emit(f"[{index}/{len(files)}] 暂不支持：{relative}")
                    return False, record

                metadata = file_metadata(file_path.relative_to(source), category_mode, args.category_id)
                with category_lock:
                    category = categories.resolve(metadata)
                record.update({
                    "categoryMode": category_mode,
                    "targetScope": args.scope,
                    "targetCategoryId": category["id"],
                    "targetCategoryName": category["name"],
                    "directoryTags": metadata["tags"],
                    "directorySourceInfo": metadata["sourceInfo"],
                })

                document_id = prior.get("documentId") if prior else None
                version_id = prior.get("versionId") if prior else None
                file_id = prior.get("fileId") if prior else None
                if document_id and version_id:
                    document = refresh_document_state(api, record, document_id)
                    require_document_target(api, document, record)
                    version_id = document["currentVersionId"]
                    record.update(documentId=document_id, versionId=version_id, fileId=file_id)
                    append_manifest(manifest, record)
                    emit(f"[{index}/{len(files)}] 已加入处理队列：{relative}")
                    return finish_serialized(record, api)
                else:
                    if not file_id:
                        staged = api.stage_file(file_path)
                        file_id = staged["fileId"]
                        record.update(fileId=file_id)
                        append_manifest(manifest, record)
                    duplicate = api.json("POST", "/api/v1/knowledge/uploads/preflight", {
                        "fileId": file_id,
                        "categoryId": category["id"],
                    })
                    if duplicate.get("decision") == "EXACT_DUPLICATE":
                        matches = duplicate.get("exactMatches") or []
                        if not matches:
                            raise ImportErrorWithMessage("系统确认文件重复，但没有返回对应知识文档")
                        duplicate_document_value = duplicate_document(api, matches, record)
                        duplicate_document_id = duplicate_document_value["id"]
                        record["documentId"] = duplicate_document_id
                        copy_document_state(record, duplicate_document_value)
                        record["versionId"] = duplicate_document_value["currentVersionId"]
                        record["duplicate"] = True
                        record["documentUrl"] = f"{api.base_url}/knowledge/documents/{duplicate_document_id}"
                        require_document_target(api, duplicate_document_value, record)
                        record["message"] = "内容相同，继续核对元数据、发布和 AI 授权"
                        append_manifest(manifest, record)
                        emit(f"[{index}/{len(files)}] 已存在，继续处理：{relative}；"
                              f"原文档：{document_location(api, duplicate_document_value)}")
                        return finish_serialized(record, api)
                    title = file_path.stem[:260]
                    created = create_or_resolve_duplicate(api, record, lambda: queue_api_call(api, lambda: api.json(
                        "POST", "/api/v1/knowledge/documents", {
                            "fileId": file_id,
                            "title": title,
                            "libraryScope": args.scope,
                            "categoryId": category["id"],
                            "tags": metadata["tags"],
                            "resolution": "NEW_DOCUMENT",
                            "targetDocumentId": None,
                            "sourceInfo": metadata["sourceInfo"],
                            "projectRelations": [],
                        }
                    ), queue_timeout_seconds, 2))
                    document_id = created["id"]
                    version_id = created["currentVersionId"]
                    record.update(documentId=document_id, versionId=version_id)
                    refresh_document_state(api, record, document_id)
                    append_manifest(manifest, record)
                    emit(f"[{index}/{len(files)}] 已建文档并加入解析队列：{relative}")
                    return finish_serialized(record, api)
            except Exception as error:  # Preserve progress for this item and continue the directory.
                require_session(error)
                record.update({"sha256": digest or record.get("sha256", ""), "importStatus": "FAILED",
                               "message": str(error),
                               "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")})
                if record.get("documentId"):
                    try:
                        refresh_document_state(api, record, record["documentId"])
                    except Exception as refresh_error:
                        require_session(refresh_error)
                        for field in DOCUMENT_STATE_FIELDS:
                            record.pop(field, None)
                else:
                    for field in DOCUMENT_STATE_FIELDS:
                        record.pop(field, None)
                append_manifest(manifest, record)
                emit(f"[{index}/{len(files)}] 失败：{relative} — {error}", file=sys.stderr)

                return False, record

        for index, file_path in enumerate(files, start=1):
            pending.add(executor.submit(import_file, index, file_path))
            if len(pending) >= effective_max_in_flight:
                finish_next()

        while pending:
            finish_next()
        deadline = time.monotonic() + args.metadata_wait_seconds
        while args.metadata_wait_seconds > 0:
            def refresh_one(client, record):
                sync_enrichment(client, record)
            outstanding = [record for record in finished_records
                           if record.get('metadataStatus') in {'PENDING', 'WAITING_PROVIDER', 'CHECK_FAILED'}
                           or record.get('vectorIndexStatus') != 'PRESENT']
            for record, _ in parallel_items(api, outstanding, effective_max_in_flight, refresh_one):
                record["updatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
                append_manifest(manifest, record)
            if (all(r.get("metadataStatus") not in {"PENDING", "WAITING_PROVIDER"} for r in finished_records)
                    or time.monotonic() >= deadline):
                break
            pause(api, min(3, max(0, deadline - time.monotonic())))
        api.json("POST", "/api/v1/auth/logout", {})

    summary = summarize(finished_records)
    emit("分阶段结果：" + json.dumps(summary, ensure_ascii=False))
    emit(f"正文已发布/已关联 {completed}，正文失败或待人工处理 {failed}。补全与向量状态请查看明细：{manifest}")
    return 1 if failed else 0


class TargetChanged(ImportErrorWithMessage):
    pass


class RebuildClient(ApiClient):
    def _run(self, args, body=None):
        return super()._run(["--connect-timeout", "10", "--max-time", "120", *args], body)


def capture_parse_provenance(api, record):
    review = api.json("GET", f"{api_path_document(record['documentId'])}/versions/{record['versionId']}/review")
    if not isinstance(review, dict):
        return
    run = review.get("parseRun") or {}
    if not isinstance(run, dict):
        return
    metadata = run.get("diagnosticResult") or {}
    if not isinstance(metadata, dict):
        return
    for name, value in {
        "parseSource": metadata.get("provider"), "parseTier": metadata.get("tier"),
        "parseOcrMode": metadata.get("ocrMode", metadata.get("requestedOcrMode")),
        "remoteTaskId": run.get("providerTaskId"), "remoteFileId": metadata.get("remoteFileId"),
        "parseBatchFileCount": metadata.get("batchFileCount"),
        "completenessWarnings": metadata.get("completenessWarnings", []),
    }.items():
        if value is not None:
            record[name] = value


def inventory(api, *, category_id=None, scope=None, page_size=100):
    """Collect all pages before submitting work so index updates cannot shift pages."""
    documents = {}
    page, total = 1, None
    while True:
        query = {"page": page, "size": page_size, "lifecycleStatus": "ACTIVE"}
        if category_id:
            query["categoryId"] = category_id
        if scope:
            query["scope"] = scope
        result = api.json("GET", "/api/v1/knowledge/documents?" + urllib.parse.urlencode(query))
        if not isinstance(result, dict) or not isinstance(result.get("items"), list):
            raise ImportErrorWithMessage("知识库分页响应无效")
        if total is None:
            total = int(result["total"])
        elif int(result["total"]) != total:
            raise ImportErrorWithMessage("清点期间资料数量变化，请重新运行；尚未提交重建任务")
        for document in result["items"]:
            document_id = document["id"]
            if document_id in documents:
                raise ImportErrorWithMessage("清点期间资料顺序发生变化，请重新运行；尚未提交重建任务")
            documents[document_id] = document
        if len(documents) == total:
            return list(documents.values())
        if not result["items"] or page >= int(result["totalPages"]):
            raise ImportErrorWithMessage("知识库分页数量不完整，未提交重建任务")
        page += 1


def copy_repair_state(record, row):
    record.update(status=row['status'], message=row.get('error'), parseRunId=row.get('parseRunId'))
    result = row.get('result') or {}
    metadata = result.get('metadata') or {}
    record.update(parseSource=metadata.get('repairSource'), parseTier=metadata.get('tier'),
                  parseOcrMode=metadata.get('ocrMode', metadata.get('parseMode')),
                  remoteTaskId=result.get('providerTaskId'),
                  completenessWarnings=metadata.get('completenessWarnings', []))


def repair_existing(args):
    """Persistent candidates only. Never adopt, edit, approve, publish or rebuild vectors."""
    username = args.username or os.environ.get("JSD_AIRD_USERNAME")
    password = os.environ.get("JSD_AIRD_PASSWORD")
    curl = shutil.which("curl")
    if not username or not password or not curl:
        raise ImportErrorWithMessage("需要 curl、JSD_AIRD_USERNAME 和 JSD_AIRD_PASSWORD")
    if not 1 <= args.page_size <= 100:
        raise ValueError("page-size 须为 1–100")
    context = {"mode": "repair-existing", "baseUrl": args.base_url.rstrip('/'),
               "categoryId": args.category_id, "scope": args.scope}
    manifest = Path(args.manifest or ".runtime/knowledge-parse-repair.jsonl").expanduser().resolve()
    previous = read_progress(manifest, context)
    with tempfile.TemporaryDirectory(prefix="jsd-kb-repair-") as temporary:
        api = RebuildClient(args.base_url, Path(temporary) / "cookies.txt", curl)
        api.login(username, password)
        try:
            documents = inventory(api, category_id=args.category_id, scope=args.scope, page_size=args.page_size)
            records = []
            run_key = args.request_key or str(uuid.uuid4())
            for document in documents:
                version = document.get("currentVersionId")
                if not version:
                    continue
                old = previous.get(document['id'])
                if old and old.get('versionId') != version:
                    old = None
                record = copy.deepcopy(old) if old else {
                    "context": context, "documentId": document['id'], "versionId": version,
                    "title": document.get('title'), "requestKey": run_key,
                    "status": "SELECTED",
                }
                records.append(record)
            endpoint = "/api/v1/knowledge/parse-repairs"
            selected = [r for r in records if r.get('status') not in {'READY', 'ADOPTED'}
                        and (r.get('status') != 'FAILED' or args.retry_failed)]
            width = min(100, args.max_in_flight)
            for start in range(0, len(selected), width):
                group = selected[start:start + width]
                files = [{"documentId": r['documentId'], "versionId": r['versionId']} for r in group]
                preview = api.json('POST', endpoint + '/preview', {"files": files, "requestKey": "preview"})
                if not isinstance(preview, list) or len(preview) != len(group):
                    raise ImportErrorWithMessage("历史修复预览数量不匹配")
                emit(f"修复预览 {len(group)} 份，已有 ZIP {sum(bool(v.get('hasArchivedZip')) for v in preview)} 份")
                if args.dry_run:
                    continue
                # Persist keys BEFORE submission. A lost HTTP response resumes the same request.
                for record in group:
                    record['status'] = 'SUBMITTING' if not record.get('repairId') else record['status']
                    save(manifest, record)
                # A selection may contain keys from several interrupted runs. Keep each original key.
                by_key = {}
                for record in group:
                    by_key.setdefault(record['requestKey'], []).append(record)
                for key, targets in by_key.items():
                    body = {"files": [{"documentId": r['documentId'], "versionId": r['versionId']} for r in targets],
                            "requestKey": key, "retryFailed": args.retry_failed}
                    rows = queue_api_call(api, lambda: api.json('POST', endpoint, body), args.queue_wait_timeout_minutes * 60,
                                          len(targets))
                    returned = {r['versionId']: r for r in rows}
                    if len(returned) != len(targets):
                        raise ImportErrorWithMessage("历史修复提交结果不完整；保留清单后重跑")
                    for record in targets:
                        row = returned[record['versionId']]
                        record.update(repairId=row['id'], status=row['status'], jobId=row.get('jobId'))
                        copy_repair_state(record, row)
                        save(manifest, record)
            if args.dry_run:
                return 0
            active = [r for r in records if r.get('repairId') and r.get('status') not in {'READY', 'ADOPTED', 'FAILED'}]
            deadline = time.monotonic() + args.parse_timeout_minutes * 60
            while active:
                for record in active[:]:
                    row = api.json('GET', endpoint + '/' + record['repairId'])
                    copy_repair_state(record, row)
                    save(manifest, record)
                    if row['status'] in {'READY', 'ADOPTED', 'FAILED'}:
                        active.remove(record)
                        emit(f"修复候选 {row['status']}：{record.get('title')}；{row.get('error') or record['repairId']}")
                if not active:
                    break
                if time.monotonic() >= deadline:
                    raise ImportErrorWithMessage("修复等待超时，后台任务和候选结果保留，使用相同清单续跑")
                pause(api, max(0.5, args.poll_seconds))
            emit(f"历史修复候选已保存：{manifest}；请校对后采用并发布")
            return 1 if any(r.get('status') == 'FAILED' for r in records) else 0
        finally:
            api.json('POST', '/api/v1/auth/logout', {})


def verify_target(state, model, dimension):
    if (not isinstance(state, dict) or state.get("embeddingModel") != model
            or state.get("embeddingDimension") != dimension):
        raise TargetChanged("API 的向量模型/维度与指定目标不符，或接口版本过旧；请先更新并配置 API 和 Worker")
    if not state.get("publicationId") or not state.get("versionId"):
        raise TargetChanged("重建状态缺少发布快照信息，请先更新 API")


def matching_vectors(vector, model, dimension):
    total = vector.get("total", 0)
    models = vector.get("models")
    selected = [item for item in models or [] if item.get("model") == model and item.get("dimension") == dimension]
    return (isinstance(total, int) and total > 0 and isinstance(models, list)
            and sum(item.get("count", 0) for item in selected) == total
            and vector.get("embedded") == total and vector.get("status") == "PRESENT")


def read_progress(path, context):
    records = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue  # An interrupted final append is followed by a fresh state check.
            if isinstance(record, dict) and record.get("context") == context and record.get("documentId"):
                records[record["documentId"]] = record
    return records


def save(path, record):
    record["updatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    append_manifest(path, record)


def paths(document_id, request_key=None, *, vectors_only=False):
    base = f"/api/v1/knowledge/documents/{document_id}/rebuild-index"
    query = {} if request_key is None else {"requestKey": request_key}
    if vectors_only:
        query["vectorsOnly"] = "true"
    return base if not query else base + "?" + urllib.parse.urlencode(query)


def rebuild_document(api, document, args, previous, context, manifest):
    document_id = document["id"]
    state = api.json("GET", paths(document_id,vectors_only=True))
    verify_target(state, args.model, args.dimension)
    if not state.get("aiApproved"):
        return "SKIPPED"
    publication = state["publicationId"]
    version = state["versionId"]
    record = {"context": context, "documentId": document_id, "title": document.get("title"),
              "publicationId": publication, "versionId": version, "status": "PENDING"}
    vector_path = f"/api/v1/knowledge/documents/{document_id}/versions/{version}/vector-status"
    vector = api.json("GET", vector_path)
    if matching_vectors(vector, args.model, args.dimension):
        current = api.json("GET", paths(document_id,vectors_only=True))
        verify_target(current, args.model, args.dimension)
        if current["publicationId"] != publication or not current.get("aiApproved"):
            raise ImportErrorWithMessage("验证时发布版本或 AI 授权发生变化，请重新清点")
        if not args.dry_run:
            record.update(status="COMPLETED", vectors=vector)
            save(manifest, record)
        return "CURRENT"
    if "models" not in vector:
        raise TargetChanged("向量状态接口缺少模型/维度信息，请先更新 API")
    if args.dry_run:
        return "NEEDS_REBUILD"
    prior = previous if previous and previous.get("publicationId") == publication else {}
    attempt = int(prior.get("attempt", 0))
    if prior.get("status") == "FAILED":
        if not args.retry_failed:
            return "FAILED"
        attempt += 1
    elif prior.get("status") == "COMPLETED":
        attempt += 1
    request_key = hashlib.sha256(json.dumps(["vectors", context, document_id, publication, attempt], sort_keys=True).encode()).hexdigest()
    record.update(attempt=attempt, requestKey=request_key)
    deadline = time.monotonic() + args.timeout_minutes * 60
    try:
        tracked = api.json("GET", paths(document_id, request_key,vectors_only=True))
        verify_target(tracked, args.model, args.dimension)
        if tracked["publicationId"] != publication:
            raise ImportErrorWithMessage("发布版本变化，请重新运行清点")
        if not tracked.get("job"):
            # Persist before POST. A lost response is retried with this same key.
            record["status"] = "SUBMITTING"
            save(manifest, record)
            submitted = queue_api_call(api, lambda: api.json("POST", paths(document_id, request_key, vectors_only=True), {}),
                                       args.queue_wait_timeout_minutes * 60)
            if not submitted.get("jobId"):
                raise TargetChanged("重建接口未返回任务编号，请先更新 API")
            record["jobId"] = submitted["jobId"]
        record["status"] = "WAITING"
        save(manifest, record)
        while True:
            state = api.json("GET", paths(document_id, request_key,vectors_only=True))
            verify_target(state, args.model, args.dimension)
            if state["publicationId"] != publication or not state.get("aiApproved"):
                raise ImportErrorWithMessage("发布版本或 AI 授权变化，请重新清点；未修改授权")
            job = state.get("job")
            if not job:
                raise ImportErrorWithMessage("未找到已提交的重建任务，请保留清单后重试")
            record["jobId"] = job["id"]
            record["jobStatus"] = job["status"]
            if job["status"] in {"FAILED", "CANCELLED"}:
                record["status"] = "FAILED"
                raise ImportErrorWithMessage(job.get("lastError") or "重建任务失败或被取消")
            if job["status"] == "SUCCEEDED":
                vector = api.json("GET", vector_path)
                # Recheck the snapshot after reading its vectors.
                current = api.json("GET", paths(document_id, request_key,vectors_only=True))
                verify_target(current, args.model, args.dimension)
                if current["publicationId"] != publication or not current.get("aiApproved"):
                    record["status"] = "FAILED"
                    raise ImportErrorWithMessage("验证时发布版本或 AI 授权发生变化，请重新清点")
                if not matching_vectors(vector, args.model, args.dimension):
                    record["status"] = "FAILED"
                    raise ImportErrorWithMessage("任务结束，但向量模型/维度/数量不符合目标，请核对 API 和 Worker 配置")
                record.update(status="COMPLETED", vectors=vector)
                save(manifest, record)
                return "REBUILT"
            if time.monotonic() >= deadline:
                save(manifest, record)
                return "WAITING"
            pause(api, args.poll_seconds)
    except TargetChanged:
        save(manifest, record)
        raise
    except ImportErrorWithMessage as error:
        require_session(error)
        # An ambiguous POST or a polling failure retains the same request key.
        if record["status"] == "SUBMITTING":
            record["status"] = ("FAILED" if isinstance(error, ApiHttpError) and 400 <= error.status < 500
                                else "SUBMISSION_UNKNOWN")
        record["error"] = str(error)
        save(manifest, record)
        emit(f"未完成：{document.get('title') or document_id} — {error}")
        return record["status"] if record["status"] in {"FAILED", "SUBMISSION_UNKNOWN"} else "WAITING"



def rebuild_vectors(args):
    try:
        if not args.model or not args.model.strip() or args.dimension <= 0 or not 1 <= args.page_size <= 100:
            raise ImportErrorWithMessage("模型名、维度和分页大小无效")
        if args.timeout_minutes <= 0 or args.queue_wait_timeout_minutes <= 0 or args.poll_seconds <= 0:
            raise ImportErrorWithMessage("超时和轮询间隔必须大于零")
        if args.category_id:
            uuid.UUID(args.category_id)
        username = args.username or os.environ.get("JSD_AIRD_USERNAME")
        password, curl = os.environ.get("JSD_AIRD_PASSWORD"), shutil.which("curl")
        if not username or not password or not curl:
            raise ImportErrorWithMessage("需要 curl、JSD_AIRD_USERNAME 和 JSD_AIRD_PASSWORD（系统登录账号）")
        context = {"server": args.base_url.rstrip("/"), "model": args.model, "dimension": args.dimension,
                   "username": username}
        manifest = Path(args.manifest or ".runtime/knowledge-vector-rebuild.jsonl").expanduser().resolve()
        previous = read_progress(manifest, context)
        counts = {}
        with tempfile.TemporaryDirectory(prefix="jsd-vector-rebuild-") as temp:
            api = RebuildClient(args.base_url, Path(temp) / "cookies.txt", curl)
            api.login(username, password)
            try:
                documents = inventory(api, category_id=args.category_id, scope=args.scope, page_size=args.page_size)
                emit(f"完整清点 {len(documents)} 份资料；目标 {args.model} / {args.dimension} 维")
                def rebuild_one(client, document):
                    if not document.get("currentPublicationId") or document.get("aiStatus") != "APPROVED":
                        return "SKIPPED"
                    try:
                        return rebuild_document(client, document, args, previous.get(document["id"]), context, manifest)
                    except TargetChanged:
                        raise
                    except ImportErrorWithMessage as error:
                        require_session(error)
                        emit(f"状态检查失败：{document['id']} — {error}")
                        return "CHECK_FAILED"

                concurrency = args.max_in_flight
                emit(f"最多同时跟踪 {concurrency} 份资料；共享一次登录，保留原断点清单")
                for index, (document, outcome) in enumerate(parallel_items(api, documents, concurrency, rebuild_one), 1):
                    counts[outcome] = counts.get(outcome, 0) + 1
                    emit(f"[{index}/{len(documents)}] {outcome}：{document.get('title') or document['id']}")
            finally:
                try:
                    api.json("POST", "/api/v1/auth/logout", {})
                except ImportErrorWithMessage:
                    pass
        emit(json.dumps(counts, ensure_ascii=False))
        return 1 if any(counts.get(key) for key in ("FAILED", "WAITING", "SUBMISSION_UNKNOWN", "CHECK_FAILED")) else 0
    except (ImportErrorWithMessage, OSError, ValueError) as error:
        emit(f"错误：{error}")
        return 2



def main() -> int:
    parser = argparse.ArgumentParser(description="把服务器文件夹中的资料导入正常知识库文档列表")
    parser.add_argument("--verbose", action="store_true", help="终端显示完整路径、URL 和错误；默认显示精简进度")
    parser.add_argument("source_dir", nargs="?", help="服务器上的资料文件夹")
    parser.add_argument("--base-url", default=os.environ.get("JSD_AIRD_URL", "http://127.0.0.1:8080"),
                        help="系统 API 地址，默认读取 JSD_AIRD_URL")
    parser.add_argument("--username", help="登录账号；密码只从 JSD_AIRD_PASSWORD 环境变量读取")
    parser.add_argument("--category-id", help="固定分类 UUID；按一级目录分类时，仅用于根目录直属文件")
    parser.add_argument("--category-mode", choices=("first-directory", "fixed"),
                        help="first-directory：一级目录分类、下级目录标签；fixed：统一分类。未指定时，传 category-id 用 fixed，否则按目录分类")
    parser.add_argument("--scope", choices=("INTERNAL", "EXTERNAL"),
                        help="资料范围；导入默认 INTERNAL，重建向量默认全部范围")
    parser.add_argument("--manifest", help="断点清单 JSONL；导入默认在来源目录内，重建默认 .runtime/knowledge-vector-rebuild.jsonl")
    parser.add_argument("--include", action="append", help="只导入指定相对路径，可重复；不传时扫描全部来源文件")
    parser.add_argument("--parse-timeout-minutes", type=int, default=24 * 60)
    parser.add_argument("--publish-timeout-minutes", type=int, default=120,
                        help="单份资料等待发布的分钟数，默认 120；超时保留后台任务和断点，不重新上传")
    parser.add_argument("--max-in-flight", type=int,
                        help="并行跟踪文件数；目录导入默认 16，向量重建默认 8，最多 128；后端将兼容文件合并成 MinerU 批次")
    parser.add_argument("--queue-wait-timeout-minutes", type=int, default=24 * 60,
                        help="单次等待任务队列容量的最长分钟数，默认 1440")
    parser.add_argument("--retry-failed", action="store_true", help="重试解析失败的文档，以及解析完成但发布或索引失败的文档")
    parser.add_argument("--auto-approve", action=argparse.BooleanOptionalAction, default=True,
                        help="默认启用：按当前账号权限自动提交审核、审核通过并发布；--no-auto-approve 改为页面审核后续跑，已驳回项还需 --retry-failed")
    parser.add_argument("--dry-run", action="store_true", help="导入时仅扫描本地文件；重建向量时登录读取存量状态，不提交重建")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--enrich-existing", action="store_true", help="仅补全服务器已有资料；按 category-id 或全部，不上传和重新 OCR")
    modes.add_argument("--rebuild-vectors", action="store_true", help="分页重建已发布且已授权 AI 的存量资料向量，无需源目录或 OCR")
    modes.add_argument("--repair-existing", action="store_true", help="修复存量解析：优先已有 ZIP，否则重新解析；仅生成候选，绝不自动发布")
    parser.add_argument("--embedding-model", dest="model", default=os.environ.get("JSD_AIRD_EMBEDDING_MODEL"),
                        help="重建目标模型名，须与 API 和 Worker 配置完全一致")
    parser.add_argument("--embedding-dimension", dest="dimension", type=int,
                        default=os.environ.get("JSD_AIRD_EMBEDDING_DIMENSION", 1024))
    parser.add_argument("--page-size", type=int, default=100, help="重建清点分页大小，最大 100")
    parser.add_argument("--vector-poll-seconds", dest="poll_seconds", type=float, default=2)
    parser.add_argument("--vector-timeout-minutes", dest="timeout_minutes", type=int, default=120)
    parser.add_argument("--request-key", help="已有资料补全任务的幂等请求标识，重跑可复用")
    parser.add_argument("--metadata-wait-seconds", type=int, default=0, help="等候异步补全的秒数，默认提交后记录状态即退出；断点续跑可重新检查")
    args = parser.parse_args()
    if args.max_in_flight is None:
        args.max_in_flight = 8 if args.rebuild_vectors else 16
    global OUTPUT
    OUTPUT = ConsoleOutput(args.verbose)
    try:
        if args.parse_timeout_minutes < 1 or args.publish_timeout_minutes < 1 or not 1 <= args.max_in_flight <= 128 or args.queue_wait_timeout_minutes < 1:
            raise ValueError("超时必须为正整数，并行文件数须为 1–128")
        uuid.UUID(args.category_id) if args.category_id else None
        if args.metadata_wait_seconds < 0:
            raise ValueError("等候秒数不能为负数")
        if args.rebuild_vectors:
            if args.source_dir or args.include:
                raise ValueError("重建向量不使用源目录或 --include；请用 --category-id / --scope 限定存量资料")
            return rebuild_vectors(args)
        if args.enrich_existing:
            return enrich_existing(args)
        if args.repair_existing:
            if args.source_dir or args.include:
                raise ValueError("历史修复不使用源目录或 --include；用 --category-id / --scope 筛选")
            return repair_existing(args)
        if not args.source_dir:
            raise ValueError("请指定来源目录，或使用 --enrich-existing / --rebuild-vectors 处理已入库资料")
        if args.scope is None:
            args.scope = "INTERNAL"
        return import_directory(args)
    except (ImportErrorWithMessage, OSError, ValueError) as error:
        emit(f"错误：{error}", file=sys.stderr)
        return 2
    finally:
        OUTPUT = None


if __name__ == "__main__":
    raise SystemExit(main())
