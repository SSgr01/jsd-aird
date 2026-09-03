package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.rnd.application.port.ResearchTestRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.BlankWordDocumentFactory;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.application.port.WordOoxmlPatcher;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class ResearchTestExportService {
    private final ResearchTestRepository repository;private final FileStorageFacade files;private final TemplateRepository templates;private final ObjectMapper json;private final SnapshotWorkbookExporter sheets;private final WordOoxmlPatcher word;private final BlankWordDocumentFactory blankWord;
    public ResearchTestExportService(ResearchTestRepository repository,FileStorageFacade files,TemplateRepository templates,ObjectMapper json,SnapshotWorkbookExporter sheets,WordOoxmlPatcher word,BlankWordDocumentFactory blankWord){this.repository=repository;this.files=files;this.templates=templates;this.json=json;this.sheets=sheets;this.word=word;this.blankWord=blankWord;}
    public Download export(UUID id){var a=ActorContext.required();var d=repository.detail(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));var snapshot=current(d);if("EXCEL".equals(d.summary().documentFormat())){if(snapshot==null||!snapshot.isObject()||snapshot.isEmpty())throw new ApiException(ApiErrorCode.FILE_NOT_READY,"工作簿快照不存在，请先保存");var result=sheets.export(snapshot,json.createArrayNode(),json.createObjectNode(),new SnapshotWorkbookExporter.Manifest(id.toString(),null,null,d.summary().status().name(),null));return new Download(name(d.summary().name(),d.summary().versionNo(),"xlsx"),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",result.content());}
        var sourceName=d.editModel()==null?"":d.editModel().path("sourceFileName").asText("");UUID source=sourceName.toLowerCase().endsWith(".docx")?d.summary().sourceFileId():null;if(source==null&&d.templateVersionId()!=null)source=templates.findWorkspace(a.organizationId(),d.templateVersionId()).map(TemplateRepository.TemplateWorkspace::wordDocument).map(this::wordFile).orElse(null);byte[] bytes=source==null?blankWord.create(d.summary().name()):read(a.organizationId(),source);return new Download(name(d.summary().name(),d.summary().versionNo(),"docx"),"application/vnd.openxmlformats-officedocument.wordprocessingml.document",word.applySnapshot(bytes,snapshot==null?json.createObjectNode():snapshot));}
    private JsonNode current(com.jsd.aird.rnd.domain.ResearchTestModels.Detail d){var n=d.editModel()==null?null:d.editModel().path("documentSnapshot");return n!=null&&n.isObject()&&!n.isEmpty()?n:d.templateSnapshot();}
    private UUID wordFile(JsonNode n){if(n==null)return null;for(var f:new String[]{"publishedDocxFileId","workingDocxFileId","sourceDocxFileId"})try{var s=n.path(f).asText();if(!s.isBlank())return UUID.fromString(s);}catch(Exception ignored){}return null;}
    private byte[] read(UUID org,UUID id){try(var f=files.open(org,id)){return f.stream().readAllBytes();}catch(Exception e){throw new ApiException(ApiErrorCode.FILE_NOT_READY,"Word 原始文件不可读取");}}
    private String name(String n,int v,String ext){return n.replaceAll("[\\\\/:*?\"<>|\\r\\n]+","_")+"-V"+v+"."+ext;}public record Download(String fileName,String contentType,byte[] content){}
}
