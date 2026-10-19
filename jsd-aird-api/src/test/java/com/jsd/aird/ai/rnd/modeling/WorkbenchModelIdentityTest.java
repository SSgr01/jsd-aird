package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchModelIdentityTest {
    @Test
    void versionsDependOnFieldIdentityUnitsAndMethodsRatherThanDisplayNameOrXOrder() throws Exception {
        var json=new ObjectMapper();
        var service=new WorkbenchModelRegistrationService(null,json,new ConfigurationHashing(json),null,null);
        var request=json.readTree("""
                {"yColumn":{"code":"gloss","name":"光泽","kind":"NUMERIC","unit":"GU","testMethods":["60°"]},
                 "xColumns":[{"code":"solids","kind":"NUMERIC","unit":"%"},{"code":"substrate","kind":"CATEGORICAL"}]}
                """);
        var reordered=request.deepCopy();
        var xs=(com.fasterxml.jackson.databind.node.ArrayNode)reordered.path("xColumns");
        var first=xs.remove(0);xs.add(first);
        assertThat(service.familyIdentity(reordered)).isEqualTo(service.familyIdentity(request));
        for(var key:java.util.List.of("code","kind","unit","testMethods","semanticType","mappingStatus","compositionBasis")){
            var changed=request.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("yColumn")).put(key,"different");
            assertThat(service.familyIdentity(changed)).isNotEqualTo(service.familyIdentity(request));
        }
        var anotherX=request.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)anotherX.path("xColumns").get(0)).put("code","viscosity");
        assertThat(service.familyIdentity(anotherX)).isNotEqualTo(service.familyIdentity(request));
    }

    @Test
    void fakeOrRetiredMaterialMappingsAreDroppedBeforeRegistration() throws Exception {
        var json=new ObjectMapper();var id="f44a3459-05c1-4958-a246-b5736c0aa682";
        var field=json.createObjectNode().put("code","formula:"+id).put("semanticType","MATERIAL_RATIO")
                .put("mappingStatus","MAPPED").put("materialId",id).put("compositionBasis","MASS_PERCENT").put("unit","%");
        WorkbenchModelRegistrationService.sanitizeMaterialColumn(field,java.util.Set.of());
        assertThat(field.path("mappingStatus").asText()).isEqualTo("UNMAPPED");
        assertThat(field.has("materialId")).isFalse();
        var known=json.createObjectNode().put("code","formula:"+id).put("semanticType","MATERIAL_RATIO")
                .put("mappingStatus","MAPPED").put("materialId",id).put("compositionBasis","MASS_PERCENT").put("unit","%");
        WorkbenchModelRegistrationService.sanitizeMaterialColumn(known,java.util.Set.of(id));
        assertThat(known.path("mappingStatus").asText()).isEqualTo("MAPPED");
    }
}
