package com.jsd.aird.tpl.api;

import java.io.InputStream;

public interface WorkbookSnapshotStructureParser {
    OfficeStructureParser.ParseResult parse(InputStream input);
}
