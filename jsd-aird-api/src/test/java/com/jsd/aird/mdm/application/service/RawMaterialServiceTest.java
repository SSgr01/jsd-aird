package com.jsd.aird.mdm.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RawMaterialServiceTest {
    @Test void everyChemicalFactIsBoundToAnExactDocumentVersionAndExcerpt() {
        var json=new ObjectMapper();var jdbc=mock(JdbcTemplate.class);var sourceId=UUID.randomUUID();
        var actor=ActorContext.developmentDefault();ActorContext.set(actor);
        try {
            var source=json.createObjectNode().put("id",sourceId.toString()).put("versionNo",2);
            source.putArray("lines").addObject().put("text","density 1.02 g/mL at 25 C");
            doReturn(List.of(source)).when(jdbc).query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<ObjectNode>>any(),eq(actor.organizationId()),eq(sourceId));
            var service=new RawMaterialService(jdbc,json,new JsonCanonicalizer(json),mock(AuthorizationService.class));
            var facts=json.createObjectNode().put("grade","AC48").put("manufacturer","材料中心").put("category","树脂");
            facts.putArray("suppliers").add("材料中心");facts.putArray("roles").add("主体成膜");
            var ref=json.createObjectNode().put("documentId",sourceId.toString()).put("versionNo",2).put("lineStart",1).put("lineEnd",1).put("excerpt","density 1.02 g/mL at 25 C");
            facts.putArray("identitySources").add(ref);var p=facts.putArray("properties").addObject().put("name","密度").put("value",1.02).put("unit","g/mL");p.putObject("conditions").put("temperature","25 ℃");p.putArray("sources").add(ref.deepCopy());
            facts.putArray("knownComponents");facts.putArray("functionalGroups");facts.putArray("mechanisms");
            ReflectionTestUtils.invokeMethod(service,"validateFacts",facts);
            ref.put("versionNo",1);
            assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"validateFacts",facts)).hasMessageContaining("来源版本不一致");
            ref.put("versionNo",2).put("excerpt","density 1.20 g/mL at 25 C");
            assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"validateFacts",facts)).hasMessageContaining("原文位置不一致");
            ref.put("excerpt","density 1.02 g/mL at 25 C");
            var c=facts.withArray("knownComponents").addObject().put("name","已知共聚物").put("minimum",48).put("maximum",101).put("unit","wt%");c.putObject("conditions");c.putArray("sources").add(ref.deepCopy());
            assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"validateFacts",facts)).hasMessageContaining("质量百分数范围");
        } finally {ActorContext.clear();}
    }
}
