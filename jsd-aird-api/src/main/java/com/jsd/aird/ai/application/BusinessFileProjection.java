package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.kb.api.DocumentParser;
import com.jsd.aird.shared.business.BusinessReadPort.FileContent;
import com.jsd.aird.shared.resource.IsolatedDocumentTasks;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.hash;

/** A bounded read-only view of the exact downloaded bytes, never the saved editor body. */
@Service
public class BusinessFileProjection {
    private final List<DocumentParser> parsers;
    private final IsolatedDocumentTasks isolated;
    private final ObjectMapper json;
    public BusinessFileProjection(List<DocumentParser> parsers,IsolatedDocumentTasks isolated,ObjectMapper json){
        this.parsers=parsers;this.isolated=isolated;this.json=json;
    }
    public void attach(ObjectNode transfer,FileContent file){
        if(file.contentType().startsWith("image/"))return;
        try {
            DocumentParser.ParsedDocument document;
            if(file.contentType().equals("application/pdf"))
                document=isolated.executeVerified("business-pdf",new ByteArrayInputStream(file.bytes()),json.nullNode(),DocumentParser.ParsedDocument.class,file.sha256());
            else {
                var parser=parsers.stream().filter(p->p.supports(file.name(),file.contentType())&&p.isConfigured()).findFirst();
                if(parser.isEmpty()){transfer.put("reading_notice","此格式没有可用的文本投影；原件已保留，可在业务页面阅读");return;}
                document=parser.get().parse(new ByteArrayInputStream(file.bytes()),file.name());
            }
            var text=new StringBuilder("原文件：").append(file.name()).append("\n原文件SHA-256：").append(file.sha256())
                .append("\n以下是原上传文件的文本投影，不代表页面保存后的正文；内嵌图片和版式须查看原件。\n");
            for(var block:document.blocks()){
                if(block.pageNo()!=null)text.append("\n页 ").append(block.pageNo()).append('\n');
                if(block.sheetName()!=null)text.append("\n工作表 ").append(block.sheetName()).append(' ').append(Objects.toString(block.cellRange(),"")).append('\n');
                text.append(Objects.toString(block.content(),"")).append('\n');
                check(text);
            }
            for(var table:document.sourceTables())for(var cell:table.cells()){
                text.append(table.sheetName()).append('!').append(cell.cellRange()).append('\t').append(cell.displayValue()).append('\n');check(text);
            }
            if(document.blocks().isEmpty()&&document.sourceTables().isEmpty()){
                transfer.put("reading_notice","原文件没有可提取的文本，可能需要查看图片或扫描页");return;
            }
            byte[] bytes=text.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>8*1024*1024)throw new IllegalStateException("projection too large");
            transfer.putObject("text_projection").put("source_sha256",file.sha256()).put("sha256",hash(bytes)).put("bytes",bytes.length)
                .put("content_base64",Base64.getEncoder().encodeToString(bytes)).put("parser",document.parserVersion());
        } catch(Exception error){transfer.put("reading_notice","原文件已保留，文本投影暂不可用；可读取保存正文，或通过业务页面阅读原件");}
    }
    private void check(StringBuilder value){if(value.length()>8*1024*1024)throw new IllegalStateException("projection too large");}
}
