package com.jsd.aird.tpl.domain;

public enum TemplateFormat {
    XLSX("UNIVER_WORKBOOK"),
    DOCX("UNIVER_DOCUMENT");

    private final String snapshotKind;

    TemplateFormat(String snapshotKind) {
        this.snapshotKind = snapshotKind;
    }

    public String snapshotKind() {
        return snapshotKind;
    }
}
