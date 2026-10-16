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
        for(var key:java.util.List.of("code","kind","unit","testMethods")){
            var changed=request.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("yColumn")).put(key,"different");
            assertThat(service.familyIdentity(changed)).isNotEqualTo(service.familyIdentity(request));
        }
        var anotherX=request.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)anotherX.path("xColumns").get(0)).put("code","viscosity");
        assertThat(service.familyIdentity(anotherX)).isNotEqualTo(service.familyIdentity(request));
    }
}
