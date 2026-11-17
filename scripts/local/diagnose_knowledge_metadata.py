#!/usr/bin/env python3
"""Read-only metadata task diagnostics. Requires Python 3.12, curl and sibling importer."""
import argparse
from collections import Counter
import csv
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from urllib.parse import urlencode
from uuid import UUID

from import_knowledge_directory import ApiClient, ImportErrorWithMessage, parallel_items, require_session


class DiagnosticClient(ApiClient):
    def _run(self, args, body=None):
        return super()._run(['--connect-timeout', '10', '--max-time', '60', *args], body)


def read_items(api, task):
    items, seen, cursors = [], set(), set()
    cursor = None
    first_counts = None
    while True:
        query = {'limit': 200}
        if cursor:
            query['cursor'] = cursor
        result = api.json('GET', f'/api/v1/knowledge/bibliography-tasks/{task}?{urlencode(query)}')
        if first_counts is None:
            first_counts = result['counts']
        for item in result['items']:
            if item['id'] not in seen:
                seen.add(item['id'])
                items.append(item)
        cursor = result.get('nextCursor')
        if not cursor:
            return items, first_counts, result['counts']
        if cursor in cursors:
            raise ImportErrorWithMessage('服务器返回重复分页游标，已停止，避免无限请求')
        cursors.add(cursor)


def inspect_item(api, item):
    row = {key: item.get(key) for key in (
        'id', 'documentId', 'versionId', 'status', 'errorCode', 'failureCount', 'retryAt')}
    row['itemId'] = row.pop('id')
    try:
        value = api.json('GET', '/api/v1/knowledge/documents/' + str(UUID(item['documentId']))
                         + '/versions/' + str(UUID(item['versionId'])) + '/bibliography')
    except ImportErrorWithMessage as error:
        require_session(error)
        row['detailReadFailed'] = True
        return row
    fields = value.get('accepted') or {}
    provenance = value.get('provenance') or {}
    model = provenance.get('_model_extraction') or {}
    candidates = value.get('candidates') or []
    row.update(documentType=value.get('documentType', 'UNKNOWN'),
               bibliographyStatus=value.get('status'), message=value.get('message'),
               title=fields.get('title'), doi=fields.get('doi'), authors=fields.get('authors'),
               candidateCount=len(candidates),
               modelStatus=model.get('status', 'NOT_RECORDED'), cacheHit=model.get('cacheHit'),
               fieldSources={key: origin.get('source') or origin.get('provider')
                             for key, origin in provenance.items() if isinstance(origin, dict)
                             and not key.startswith('_')},
               candidateTitles=[c.get('title') or c.get('display_name') for c in candidates[:5]
                                if isinstance(c, dict)])
    return row


def summarize(rows):
    return {
        'inspected': len(rows),
        'taskStatuses': dict(Counter(r.get('status', 'UNKNOWN') for r in rows)),
        'documentTypes': dict(Counter(r.get('documentType', 'UNAVAILABLE') for r in rows)),
        'modelStatuses': dict(Counter(r.get('modelStatus', 'UNAVAILABLE') for r in rows)),
        'errorCodes': dict(Counter(r['errorCode'] for r in rows if r.get('errorCode'))),
        'messages': dict(Counter(r['message'] for r in rows if r.get('message'))),
        'paperCount': sum(r.get('documentType') == 'PAPER' for r in rows),
        'papersWithoutSavedDoi': sum(r.get('documentType') == 'PAPER' and not r.get('doi')
                                    and not r.get('detailReadFailed') for r in rows),
        'withCandidates': sum(bool(r.get('candidateCount')) for r in rows),
        'cacheHits': sum(r.get('cacheHit') is True for r in rows),
        'detailReadFailures': sum(bool(r.get('detailReadFailed')) for r in rows),
        'limitations': [
            '任务和资料信息不是同一时刻的数据库快照；运行中的任务可能变化。',
            '资料信息是当前保存记录，可能来自早期任务或人工编辑，不能当作本批调用日志。',
            'NOT_RECORDED 仅代表未保存模型状态，不能证明未调用模型。',
            '无保存 DOI 不代表原文无 DOI；候选为空也不能证明没有发出外部查询。',
            '现有接口不能完整追踪每次 OpenAlex/Crossref 请求或精确解释每个匹配拒绝原因。',
        ],
    }


def csv_value(value):
    text = json.dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else ('' if value is None else str(value))
    return "'" + text if text.lstrip().startswith(('=', '+', '-', '@')) else text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--task-id', required=True, type=UUID)
    parser.add_argument('--base-url', default=os.environ.get('JSD_AIRD_URL', 'http://127.0.0.1:8080'))
    parser.add_argument('--username', default=os.environ.get('JSD_AIRD_USERNAME'))
    parser.add_argument('--output', type=Path, help='New output directory; defaults under .runtime/metadata-diagnostics')
    parser.add_argument('--concurrency', type=int, default=4, help='Parallel read requests, 1–16; default 4')
    args = parser.parse_args()
    if not 1 <= args.concurrency <= 16:
        parser.error('--concurrency 须为 1–16')
    password, curl = os.environ.get('JSD_AIRD_PASSWORD'), shutil.which('curl')
    if not args.username or not password or not curl:
        parser.error('需要 curl、JSD_AIRD_USERNAME 和 JSD_AIRD_PASSWORD；不自动加载 .env')
    # Reports may contain internal titles and author names; keep them private.
    os.umask(0o077)
    output = args.output or Path('.runtime/metadata-diagnostics') / (
        str(args.task_id) + '-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    output.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(prefix='jsd-metadata-diagnosis-') as folder:
        api = DiagnosticClient(args.base_url, Path(folder) / 'cookies.txt', curl)
        api.login(args.username, password)
        try:
            items, initial, final = read_items(api, args.task_id)
            print(f'读取 {len(items)} 条；服务器统计：{json.dumps(final, ensure_ascii=False)}', flush=True)
            rows = []
            with (output / 'details.jsonl').open('x', encoding='utf-8') as stream:
                for number, (_, row) in enumerate(parallel_items(api, items, args.concurrency, inspect_item), 1):
                    rows.append(row)
                    stream.write(json.dumps(row, ensure_ascii=False) + '\n')
                    stream.flush()
                    if number % 25 == 0 or number == len(items):
                        print(f'已检查 {number}/{len(items)}', flush=True)
            summary = summarize(rows)
            summary.update(taskId=str(args.task_id), initialServerCounts=initial, finalServerCounts=final)
            (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
            columns = ['itemId', 'documentId', 'versionId', 'status', 'documentType', 'title', 'doi',
                       'candidateCount', 'modelStatus', 'cacheHit', 'errorCode', 'message', 'detailReadFailed']
            with (output / 'details.csv').open('x', encoding='utf-8-sig', newline='') as stream:
                writer = csv.DictWriter(stream, fieldnames=columns)
                writer.writeheader()
                writer.writerows({key: csv_value(row.get(key)) for key in columns} for row in rows)
            print(json.dumps(summary, ensure_ascii=False, indent=2))
            print(f'报告目录：{output.resolve()}')
            return 2 if summary['detailReadFailures'] else 0
        finally:
            try:
                api.json('POST', '/api/v1/auth/logout', {})
            except ImportErrorWithMessage:
                print('警告：注销请求未成功，诊断结果不受影响', file=sys.stderr)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ImportErrorWithMessage, OSError, ValueError, KeyError, TypeError) as error:
        print(f'诊断未完成：{error}', file=sys.stderr)
        sys.exit(1)
