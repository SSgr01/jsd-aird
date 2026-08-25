package com.jsd.aird.tpl.domain;

public enum TemplateFormat {
    XLSX("UNIVER_WORKBOOK"),
    XLS("UNIVER_WORKBOOK"),
    CSV("UNIVER_WORKBOOK"),
    DOCX("UNIVER_DOCUMENT"),
    PDF("UNIVER_DOCUMENT"),
    IMAGE("UNIVER_DOCUMENT");

    private final String snapshotKind;

    TemplateFormat(String snapshotKind) {
        this.snapshotKind = snapshotKind;
    }

    public String snapshotKind() {
        return snapshotKind;
    }
}
