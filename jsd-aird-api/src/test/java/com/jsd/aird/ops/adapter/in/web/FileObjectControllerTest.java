package com.jsd.aird.ops.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.NoSuchFileException;

import com.jsd.aird.ops.application.FileObjectService;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class FileObjectControllerTest {

    @Test
    void reportsAnUploadPartRemovedByEndpointSecurityAsAReadableClientError() throws Exception {
        var service = mock(FileObjectService.class);
        var file = mock(MultipartFile.class);
        when(file.getInputStream()).thenThrow(new NoSuchFileException("temporary upload part"));

        var controller = new FileObjectController(service);

        assertThatThrownBy(() -> controller.stage(file, "KNOWLEDGE"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("可能已被安全软件隔离")
                .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
                        ((ApiException) exception).errorCode()).isEqualTo(ApiErrorCode.BAD_REQUEST));
        verify(service, never()).stage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
