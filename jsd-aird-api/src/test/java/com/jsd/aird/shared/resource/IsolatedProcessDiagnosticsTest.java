package com.jsd.aird.shared.resource;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class IsolatedProcessDiagnosticsTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void retainsOnlyTheFinalDiagnosticBytesAcrossLargeAndSmallChunks() {
        var tail=new IsolatedProcessRunner.OutputTail();
        var noisy="x".repeat(100_000).getBytes(StandardCharsets.UTF_8);
        tail.append(noisy,noisy.length);
        var error="\njava.lang.IllegalStateException: document diagnostic".getBytes(StandardCharsets.UTF_8);
        tail.append(error,error.length);
        assertThat(tail.text().getBytes(StandardCharsets.UTF_8)).hasSize(8192);
        assertThat(tail.text()).endsWith(new String(error,StandardCharsets.UTF_8));
    }
    @Test void drainsNoisyFailedChildAndReleasesItsCapacity() {
        var runner=new IsolatedProcessRunner();
        var javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        var classes=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->runner.run(java.util.List.of(javaExecutable.toString(),"-Xmx64m","-cp",classes,NoisyChild.class.getName()),directory,30_000))
                .isInstanceOf(com.jsd.aird.shared.error.ApiException.class).hasMessageContaining("文档处理失败");
        assertThat(runner.active()).isZero();
    }
    public static class NoisyChild {
        public static void main(String[] args) {
            System.err.print("x".repeat(100_000));System.err.println("\nTEST_DOCUMENT_FAILURE");System.exit(2);
        }
    }
}
