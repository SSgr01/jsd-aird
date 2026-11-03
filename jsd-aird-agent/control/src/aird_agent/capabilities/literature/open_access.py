"""Acquire licensed PMC full text by identifier; no caller-controlled URL or redirect."""

import hashlib
import json
import re
from datetime import UTC, datetime
from urllib.parse import parse_qs, unquote, urlsplit
from xml.etree import ElementTree as ET

import httpx
from fastapi import HTTPException


async def bounded_get(client, url, maximum, *, params=None):
    async with client.stream("GET", url, params=params) as response:
        if response.status_code != 200:
            raise HTTPException(502, "OA_SOURCE_UNAVAILABLE")
        content = bytearray()
        async for chunk in response.aiter_bytes():
            content.extend(chunk)
            if len(content) > maximum:
                raise HTTPException(413, "OA_DOWNLOAD_BUDGET_EXCEEDED")
        return bytes(content)


def xml_document(content):
    # External entities are never resolved, and DTD/entity declarations are refused.
    if re.search(rb"<!\s*(DOCTYPE|ENTITY)", content, re.IGNORECASE):
        # JATS usually has an inert public DOCTYPE. Strip it only when no internal subset/entities exist.
        if b"<!ENTITY" in content.upper() or re.search(rb"<!DOCTYPE[^>]*\[", content, re.IGNORECASE):
            raise HTTPException(422, "OA_XML_ENTITIES_NOT_ALLOWED")
        content = re.sub(rb"<!DOCTYPE[^>]*>", b"", content, flags=re.IGNORECASE)
    try:
        return ET.fromstring(content)
    except ET.ParseError:
        raise HTTPException(422, "OA_XML_INVALID") from None


def asset_url(value, prefix):
    parsed = urlsplit(value or "")
    if parsed.scheme == "s3" and parsed.netloc == "pmc-oa-opendata":
        parsed = urlsplit(
            "https://pmc-oa-opendata.s3.amazonaws.com/"
            + parsed.path.lstrip("/")
            + ("?" + parsed.query if parsed.query else "")
        )
    path = unquote(parsed.path)
    if (
        parsed.scheme != "https"
        or parsed.hostname != "pmc-oa-opendata.s3.amazonaws.com"
        or parsed.port not in {None, 443}
        or parsed.username
        or parsed.password
        or parsed.fragment
        or not path.startswith("/" + prefix)
        or any(part in {".", ".."} for part in path.split("/"))
    ):
        raise HTTPException(403, "OA_ASSET_LOCATION_REFUSED")
    checksum = parse_qs(parsed.query).get("md5", [None])[0]
    if not checksum or not re.fullmatch(r"[a-fA-F0-9]{32}", checksum):
        raise HTTPException(422, "OA_ASSET_CHECKSUM_REQUIRED")
    return parsed.geturl(), checksum.lower()


async def acquire(pmcid, *, version=None, client=None):
    if not re.fullmatch(r"PMC[0-9]{1,10}", pmcid):
        raise HTTPException(422, "OA_REQUIRES_PMC_IDENTIFIER")
    owned = client is None
    client = client or httpx.AsyncClient(timeout=60, follow_redirects=False)
    cloud = "https://pmc-oa-opendata.s3.amazonaws.com/"
    try:
        # The legacy OA API/FTP dataset was retired in August 2026. Use the current official Cloud layout.
        listing = xml_document(
            await bounded_get(
                client,
                cloud,
                100000,
                params={
                    "list-type": "2",
                    "prefix": pmcid + ".",
                    "delimiter": "/",
                    "max-keys": "20",
                },
            )
        )
        if listing.findtext("{*}IsTruncated") == "true":
            raise HTTPException(422, "OA_VERSION_LIST_TOO_LARGE")
        prefixes = [
            element.text
            for element in listing.findall("{*}CommonPrefixes/{*}Prefix")
            if element.text and re.fullmatch(re.escape(pmcid) + r"\.[1-9][0-9]*/", element.text)
        ]
        if len(prefixes) > 10:
            raise HTTPException(422, "OA_VERSION_LIST_TOO_LARGE")
        selected = []
        for prefix in prefixes:
            if version is not None and prefix != pmcid + "." + str(version) + "/":
                continue
            metadata = json.loads(await bounded_get(client, cloud + prefix + prefix[:-1] + ".json", 100000))
            license_code = str(metadata.get("license_code", "")).lower().replace(" ", "-")
            if (
                str(metadata.get("pmcid")) != pmcid
                or str(metadata.get("is_retracted", "")).lower() not in {"no", "false"}
                or str(metadata.get("is_pmc_openaccess", "")).lower() not in {"yes", "true"}
                or str(metadata.get("is_manuscript", "")).lower() not in {"no", "false"}
                or license_code not in {"cc-by", "cc-by-sa", "cc0"}
            ):
                continue
            selected.append((prefix, metadata))
        if not selected:
            raise HTTPException(403, "OA_REUSABLE_PUBLISHED_VERSION_NOT_CONFIRMED")
        if len(selected) != 1:
            raise HTTPException(422, "OA_MULTIPLE_VERSIONS_REQUIRE_SELECTION")
        prefix, metadata = selected[0]
        pdf_url, pdf_md5 = asset_url(metadata.get("pdf_url"), prefix)
        xml_url, xml_md5 = asset_url(metadata.get("xml_url"), prefix)
        xml = await bounded_get(client, xml_url, 10 * 1024 * 1024)
        pdf = await bounded_get(client, pdf_url, 10 * 1024 * 1024)
        if hashlib.md5(xml).hexdigest() != xml_md5 or hashlib.md5(pdf).hexdigest() != pdf_md5:
            raise HTTPException(422, "OA_ASSET_CHECKSUM_MISMATCH")
        if not pdf.startswith(b"%PDF-"):
            raise HTTPException(422, "OA_ARTICLE_PDF_INVALID")
        document = xml_document(xml)
        actual = document.find(".//article-id[@pub-id-type='pmcid']")
        if actual is None:
            actual = document.find(".//article-id[@pub-id-type='pmc']")
        if actual is None or actual.text is None or actual.text.removeprefix("PMC") != pmcid[3:]:
            raise HTTPException(422, "OA_IDENTIFIER_MISMATCH")
        href = "{http://www.w3.org/1999/xlink}href"
        license_urls = [
            element.get(href, "")
            for license in document.findall(".//permissions/license")
            for element in license.iter()
        ]
        license_url = next(
            (
                url
                for url in license_urls
                if re.fullmatch(
                    r"https?://creativecommons\.org/(?:licenses/by(?:-sa)?/[0-9.]+|publicdomain/zero/[0-9.]+)/?",
                    url,
                )
            ),
            None,
        )
        if not license_url:
            raise HTTPException(403, "OA_XML_LICENSE_NOT_CONFIRMED")
        return {
            "name": prefix[:-1] + ".pdf",
            "content": pdf,
            "xml": xml,
            "provenance": {
                "pmcid": pmcid,
                "version": metadata["version"],
                "doi": metadata.get("doi"),
                "title": metadata.get("title", pmcid),
                "license": license_url,
                "source_url": "https://pmc.ncbi.nlm.nih.gov/articles/" + pmcid + "/",
                "metadata_url": cloud + prefix + prefix[:-1] + ".json",
                "pdf_url": pdf_url,
                "xml_url": xml_url,
                "xml_sha256": hashlib.sha256(xml).hexdigest(),
                "sha256": hashlib.sha256(pdf).hexdigest(),
                "retrieved_at": datetime.now(UTC).isoformat(),
            },
        }
    except (httpx.HTTPError, ValueError, KeyError):
        raise HTTPException(502, "OA_ACQUISITION_FAILED") from None
    finally:
        if owned:
            await client.aclose()
