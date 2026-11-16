package com.jsd.aird.iam.infrastructure;

import com.jsd.aird.iam.api.*;
import com.jsd.aird.shared.business.BusinessDataAccess;
import com.jsd.aird.shared.security.Actor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileBusinessPermissionResolverTest {
    @Test void originalFileRequiresBothRecordReadAndDownloadWithoutLegacyFallback() {
        var jdbc=mock(JdbcTemplate.class);var auth=mock(AuthorizationService.class);var policy=mock(BusinessDataAccess.class);
        UUID org=UUID.randomUUID(),id=UUID.randomUUID(),file=UUID.randomUUID(),user=UUID.randomUUID();
        var actor=new Actor(org,user,"xumeng");
        doReturn(List.of(user)).when(jdbc).query(contains("ops.file_object"),any(RowMapper.class),eq(org),eq(file));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("type","QUALITY_RECORD","id",id)));
        doReturn(List.of()).when(jdbc).query(contains("source_owner"),any(RowMapper.class),any(Object[].class));
        when(policy.resourceTypes()).thenReturn(Set.of("QUALITY_RECORD"));
        when(policy.allowed(eq(actor),eq("QUALITY_RECORD"),eq(id),eq("READ"),any())).thenReturn(true);
        when(policy.allowed(eq(actor),eq("QUALITY_RECORD"),eq(id),eq("DOWNLOAD"),any())).thenReturn(false);
        var resolver=new FileBusinessPermissionResolver(jdbc,auth,List.of(policy));
        assertThat(resolver.mayRead(actor,file)).isFalse();
        verifyNoInteractions(auth);
        when(policy.allowed(eq(actor),eq("QUALITY_RECORD"),eq(id),eq("DOWNLOAD"),any())).thenReturn(true);
        assertThat(resolver.mayRead(actor,file)).isTrue();
    }
}
