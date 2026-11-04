package com.jsd.aird.kb.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.kb.api.ScholarlyLiteratureFacade;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class ScholarlyLiteratureService implements ScholarlyLiteratureFacade {
    private final OpenAlexClient client;
    private final ObjectMapper mapper;
    private final HttpClient crossref = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Duration RECORD_TTL = Duration.ofDays(7);
    private static final Duration SEARCH_TTL = Duration.ofMinutes(15);

    public ScholarlyLiteratureService(OpenAlexClient client, ObjectMapper mapper) {
        this.client = client; this.mapper = mapper;
    }

    @Override public JsonNode lookup(JsonNode args) {
        var query = text(args, "query", 1000);
        try {
            var envelope = envelope("OpenAlex");
            var results = envelope.putArray("results");
            if (query.matches("(?:https://openalex.org/)?W[1-9][0-9]{0,20}")) {
                results.add(project(work(workId(query))));
            } else if (query.matches("(?i)(?:https?://(?:dx\\.)?doi.org/|doi:\\s*)?10\\.[0-9]{4,9}/.*")) {
                var doi = query.replaceFirst("(?i)^(?:https?://(?:dx\\.)?doi.org/|doi:\\s*)", "").toLowerCase(Locale.ROOT);
                if (doi.matches(".*[\\s?#\\\\].*")) throw new IllegalArgumentException("Invalid DOI");
                results.add(project(client.get("/works/https://doi.org/" + doi, Map.of(), "singleton", RECORD_TTL)));
            } else {
                var found = client.get("/works", Map.of("search", query, "per_page", "10"), "search", SEARCH_TTL);
                for (var item : found.path("results")) results.add(project(item));
            }
            if (results.isEmpty()) return crossref(query);
            return legacyLookup(envelope);
        } catch (Unavailable error) {
            // Preserve the existing query tool for installations without OpenAlex, and for missing records.
            if (Set.of("OPENALEX_NOT_FOUND", "OPENALEX_NOT_CONFIGURED").contains(error.code())) return crossref(query);
            return failure(error);
        }
    }

    @Override public JsonNode search(JsonNode args) {
        var query = text(args, "query", 2000);
        var mode = args.path("mode").asText("keyword");
        if (!Set.of("keyword", "semantic").contains(mode)) throw new IllegalArgumentException("Invalid search mode");
        var params = new LinkedHashMap<String, String>();
        params.put("semantic".equals(mode) ? "search.semantic" : "search", query);
        int size = pageSize(args);
        int semanticPage = 1;
        params.put("per_page", Integer.toString(size));
        if ("semantic".equals(mode)) {
            var token = cursor(args, "semantic:1:" + size);
            if (!token.matches("semantic:[1-9][0-9]?:" + size)) throw new IllegalArgumentException("Invalid semantic cursor or changed page size");
            semanticPage = Integer.parseInt(token.split(":")[1]);
            if ((semanticPage - 1) * size >= 50) throw new IllegalArgumentException("Semantic search is limited to 50 results");
            params.put("page", Integer.toString(semanticPage));
        } else params.put("cursor", cursor(args, "*"));
        var filters = new ArrayList<String>();
        filters.add("is_retracted:false");
        int from = args.path("from_year").asInt(0), to = args.path("to_year").asInt(0);
        if ((from != 0 && (from < 1600 || from > 2200)) || (to != 0 && (to < 1600 || to > 2200))
                || (from > 0 && to > 0 && from > to)) throw new IllegalArgumentException("Invalid year range");
        if (from > 0) filters.add("from_publication_date:" + from + "-01-01");
        if (to > 0) filters.add("to_publication_date:" + to + "-12-31");
        if (args.has("open_access")) filters.add("is_oa:" + args.path("open_access").asBoolean());
        if (args.hasNonNull("type")) {
            var type = args.path("type").asText();
            if (!Set.of("article", "review", "preprint", "book-chapter", "dissertation", "report").contains(type))
                throw new IllegalArgumentException("Invalid work type");
            filters.add("type:" + type);
        }
        params.put("filter", String.join(",", filters));
        try {
            var raw = client.get("/works", params, "semantic".equals(mode) ? "semantic" : "search", SEARCH_TTL);
            var result = page(raw).put("query", query).put("mode", mode);
            if ("semantic".equals(mode)) {
                int available = Math.min(50, raw.path("meta").path("count").asInt());
                if (semanticPage * size < available && raw.path("results").size() == size)
                    result.put("next_cursor", "semantic:" + (semanticPage + 1) + ":" + size);
                else result.putNull("next_cursor");
            }
            return result;
        } catch (Unavailable error) { return failure(error); }
    }

    @Override public JsonNode expand(JsonNode args) {
        var id = workId(text(args, "work_id", 100));
        var direction = args.path("direction").asText("references");
        try {
            var params = new LinkedHashMap<String, String>();
            params.put("per_page", Integer.toString(pageSize(args)));
            if ("citations".equals(direction)) {
                params.put("filter", "cites:" + id);
                params.put("cursor", cursor(args, "*"));
                return page(client.get("/works", params, "list", SEARCH_TTL)).put("direction", direction);
            }
            if (!"references".equals(direction)) throw new IllegalArgumentException("Invalid citation direction");
            var references = work(id).path("referenced_works");
            int offset;
            try { offset = Integer.parseInt(cursor(args, "0")); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid reference cursor"); }
            if (offset < 0 || offset > references.size()) throw new IllegalArgumentException("Invalid reference cursor");
            int end = Math.min(references.size(), offset + pageSize(args));
            var ids = new ArrayList<String>();
            for (int index = offset; index < end; index++) ids.add(workId(references.get(index).asText()));
            var result = envelope("OpenAlex").put("direction", direction).put("total", references.size());
            result.putArray("results");
            if (!ids.isEmpty()) {
                params.put("filter", "openalex_id:" + String.join("|", ids));
                result = page(client.get("/works", params, "list", SEARCH_TTL))
                    .put("direction", direction).put("total", references.size());
            }
            if (end < references.size()) result.put("next_cursor", Integer.toString(end)); else result.putNull("next_cursor");
            return result;
        } catch (Unavailable error) { return failure(error); }
    }

    @Override public FullText download(String value) {
        var id = workId(value);
        var work = work(id);
        if (work.path("is_retracted").asBoolean()) throw new Unavailable("OPENALEX_WORK_RETRACTED", 0);
        var license = work.path("best_oa_location").path("license").asText("").toLowerCase(Locale.ROOT);
        if (!Set.of("cc-by", "cc-by-sa", "cc0").contains(license))
            throw new Unavailable("OPENALEX_REUSABLE_LICENSE_NOT_CONFIRMED", 0);
        if (!work.path("has_content").path("pdf").asBoolean()
                || !work.path("content_urls").path("pdf").asText().equals("https://content.openalex.org/works/" + id + ".pdf"))
            throw new Unavailable("OPENALEX_FULLTEXT_UNAVAILABLE", 0);
        var bytes = client.pdf(id);
        if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
            throw new Unavailable("OPENALEX_INVALID_PDF", 0);
        var provenance = mapper.createObjectNode().put("provider", "OpenAlex").put("openalex_id", id)
            .put("source_url", "https://openalex.org/" + id).put("license", license)
            .put("retrieved_at", Instant.now().toString()).put("updated_date", work.path("updated_date").asText())
            .put("sha256", sha(bytes));
        provenance.set("bibliography", project(work));
        var name = work.path("title").asText("论文全文").replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", " ").strip();
        if (name.isBlank()) name = "论文全文";
        if (name.length() > 140) name = name.substring(0, 140);
        return new FullText(name + ".pdf", bytes, provenance);
    }

    private JsonNode work(String id) {
        var value = client.get("/works/" + id, Map.of(), "singleton", RECORD_TTL);
        if (!value.path("id").asText().equals("https://openalex.org/" + id))
            throw new Unavailable("OPENALEX_INVALID_RESPONSE", 0);
        return value;
    }

    private ObjectNode page(JsonNode raw) {
        if (!raw.path("results").isArray() || !raw.path("meta").isObject())
            throw new Unavailable("OPENALEX_INVALID_RESPONSE", 0);
        var result = envelope("OpenAlex").put("total", raw.path("meta").path("count").asLong());
        result.set("next_cursor", raw.path("meta").path("next_cursor"));
        var items = result.putArray("results");
        for (var item : raw.path("results")) items.add(project(item));
        return result;
    }

    private ObjectNode project(JsonNode work) {
        if (!work.path("id").asText().matches("https://openalex.org/W[1-9][0-9]{0,20}"))
            throw new Unavailable("OPENALEX_INVALID_RESPONSE", 0);
        var item = mapper.createObjectNode();
        for (var name : List.of("id", "doi", "title", "publication_year", "publication_date", "type", "is_retracted", "cited_by_count", "updated_date"))
            if (work.hasNonNull(name)) item.set(name, work.path(name));
        item.put("work_id", workId(work.path("id").asText()));
        item.put("source", work.path("primary_location").path("source").path("display_name").asText());
        item.set("biblio", work.path("biblio"));
        var topics=item.putArray("topics");
        for(var topic:work.path("topics")) {
            if(topics.size()>=10)break;
            if(!topic.path("display_name").asText().isBlank())topics.add(topic.path("display_name").asText());
        }
        var authors = item.putArray("authors");
        for (var authorship : work.path("authorships")) {
            if (authors.size() >= 30) break;
            var author = authors.addObject().put("name", authorship.path("author").path("display_name").asText());
            if (authorship.path("author").hasNonNull("orcid")) author.set("orcid", authorship.path("author").path("orcid"));
            var institutions = author.putArray("institutions");
            for (var institution : authorship.path("institutions")) {
                if (institutions.size() >= 10) break;
                institutions.add(institution.path("display_name").asText());
            }
        }
        item.put("authors_truncated", work.path("authorships").size() > authors.size());
        var words = new TreeMap<Integer, String>();
        work.path("abstract_inverted_index").fields().forEachRemaining(entry -> {
            for (var position : entry.getValue())
                if (position.isInt() && position.asInt() >= 0 && position.asInt() < 1500)
                    words.put(position.asInt(), entry.getKey());
        });
        item.put("abstract", String.join(" ", words.values()));
        item.put("abstract_truncated", words.size() >= 1500);
        var location = work.path("best_oa_location");
        item.put("open_access", work.path("open_access").path("is_oa").asBoolean());
        item.set("license", location.path("license"));
        item.put("fulltext_available", work.path("has_content").path("pdf").asBoolean());
        item.put("automatic_acquisition_allowed", Set.of("cc-by", "cc-by-sa", "cc0").contains(location.path("license").asText())
            && item.path("fulltext_available").asBoolean() && !work.path("is_retracted").asBoolean());
        item.put("source_url", work.path("id").asText());
        item.put("metadata_only", true);
        return item;
    }

    private JsonNode crossref(String query) {
        try {
            var uri = UriComponentsBuilder.fromUriString("https://api.crossref.org/works")
                .queryParam("query.bibliographic", query).queryParam("rows", 10).build().encode().toUri();
            var response = crossref.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "JSD-AIRD-Research/1.0").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) return failure(new Unavailable("CROSSREF_UNAVAILABLE", 60));
                var bytes = body.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) return failure(new Unavailable("CROSSREF_RESPONSE_TOO_LARGE", 0));
                var raw = mapper.readTree(bytes);
                var result = envelope("Crossref");
                var items = result.putArray("results");
                for (var record : raw.path("message").path("items")) {
                    var item = items.addObject().put("title", record.path("title").path(0).asText())
                        .put("doi", record.path("DOI").asText()).put("source", record.path("container-title").path(0).asText())
                        .put("source_url", record.path("URL").asText()).put("metadata_only", true);
                    item.set("authors", record.path("author")); item.set("published", record.path("published"));
                }
                // Keep the historical envelope member; normalized results are the agent-facing contract.
                result.set("result", raw);
                return result;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return failure(new Unavailable("CROSSREF_INTERRUPTED", 0));
        } catch (Exception error) { return failure(new Unavailable("CROSSREF_UNAVAILABLE", 60)); }
    }

    private ObjectNode envelope(String provider) {
        return mapper.createObjectNode().put("provider", provider).put("metadata_only", true)
            .put("retrieved_at", Instant.now().toString());
    }
    private ObjectNode legacyLookup(ObjectNode result) {
        var items = mapper.createArrayNode();
        for (var item : result.path("results")) {
            var legacy = items.addObject().put("DOI", item.path("doi").asText().replaceFirst("^https://doi.org/", ""))
                .put("URL", item.path("source_url").asText());
            legacy.putArray("title").add(item.path("title").asText());
            legacy.putArray("container-title").add(item.path("source").asText());
            legacy.set("author", item.path("authors").deepCopy());
        }
        result.set("result", mapper.createObjectNode().set("message", mapper.createObjectNode().set("items", items)));
        return result;
    }
    public ObjectNode failure(Unavailable error) {
        return mapper.createObjectNode().put("error", error.code()).put("retryable", error.retryAfter() > 0)
            .put("retry_after_seconds", error.retryAfter()).put("metadata_only", true);
    }
    public static String workId(String value) {
        var id = value.replaceFirst("^https://openalex.org/", "");
        if (!id.matches("W[1-9][0-9]{0,20}")) throw new IllegalArgumentException("A canonical OpenAlex work ID is required");
        return id;
    }
    private static String text(JsonNode args, String field, int max) {
        var value = args.path(field).asText("").strip();
        if (value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + field);
        return value;
    }
    private static int pageSize(JsonNode args) {
        int count = args.path("limit").asInt(10);
        if (count < 1 || count > 20) throw new IllegalArgumentException("Invalid page size");
        return count;
    }
    private static String cursor(JsonNode args, String fallback) {
        var value = args.path("cursor").asText(fallback);
        if (value.isBlank() || value.length() > 2048 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid cursor");
        return value;
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
