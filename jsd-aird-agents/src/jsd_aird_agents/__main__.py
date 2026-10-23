import argparse
import asyncio
import json
import logging
import secrets
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Independent JSD project agent runtime")
    parser.add_argument(
        "command",
        choices=[
            "api",
            "worker",
            "scheduler",
            "packs-list",
            "migrate",
            "seed",
            "seed-monitoring",
            "demo-env",
            "skills-sync",
            "skills-list",
            "skills-enable",
            "skills-disable",
        ],
    )
    parser.add_argument("--name")
    parser.add_argument("--sha256")
    parser.add_argument(
        "--database-url", default="postgresql://jsd_aird_agents@127.0.0.1:5432/jsd_aird_agents"
    )
    args = parser.parse_args()
    if args.command in {"skills-enable", "skills-disable"} and (not args.name or not args.sha256):
        parser.error("Skill state changes require --name and the exact --sha256 from skills-list")
    if args.command == "demo-env":
        target = Path(".env")
        if target.exists():
            parser.error(".env exists; it will not be overwritten")
        target.write_text(
            f"JSD_AIRD_AGENTS_DATABASE_URL={args.database_url}\n"
            "JSD_AIRD_AGENTS_MODEL_BACKEND=scripted\n"
            f"JSD_AIRD_AGENTS_ACTOR_TOKEN={secrets.token_urlsafe(32)}\n"
            f"JSD_AIRD_AGENTS_APPROVER_TOKEN={secrets.token_urlsafe(32)}\n"
            f"JSD_AIRD_AGENTS_OUTSIDER_TOKEN={secrets.token_urlsafe(32)}\n",
            encoding="utf-8",
        )
        print("Created local .env with random fixture tokens; no credentials were printed.")
        return
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    from jsd_aird_agents.settings import Settings

    settings = Settings()
    if args.command == "api":
        import uvicorn

        from jsd_aird_agents.api import create_app

        async def serve():
            # Own the loop: Uvicorn's Windows default is Proactor, incompatible with Psycopg async.
            config = uvicorn.Config(
                create_app(settings), host=settings.host, port=settings.port, access_log=False
            )
            await uvicorn.Server(config).serve()

        asyncio.run(serve())
        return

    async def execute():
        from jsd_aird_agents.adapters.fixtures import seed
        from jsd_aird_agents.services import Services
        from jsd_aird_agents.worker import Worker

        services = Services(settings)
        await services.open()
        try:
            if args.command == "migrate":
                await services.db.migrate()
            elif args.command == "seed":
                await seed(services.db)
                result = await services.skills.sync()
                print(json.dumps(result, ensure_ascii=False))
                if result["errors"]:
                    raise SystemExit(1)
            elif args.command == "seed-monitoring":
                from jsd_aird_agents.app.verticals.project.monitoring_fixtures import seed_monitoring

                await seed_monitoring(services.db, services.clock.now())
                print(
                    "Seeded opt-in monitoring fixtures in the second mock project; no schedules were enabled."
                )
            elif args.command == "scheduler":
                from jsd_aird_agents.app.platform.scheduler import Scheduler

                await Scheduler(services).run_forever()
            elif args.command == "packs-list":
                print(json.dumps(services.packs.describe(), ensure_ascii=False))
            elif args.command == "skills-sync":
                result = await services.skills.sync()
                print(json.dumps(result, ensure_ascii=False))
                if result["errors"]:
                    raise SystemExit(1)
            elif args.command == "skills-list":
                result = (
                    await services.skills.history(args.name) if args.name else await services.skills.catalog()
                )
                print(json.dumps(result, ensure_ascii=False))
            elif args.command in {"skills-enable", "skills-disable"}:
                await services.skills.set_enabled(args.name, args.sha256, args.command == "skills-enable")
                print("Updated the exact skill revision; scripts remain non-executable.")
            else:
                await Worker(services).run_forever()
        finally:
            await services.close()

    asyncio.run(execute())


if __name__ == "__main__":
    main()
