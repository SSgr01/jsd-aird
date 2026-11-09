package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.kb.api.ScholarlyLiteratureFacade;
import com.jsd.aird.kb.application.port.BibliographyRepository;
import com.jsd.aird.kb.application.port.BibliographyRepository.Record;
import com.jsd.aird.kb.application.port.BibliographyRepository.*;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BibliographyService {
    public static final String JOB_TYPE="KB_ENRICH_BIBLIOGRAPHY";
    private static final Set<String> FIELDS=Set.of("title","authors","doi","openalex_id","publication_date",
        "publication_year","venue","volume","issue","pages","abstract","topics","source_url",
        "patent_number","application_number","applicants","inventors","application_date","ipc","cpc");
    private final BibliographyRepository repo;
    private final KnowledgeRepository knowledge;
    private final BibliographyExtractor extractor;
    private final ScholarlyLiteratureFacade scholarly;
    private final AuthorizationService auth;
    private final OpsAsyncFacade async;
    private final AuditLogFacade audit;
    private final FileStorageFacade files;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public record Selection(List<UUID> documentIds,UUID categoryId,String requestKey) { }
    public record Confirmation(int revision,String documentType,JsonNode fields) { }
    public record Parsed(UUID organizationId,UUID actorId,UUID documentId,UUID versionId,UUID parseRunId) { }
    public BibliographyService(BibliographyRepository repo,KnowledgeRepository knowledge,BibliographyExtractor extractor,
            ScholarlyLiteratureFacade scholarly,AuthorizationService auth,OpsAsyncFacade async,AuditLogFacade audit,
            FileStorageFacade files,ObjectMapper json,PlatformTransactionManager transactions) {
        this.repo=repo;this.knowledge=knowledge;this.extractor=extractor;this.scholarly=scholarly;this.auth=auth;
        this.async=async;this.audit=audit;this.files=files;this.json=json;this.tx=new TransactionTemplate(transactions);
    }
    private PermissionCheck check(UUID org,UUID actor,UUID doc,String permission) {
        return new PermissionCheck(org,actor,permission,"KNOWLEDGE",doc,"knowledge.view".equals(permission)?"READ":"UPDATE");
    }
    private void require(UUID org,UUID actor,UUID doc,String permission) {
        auth.require(check(org,actor,doc,"knowledge.view"));
        if(!"knowledge.view".equals(permission))auth.require(check(org,actor,doc,permission));
    }
    private KnowledgeRepository.VersionRow version(UUID org,UUID doc,UUID version) {
        var value=knowledge.findVersion(org,version).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        if(!value.documentId().equals(doc))throw new ApiException(ApiErrorCode.NOT_FOUND);
        knowledge.findDocument(org,doc).filter(d->"ACTIVE".equals(d.lifecycleStatus()))
            .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        return value;
    }
    public Record get(UUID doc,UUID version,Integer revision) {
        var a=ActorContext.required();require(a.organizationId(),a.userId(),doc,"knowledge.view");
        version(a.organizationId(),doc,version);
        return repo.find(a.organizationId(),version,revision).orElseGet(()-> {
            if(revision!=null)throw new ApiException(ApiErrorCode.NOT_FOUND);
            return empty(doc,version);
        });
    }
    public JsonNode vectorStatus(UUID doc,UUID version) {
        var a=ActorContext.required();require(a.organizationId(),a.userId(),doc,"knowledge.view");
        version(a.organizationId(),doc,version);return repo.vectorStatus(a.organizationId(),doc,version);
    }
    private Record empty(UUID doc,UUID version) {
        return new Record(doc,version,0,"PENDING","UNKNOWN",json.createObjectNode(),json.createObjectNode(),
            json.createArrayNode(),json.createArrayNode(),null,null,null);
    }
    private List<Target> select(UUID org,UUID actor,Selection s) {
        if(s.documentIds()!=null&&s.documentIds().size()>500)throw new ApiException(ApiErrorCode.BAD_REQUEST,"单次指定文档最多 500 份；全部存量请使用分类或全部范围");
        if(s.categoryId()!=null&&s.documentIds()!=null&&!s.documentIds().isEmpty())throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var found=new ArrayList<Target>();UUID after=null;var before=Instant.now();
        while(true) {
            var page=repo.targets(org,s.categoryId(),s.documentIds(),after,before,200);
            for(var target:page) if(auth.check(check(org,actor,target.documentId(),"knowledge.view")).allowed()
                    &&auth.check(check(org,actor,target.documentId(),"knowledge.update")).allowed())found.add(target);
            if(page.size()<200)break;after=page.getLast().documentId();
        }
        return found;
    }
    public JsonNode preview(Selection s) {
        var a=ActorContext.required(); var targets=select(a.organizationId(),a.userId(),s);
        var result=json.createObjectNode().put("total",targets.size());var samples=result.putArray("samples");
        // Preview is local only, bounded, and never sends contents to a provider.
        for(var target:targets.stream().limit(50).toList()) {
            var source=source(a.organizationId(),target.documentId(),target.versionId());var extracted=extractor.extract(source.pages());
            var item=samples.addObject().put("documentId",target.documentId().toString()).put("versionId",target.versionId().toString())
                .put("documentType",extracted.type()).put("hasParsedContent",!source.pages().isEmpty());
            item.set("localFields",extracted.fields());item.set("provenance",extracted.provenance());
        }
        return result;
    }
    public UUID create(Selection s) {
        var a=ActorContext.required();
        var targets=select(a.organizationId(),a.userId(),s);
        String key=s.requestKey();
        if(key==null||key.isBlank()||key.length()>180)throw new ApiException(ApiErrorCode.BAD_REQUEST,"请提供任务请求标识");
        return createFor(a.organizationId(),a.userId(),targets,key);
    }
    private UUID createFor(UUID org,UUID actor,List<Target> targets,String key) {
        return tx.execute(status->{
            var task=repo.createTask(org,actor,key,fingerprint(targets.stream().map(t->t.documentId()+":"+t.versionId()).sorted().toList().toString().getBytes(StandardCharsets.UTF_8)));
            for(var target:targets) {
                var item=repo.addItem(task.id(),target);
                if(targets.size()==1&&"PENDING".equals(item.status()))queue(task,item,Instant.now());
            }
            if(targets.size()>1)queueDispatch(task,null);
            return task.id();
        });
    }
    public List<Task> tasks() {var a=ActorContext.required();return repo.tasks(a.organizationId(),a.userId(),20);}
    public void parsed(Parsed event) {
        if(event.actorId()==null)return;
        require(event.organizationId(),event.actorId(),event.documentId(),"knowledge.update");
        createFor(event.organizationId(),event.actorId(),List.of(new Target(event.documentId(),event.versionId())),
            "parse:"+event.versionId()+":"+event.parseRunId());
    }
    private void queue(Task task,Item item,Instant at) {
        var payload=json.createObjectNode().put("organizationId",task.organizationId().toString())
            .put("actorId",task.ownerId().toString()).put("taskId",task.id().toString())
            .put("itemId",item.id().toString()).put("generation",item.generation());
        async.enqueueAt(task.organizationId(),JOB_TYPE,payload,"kb-bibliography:"+item.id()+":"+item.generation(),0,at);
    }
    private void queueDispatch(Task task,UUID after) {
        var payload=json.createObjectNode().put("organizationId",task.organizationId().toString()).put("actorId",task.ownerId().toString())
            .put("taskId",task.id().toString()).put("dispatch",true);
        if(after!=null)payload.put("after",after.toString());
        async.enqueueAt(task.organizationId(),JOB_TYPE,payload,"kb-bibliography-dispatch:"+task.id()+":"+after,0,Instant.now());
    }
    private JsonNode dispatch(Task task,JsonNode payload,AsyncJobHandler.ExecutionContext execution) {
        return tx.execute(status->{
            async.requireExecution(execution,false);
            int slots=async.queueCapacity(task.organizationId()).availableSlots();
            // The running dispatcher already owns one slot. Reserve a free slot for its successor.
            if(slots<2)throw new ApiException(ApiErrorCode.SERVER_BUSY);
            int batch=Math.min(16,slots-1);
            var page=repo.items(task.id(),payload.hasNonNull("after")?UUID.fromString(payload.path("after").asText()):null,batch);
            for(var item:page)if("PENDING".equals(item.status()))queue(task,item,Instant.now());
            if(page.size()==batch)queueDispatch(task,page.getLast().id());
            return json.createObjectNode().put("dispatched",page.size());
        });
    }
    public JsonNode results(UUID id,UUID after,int limit) {
        var a=ActorContext.required();var task=repo.task(a.organizationId(),a.userId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        int size=Math.clamp(limit,1,200); var result=json.createObjectNode().put("id",id.toString());
        var counts=result.putObject("counts");var values=result.putArray("items");UUID cursor=null,next=null;boolean hasPending=false;
        while(true) {
            var page=repo.items(task.id(),cursor,200);
            for(var item:page) {
                if(!auth.check(check(a.organizationId(),a.userId(),item.documentId(),"knowledge.view")).allowed())continue;
                counts.put(item.status(),counts.path(item.status()).asInt()+1);
                hasPending|=Set.of("PENDING","WAITING_PROVIDER").contains(item.status());
                if((after==null||item.id().toString().compareTo(after.toString())>0)&&values.size()<size) { values.add(json.valueToTree(item));next=item.id(); }
            }
            if(page.size()<200)break;cursor=page.getLast().id();
        }
        result.put("finished",!hasPending);
        // Return the last scanned visible id; an empty subsequent page ends pagination.
        if(values.size()==size)result.put("nextCursor",next.toString());else result.putNull("nextCursor");
        return result;
    }
    public void retry(UUID taskId,UUID itemId) {
        var a=ActorContext.required();var task=repo.task(a.organizationId(),a.userId(),taskId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        var item=repo.item(taskId,itemId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        require(a.organizationId(),a.userId(),item.documentId(),"knowledge.update");
        if(!Set.of("FAILED","MISSING_PARSE","STALE","NO_MATCH","NEEDS_REVIEW").contains(item.status()))
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"此项正在处理、等待恢复或已经完成，请先查看结果");
        tx.executeWithoutResult(status->queue(task,repo.retryItem(item.id(),item.generation()),Instant.now()));
    }
    public Record confirm(UUID doc,UUID version,Confirmation command) {
        var a=ActorContext.required();require(a.organizationId(),a.userId(),doc,"knowledge.update");version(a.organizationId(),doc,version);
        if(command.documentType()==null||!Set.of("PAPER","PATENT","INTERNAL","UNKNOWN").contains(command.documentType())||command.fields()==null
                ||!command.fields().isObject()||command.fields().toString().length()>100000)throw new ApiException(ApiErrorCode.BAD_REQUEST);
        command.fields().fieldNames().forEachRemaining(f->{if(!FIELDS.contains(f))throw new ApiException(ApiErrorCode.BAD_REQUEST,"不支持的书目字段："+f);});
        command.fields().fields().forEachRemaining(e->{
            var value=e.getValue();if(value.isNull())return;
            if("publication_year".equals(e.getKey())) {
                if(!value.isInt()||value.asInt()<1600||value.asInt()>2200)throw new ApiException(ApiErrorCode.BAD_REQUEST,"出版年份须为有效年份");
            } else if(Set.of("authors","topics").contains(e.getKey())) {
                if(!value.isTextual()&&!value.isArray())throw new ApiException(ApiErrorCode.BAD_REQUEST);
            } else if(!value.isTextual()||value.asText().length()>20000)throw new ApiException(ApiErrorCode.BAD_REQUEST);
            if("doi".equals(e.getKey())&&!BibliographyExtractor.doi(value.asText()).matches("10\\.[0-9]{4,9}/[^\\s]+"))
                throw new ApiException(ApiErrorCode.BAD_REQUEST,"请填写有效 DOI，缺失时留空");
        });
        return tx.execute(status->{
            repo.lockVersion(a.organizationId(),doc,version);
            var old=repo.find(a.organizationId(),version,null).orElseGet(()->empty(doc,version));
            if(old.revision()!=command.revision())throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);
            var accepted=(ObjectNode)old.accepted().deepCopy();var provenance=(ObjectNode)old.provenance().deepCopy();
            var manual=json.createArrayNode();var names=new LinkedHashSet<String>();old.manualFields().forEach(f->names.add(f.asText()));
            names.add("document_type");
            command.fields().fields().forEachRemaining(e->{
                if(e.getValue().isNull())accepted.remove(e.getKey());else accepted.set(e.getKey(),e.getValue());
                names.add(e.getKey());provenance.set(e.getKey(),json.createObjectNode().put("source","manual").put("actorId",a.userId().toString()).put("confirmedAt",Instant.now().toString()));
            });names.forEach(manual::add);
            var updated=new Record(doc,version,old.revision()+1,"ACCEPTED",command.documentType(),accepted,provenance,
                old.candidates(),manual,old.parseRunId(),old.sourceFingerprint(),"书目信息已更新");
            if(!repo.save(a.organizationId(),a.userId(),updated,old.revision()))throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);
            audit.append(a.organizationId(),a.userId(),"KB_BIBLIOGRAPHY_CONFIRMED","KB_DOCUMENT",doc,
                json.createObjectNode().put("versionId",version.toString()).put("revision",updated.revision()).set("fields",command.fields()));
            return updated;
        });
    }
    private Source source(UUID org,UUID doc,UUID version) {
        var saved=repo.source(org,doc,version);
        if(!saved.pages().isEmpty())return saved;
        var v=version(org,doc,version);
        // MinerU markdown is already stored by the parser. Never upload or OCR again to enrich metadata.
        try {
            var metadata=json.readTree(Objects.toString(v.parserMetadataJson(),"{}"));
            String id=metadata.path("markdownFileId").asText();boolean archive=id.isBlank();
            if(archive)id=metadata.path("resultFileId").asText();
            if(id.isBlank())return saved;
            try(var file=files.open(org,UUID.fromString(id))) {
                if(file.size()>20*1024*1024)return saved;
                var text=new StringBuilder();String entryName=null;
                if(!archive) {
                    byte[] bytes=file.stream().readNBytes(1024*1024+1);if(bytes.length>1024*1024)return saved;
                    text.append(new String(bytes,StandardCharsets.UTF_8));
                } else try(var zip=new java.util.zip.ZipInputStream(file.stream(),StandardCharsets.UTF_8)) {
                    int entries=0,total=0;java.util.zip.ZipEntry entry;
                    while((entry=zip.getNextEntry())!=null&&++entries<=200) {
                        boolean markdown=!entry.isDirectory()&&entry.getName().toLowerCase(Locale.ROOT).endsWith(".md");
                        byte[] bytes=zip.readNBytes((markdown?1024*1024:2*1024*1024)+1);total+=bytes.length;
                        if(total>16*1024*1024||bytes.length>(markdown?1024*1024:2*1024*1024))return saved;
                        if(markdown) {text.append(new String(bytes,StandardCharsets.UTF_8));entryName=entry.getName();break;}
                    }
                }
                if(text.isEmpty())return saved;
                var anchor=json.createObjectNode().put("savedResultFileId",id);
                if(entryName!=null)anchor.put("resultEntryPath",entryName);
                return new Source(saved.parseRunId(),fingerprint(text.toString().getBytes(StandardCharsets.UTF_8)),List.of(new Page(text.toString(),anchor,null,"MARKDOWN")));
            }
        } catch(Exception error) { return saved; }
    }
    private String fingerprint(byte[] bytes) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException error) {throw new IllegalStateException(error);}
    }
    public JsonNode process(JsonNode payload,AsyncJobHandler.ExecutionContext execution) {
        UUID org=UUID.fromString(payload.path("organizationId").asText()),actor=UUID.fromString(payload.path("actorId").asText());
        var task=repo.task(org,actor,UUID.fromString(payload.path("taskId").asText())).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        if(payload.path("dispatch").asBoolean())return dispatch(task,payload,execution);
        var item=repo.item(task.id(),UUID.fromString(payload.path("itemId").asText())).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        if(item.generation()!=payload.path("generation").asInt()||!Set.of("PENDING","WAITING_PROVIDER").contains(item.status()))return json.createObjectNode().put("status","ALREADY_PROCESSED");
        require(org,actor,item.documentId(),"knowledge.update");
        var v=version(org,item.documentId(),item.versionId());
        var current=knowledge.findDocument(org,item.documentId()).orElseThrow();
        if(!v.id().equals(current.currentVersionId())||!"READY".equals(v.status())) {
            finish(item,"STALE","VERSION_OR_PARSE_CHANGED",execution);return json.createObjectNode().put("status","STALE");
        }
        var input=source(org,item.documentId(),item.versionId());
        var old=repo.find(org,item.versionId(),null).orElseGet(()->empty(item.documentId(),item.versionId()));
        var local=extractor.extract(input.pages());var candidates=json.createArrayNode();JsonNode matched=null,pdfVerification=null;
        boolean manualType=false;for(var field:old.manualFields())if("document_type".equals(field.asText()))manualType=true;
        String documentType=manualType?old.documentType():local.type();
        String state=input.pages().isEmpty()?"MISSING_PARSE":"NEEDS_REVIEW",message=input.pages().isEmpty()?"没有可用的已保存解析结果":"请核对本地提取的信息";
        if("PAPER".equals(documentType)&&!"INTERNAL".equals(local.type())) {
            // Both view/update and external permission are checked at execution time, before sending a public identifier.
            if(!auth.check(check(org,actor,item.documentId(),"ai.external")).allowed())message="尚未允许外部书目核验，可人工核对本地信息";
            else {
                var lookupLocal=local;
                if(local.doi()==null) {
                    var original=originalPdfText(org,actor,item.documentId(),v);
                    if(original!=null&&original.doi()!=null) {
                        lookupLocal=original;pdfVerification=original.provenance().path("doi");
                    }
                }
                String query=lookupLocal.doi()!=null?lookupLocal.doi():lookupLocal.titles().stream().findFirst().orElse("");
                if(!query.isBlank()) {
                    var response=scholarly.lookup(json.createObjectNode().put("query",query));
                    if(response.hasNonNull("error")) {
                        String code=response.path("error").asText();
                        boolean quota="OPENALEX_FREE_BUDGET_EXHAUSTED".equals(code);
                        if(response.path("retryable").asBoolean()||quota) {
                            postpone(task,item,code,Math.max(quota?3600:60,response.path("retry_after_seconds").asLong(60)),quota,execution);
                            return json.createObjectNode().put("status","WAITING_PROVIDER");
                        }
                        state="FAILED";message="外部书目服务暂不可用，可稍后重试";
                    } else {
                        response.path("results").forEach(candidates::add);
                        var matchingLocal=new BibliographyExtractor.Extracted(documentType,lookupLocal.fields(),lookupLocal.provenance(),lookupLocal.frontText(),lookupLocal.titles(),lookupLocal.doi(),lookupLocal.year());
                        matched=extractor.match(matchingLocal,candidates).orElse(null);
                        if(matched!=null&&pdfVerification!=null&&!originalAuthorMatches(lookupLocal,matched))matched=null;
                        if(matched==null&&local.doi()!=null&&!candidates.isEmpty()) {
                            var original=originalPdfText(org,actor,item.documentId(),v);
                            if(original!=null&&Objects.equals(local.doi(),original.doi())) {
                                matched=extractor.match(original,candidates).orElse(null);
                                if(matched!=null&&!originalAuthorMatches(original,matched))matched=null;
                                if(matched!=null)pdfVerification=original.provenance().path("doi");
                            }
                        }
                        state=matched!=null?"ACCEPTED":candidates.isEmpty()?"NO_MATCH":"NEEDS_REVIEW";
                        message=matched!=null?"已按原文核对匹配文献":candidates.isEmpty()?"未找到匹配书目":"找到候选，请核对后确认";
                    }
                }
            }
        }
        var fields=(ObjectNode)local.fields().deepCopy();var provenance=(ObjectNode)local.provenance().deepCopy();
        var externalFields=new HashSet<String>();
        if(matched!=null) {
            var projected=project(matched);
            for(var it=projected.fields();it.hasNext();) {
                var entry=it.next();if(entry.getValue().isNull()||entry.getValue().isTextual()&&entry.getValue().asText().isBlank())continue;
                externalFields.add(entry.getKey());
                fields.set(entry.getKey(),entry.getValue());
                var p=json.createObjectNode().put("source",matched.has("work_id")?"OpenAlex":"Crossref").put("sourceUrl",matched.path("source_url").asText())
                    .put("retrievedAt",Instant.now().toString()).put("match",pdfVerification!=null?"original_pdf_doi_and_title":local.doi()!=null?"doi_and_title":"exact_title_and_author_or_year");
                p.set("localVerification",pdfVerification!=null?pdfVerification:local.provenance().has("doi")?local.provenance().path("doi"):json.valueToTree(input.pages().stream().limit(4).toList()));
                provenance.set(entry.getKey(),p);
            }
        }
        var accepted=(ObjectNode)old.accepted().deepCopy();var origins=(ObjectNode)old.provenance().deepCopy();
        var manual=new HashSet<String>();old.manualFields().forEach(f->manual.add(f.asText()));
        for(var it=fields.fields();it.hasNext();) {
            var e=it.next();
            // A verified provider match supplies the default metadata; OCR or incomplete
            // local extraction must not replace an edit when no provider field is available.
            if(manual.contains(e.getKey())&&!externalFields.contains(e.getKey()))continue;
            accepted.set(e.getKey(),e.getValue());origins.set(e.getKey(),provenance.path(e.getKey()));
        }
        var manualFields=json.createArrayNode();old.manualFields().forEach(f->{if(!externalFields.contains(f.asText()))manualFields.add(f);});
        var updated=new Record(item.documentId(),item.versionId(),old.revision()+1,state,documentType,accepted,origins,
            candidates,manualFields,input.parseRunId(),input.fingerprint(),message);
        tx.executeWithoutResult(status->{
            async.requireExecution(execution,false);require(org,actor,item.documentId(),"knowledge.update");
            repo.lockVersion(org,item.documentId(),item.versionId());
            var now=source(org,item.documentId(),item.versionId());
            var latest=knowledge.findDocument(org,item.documentId()).orElseThrow();
            if(!latest.currentVersionId().equals(item.versionId())||!"READY".equals(version(org,item.documentId(),item.versionId()).status())
                    ||!Objects.equals(now.parseRunId(),input.parseRunId())||!Objects.equals(now.fingerprint(),input.fingerprint())) {
                repo.updateItem(item.id(),item.generation(),"STALE","PARSE_CHANGED",null,false);return;
            }
            if(!repo.save(org,actor,updated,old.revision()))throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);
            repo.updateItem(item.id(),item.generation(),updated.status(),null,null,false);
            audit.append(org,actor,"KB_BIBLIOGRAPHY_ENRICHED","KB_DOCUMENT",item.documentId(),
                json.createObjectNode().put("versionId",item.versionId().toString()).put("revision",updated.revision()).put("status",updated.status()));
        });
        return json.createObjectNode().put("status",updated.status());
    }
    private boolean originalAuthorMatches(BibliographyExtractor.Extracted original,JsonNode candidate) {
        if(candidate.path("authors").isEmpty())return true;
        String front=BibliographyExtractor.normalize(original.frontText().split("(?im)^\\s*(?:#{1,6}\\s*)?(?:abstract|摘要)\\b",2)[0]);
        for(var author:candidate.path("authors")) {
            String name=BibliographyExtractor.normalize(author.path("name").asText());
            if(name.length()>=5&&front.contains(name))return true;
        }
        return false;
    }
    private BibliographyExtractor.Extracted originalPdfText(UUID org,UUID actor,UUID doc,KnowledgeRepository.VersionRow v) {
        if(v.fileObjectId()==null||!("application/pdf".equalsIgnoreCase(v.contentType())||Objects.toString(v.originalName(),"").toLowerCase(Locale.ROOT).endsWith(".pdf")))return null;
        require(org,actor,doc,"knowledge.view");
        try(var file=files.open(org,v.fileObjectId())) {
            int max=20*1024*1024;if(file.size()>max)return null;
            try(var reservation=com.jsd.aird.shared.resource.WorkingMemory.reserve(Math.max(1,file.size())*8)) {
                byte[] bytes=file.stream().readNBytes(max+1);if(bytes.length>max)return null;
                try(var pdf=Loader.loadPDF(bytes)) {
                    var pages=new ArrayList<Page>();var stripper=new PDFTextStripper();
                    for(int page=1;page<=Math.min(3,pdf.getNumberOfPages());page++) {
                        stripper.setStartPage(page);stripper.setEndPage(page);
                        var anchor=json.createObjectNode().put("page",page).put("fileObjectId",v.fileObjectId().toString()).put("sha256",file.sha256());
                        pages.add(new Page(stripper.getText(pdf),anchor,null,"PDF_TEXT"));
                    }
                    var extracted=extractor.extract(pages);
                    if(Set.of("INTERNAL","PATENT").contains(extracted.type()))return null;
                    // The saved parse has already established that this is a public paper.
                    // A raw PDF text layer may omit headings/publication scaffolding, so keep
                    // the verified DOI's original page even when that layer alone is UNKNOWN.
                    if(extracted.doi()!=null&&!extracted.provenance().hasNonNull("doi")) {
                        for(var page:pages)if(page.text().toLowerCase(Locale.ROOT).contains(extracted.doi())) {
                            extracted.provenance().set("doi",json.createObjectNode().put("excerpt",extracted.doi()).set("anchor",page.anchor()));break;
                        }
                    }
                    if(extracted.doi()!=null&&!extracted.provenance().hasNonNull("doi"))return null;
                    extracted.provenance().fields().forEachRemaining(e->((ObjectNode)e.getValue()).put("source","original_pdf_text"));
                    return new BibliographyExtractor.Extracted("PAPER",extracted.fields(),extracted.provenance(),extracted.frontText(),extracted.titles(),extracted.doi(),extracted.year());
                }
            }
        } catch(ApiException failure) {throw failure;}
        catch(Exception unavailable) {return null;}
    }
    private ObjectNode project(JsonNode match) {
        var value=json.createObjectNode();
        for(var f:List.of("title","authors","publication_year","publication_date","abstract","topics","source_url"))if(match.hasNonNull(f))value.set(f,match.get(f));
        if(match.hasNonNull("doi"))value.put("doi",BibliographyExtractor.doi(match.path("doi").asText()));
        if(match.hasNonNull("id"))value.set("openalex_id",match.get("id"));
        if(match.hasNonNull("source"))value.set("venue",match.get("source"));
        var b=match.path("biblio");for(var f:List.of("volume","issue"))if(b.hasNonNull(f))value.set(f,b.get(f));
        if(b.hasNonNull("first_page"))value.put("pages",b.path("first_page").asText()+(b.hasNonNull("last_page")&&!b.path("first_page").equals(b.path("last_page"))?"–"+b.path("last_page").asText():""));
        return value;
    }
    private void postpone(Task task,Item item,String code,long seconds,boolean quota,AsyncJobHandler.ExecutionContext context) {
        tx.executeWithoutResult(status->{
            async.requireExecution(context,false);
            if(!quota&&item.failureCount()>=4) {repo.updateItem(item.id(),item.generation(),"FAILED",code,null,true);return;}
            var at=Instant.now().plusSeconds(Math.min(172800,seconds));
            repo.updateItem(item.id(),item.generation(),"WAITING_PROVIDER",code,at,!quota);
            queue(task,repo.retryItem(item.id(),item.generation()),at);
        });
    }
    private void finish(Item item,String status,String code,AsyncJobHandler.ExecutionContext context) {
        tx.executeWithoutResult(s->{async.requireExecution(context,false);repo.updateItem(item.id(),item.generation(),status,code,null,false);});
    }
    public void terminalFailure(JsonNode payload,AsyncJobHandler.ExecutionContext context) {
        tx.executeWithoutResult(s->{
            async.requireExecution(context,true);
            if(payload.path("dispatch").asBoolean()) {
                UUID cursor=payload.hasNonNull("after")?UUID.fromString(payload.path("after").asText()):null;
                while(true) {
                    var page=repo.items(UUID.fromString(payload.path("taskId").asText()),cursor,200);
                    for(var item:page)if("PENDING".equals(item.status()))repo.updateItem(item.id(),item.generation(),"FAILED","DISPATCH_FAILED",null,true);
                    if(page.size()<200)break;cursor=page.getLast().id();
                }
                return;
            }
            repo.updateItem(UUID.fromString(payload.path("itemId").asText()),payload.path("generation").asInt(),"FAILED","ENRICHMENT_FAILED",null,true);
        });
    }
}
