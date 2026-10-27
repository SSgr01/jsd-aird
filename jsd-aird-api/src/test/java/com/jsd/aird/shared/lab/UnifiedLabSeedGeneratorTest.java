package com.jsd.aird.shared.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in reproducible SQL generation, also validates the packages during every test run. */
class UnifiedLabSeedGeneratorTest {
    @Test void compilePortableSql()throws Exception {
        var json=new ObjectMapper();var canon=new JsonCanonicalizer(json);var sql=new StringBuilder("-- Generated from the reviewed blank XLSX assets. No experiment records are seeded.\nBEGIN;\n");
        sql.append(Files.readString(Path.of("src/main/resources/db/migration/V82__unified_lab_template_packages.sql")));
        for(var kind:new String[]{"test","synthesis"})try(var source=getClass().getResourceAsStream("/templates/unified/"+kind+".xlsx")) {
            var pack=new UnifiedLabTemplateCatalog(json).compile(kind.equals("test")?UnifiedLabTemplateCatalog.TEST:UnifiedLabTemplateCatalog.SYNTHESIS,new com.jsd.aird.tpl.infrastructure.XlsxStructureParser(json).parseInstance(source));
            var payload=pack.payload();payload.put("snapshotHash",pack.snapshotHash()).put("schemaHash",canon.hash(payload.path("schema"))).put("mappingHash",canon.hash(payload.path("mapping"))).put("dataHash",canon.hash(json.createObjectNode())).put("pluginHash",canon.hash(json.createObjectNode())).put("workspaceHash",pack.packageHash());
            Files.createDirectories(Path.of("target/unified-lab"));
            Files.writeString(Path.of("target/unified-lab/"+kind+".snapshot.json"),json.writeValueAsString(payload.path("snapshot")));
            try(var example=getClass().getResourceAsStream("/unified-lab/"+kind+"-example.xlsx")) {
                var parsed=new com.jsd.aird.tpl.infrastructure.XlsxStructureParser(json).parse(example).initialEditorSnapshot();
                var filled=LabWorkbook.prepare(json,parsed,payload.path("snapshot"),"browser-"+kind);
                Files.writeString(Path.of("target/unified-lab/"+kind+"-example.snapshot.json"),json.writeValueAsString(filled));
            }
            var encoded=json.writeValueAsString(payload);
            sql.append("\nDO $package$ BEGIN\nIF EXISTS(SELECT 1 FROM tpl.unified_lab_template_package WHERE template_code='").append(pack.code()).append("' AND package_hash<>'").append(pack.packageHash()).append("') THEN RAISE EXCEPTION 'Unified template package content differs; publish a new version'; END IF;\nEND $package$;\n");
            sql.append("INSERT INTO tpl.unified_lab_template_package(template_code,package_hash,payload_jsonb) VALUES('").append(pack.code()).append("','").append(pack.packageHash()).append("',$payload$").append(encoded).append("$payload$::jsonb) ON CONFLICT(template_code) DO NOTHING;\n");
        }
        sql.append("\nDO $organizations$ DECLARE o record; actor uuid; BEGIN FOR o IN SELECT id FROM iam.organization LOOP SELECT id INTO actor FROM iam.app_user WHERE organization_id=o.id ORDER BY dictionary_admin DESC,created_at,id LIMIT 1; IF actor IS NOT NULL THEN PERFORM tpl.install_unified_lab_templates(o.id,actor); END IF; END LOOP; END $organizations$;\nCOMMIT;\n");
        var output=System.getProperty("lab.seed.output");if(output!=null){var path=Path.of(output).toAbsolutePath();Files.createDirectories(path.getParent());Files.writeString(path,sql.toString());}
    }
}
