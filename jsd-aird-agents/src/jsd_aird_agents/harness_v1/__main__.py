import argparse
import asyncio
import sys

from .contracts import HarnessSettings
from .store import Store


def main():
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    parser = argparse.ArgumentParser()
    parser.add_argument("--migrate", action="store_true")
    args = parser.parse_args()
    settings = HarnessSettings()
    if args.migrate:

        async def migrate():
            store = Store(settings.database_url.get_secret_value())
            await store.pool.open(wait=True)
            try:
                await store.migrate()
            finally:
                await store.pool.close()

        asyncio.run(migrate())
    else:
        import uvicorn

        from .api import create_app

        # Uvicorn 0.52 explicitly constructs Proactor on Windows, ignoring the
        # policy above; psycopg async connections require Selector instead.
        uvicorn.run(
            create_app(settings),
            host="127.0.0.1",
            port=8093,
            loop="asyncio:SelectorEventLoop" if sys.platform == "win32" else "auto",
        )


if __name__ == "__main__":
    main()
