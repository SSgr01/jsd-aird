package com.jsd.aird.data.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcDataParsedBatchTest {
    final ObjectMapper json=new ObjectMapper();
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final JdbcDataRepository repository=new JdbcDataRepository(jdbc,json);
    final UUID job=UUID.randomUUID(),sheetId=UUID.randomUUID();
    @Test void parsedAndValidatedBatchesKeepEverySourceValueCorrectionAndExclusion() {
        var sheets=List.of(new DataRepository.Sheet(sheetId,"sheet-2","实验第二页",1,true,List.of(),1,600,json.createObjectNode(),"PENDING"));
        var mappings=new ArrayList<DataRepository.Mapping>();var rows=new ArrayList<DataRepository.Row>();var issues=new ArrayList<DataRepository.Issue>();
        for(int i=0;i<600;i++) {
            mappings.add(new DataRepository.Mapping(UUID.randomUUID(),"sheet-2","C","原值","test"+i,"test","MAP","TEXT","h","h",json.createObjectNode().put("bindingId","binding"+i),"MATCHED"));
            rows.add(new DataRepository.Row(UUID.randomUUID(),"sheet-2",i+1,json.createObjectNode().put("C","5h"),json.createObjectNode().put("test",i),json.createObjectNode().put("test","修正"+i),"VALID",json.createObjectNode().put("address","C"+(i+1)),i%2==0,"原排除理由"));
            issues.add(new DataRepository.Issue(UUID.randomUUID(),"sheet-2","test"+i,"WARNING","TEST",i+1,"C","C"+(i+1),"原提示",json.createObjectNode(),"OPEN"));
        }
        var parsedRows=new ArrayList<Object[]>();var parsedMappings=new ArrayList<Object[]>();var validatedRows=new ArrayList<Object[]>();var writtenIssues=new ArrayList<Object[]>();
        var batchSizes=new ArrayList<Integer>();
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0);var args=(Object[])call.getRawArguments()[1];
            List<Object[]> target=null;int columns=0;
            if(sql.contains("INSERT INTO data.import_mapping")){target=parsedMappings;columns=13;}
            else if(sql.contains("INSERT INTO data.staging_row")){target=parsedRows;columns=12;}
            else if(sql.contains("UPDATE data.staging_row AS target")){target=validatedRows;columns=8;assertThat(sql).contains("target.import_job_id=source.import_job_id");}
            else if(sql.contains("INSERT INTO data.import_issue")){target=writtenIssues;columns=12;}
            if(target!=null){batchSizes.add(args.length/columns);for(int at=0;at<args.length;at+=columns)target.add(Arrays.copyOfRange(args,at,at+columns));}
            return 1;
        });
        repository.saveParsed(job,"parser",sheets,mappings,rows,null);
        repository.replaceValidation(UUID.randomUUID(),job,rows,issues,"WAITING_CONFIRM");
        assertThat(batchSizes).containsExactly(256,256,88,256,256,88,256,256,88,256,256,88);
        for(int i=0;i<600;i++) {
            assertThat(parsedMappings.get(i)[0]).isEqualTo(mappings.get(i).id());
            assertThat(parsedMappings.get(i)[2]).isEqualTo(sheetId);
            var row=parsedRows.get(i);
            assertThat(row[0]).isEqualTo(rows.get(i).id());assertThat(row[2]).isEqualTo(sheetId);
            assertThat(((PGobject)row[4]).getValue()).isEqualTo("{\"C\":\"5h\"}");
            assertThat(((PGobject)row[6]).getValue()).contains("修正"+i);
            assertThat(((PGobject)row[7]).getValue()).contains("C"+(i+1));
            assertThat(row[10]).isEqualTo(i%2==0);assertThat(row[11]).isEqualTo("原排除理由");
            var validated=validatedRows.get(i);assertThat(validated[0]).isEqualTo(rows.get(i).id());assertThat(validated[1]).isEqualTo(job);
            assertThat(((PGobject)validated[3]).getValue()).contains("修正"+i);assertThat(validated[6]).isEqualTo(i%2==0);
            assertThat(writtenIssues.get(i)[2]).isEqualTo("sheet-2");assertThat(writtenIssues.get(i)[8]).isEqualTo("C"+(i+1));
        }
    }
}
