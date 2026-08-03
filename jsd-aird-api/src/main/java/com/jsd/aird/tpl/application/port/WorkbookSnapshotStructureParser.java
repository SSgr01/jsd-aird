package com.jsd.aird.tpl.application.port;

import java.io.InputStream;

public interface WorkbookSnapshotStructureParser {
    OfficeStructureParser.ParseResult parse(InputStream input);
}
