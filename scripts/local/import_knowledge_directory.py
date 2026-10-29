#!/usr/bin/env python3
"""Import a server directory through the existing knowledge document APIs.

Requires Python 3.10+ and curl. The script creates ordinary knowledge documents,
so every file appears in the normal knowledge list. It does not create upload-task
records or add application tables/pages. Progress and resume state stay in a local
JSONL manifest beside the selected source directory (or --manifest).

Example (Linux):
  export JSD_AIRD_URL=http://127.0.0.1:8080
  export JSD_AIRD_USERNAME=admin JSD_AIRD_PASSWORD='...'
  python3 import_knowledge_directory.py /srv/kb/import

By default, the first relative directory selects/creates a category, deeper
directories become tags, and the full relative file path becomes the source
description. Supplying --category-id retains the single-category workflow.
"""

from __future__ import annotations

import argparse
from collections import deque
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unicodedata
import urllib.parse
import uuid
from pathlib import Path
from typing import Any


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


class ApiClient:
    def __init__(self, base_url: str, cookie_file: Path, curl: str):
        self.base_url = base_url.rstrip("/")
        self.cookie_file = cookie_file
        self.cookie_file.touch(exist_ok=True)
        self.curl = curl
        self.csrf_token: str | None = None

    def _run(self, args: list[str], body: bytes | None = None) -> Any:
        command = [
            self.curl, "--silent", "--show-error", "--cookie", str(self.cookie_file),
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
        return self._run(args, body)

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

    def stage_file(self, file_path: Path) -> dict[str, Any]:
        # Escape curl's form syntax while preserving the actual path and filename.
        source = str(file_path.resolve()).replace("\\", "/")
        name = file_path.name
        escape = lambda value: value.replace("\\", "\\\\").replace('"', '\\"').replace(";", "\\;")
        form = f'file=@{escape(source)};filename={escape(name)}'
        query = urllib.parse.urlencode({"kind": "KNOWLEDGE"})
        return self._run([
            "--request", "POST", "--header", "Accept: application/json",
            "--header", f"X-XSRF-TOKEN: {self.csrf_token}",
            "--form", form,
            self._url(f"/api/v1/files/staged?{query}"),
        ])


def append_manifest(path: Path, record: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as stream:
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
        print(f"分类：{name} → {category['id']}", flush=True)
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
    # Remove states written by older script iterations; they are not application states.
    for field in ("vectorIndexStatus", "vectorIndexError"):
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
        time.sleep(min(retry_after, remaining))


def wait_for_parse(api: ApiClient, document_id: str, version_id: str, timeout_seconds: int) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        state = api.json("GET", f"{api_path_document(document_id)}/processing")
        status = state.get("parseRunStatus")
        if status == "SUCCEEDED" and state.get("documentStatus") == "READY":
            return state
        if status in {"FAILED", "REJECTED"} or (state.get("terminal") and status != "SUCCEEDED"):
            reason = state.get("parseRunError") or state.get("lastError") or "文件解析失败"
            raise ImportErrorWithMessage(reason)
        time.sleep(10)
    raise ImportErrorWithMessage(f"解析等待超时，文档 {document_id} 可在知识库查看后续状态")


def finish_document(api: ApiClient, document_id: str, version_id: str, timeout_seconds: int,
                    retry_failed: bool, queue_timeout_seconds: int) -> dict[str, Any]:
    document = api.json("GET", api_path_document(document_id))
    if document.get("currentVersionId") != version_id:
        raise ImportErrorWithMessage("文档的当前文件版本已变化，请核对后重跑")
    if not document.get("currentPublicationId") or document.get("reviewStatus") != "PUBLISHED":
        processing = api.json("GET", f"{api_path_document(document_id)}/processing")
        if processing.get("parseRunStatus") in {"FAILED", "REJECTED"}:
            if not retry_failed:
                raise ImportErrorWithMessage(processing.get("parseRunError") or "文件解析失败；可使用 --retry-failed 重试")
            queue_api_call(api, lambda: api.json(
                "POST", f"/api/v1/knowledge/documents/{document_id}/versions/{version_id}/reparse", {}
            ), queue_timeout_seconds)
        wait_for_parse(api, document_id, version_id, timeout_seconds)
        review = api.json("GET", f"/api/v1/knowledge/documents/{document_id}/versions/{version_id}/review")
        revision = review.get("reviewRevision")
        if not revision:
            raise ImportErrorWithMessage("解析完成，但没有生成待校对内容")
        if revision.get("status") == "FAILED":
            raise ImportErrorWithMessage(revision.get("failureReason") or "校对版本生成失败")
        if revision.get("status") == "DRAFT":
            queue_api_call(api, lambda: api.json(
                "POST", f"/api/v1/knowledge/documents/{document_id}/versions/{version_id}/publish", {
                    "reviewRevisionId": revision["id"],
                    "lockVersion": revision["lockVersion"],
                    "basePublicationId": revision.get("basePublicationId"),
                    "authorizeAi": True,
                }
            ), queue_timeout_seconds, 2)

        deadline = time.monotonic() + min(timeout_seconds, 600)
        while time.monotonic() < deadline:
            document = api.json("GET", api_path_document(document_id))
            if document.get("currentVersionId") != version_id:
                raise ImportErrorWithMessage("等待发布时文档文件版本发生变化，请核对后重跑")
            if document.get("currentPublicationId") and document.get("reviewStatus") == "PUBLISHED":
                break
            latest_review = api.json("GET", f"/api/v1/knowledge/documents/{document_id}/versions/{version_id}/review")
            latest_revision = latest_review.get("reviewRevision") or {}
            if latest_revision.get("status") == "FAILED":
                raise ImportErrorWithMessage(latest_revision.get("failureReason") or "发布任务失败")
            time.sleep(3)
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


def import_directory(args: argparse.Namespace) -> int:
    source = Path(args.source_dir).expanduser().resolve(strict=True)
    if not source.is_dir():
        raise ImportErrorWithMessage("来源路径不是文件夹")
    manifest = Path(args.manifest).expanduser().resolve() if args.manifest else source / ".knowledge-import.jsonl"
    previous = read_manifest(manifest)
    manifest_key = manifest.resolve()
    files = sorted(
        path for path in source.rglob("*")
        if path.is_file() and not path.is_symlink() and path.resolve() != manifest_key
    )
    category_mode = args.category_mode or ("fixed" if args.category_id else "first-directory")
    if category_mode == "fixed" and not args.category_id:
        raise ImportErrorWithMessage("固定分类模式需要 --category-id")
    if args.dry_run:
        total_bytes = sum(path.stat().st_size for path in files)
        print(f"扫描到 {len(files)} 个文件，总计 {total_bytes / (1024 ** 3):.2f} GB。"
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
                    print(f"预览：{relative.as_posix()} → 分类：{category}；标签：{', '.join(metadata['tags']) or '无'}；"
                          f"来源：{metadata['sourceInfo']['description']}")
            except ImportErrorWithMessage as error:
                errors += 1
                print(f"目录映射失败：{relative.as_posix()} — {error}", file=sys.stderr)
        for category, count in sorted(counts.items()):
            print(f"分类统计：{category}，{count} 个文件")
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
    pending: deque[dict[str, Any]] = deque()
    with tempfile.TemporaryDirectory(prefix="jsd-kb-import-") as temp_dir:
        api = ApiClient(args.base_url, Path(temp_dir) / "cookies.txt", curl)
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

        def finish_next() -> None:
            nonlocal completed, failed, processed_pending
            record = pending.popleft()
            processed_pending += 1
            relative = record["relativePath"]
            document_id = record.get("documentId")
            try:
                if not document_id or not record.get("versionId"):
                    raise ImportErrorWithMessage("断点记录缺少知识文档或版本编号")
                sync_document_metadata(api, record)
                document = finish_document(api, document_id, record["versionId"],
                                           args.parse_timeout_minutes * 60, args.retry_failed,
                                           queue_timeout_seconds)
                copy_document_state(record, document)
                record["message"] = "已发布并授权 AI" + ("（关联已有文档）" if record.get("duplicate") else "")
                record["importStatus"] = "COMPLETED"
                record["documentUrl"] = f"{api.base_url}/knowledge/documents/{document_id}"
                record["updatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
                append_manifest(manifest, record)
                print(f"[处理 {processed_pending}] {record['message']}：{relative}；"
                      f"分类：{document.get('categoryName')}；文档：{document.get('title')}；{record['documentUrl']}")
                completed += 1
            except Exception as error:
                failed += 1
                record.update({"sha256": record.get("sha256", ""), "message": str(error), "importStatus": "FAILED",
                               "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")})
                if document_id:
                    try:
                        refresh_document_state(api, record, document_id)
                    except Exception:
                        for field in DOCUMENT_STATE_FIELDS:
                            record.pop(field, None)
                append_manifest(manifest, record)
                print(f"[处理 {processed_pending}] 失败：{relative} — {error}", file=sys.stderr)

        for index, file_path in enumerate(files, start=1):
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
                for field in ("vectorIndexStatus", "vectorIndexError"):
                    record.pop(field, None)
                if record.get("status") not in DOCUMENT_STATUSES:
                    record.pop("status", None)
                if size > max_file_bytes:
                    record["importStatus"] = "FAILED"
                    record["message"] = f"文件超过 {max_file_bytes} 字节限制"
                    append_manifest(manifest, record)
                    print(f"[{index}/{len(files)}] 超过限制：{relative}")
                    failed += 1
                    continue
                if file_path.suffix.lower() not in supported_suffixes:
                    record["importStatus"] = "FAILED"
                    record["message"] = "文件格式不在知识库导入支持范围内"
                    append_manifest(manifest, record)
                    print(f"[{index}/{len(files)}] 暂不支持：{relative}")
                    failed += 1
                    continue

                metadata = file_metadata(file_path.relative_to(source), category_mode, args.category_id)
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
                    pending.append(record.copy())
                    print(f"[{index}/{len(files)}] 已加入处理队列：{relative}")
                    if len(pending) >= effective_max_in_flight:
                        finish_next()
                    continue
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
                        pending.append(record.copy())
                        print(f"[{index}/{len(files)}] 已存在，继续处理：{relative}；"
                              f"原文档：{document_location(api, duplicate_document_value)}")
                        if len(pending) >= effective_max_in_flight:
                            finish_next()
                        continue
                    title = file_path.stem[:260]
                    created = queue_api_call(api, lambda: api.json(
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
                    ), queue_timeout_seconds, 2)
                    document_id = created["id"]
                    version_id = created["currentVersionId"]
                    record.update(documentId=document_id, versionId=version_id)
                    refresh_document_state(api, record, document_id)
                    append_manifest(manifest, record)
                    pending.append(record.copy())
                    print(f"[{index}/{len(files)}] 已建文档并加入解析队列：{relative}")
                    if len(pending) >= effective_max_in_flight:
                        finish_next()
            except Exception as error:  # Preserve progress for this item and continue the directory.
                failed += 1
                record.update({"sha256": digest or record.get("sha256", ""), "importStatus": "FAILED",
                               "message": str(error),
                               "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")})
                if record.get("documentId"):
                    try:
                        refresh_document_state(api, record, record["documentId"])
                    except Exception:
                        for field in DOCUMENT_STATE_FIELDS:
                            record.pop(field, None)
                else:
                    for field in DOCUMENT_STATE_FIELDS:
                        record.pop(field, None)
                append_manifest(manifest, record)
                print(f"[{index}/{len(files)}] 失败：{relative} — {error}", file=sys.stderr)

        while pending:
            finish_next()

    print(f"处理完成：已发布/已关联 {completed}，失败或待人工处理 {failed}。明细：{manifest}")
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="把服务器文件夹中的资料导入正常知识库文档列表")
    parser.add_argument("source_dir", help="服务器上的资料文件夹")
    parser.add_argument("--base-url", default=os.environ.get("JSD_AIRD_URL", "http://127.0.0.1:8080"),
                        help="系统 API 地址，默认读取 JSD_AIRD_URL")
    parser.add_argument("--username", help="登录账号；密码只从 JSD_AIRD_PASSWORD 环境变量读取")
    parser.add_argument("--category-id", help="固定分类 UUID；按一级目录分类时，仅用于根目录直属文件")
    parser.add_argument("--category-mode", choices=("first-directory", "fixed"),
                        help="first-directory：一级目录分类、下级目录标签；fixed：统一分类。未指定时，传 category-id 用 fixed，否则按目录分类")
    parser.add_argument("--scope", choices=("INTERNAL", "EXTERNAL"), default="INTERNAL")
    parser.add_argument("--manifest", help="断点续跑清单 JSONL，默认写在来源目录内")
    parser.add_argument("--parse-timeout-minutes", type=int, default=24 * 60)
    parser.add_argument("--max-in-flight", type=int, default=8,
                        help="同时处于解析/发布流程的最大文件数，默认 8，并自动受组织队列容量限制")
    parser.add_argument("--queue-wait-timeout-minutes", type=int, default=24 * 60,
                        help="单次等待任务队列容量的最长分钟数，默认 1440")
    parser.add_argument("--retry-failed", action="store_true", help="重新解析知识库中解析失败的文档")
    parser.add_argument("--dry-run", action="store_true", help="只扫描并统计文件，不登录、不上传")
    args = parser.parse_args()
    try:
        if args.parse_timeout_minutes < 1 or args.max_in_flight < 1 or args.queue_wait_timeout_minutes < 1:
            raise ValueError("超时和并行参数必须为正整数")
        uuid.UUID(args.category_id) if args.category_id else None
        return import_directory(args)
    except (ImportErrorWithMessage, OSError, ValueError) as error:
        print(f"错误：{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
