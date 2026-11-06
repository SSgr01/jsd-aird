"""Readable links to immutable originals, for native Markdown and document exports."""
import re


def citation_links(evidence, origin):
    result = {}
    for eid, candidate in evidence.items():
        source = candidate.get("source", {})
        if source.get("kind") == "BUSINESS_RECORD" and source.get("resource_type") in {"PROJECT", "STAGE", "TASK", "REQUIREMENT", "MATERIAL"}:
            project = source.get("project_id") or source.get("anchor", {}).get("project_id")
            if source["resource_type"] == "PROJECT":
                project = source["resource_id"]
            if project:
                result[eid] = {"url": origin.rstrip("/") + f"/projects/{project}", "label": source.get("title") or "项目记录"}
            continue
        if source.get("kind") == "BUSINESS_RECORD" and source.get("resource_type") == "EXPERIMENT":
            result[eid] = {"url": origin.rstrip("/") + f"/experiments/{source['resource_id']}?version_id={source['version_id']}",
                           "label": "实验记录·V" + str(source.get("anchor", {}).get("versionNo", ""))}
            continue
        kind = source.get("kind")
        if kind not in {"KB_PUBLICATION", "RUN_UPLOAD", "EXTERNAL_FULLTEXT"}:
            continue
        anchor = source.get("anchor") or {}
        page = (anchor.get("original") or {}).get("page") or anchor.get("page") or anchor.get("page_no")
        if kind == "KB_PUBLICATION":
            path = f"/api/v1/knowledge/documents/{source['document_id']}/versions/{source['version_id']}/content"
        else:
            path = f"/api/v1/agents/private-sources/{source['document_id']}/content?version_id={source['version_id']}"
        if isinstance(page, int) and page > 0:
            path += f"#page={page}"
        result[eid] = {"url": origin.rstrip("/") + path,
                       "label": f"原文·第{page}页" if page else "原文"}
    return result


def public_answer(text, evidence, origin=""):
    links = citation_links(evidence, origin)
    def replace(match):
        eid = match[1]
        link = links.get(eid)
        if not link:
            return "（" + evidence.get(eid, {}).get("source", {}).get("title", "来源记录") + "）"
        target = link["url"] + ("&" if "#" in link["url"] else "#") + "evidence=" + eid
        return "[" + link["label"] + "](" + target + ")"
    return re.sub(r"\[E:(e[a-f0-9]{24})\]", replace, text)


def public_citations(text, evidence, origin=""):
    links = citation_links(evidence, origin)
    return [{"evidenceId": eid, "source": evidence[eid]["source"],
             "sourceType": evidence[eid]["source"]["kind"], "title": evidence[eid]["source"].get("title", "来源记录"),
             "url": links.get(eid, {}).get("url")}
            for eid in dict.fromkeys(re.findall(r"\[E:(e[a-f0-9]{24})\]", text)) if eid in evidence]


def canonical_links(text, links):
    for eid, link in links.items():
        target = link["url"] + ("&" if "#" in link["url"] else "#") + "evidence=" + eid
        text = re.sub(r"\[[^\]\n]+\]\(" + re.escape(target) + r"\)", "[E:" + eid + "]", text)
    return text
