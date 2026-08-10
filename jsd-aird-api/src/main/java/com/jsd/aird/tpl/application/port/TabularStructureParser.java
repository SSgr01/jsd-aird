package com.jsd.aird.tpl.application.port;

import java.io.InputStream;

import com.jsd.aird.tpl.api.TemplateDataImportFacade;

public interface TabularStructureParser {

    TemplateDataImportFacade.ParsedTabularFile parse(InputStream input, String fileName);
}
