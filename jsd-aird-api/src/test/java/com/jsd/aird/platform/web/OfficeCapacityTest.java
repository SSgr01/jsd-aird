package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.resource.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.tpl.infrastructure.IsolatedOfficeProcess;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OfficeCapacityTest {
    @Test void changedSourceIsRejectedBeforeStartingAChild()throws Exception{
        var space=new TemporarySpace(8L*1024*1024*1024,directory);var runner=new IsolatedProcessRunner();
        var tasks=new IsolatedDocumentTasks(new ObjectMapper().findAndRegisterModules(),space,runner);
        assertThatThrownBy(()->tasks.executeVerified("patch",new ByteArrayInputStream(new byte[]{1}),null,byte[].class,"a".repeat(64))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));
        assertThat(runner.active()).isZero();assertThat(space.reserved()).isZero();
    }
    @Test void workbookAndKnowledgeParsingRetainTheirContentAcrossTheFileProtocol()throws Exception{
        var space=new TemporarySpace(8L*1024*1024*1024,directory);var runner=new IsolatedProcessRunner();var json=new ObjectMapper().findAndRegisterModules();var tasks=new IsolatedDocumentTasks(json,space,runner);
        byte[] bytes;try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("验证");sheet.createRow(0).createCell(0).setCellValue("编号");sheet.createRow(1).createCell(0).setCellValue("隔离样本");workbook.write(output);bytes=output.toByteArray();
        }
        var parsed=tasks.execute("xlsx",new ByteArrayInputStream(bytes),null,com.jsd.aird.tpl.application.port.OfficeStructureParser.ParseResult.class);
        assertThat(parsed.initialEditorSnapshot().toString()).contains("隔离样本");
        var knowledge=tasks.execute("knowledge-office",new ByteArrayInputStream(bytes),json.getNodeFactory().textNode("fixture.xlsx"),com.jsd.aird.kb.domain.DocumentParser.ParsedDocument.class);
        assertThat(knowledge.toString()).contains("隔离样本");assertThat(space.reserved()).isZero();
    }
    @TempDir Path directory;
    @Test void timeoutTerminatesAnUncooperativeChildBeforeReleasingItsSlot()throws Exception{
        var runner=new IsolatedProcessRunner();
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var command=List.of(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),StuckChild.class.getName(),directory.toString());
        assertThatThrownBy(()->runner.run(command,directory,1500)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPERATION_TIMEOUT));
        long pid=Long.parseLong(Files.readString(directory.resolve("pid")));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();assertThat(runner.active()).isZero();assertThat(runner.owns(directory)).isFalse();
        runner.run(List.of(java,"-version"),directory,3000);assertThat(runner.active()).isZero();
    }
    @Test void realDocxParsingUsesAChildAndCleansOnlyItsOwnedFiles()throws Exception{
        var space=new TemporarySpace(8L*1024*1024*1024,directory);var runner=new IsolatedProcessRunner();
        var service=new IsolatedOfficeProcess(new ObjectMapper().findAndRegisterModules(),space,runner);
        byte[] input;try(var doc=new XWPFDocument();var output=new ByteArrayOutputStream()){doc.createParagraph().createRun().setText("容量隔离验证");doc.write(output);input=output.toByteArray();}
        var sentinel=directory.resolve("unrelated.txt");Files.writeString(sentinel,"keep");
        var result=service.parse(new ByteArrayInputStream(input));
        assertThat(result.structureSummary().toString()).contains("容量隔离验证");assertThat(space.reserved()).isZero();assertThat(runner.active()).isZero();
        try(var paths=Files.list(directory)){assertThat(paths.toList()).containsExactly(sentinel);}
    }
    public static class StuckChild {
        public static void main(String[] args)throws Exception{
            Files.writeString(Path.of(args[0],"pid"),Long.toString(ProcessHandle.current().pid()));
            while(true){try{Thread.sleep(100);}catch(InterruptedException ignored){ }}
        }
    }
}
