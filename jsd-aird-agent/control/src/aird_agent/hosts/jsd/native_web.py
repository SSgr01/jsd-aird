"""Existing enterprise search backend for dsh's native web capability."""
from fastapi import HTTPException

from aird_agent.capabilities.schema import tool


def search_spec():
    return tool("dsh_web_search", "Internal native web search transport", {
        "query": {"type": "string", "minLength": 1, "maxLength": 300},
        "max_results": {"type": "integer", "minimum": 1, "maximum": 10},
    }, ["query", "max_results"])


async def search(service, principal, run, args):
    result = await service.host_call(principal, "external_search", {"query": args["query"]}, run["id"])
    if result.get("status") not in {"SUCCEEDED", "EMPTY"}:
        # Provider failures are never projected as a successful empty search.
        raise HTTPException(503, "WEB_SEARCH_" + {
            "NOT_CONFIGURED": "NOT_CONFIGURED", "INVALID_QUERY": "INVALID_QUERY",
            "HTTP_ERROR": "PROVIDER_FAILED", "PROVIDER_ERROR": "PROVIDER_FAILED",
        }.get(result.get("status"), "PROVIDER_FAILED"))
    sources = [{key: value for key, value in {
        "url": item["url"], "title": item.get("title"), "summary": item.get("content"),
        "publishedAt": item.get("publishedAt"),
    }.items() if value} for item in result.get("results", [])]
    return {"sources": sources[:args["max_results"]], "truncated": len(sources) > args["max_results"]}
