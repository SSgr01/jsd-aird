from __future__ import annotations

import logging

import uvicorn

from jsd_aird_ai.settings import Settings


def run() -> None:
    logging.basicConfig(
        level="INFO",
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    settings = Settings()
    uvicorn.run(
        "jsd_aird_ai.api:app",
        host=settings.host,
        port=settings.port,
        access_log=False,
    )


if __name__ == "__main__":
    run()
