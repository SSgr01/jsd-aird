package com.jsd.aird.kb.application;

import java.util.Set;

/** Source metadata supports arbitrary document kinds; these are fields, not a type taxonomy. */
public final class KnowledgeMetadataFields {
    private KnowledgeMetadataFields() { }
    public static final int MAX_EXTRA_FIELDS = 20;
    public static final int MAX_EXTRA_LABEL = 64;
    public static final int MAX_EXTRA_VALUE = 1000;
    public static final Set<String> MODEL = Set.of("document_kind", "title", "authors", "organization",
            "document_date", "document_number", "version", "doi", "publication_date", "publication_year", "venue",
            "volume", "issue", "pages", "abstract", "source_url", "patent_number", "application_number",
            "applicants", "inventors", "application_date", "ipc", "cpc");
}
