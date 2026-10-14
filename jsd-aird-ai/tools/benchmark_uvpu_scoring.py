from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import tracemalloc
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.contracts import ScoreRequest, ScoreRow
from jsd_aird_ai.devtools import (
    DEFAULT_MODEL_INDEX,
    DEFAULT_SCORE_INPUT,
    build_indexed_score_request,
    read_score_input,
)
from jsd_aird_ai.service import FormulaModelService
from jsd_aird_ai.settings import Settings


def main() -> None:
    parser = argparse.ArgumentParser(description="Benchmark the T07-PRE batch scoring path")
    parser.add_argument("--index", default=str(DEFAULT_MODEL_INDEX))
    parser.add_argument("--input", default=str(DEFAULT_SCORE_INPUT))
    parser.add_argument("--sizes", default="1,20,100,1000,20000")
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()

    source = read_score_input(Path(args.input))
    base = build_indexed_score_request(source, Path(args.index))
    template = base.rows[0]
    service = FormulaModelService(Settings(allow_file_urls=True, model_cache_entries=2))
    output = []
    for size in [int(item) for item in args.sizes.split(",")]:
        rows = [
            ScoreRow(
                row_id=f"benchmark-{size}-{index}",
                formula=template.formula,
                context=template.context,
            )
            for index in range(size)
        ]
        request = ScoreRequest.model_validate(
            {**base.model_dump(mode="json", by_alias=True), "rows": rows}
        )
        durations = []
        peaks = []
        for _ in range(args.repeats):
            tracemalloc.start()
            started = time.perf_counter()
            response = service.score(request)
            durations.append(time.perf_counter() - started)
            _, peak = tracemalloc.get_traced_memory()
            tracemalloc.stop()
            peaks.append(peak)
            if len(response.rows) != size:
                raise RuntimeError("batch scoring returned an unexpected row count")
        output.append(
            {
                "rows": size,
                "medianSeconds": statistics.median(durations),
                "minimumSeconds": min(durations),
                "maximumSeconds": max(durations),
                "rowsPerSecond": size / statistics.median(durations),
                "peakPythonBytes": max(peaks),
            }
        )
    print(json.dumps({"repeats": args.repeats, "results": output}, indent=2))


if __name__ == "__main__":
    main()
