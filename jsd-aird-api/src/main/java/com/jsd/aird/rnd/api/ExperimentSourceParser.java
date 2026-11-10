package com.jsd.aird.rnd.api;
import java.util.UUID;
import com.jsd.aird.tpl.api.OfficeStructureParser;
import com.jsd.aird.tpl.api.TemplateFormat;
public interface ExperimentSourceParser {
 OfficeStructureParser.ParseResult parseSourceFile(UUID organizationId, UUID fileId, String fileName, String sourceHash, TemplateFormat format);
}
