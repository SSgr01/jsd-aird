package com.jsd.aird.mfg.upload.infrastructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.upload.application.ProductionRecognitionScope;
import com.jsd.aird.ops.application.port.AsyncJobHandler.ExecutionContext;
import com.jsd.aird.shared.error.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductionRecognitionFenceTest {
    @Test void cancelledOrReplacedWorkerCannotWriteFieldsSnapshotsOrFailureStatus(){
        var jdbc=mock(JdbcTemplate.class);var json=new ObjectMapper();var repository=new JdbcProductionUploadRepository(jdbc,json);
        var context=new ExecutionContext(UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),1,()->true);var id=UUID.randomUUID();
        for(Runnable write:List.<Runnable>of(()->repository.replaceRecognitionFields(id,List.of()),()->repository.saveGeneratedWorkbook(id,json.createObjectNode()),()->repository.failRecognition(id,"old failure"))){
            assertThatThrownBy(()->ProductionRecognitionScope.run(context,false,()->{write.run();return null;})).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));
            assertThat(ProductionRecognitionScope.current()).isNull();
        }
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(jdbc,atLeastOnce()).query(contains("j.lease_generation=?"),any(RowMapper.class),any(Object[].class));
    }
    @Test void nestedExecutionIdentityIsRestoredAfterFailure(){
        var outer=new ExecutionContext(UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),1,()->false);
        ProductionRecognitionScope.run(outer,false,()->{
            assertThatThrownBy(()->ProductionRecognitionScope.run(outer,true,()->{throw new IllegalStateException("failed");})).isInstanceOf(IllegalStateException.class);
            assertThat(ProductionRecognitionScope.current().terminal()).isFalse();return null;
        });
        assertThat(ProductionRecognitionScope.current()).isNull();
    }
}
