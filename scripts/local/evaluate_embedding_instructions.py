#!/usr/bin/env python3
"""Compare none/generic/materials query instructions on a frozen, locally judged corpus.

Only calls the configured /embeddings API. Does not change knowledge publications,
grants or database vectors. See docs/qwen3-embedding.md for suite format and limits.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request


PRESETS = (Path(__file__).resolve().parents[2] / "jsd-aird-api/src/main/resources/ai/embedding-query-presets.json")
PROFILES = ("none", "generic", "materials")


def sha(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def validate_suite(suite):
    """Reject missing/ambiguous judgments rather than silently scoring a partial suite."""
    if not isinstance(suite, dict):
        raise ValueError("Suite must be an object")
    corpus, queries = suite.get("corpus"), suite.get("queries")
    if not isinstance(corpus, list) or not corpus or not isinstance(queries, list) or not queries:
        raise ValueError("Nonempty corpus and queries arrays are required")
    for items, text_key in ((corpus, "text"), (queries, "query")):
        ids = set()
        for item in items:
            if not isinstance(item, dict) or not isinstance(item.get("id"), str) or not item["id"].strip():
                raise ValueError("Each item needs a nonempty string id")
            if item["id"] in ids:
                raise ValueError("Duplicate id in corpus or queries")
            ids.add(item["id"])
            if not isinstance(item.get(text_key), str) or not item[text_key].strip():
                raise ValueError(f"Each item needs nonempty {text_key}")
    corpus_ids = {item["id"] for item in corpus}
    for query in queries:
        relevance = query.get("relevance")
        if not isinstance(relevance, dict) or not relevance or not set(relevance).issubset(corpus_ids):
            raise ValueError("Relevance must map known corpus ids to grades")
        if any(type(grade) is not int or not 0 <= grade <= 3 for grade in relevance.values()):
            raise ValueError("Relevance grades must be integers from 0 to 3")
        if not any(grade > 0 for grade in relevance.values()):
            raise ValueError("Every query needs at least one relevant corpus item")
    return corpus, queries


def format_query(query, profile, presets):
    if profile == "none":
        return query
    return presets["format"].replace("{instruction}", presets[profile]).replace("{query}", query)


def normalize(vector, dimension):
    if (not isinstance(vector, list) or len(vector) != dimension
            or any(isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x) for x in vector)):
        raise ValueError("Embedding response has invalid values or dimension")
    length = math.hypot(*vector)
    if not math.isfinite(length) or length == 0:
        raise ValueError("Embedding response contains a zero or invalid vector")
    return [x / length for x in vector]


class EmbeddingClient:
    def __init__(self, base_url, model, dimension, key="", batch_size=10, timeout=60):
        parsed = urllib.parse.urlsplit(base_url)
        if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment):
            raise ValueError("Use an http(s) API base URL without credentials, query or fragment")
        if not model or not model.strip() or dimension < 1 or not 1 <= batch_size <= 10 or not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("Model, positive dimension/timeout and batch size 1..10 are required")
        self.endpoint = base_url.rstrip("/") + "/embeddings"
        self.model, self.dimension, self.key = model.strip(), dimension, key.strip()
        self.batch_size, self.timeout = batch_size, timeout

    def embed(self, texts):
        result = []
        for start in range(0, len(texts), self.batch_size):
            batch = texts[start:start + self.batch_size]
            headers = {"Content-Type": "application/json", "Accept": "application/json"}
            if self.key:
                headers["Authorization"] = "Bearer " + self.key
            body = {"model": self.model, "dimensions": self.dimension, "encoding_format": "float",
                    "input": [text.strip() for text in batch]}
            request = urllib.request.Request(self.endpoint, data=canonical(body).encode(), headers=headers, method="POST")
            try:
                with urllib.request.urlopen(request, timeout=self.timeout) as response:
                    payload = json.load(response)
                    data = payload.get("data") if isinstance(payload, dict) else None
            except urllib.error.HTTPError as error:
                raise RuntimeError(f"Embedding API returned HTTP {error.code}; evaluation stopped") from None
            except (OSError, ValueError) as error:
                raise RuntimeError("Embedding API request/JSON failed; evaluation stopped") from None
            if not isinstance(data, list) or len(data) != len(batch):
                raise ValueError("Embedding response is missing batch items")
            ordered = [None] * len(batch)
            for item in data:
                index = item.get("index", 0 if len(batch) == 1 else None) if isinstance(item, dict) else None
                if type(index) is not int or not 0 <= index < len(batch) or ordered[index] is not None:
                    raise ValueError("Embedding response has missing, duplicate or invalid indices")
                ordered[index] = normalize(item.get("embedding"), self.dimension)
            result.extend(ordered)
        return result


def metrics(ranked_ids, relevance, k):
    relevant = {key for key, grade in relevance.items() if grade > 0}
    selected = ranked_ids[:k]
    recall = len(relevant.intersection(selected)) / len(relevant)
    def dcg(grades):
        return sum((2 ** grade - 1) / math.log2(rank + 2) for rank, grade in enumerate(grades))
    ideal = dcg(sorted(relevance.values(), reverse=True)[:k])
    return {f"recall@{k}": recall, f"ndcg@{k}": dcg([relevance.get(key, 0) for key in selected]) / ideal}


def evaluate(suite, client, presets, top_k):
    corpus, queries = validate_suite(suite)
    if not top_k or any(type(k) is not int or k < 1 for k in top_k):
        raise ValueError("K values must be positive integers")
    # One document embedding pass, shared unchanged by all three query profiles.
    documents = client.embed([item["text"] for item in corpus])
    if len(documents) != len(corpus):
        raise ValueError("Document embedding count mismatch")
    groups = {}
    for profile in PROFILES:
        inputs = [format_query(item["query"].strip(), profile, presets) for item in queries]
        vectors = client.embed(inputs)
        if len(vectors) != len(queries):
            raise ValueError("Query embedding count mismatch")
        rows = []
        for query, vector in zip(queries, vectors):
            ranked = sorted(((item["id"], math.fsum(a * b for a, b in zip(vector, doc)))
                             for item, doc in zip(corpus, documents)), key=lambda hit: (-hit[1], hit[0]))
            scores = {}
            for k in top_k:
                scores.update(metrics([key for key, _ in ranked], query["relevance"], k))
            rows.append({"id": query["id"], "metrics": scores,
                         "hits": [{"id": key, "cosine": score, "relevance": query["relevance"].get(key, 0)}
                                  for key, score in ranked[:max(top_k)]]})
        means = {key: math.fsum(row["metrics"][key] for row in rows) / len(rows) for key in rows[0]["metrics"]}
        groups[profile] = {"instruction": "" if profile == "none" else presets[profile],
                           "instruction_sha256": sha("" if profile == "none" else presets[profile]),
                           "mean": means, "queries": rows}
    return {"kind": "embedding-only-exact-cosine", "model": client.model, "dimension": client.dimension,
            "suite_sha256": sha(canonical(suite)), "corpus_sha256": sha(canonical(corpus)),
            "preset_sha256": sha(canonical(presets)), "instruction_version": presets["version"],
            "corpus_count": len(corpus), "query_count": len(queries), "top_k": sorted(set(top_k)),
            "profiles": groups,
            "delta_vs_none": {profile: {key: value - groups["none"]["mean"][key]
                                        for key, value in groups[profile]["mean"].items()}
                              for profile in PROFILES[1:]}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New report JSON, preferably under .runtime")
    parser.add_argument("--base-url", default=os.environ.get("JSD_AIRD_EMBEDDING_BASE_URL", ""))
    parser.add_argument("--model", default=os.environ.get("JSD_AIRD_EMBEDDING_MODEL", ""))
    parser.add_argument("--dimension", type=int, default=os.environ.get("JSD_AIRD_EMBEDDING_DIMENSION", "1024"))
    parser.add_argument("--batch-size", type=int, default=10)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--top-k", default="5,10,20")
    args = parser.parse_args(argv)
    try:
        if args.output.exists():
            raise ValueError("Output already exists; choose a new report path")
        top_k = sorted({int(k) for k in args.top_k.split(",")})
        suite_bytes = args.cases.read_bytes()
        suite = json.loads(suite_bytes.decode("utf-8-sig"))
        presets = json.loads(PRESETS.read_text(encoding="utf-8"))
        client = EmbeddingClient(args.base_url, args.model, args.dimension,
                                 os.environ.get("JSD_AIRD_EMBEDDING_API_KEY", ""), args.batch_size, args.timeout)
        report = evaluate(suite, client, presets, top_k)
        report["suite_file_sha256"] = hashlib.sha256(suite_bytes).hexdigest()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2, allow_nan=False)
        for profile, group in report["profiles"].items():
            print(profile + ": " + canonical(group["mean"]))
        print("Report: " + str(args.output))
    except (OSError, ValueError, RuntimeError) as error:
        parser.exit(1, f"Evaluation failed: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
