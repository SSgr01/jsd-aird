package com.jsd.aird.data.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcDataImportCommitTest {
    @Test void batchesAcrossGroupsWhileKeepingEveryValueLinkedToItsOwnRecordAndSource() {
        var json=new ObjectMapper();var jdbc=mock(JdbcTemplate.class);
        var repository=spy(new JdbcDataRepository(jdbc,json));
        var org=UUID.randomUUID();var jobId=UUID.randomUUID();var fileId=UUID.randomUUID();
        doReturn(Optional.of(new DataRepository.Job(jobId,fileId,"hash","source.xlsx","XLSX",UUID.randomUUID(),null,
                "WAITING_CONFIRM",100,"WAITING_CONFIRM","test",null,Instant.now(),Instant.now())))
                .when(repository).findJob(org,jobId);
        var rows=new ArrayList<DataRepository.CommittedRow>();
        for(int i=0;i<46;i++) {
            var value=json.createObjectNode();
            value.putObject("amount").put("fieldCode","amount").put("bindingId","charge")
                    .put("valuePath","/amount").put("normalizedValue",i).put("normalizedUnit","g");
            var a=new DataRepository.Anchor("amount","charge","/amount","投料","INPUT","VALID",
                    "sheet","实验",i+1,3,"C","C"+(i+1),json.getNodeFactory().numberNode(i));
            rows.add(new DataRepository.CommittedRow("sheet","实验",i+1,json.createObjectNode(),value,
                    json.createObjectNode(),"IMPORT:group"+i,List.of(a)));
        }
        var sqls=new ArrayList<String>();var args=new ArrayList<Object[]>();
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call->{
            var sql=(String)call.getArgument(0);
            if(sql.contains("INSERT INTO data.")){sqls.add(sql);args.add((Object[])call.getRawArguments()[1]);}
            return 1;
        });
        var result=repository.commit(org,jobId,UUID.randomUUID(),rows);
        assertThat(result.rowCount()).isEqualTo(46);
        assertThat(sqls).hasSize(3);
        assertThat(sqls.get(0)).contains("data.data_record");
        assertThat(sqls.get(1)).contains("data.source_anchor");
        assertThat(sqls.get(2)).contains("data.data_value");
        for(int i=0;i<46;i++) {
            var id=result.records().get(i).recordId();
            assertThat(args.get(0)[i*13]).isEqualTo(id);
            assertThat(args.get(1)[i*17+1]).isEqualTo(id);
            assertThat(args.get(1)[i*17+15]).isEqualTo("C"+(i+1));
            assertThat(args.get(2)[i*12+2]).isEqualTo(id);
            assertThat(args.get(2)[i*12+10]).isEqualTo(args.get(1)[i*17]);
            assertThat(args.get(2)[i*12+8]).isEqualTo(Integer.toString(i));
        }
    }
    @Test void batchesAllValuesAndProvenanceWithoutChangingRecordIdentity() throws Exception {
        var json = new ObjectMapper();
        var jdbc = mock(JdbcTemplate.class);
        var repository = spy(new JdbcDataRepository(jdbc, json));
        var org = UUID.randomUUID(); var jobId = UUID.randomUUID(); var fileId = UUID.randomUUID();
        var job = new DataRepository.Job(jobId,fileId,"hash","source.xlsx","XLSX",UUID.randomUUID(),null,
                "WAITING_CONFIRM",100,"WAITING_CONFIRM","test",null,Instant.now(),Instant.now());
        doReturn(Optional.of(job)).when(repository).findJob(org,jobId);
        var normalized = json.createObjectNode();
        var anchors = new ArrayList<DataRepository.Anchor>();
        for (int i = 0; i < 600; i++) {
            var code = "field" + i;
            normalized.putObject(code).put("fieldCode",code).put("bindingId","binding" + i)
                    .put("valuePath","/tests/" + i).put("normalizedValue",i)
                    .put("rawValue", "原值 " + i).put("normalizedUnit","g").put("trainingEligible",false);
            anchors.add(new DataRepository.Anchor(code,"binding" + i,"/tests/" + i,"温度 / 24h",
                    "INPUT","VALID","sheet","实验",i + 1,3,"C","C" + (i + 1),json.getNodeFactory().textNode("原值 " + i)));
        }
        normalized.putObject("blank").put("normalizedValue", "");
        var row = new DataRepository.CommittedRow("sheet","实验",1,json.createObjectNode(),normalized,
                json.createObjectNode(),"IMPORT:group1",anchors);
        var sqls = new ArrayList<String>(); var batches = new ArrayList<List<Object[]>>();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("INSERT INTO data.source_anchor") || sql.contains("INSERT INTO data.data_value")) {
                sqls.add(sql);
                var arguments = (Object[])call.getRawArguments()[1];
                int columns = sql.contains("data.source_anchor") ? 17 : 12;
                var batch = new ArrayList<Object[]>();
                for (int index = 0; index < arguments.length; index += columns) {
                    batch.add(java.util.Arrays.copyOfRange(arguments,index,index + columns));
                }
                batches.add(batch);
            }
            return 1;
        });
        var first = repository.commit(org,jobId,UUID.randomUUID(),List.of(row));
        assertThat(first.rowCount()).isEqualTo(1);
        assertThat(batches).extracting(List::size).containsExactly(256,256,88,256,256,88);
        assertThat(sqls.subList(0,3)).allMatch(sql -> sql.contains("data.source_anchor"));
        assertThat(sqls.subList(3,6)).allMatch(sql -> sql.contains("data.data_value"));
        var recordId = first.records().getFirst().recordId();
        var allAnchors = batches.subList(0,3).stream().flatMap(List::stream).toList();
        var allValues = batches.subList(3,6).stream().flatMap(List::stream).toList();
        for (int i = 0; i < 600; i++) {
            var anchor = allAnchors.get(i); var value = allValues.get(i);
            assertThat(anchor[1]).isEqualTo(recordId);
            assertThat(anchor[9]).isEqualTo(fileId);
            assertThat(anchor[6]).isEqualTo("温度 / 24h");
            assertThat(((PGobject)anchor[16]).getValue()).isEqualTo(json.writeValueAsString("原值 " + i));
            assertThat(value[2]).isEqualTo(recordId);
            assertThat(value[5]).isEqualTo("/tests/" + i);
            assertThat(((PGobject)value[7]).getValue()).isEqualTo(Integer.toString(i));
            assertThat(value[8]).isEqualTo(Integer.toString(i));
            assertThat(value[9]).isEqualTo("g");
            assertThat(value[10]).isEqualTo(anchor[0]);
            assertThat(value[11]).isEqualTo(false);
        }
        var second = repository.commit(org,jobId,UUID.randomUUID(),List.of(row));
        assertThat(second.records()).isEqualTo(first.records());
        assertThat(batches).hasSize(12);
        assertThat(sqls.getFirst()).contains("?),(?");
        // A repeated binding/path must remain an ordered upsert, rather than
        // causing PostgreSQL's "cannot affect row a second time" error.
        ((com.fasterxml.jackson.databind.node.ObjectNode)normalized.get("field1"))
                .put("bindingId","binding0").put("valuePath","/tests/0");
        repository.commit(org,jobId,UUID.randomUUID(),List.of(row));
        var valueBatches = batches.subList(15,batches.size());
        assertThat(valueBatches.getFirst()).hasSize(1);
        assertThat(valueBatches.stream().flatMap(List::stream).map(value -> value[8]).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0,600).mapToObj(Integer::toString).toList());
        for (var batch : valueBatches) {
            assertThat(batch.stream().map(value -> List.of(value[2],value[4],value[5])).toList()).doesNotHaveDuplicates();
        }
    }
}
