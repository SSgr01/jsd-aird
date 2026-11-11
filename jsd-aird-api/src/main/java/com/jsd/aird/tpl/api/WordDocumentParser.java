package com.jsd.aird.tpl.api;

import java.io.InputStream;

public interface WordDocumentParser {

    OfficeStructureParser.ParseResult parse(InputStream input);
}
