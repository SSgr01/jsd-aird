package com.jsd.aird.tpl.application.port;

import java.io.InputStream;

public interface WordDocumentParser {

    OfficeStructureParser.ParseResult parse(InputStream input);
}
