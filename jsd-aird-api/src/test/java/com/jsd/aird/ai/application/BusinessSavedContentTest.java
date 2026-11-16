package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.quality.application.BusinessQualityReader;
import com.jsd.aird.quality.application.port.QualityDataStore;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.shared.error.ApiException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessSavedContentTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final UUID record=UUID.randomUUID(),org=UUID.randomUUID();
    @BeforeEach void setup(){ActorContext.set(new Actor(org,UUID.randomUUID(),"reader"));}
    @AfterEach void clear(){ActorContext.clear();}
    @Test void qualityReadsSavedCellsAndCanReadAndValidateEveryPage() throws Exception {
        var workbook=(ObjectNode)json.readTree("""
            {"sheetOrder":["b","a"],"sheets":{
              "a":{"name":"原始记录","cellData":{"10":{"1":{"v":"000123"}},"2":{"0":{"v":0},"1":{"v":null},"2":{"f":"=A3+1","v":1}},"3":{"0":{"v":46339,"s":{"n":{"pattern":"yyyy-mm-dd"}}}}}},
              "b":{"name":"补充说明","cellData":{"0":{"0":{"v":"保存后增加的检测条件"}}}}
            }}
            """);
        var repository=mock(QualityDataStore.class);
        var row=new QualityDataStore.RecordView(record,"record",UUID.randomUUID(),"树脂检测","JC-862","UV-862检测记录",json.createObjectNode(),null,null,
            null,null,null,null,null,null,"ALL",workbook,3,Instant.EPOCH,Instant.EPOCH,"NOT_SUBMITTED");
        when(repository.record(org,"ADMIN",record)).thenReturn(row);
        var reader=new BusinessQualityReader(repository,json);
        var access=new BusinessDataAccess(){public Set<String> resourceTypes(){return reader.resourceTypes();}public boolean allowed(Actor a,String t,UUID id,String operation,JsonNode context){return true;}};
        var service=new BusinessDataService(List.of(reader),List.of(access),mock(BusinessExistingData.class),json);
        var originals=mock(BusinessOriginalFiles.class);UUID file=UUID.randomUUID();
        when(originals.list(any())).thenReturn(List.of(new BusinessOriginalFiles.Entry(file,"UV-862检测记录.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",1024,"a".repeat(64))));
        service.setOriginalFiles(originals);
        var ref=BusinessViews.ref("QUALITY_RECORD",record);
        var whole=service.invoke("business_read",json.createObjectNode().set("ref",ref));
        assertThat(whole.path("candidates").get(0).path("text").asText()).contains("保存后增加的检测条件","000123","=A3+1");
        assertThat(whole.path("candidates").get(0).path("files").path(0).path("file_id").asText()).isEqualTo(file.toString());
        var values=new ArrayList<String>();
        for(int offset=0;offset<6;offset+=2){
            var args=json.createObjectNode().put("section","content").put("offset",offset).put("limit",2);args.set("ref",ref);
            var page=service.invoke("business_read",args);var source=page.path("candidates").get(0).path("source");
            service.validateSource(source,null);
            var options=((ObjectNode)source).deepCopy();((ObjectNode)options.path("anchor").path("read_options")).put("offset",99);
            assertThatThrownBy(()->service.validateSource(options,null)).isInstanceOf(ApiException.class);
            page.path("candidates").get(0).path("text").asText().lines()
                .filter(line->line.matches("\\|[A-Z]+[0-9]+\\|.*"))
                .forEach(values::add);
            assertThat(page.path("reading").path("returned").asInt()).isEqualTo(2);
            assertThat(source.path("anchor").path("read_path").asText()).contains("&section=content&offset="+offset+"&limit=2");
            assertThat(page.path("reading").path("total").asInt()).isEqualTo(6);
            if(offset<4)assertThat(page.path("next_offset").asInt()).isEqualTo(offset+2);
            else assertThat(page.has("next_offset")).isFalse();
        }
        assertThat(values).hasSize(6).doesNotHaveDuplicates();
        assertThat(values.stream().map(value->value.split("\\|",-1)[1]).toList())
            .containsExactly("A1","A3","B3","C3","A4","B11");
        assertThat(values.get(0)).contains("补充说明","保存后增加的检测条件");
        assertThat(values.get(1)).contains("|0|");
        assertThat(values.get(2)).contains("|缺失|");
        assertThat(values.get(3)).contains("=A3+1");
        assertThat(values.get(4)).contains("46339","yyyy-mm-dd");
        assertThat(values.get(5)).contains("|000123|");
        when(repository.record(org,"ADMIN",record)).thenReturn(new QualityDataStore.RecordView(record,"record",row.categoryId(),row.categoryName(),row.businessNo(),row.displayName(),row.data(),null,null,
            null,null,null,null,null,null,"ALL",null,4,Instant.EPOCH,Instant.EPOCH,"NOT_SUBMITTED"));
        var missing=service.invoke("business_read",json.createObjectNode().set("ref",ref));
        assertThat(missing.path("reading").path("complete").asBoolean()).isFalse();
        assertThat(missing.path("reading").path("reason").asText()).contains("没有已保存的正文");
        var noContent=json.createObjectNode().put("section","content");noContent.set("ref",ref);
        assertThat(service.invoke("business_read",noContent).path("reading").path("complete").asBoolean()).isFalse();
    }
}
