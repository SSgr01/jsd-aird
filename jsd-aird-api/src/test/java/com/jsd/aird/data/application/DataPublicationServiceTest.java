package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DataPublicationServiceTest {
    @Test
    void snapshotHashDoesNotDependOnJsonPropertyOrder() throws Exception {
        var json=new ObjectMapper();var service=new DataPublicationService(null,json,null,null,null,null);
        assertThat(service.hash(json.readTree("{\"a\":1,\"b\":{\"c\":2,\"d\":3}}")))
                .isEqualTo(service.hash(json.readTree("{\"b\":{\"d\":3,\"c\":2},\"a\":1}")));
        assertThat(service.hash(json.readTree("{\"value\":1}"))).isNotEqualTo(service.hash(json.readTree("{\"value\":2}")));
    }
    @Test
    void csvEscapesMultilineQuotesAndFormulaTextWhilePreservingNumericValues() {
        var out=new StringBuilder();DataPublicationService.appendCsv(out,"树脂,\"A\"","=1\n+2","\t@SUM(A1)",-25.5);
        assertThat(out.toString()).isEqualTo("\"树脂,\"\"A\"\"\",\"'=1\n+2\",\"'\t@SUM(A1)\",\"-25.5\"\r\n");
    }
}
