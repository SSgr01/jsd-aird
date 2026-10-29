package com.jsd.aird.shared.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.assertj.core.api.Assertions.assertThat;

class IsolatedDocumentTasksTest {
    @TempDir Path directory;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void developmentClasspathEndingInADependencyJarCanParseWord() throws Exception {
        var properties = System.getProperties();
        var testClasspath = properties.getProperty("surefire.test.class.path");
        var originalClasspath = properties.getProperty("java.class.path");
        var developmentClasspath = java.util.Arrays.stream(
                        (testClasspath == null ? originalClasspath : testClasspath)
                                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(entry -> !entry.isBlank())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        assertThat(developmentClasspath).contains(java.io.File.pathSeparator).endsWith(".jar");
        var space = new TemporarySpace(8L * 1024 * 1024 * 1024, directory);
        var runner = new IsolatedProcessRunner();
        var tasks = new IsolatedDocumentTasks(new ObjectMapper().findAndRegisterModules(), space, runner);
        byte[] bytes;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("开发服务 Word 验证");
            document.write(output);
            bytes = output.toByteArray();
        }
        try {
            properties.remove("surefire.test.class.path");
            properties.setProperty("java.class.path", developmentClasspath);
            var parsed = tasks.execute("parse", new ByteArrayInputStream(bytes), null,
                    OfficeStructureParser.ParseResult.class);
            assertThat(parsed.initialEditorSnapshot().path("body").path("dataStream").asText())
                    .contains("开发服务 Word 验证");
            assertThat(runner.active()).isZero();
            assertThat(space.reserved()).isZero();
        } finally {
            properties.setProperty("java.class.path", originalClasspath);
            if (testClasspath == null) properties.remove("surefire.test.class.path");
            else properties.setProperty("surefire.test.class.path", testClasspath);
        }
    }
}
