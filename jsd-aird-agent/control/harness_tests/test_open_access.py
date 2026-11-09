import hashlib
import json

import httpx
import pytest
from fastapi import HTTPException

from aird_agent.capabilities.literature.open_access import acquire, asset_url, xml_document


@pytest.mark.parametrize(
    "url",
    [
        "https://evil.example/PMC1.1/article.pdf?md5=" + "a" * 32,
        "https://pmc-oa-opendata.s3.amazonaws.com/PMC2.1/article.pdf?md5=" + "a" * 32,
        "https://pmc-oa-opendata.s3.amazonaws.com/PMC1.1/%2e%2e/secret?md5=" + "a" * 32,
        "https://user:secret@pmc-oa-opendata.s3.amazonaws.com/PMC1.1/article.pdf?md5=" + "a" * 32,
    ],
)
def test_cloud_assets_cannot_escape_the_selected_published_version(url):
    with pytest.raises(HTTPException):
        asset_url(url, "PMC1.1/")


def test_article_xml_refuses_entities_and_internal_dtd():
    with pytest.raises(HTTPException):
        xml_document(
            b'<!DOCTYPE article [<!ENTITY secret SYSTEM "file:///private">]><article>&secret;</article>'
        )
    assert xml_document(b'<!DOCTYPE article PUBLIC "JATS" "remote.dtd"><article/>').tag == "article"


@pytest.mark.parametrize("fault", [None, "checksum", "license", "identifier", "redirect", "versions"])
async def test_acquisition_requires_license_identity_hash_and_unambiguous_version(fault):
    pdf = b"%PDF-1.7\nsynthetic test only"
    xml = (
        b'<article xmlns:xlink="http://www.w3.org/1999/xlink"><front><article-meta>'
        + (
            b'<article-id pub-id-type="pmcid">PMC2</article-id>'
            if fault == "identifier"
            else b'<article-id pub-id-type="pmcid">PMC1</article-id>'
        )
        + b'<permissions><license><ext-link xlink:href="https://creativecommons.org/licenses/by/4.0/"/>'
        b"</license></permissions></article-meta></front></article>"
    )
    cloud = "https://pmc-oa-opendata.s3.amazonaws.com/"

    def respond(request):
        if request.url.path == "/":
            prefixes = ["PMC1.1/", "PMC1.2/"] if fault == "versions" else ["PMC1.1/"]
            return httpx.Response(
                200,
                content=(
                    "<ListBucketResult><IsTruncated>false</IsTruncated>"
                    + "".join("<CommonPrefixes><Prefix>" + p + "</Prefix></CommonPrefixes>" for p in prefixes)
                    + "</ListBucketResult>"
                ).encode(),
            )
        prefix = request.url.path.split("/")[1] + "/"
        if request.url.path.endswith(".json"):
            return httpx.Response(
                200,
                json={
                    "pmcid": "PMC1",
                    "version": 1,
                    "license_code": "cc-by-nc" if fault == "license" else "cc-by",
                    "is_retracted": "no",
                    "is_pmc_openaccess": "yes",
                    "is_manuscript": "no",
                    "pdf_url": cloud + prefix + "article.pdf?md5=" + hashlib.md5(pdf).hexdigest(),
                    "xml_url": cloud + prefix + "article.xml?md5=" + hashlib.md5(xml).hexdigest(),
                },
            )
        if fault == "redirect":
            return httpx.Response(302, headers={"location": "https://evil.example/private"})
        content = (
            xml if request.url.path.endswith(".xml") else pdf + (b"changed" if fault == "checksum" else b"")
        )
        return httpx.Response(200, content=content)

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond), follow_redirects=False) as client:
        if fault:
            with pytest.raises(HTTPException):
                await acquire("PMC1", client=client)
        else:
            result = await acquire("PMC1", client=client)
            assert result["content"] == pdf
            assert result["provenance"]["sha256"] == hashlib.sha256(pdf).hexdigest()
            assert "secret" not in json.dumps(result["provenance"])
