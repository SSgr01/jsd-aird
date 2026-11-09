package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ResearchKnowledgeService implements ResearchKnowledgeFacade {
    private final KnowledgeRepository repository;
    private final KnowledgeGovernanceService governance;
    private final StructuredDocumentCodec codec;
    private final AuthorizationService authorization;
    private final ObjectMapper mapper;
    private final com.jsd.aird.kb.api.KnowledgeSearchFacade publishedRecall;
    private com.jsd.aird.kb.application.port.BibliographyRepository bibliography;
    @org.springframework.beans.factory.annotation.Autowired
    void bibliography(com.jsd.aird.kb.application.port.BibliographyRepository value) {bibliography=value;}
    @org.springframework.beans.factory.annotation.Autowired
    public ResearchKnowledgeService(KnowledgeRepository repository, KnowledgeGovernanceService governance,
            StructuredDocumentCodec codec,com.jsd.aird.kb.api.KnowledgeSearchFacade recall,
            AuthorizationService authorization,ObjectMapper mapper) {
        this.repository=repository;this.governance=governance;this.codec=codec;this.publishedRecall=recall;
        this.authorization=authorization;this.mapper=mapper;
    }

    public ResearchKnowledgeService(KnowledgeRepository repository, KnowledgeGovernanceService governance,
            StructuredDocumentCodec codec, LexicalAnalyzer analyzer, ObjectProvider<KnowledgeEmbeddingFacade> embeddings,
            AuthorizationService authorization, ObjectMapper mapper,
            @Value("${app.ai.embedding.dimension:1024}") int dimension) {
        this(repository,governance,codec,null,authorization,mapper);
    }

    @Override
    public void authorize(List<DocumentRef> documents) {
        var actor = ActorContext.required();
        authorization.require(new com.jsd.aird.iam.api.PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "READ"));
        if (documents == null || documents.size() > 100) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        for (var ref : documents) {
            authorization.require(new com.jsd.aird.iam.api.PermissionCheck(actor.organizationId(), actor.userId(), "knowledge.view", "KNOWLEDGE", ref.document_id(), "READ"));
            var view = governance.publishedContent(ref.document_id(), ref.publication_id());
            if (!repository.isAiApproved(actor.organizationId(), ref.document_id()))
                throw new ApiException(ApiErrorCode.AI_DOCUMENT_NOT_APPROVED);
            if (!view.publication().documentId().equals(ref.document_id())) throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    @Override
    public Result search(List<DocumentRef> documents, String query, int offset) {
        authorize(documents);
        return searchAuthorized(documents, query, offset, "auto");
    }

    @Override public Set<UUID> authorizedSearchDocuments() {
        var actor=ActorContext.required();
        authorize(List.of());
        var scope=authorization.resolveScope(new com.jsd.aird.iam.api.PermissionCheck(actor.organizationId(),actor.userId(),"knowledge.view","KNOWLEDGE",null,"READ"));
        if(scope==null||!scope.allowed())throw new ApiException(ApiErrorCode.PERMISSION_DENIED);
        if("ALL".equals(scope.scopeType()))return null;
        if("SELECTED".equals(scope.scopeType()))return scope.targetIds();
        // Complex IAM scopes use the same per-document policy; never infer PROJECT/CATEGORY/SELF membership.
        var allowed=new LinkedHashSet<UUID>();
        for(int offset=0;offset<=100000;offset+=500) {
            var window=repository.researchDocuments(actor.organizationId(),offset,500);
            for(var row:window)if(authorization.check(new com.jsd.aird.iam.api.PermissionCheck(actor.organizationId(),actor.userId(),"knowledge.view","KNOWLEDGE",row.id(),"READ")).allowed())allowed.add(row.id());
            if(window.size()<500)return Set.copyOf(allowed);
        }
        throw new ApiException(ApiErrorCode.BAD_REQUEST,"Authorized retrieval scope exceeds supported catalog size");
    }

    @Override public Result search(List<DocumentRef> documents,String query,int offset,String mode) {
        authorize(documents); return searchAuthorized(documents,query,offset,mode);
    }
    @Override public Result discover(String query,int offset,String cursor) { return discover(query,offset,cursor,"auto"); }
    @Override
    public Result discover(String query, int offset, String cursor, String mode) {
        authorize(List.of());
        var actor = ActorContext.required();
        int after;
        try { after = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
            if (after < 0 || after > 100000) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException error) { throw new ApiException(ApiErrorCode.BAD_REQUEST, "Invalid discovery cursor"); }
        var page = repository.researchDocuments(actor.organizationId(), after, 501);
        var window = page.stream().limit(500).toList();
        // Filter before any chunk retrieval/ranking. Forbidden titles and counts never leave this service.
        var allowed = window.stream().filter(row -> authorization.check(new com.jsd.aird.iam.api.PermissionCheck(
            actor.organizationId(), actor.userId(), "knowledge.view", "KNOWLEDGE", row.id(), "READ")).allowed())
            .map(row -> new DocumentRef(row.id(), row.publicationId())).toList();
        var result = searchAuthorized(allowed, query, offset, mode);
        var trace = new LinkedHashMap<>(result.trace());
        trace.put("scope", "AUTHORIZED_LIBRARY");
        trace.put("coverage", page.size() > 500 ? "MORE_LIBRARY_WINDOWS_AVAILABLE" : "CURRENT_WINDOW_COMPLETE");
        return new Result(result.candidates(), trace, result.next_offset(),
            page.size() > 500 ? Integer.toString(after + 500) : null);
    }

    private Result searchAuthorized(List<DocumentRef> documents,String query,int offset,String mode) {
        var raw=recallAuthorized(documents,query,mode,new RecallOptions(true,true,true,48));
        var channels=raw.channels().stream().map(c->new com.jsd.aird.shared.retrieval.RankFusion.Channel<>(c.name(),c.weight(),c.candidates())).toList();
        var ranked=com.jsd.aird.shared.retrieval.RankFusion.fuse(channels,c->com.jsd.aird.shared.evidence.EvidenceProtocol.evidenceId(mapper.valueToTree(c.source())),60)
            .stream().map(r->new Candidate(r.value().text(),r.value().source(),r.score(),r.value().node_offset(),r.value().role())).toList();
        if(offset<0||offset>100000) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var trace=new LinkedHashMap<>(raw.trace());trace.put("offset",offset);trace.put("candidate_count",ranked.size());
        return new Result(ranked.stream().skip(offset).limit(12).toList(),trace,offset+12<ranked.size()?offset+12:null);
    }

    @Override public Recall recall(List<DocumentRef> refs,String query,String mode,RecallOptions options) {
        authorize(refs);return recallAuthorized(refs,query,mode,options);
    }

    private record Window(List<DocumentRef> refs,String nextCursor) { }
    private Window authorizedWindow(String cursor) {
        int after;
        try {after=cursor==null||cursor.isBlank()?0:Integer.parseInt(cursor);if(after<0||after>100000)throw new IllegalArgumentException();}
        catch(IllegalArgumentException error){throw new ApiException(ApiErrorCode.BAD_REQUEST,"Invalid discovery cursor");}
        var actor=ActorContext.required();var window=repository.researchDocuments(actor.organizationId(),after,501);
        var refs=window.stream().limit(500).filter(row->authorization.check(new com.jsd.aird.iam.api.PermissionCheck(
            actor.organizationId(),actor.userId(),"knowledge.view","KNOWLEDGE",row.id(),"READ")).allowed())
            .map(row->new DocumentRef(row.id(),row.publicationId())).toList();
        return new Window(refs,window.size()>500?Integer.toString(after+500):null);
    }
    private String windowRevision(Window window) {return sha(mapper.valueToTree(window).toString());}
    @Override public void validateWindow(String cursor,String revision) {
        authorize(List.of());
        if(!windowRevision(authorizedWindow(cursor)).equals(revision))
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT,"RETRIEVAL_SNAPSHOT_CHANGED: restart search at offset 0");
    }
    @Override public Recall discoverRecall(String query,String cursor,String mode,RecallOptions options) {
        authorize(List.of());var window=authorizedWindow(cursor);
        var raw=recallAuthorized(window.refs(),query,mode,options);var trace=new LinkedHashMap<>(raw.trace());
        trace.put("scope","AUTHORIZED_LIBRARY");trace.put("coverage",window.nextCursor()!=null?"MORE_LIBRARY_WINDOWS_AVAILABLE":"CURRENT_WINDOW_COMPLETE");
        trace.put("window_revision",windowRevision(window));
        return new Recall(raw.channels(),trace,window.nextCursor());
    }

    private Recall recallAuthorized(List<DocumentRef> documents,String query,String mode,RecallOptions options) {
        com.jsd.aird.kb.api.ResearchTextMatch.terms(query);
        if(!Set.of("auto","phrase","all","any").contains(mode)||options.limit()<1||options.limit()>100)throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var actor=ActorContext.required();var trace=new LinkedHashMap<String,Object>();var channels=new ArrayList<RecallChannel>();
        var indexedRefs=new LinkedHashMap<UUID,DocumentRef>();var canonicalRefs=new ArrayList<DocumentRef>();
        for(var ref:new LinkedHashSet<>(documents)) {
            var current=repository.findDocument(actor.organizationId(),ref.document_id()).map(KnowledgeRepository.DocumentRow::currentPublicationId).orElse(null);
            if("auto".equals(mode)&&ref.publication_id().equals(current))indexedRefs.put(ref.document_id(),ref);else canonicalRefs.add(ref);
        }
        var allowed=Set.copyOf(indexedRefs.keySet());
        trace.put("match_mode",mode);trace.put("current_documents",indexedRefs.size());trace.put("canonical_documents",canonicalRefs.size());
        trace.put("literal_only",indexedRefs.isEmpty());trace.put("fulltext_coverage",false);
        trace.put("ranking_scope",indexedRefs.isEmpty()?"FULL_IMMUTABLE_LEXICAL_MATCH_RANGE":"BOUNDED_CURRENT_INDEX_CANDIDATES");
        trace.put("current_index_limit_per_channel",options.limit());
        trace.put("coverage_note","Current-index recall is a bounded candidate window; empty results do not prove absence in the full text. Immutable lexical matches retain their complete range.");
        if(!allowed.isEmpty()) {
            if(publishedRecall==null)throw new IllegalStateException("Published recall component unavailable");
            var raw=publishedRecall.recall(new com.jsd.aird.kb.api.KnowledgeSearchFacade.SearchRequest(actor.organizationId(),query,true,options.limit(),List.of(),List.of(),List.of(),List.of(),query,
                new com.jsd.aird.kb.api.KnowledgeSearchFacade.RecallOptions(options.bm25(),options.vector(),options.metadata(),Math.max(1,options.limit()/2)),allowed));
            trace.put("queries",raw.trace().queries());trace.put("timings",raw.trace().timings());trace.put("fallbacks",raw.trace().fallbacks());
            trace.put("embedding_calls",raw.trace().queries().stream().filter(t->"EMBEDDING_BATCH".equals(t.channel())&&!"UNAVAILABLE".equals(t.status())).count());
            for(var entry:raw.channels()) {
                var values=new ArrayList<Candidate>();
                for(var row:entry.hits()) {
                    var ref=indexedRefs.get(row.documentId());
                    if(ref==null||!governance.publishedContent(ref.document_id(),ref.publication_id()).publication().versionId().equals(row.versionId()))continue;
                    var locator=mapper.createObjectNode().put("chunkId",row.chunkId().toString());
                    if(row.anchor()!=null&&row.anchor().isObject())locator.setAll((com.fasterxml.jackson.databind.node.ObjectNode)row.anchor());
                    locator.set("sourceNodeKeys",mapper.valueToTree(row.sourceNodeKeys()));
                    values.add(new Candidate(row.content(),source(ref,row.versionId(),row.chunkId().toString(),locator,row.title(),row.content()),row.retrievalScore()));
                }
                channels.add(new RecallChannel(entry.name(),entry.weight(),values));
            }
        } else {trace.put("bm25","NOT_APPLICABLE");trace.put("vector","NOT_APPLICABLE");}
        for(var ref:canonicalRefs) {
            var values=project(ref).stream().map(c->new Candidate(c.text(),c.source(),com.jsd.aird.kb.api.ResearchTextMatch.score(c.text(),query,mode),c.node_offset(),null))
                .filter(c->c.score()>=0).sorted(Comparator.comparingDouble(Candidate::score).reversed().thenComparingInt(c->c.node_offset()==null?0:c.node_offset())).toList();
            channels.add(new RecallChannel("CANONICAL:"+ref.document_id()+":"+ref.publication_id(),1,values));
        }
        trace.put("strategy","RAW_SCOPED_CHANNELS");
        return new Recall(channels,trace,null);
    }

    @Override
    public Result read(DocumentRef ref, int offset, int limit) {
        authorize(List.of(ref));
        if (offset < 0 || limit < 1 || limit > 20) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var result=project(ref);
        return new Result(result.stream().skip(offset).limit(limit).toList(),Map.of("total_nodes",result.size(),"publication_id",ref.publication_id()),offset+limit<result.size()?offset+limit:null);
    }

    private List<Candidate> project(DocumentRef ref) {
        var view = governance.publishedContent(ref.document_id(), ref.publication_id());
        var nodes = codec.project(view.confirmedDocument(), view.excludedReviewNodeIds()).nodes();
        var sourceNodes = new HashMap<UUID, KnowledgeGovernanceRepository.SourceNodeView>();
        view.sourceNodes().forEach(node -> sourceNodes.put(node.sourceNodeKey(), node));
        var result = nodes.stream().map(node -> {
            var locator = mapper.createObjectNode().put("reviewNodeId", node.reviewNodeId().toString());
            locator.set("sourceNodeKeys", mapper.valueToTree(node.sourceNodeKeys()));
            if (!node.sourceNodeKeys().isEmpty() && sourceNodes.containsKey(node.sourceNodeKeys().getFirst()))
                locator.set("original", sourceNodes.get(node.sourceNodeKeys().getFirst()).sourceAnchor());
            return new Candidate(node.text(), source(ref, view.publication().versionId(), node.reviewNodeId().toString(), locator,
                view.originalName(), node.text()), 1, nodes.indexOf(node), null);
        }).toList();
        return result;
    }

    @Override public JsonNode outline(DocumentRef ref,int offset) {
        authorize(List.of(ref));
        var candidates=project(ref);
        if(offset<0||offset>candidates.size()) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var nodes=mapper.createArrayNode();
        for(var candidate:candidates) {
            var node=nodes.addObject().put("key",candidate.source().source_node_key()).put("text",candidate.text());
            var anchor=candidate.source().anchor();
            node.set("anchor",anchor.has("original")?anchor.path("original"):anchor);
        }
        return com.jsd.aird.kb.api.ResearchDocumentOutline.outline(mapper,nodes,offset);
    }

    @Override public Result context(SourceRef source,int before,int after,int cursor) {
        if(before<0||before>2||after<0||after>2||cursor<0) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        validate(source, "");
        var ref=new DocumentRef(UUID.fromString(source.document_id()),UUID.fromString(source.publication_id()));
        var view=governance.publishedContent(ref.document_id(),ref.publication_id());
        var nodes=codec.project(view.confirmedDocument(),view.excludedReviewNodeIds()).nodes();
        var wanted=new HashSet<String>(); source.anchor().path("sourceNodeKeys").forEach(k->wanted.add(k.asText()));
        var hit=new TreeSet<Integer>();
        for(int i=0;i<nodes.size();i++) if(nodes.get(i).reviewNodeId().toString().equals(source.source_node_key()) || nodes.get(i).sourceNodeKeys().stream().anyMatch(k->wanted.contains(k.toString()))) hit.add(i);
        if(hit.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND,"Source node mapping unavailable");
        var selected=new TreeSet<Integer>();
        for(int n:hit) for(int i=Math.max(0,n-before);i<=Math.min(nodes.size()-1,n+after);i++) selected.add(i);
        var indices=new ArrayList<>(selected); if(cursor>indices.size()) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var result=new ArrayList<Candidate>();
        var projected=project(ref);
        for(int i:indices.stream().skip(cursor).limit(5).toList()) {
            var value=projected.get(i);
            result.add(new Candidate(value.text(),value.source(),value.score(),i,hit.contains(i)?"MATCH":"NEIGHBOR"));
        }
        return new Result(result,Map.of("fulltext_coverage",false,"context_nodes",indices.size()),cursor+5<indices.size()?cursor+5:null);
    }

    @Override
    public Object table(DocumentRef ref, UUID tableId, int offset) {
        authorize(List.of(ref));
        return governance.publishedTable(ref.document_id(), ref.publication_id(), tableId, offset, 30, 0, 20);
    }

    @Override public void validate(SourceRef source,String excerpt) {
        var text=authoritativeText(source);
        if(excerpt==null||!text.contains(excerpt))throw new ApiException(ApiErrorCode.VERSION_CONFLICT,"原文摘录不匹配");
    }

    @Override public List<Candidate> replay(List<Handle> handles) {
        if(handles.size()>30)throw new ApiException(ApiErrorCode.BAD_REQUEST);
        return handles.stream().map(h->new Candidate(authoritativeText(h.source()),h.source(),h.score(),h.node_offset(),h.role())).toList();
    }

    private String authoritativeText(SourceRef source) {
        if (!"KB_PUBLICATION".equals(source.kind()) || source.anchor() == null || source.url() != null)
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Unsupported source authority");
        var ref = new DocumentRef(UUID.fromString(source.document_id()), UUID.fromString(source.publication_id()));
        authorize(List.of(ref));
        var view = governance.publishedContent(ref.document_id(), ref.publication_id());
        if (!view.publication().versionId().toString().equals(source.version_id()))
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "来源文件版本不匹配");
        if(source.anchor().hasNonNull("parent_chunk_id"))return parentText(source);
        String text = null;
        if (source.anchor().hasNonNull("chunkId")) {
            var document = repository.findDocument(ActorContext.required().organizationId(), ref.document_id())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (!ref.publication_id().equals(document.currentPublicationId())
                || !source.source_node_key().equals(source.anchor().get("chunkId").asText()))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "检索来源已变化，请回读固定发布版本后重新提取");
            var row = repository.loadSearchRows(ActorContext.required().organizationId(), List.of(UUID.fromString(source.anchor().get("chunkId").asText())))
                .stream().filter(item -> item.documentId().equals(ref.document_id()) && item.versionId().toString().equals(source.version_id())).findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            text = row.content();
            var anchor = repository.findChunkAnchor(ActorContext.required().organizationId(), row.chunkId()).orElse(null);
            var locator = mapper.createObjectNode().put("chunkId", row.chunkId().toString());
            if (anchor != null) {
                if (anchor.primaryAnchorJson() != null) try { locator.setAll((com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(anchor.primaryAnchorJson())); } catch (Exception ignored) { }
                locator.set("sourceNodeKeys", mapper.valueToTree(anchor.sourceNodeKeys()));
            }
            if (!com.jsd.aird.kb.api.SourceAnchorComparison.same(locator, locatorAnchor(source.anchor())) || !sourceTitleMatches(source,row.title()))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "来源位置或标题不匹配");
        } else {
            var node = codec.project(view.confirmedDocument(), view.excludedReviewNodeIds()).nodes().stream()
                .filter(item -> item.reviewNodeId().toString().equals(source.source_node_key()))
                .findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            text = node.text();
            var locator = mapper.createObjectNode().put("reviewNodeId", node.reviewNodeId().toString());
            locator.set("sourceNodeKeys", mapper.valueToTree(node.sourceNodeKeys()));
            if (!node.sourceNodeKeys().isEmpty()) view.sourceNodes().stream()
                .filter(item -> item.sourceNodeKey().equals(node.sourceNodeKeys().getFirst())).findFirst()
                .ifPresent(item -> locator.set("original", item.sourceAnchor()));
            if (!com.jsd.aird.kb.api.SourceAnchorComparison.same(locator, locatorAnchor(source.anchor())) || !sourceTitleMatches(source,view.originalName()))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "来源位置或标题不匹配");
        }
        if (!sha(text).equals(source.content_sha256()))throw new ApiException(ApiErrorCode.VERSION_CONFLICT,"来源原文哈希已变化");
        return text;
    }

    @Override public Result parents(List<SourceRef> children,int count,int chars,int budget) {
        if(children.size()>100||count<0||count>10||chars<1||chars>6000||budget<0||budget>18000)throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var refs=children.stream().filter(c->"KB_PUBLICATION".equals(c.kind())).map(c->new DocumentRef(UUID.fromString(c.document_id()),UUID.fromString(c.publication_id()))).distinct().toList();
        authorize(refs);
        var wanted=new ArrayList<KnowledgeRepository.ChunkPublication>();
        for(var child:children)if(child.anchor()!=null&&child.anchor().hasNonNull("chunkId"))
            wanted.add(new KnowledgeRepository.ChunkPublication(UUID.fromString(child.source_node_key()),UUID.fromString(child.publication_id())));
        if(wanted.isEmpty())return new Result(List.of(),Map.of("parent_status","NOT_APPLICABLE"),null);
        if(count==0||budget==0)return new Result(List.of(),Map.of("parent_status","BUDGET_SKIPPED"),null);
        var rows=repository.loadParents(ActorContext.required().organizationId(),wanted);
        var byChild=new HashMap<String,KnowledgeRepository.ParentChunk>();
        for(var row:rows)byChild.put(row.childId()+":"+row.publicationId(),row);
        var contexts=new ArrayList<Candidate>();var emitted=new HashMap<String,Candidate>();
        var links=new LinkedHashMap<String,List<String>>();var pages=new LinkedHashMap<String,Object>();
        int missing=0,omitted=0,used=0,notApplicable=0;
        for(var child:children) {
            if(child.anchor()==null||!child.anchor().hasNonNull("chunkId")){notApplicable++;continue;}
            var row=byChild.get(child.source_node_key()+":"+child.publication_id());
            if(row==null){missing++;continue;}
            if(!row.documentId().toString().equals(child.document_id())||!row.versionId().toString().equals(child.version_id())||!sha(row.childContent()).equals(child.content_sha256()))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT,"父子块的来源版本不匹配");
            var key=row.parentId()+":"+row.publicationId();var value=emitted.get(key);
            String location="UNIQUE_MATCH";
            if(value==null) {
                if(contexts.size()>=count||used>=budget){omitted++;continue;}
                var body=row.childContent();int separator=body.indexOf("\n\n");if(separator>=0)body=body.substring(separator+2);
                int match=row.content().indexOf(body);
                int start=0;
                if(!body.isBlank()&&match>=0&&match==row.content().lastIndexOf(body)) start=Math.max(0,row.content().codePointCount(0,match)-Math.min(500,chars/4));
                else location="PARENT_START_UNLOCATED";
                value=parentSpan(row,start,Math.min(chars,budget-used));
                if(value.text().isBlank()){missing++;continue;}
                emitted.put(key,value);contexts.add(value);used+=value.text().codePointCount(0,value.text().length());
                var id=evidenceId(value.source());var anchor=value.source().anchor();int end=anchor.path("span_end").asInt();int total=row.content().codePointCount(0,row.content().length());
                var page=new LinkedHashMap<String,Object>();page.put("start",start);page.put("end",end);page.put("total_chars",total);
                page.put("next_cursor",end<total?end:null);page.put("unread_before",start>0);page.put("location",location);pages.put(id,page);
            }
            links.computeIfAbsent(evidenceId(value.source()),k->new ArrayList<>()).add(evidenceId(child));
        }
        var trace=new LinkedHashMap<String,Object>();trace.put("parent_status",contexts.isEmpty()?"MISSING_OR_BUDGET":"SUCCEEDED");
        trace.put("parent_links",links);trace.put("parent_pages",pages);trace.put("parent_chars",used);trace.put("parent_missing",missing);trace.put("parent_omitted",omitted);trace.put("parent_not_applicable",notApplicable);trace.put("fulltext_coverage",false);
        return new Result(List.of(),trace,null,null,contexts,List.of());
    }

    @Override public Result parentContext(SourceRef reference,int cursor,int chars) {
        if(cursor<0||chars<1||chars>6000)throw new ApiException(ApiErrorCode.BAD_REQUEST);
        validate(reference,"");
        KnowledgeRepository.ParentChunk parent;
        if(reference.anchor().hasNonNull("parent_chunk_id")) {
            parent=repository.parentAtPublication(ActorContext.required().organizationId(),UUID.fromString(reference.source_node_key()),UUID.fromString(reference.publication_id()))
                .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        } else if(reference.anchor().hasNonNull("chunkId")) {
            var values=repository.loadParents(ActorContext.required().organizationId(),List.of(new KnowledgeRepository.ChunkPublication(UUID.fromString(reference.source_node_key()),UUID.fromString(reference.publication_id()))));
            if(values.isEmpty())return new Result(List.of(),Map.of("parent_status","MISSING"),null);
            parent=values.getFirst();
        } else return new Result(List.of(),Map.of("parent_status","NOT_APPLICABLE"),null);
        if(!parent.documentId().toString().equals(reference.document_id())||!parent.versionId().toString().equals(reference.version_id()))throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        var total=parent.content().codePointCount(0,parent.content().length());
        if(cursor>total)throw new ApiException(ApiErrorCode.BAD_REQUEST);
        if(cursor==total)return new Result(List.of(),Map.of("parent_status","END","total_chars",total,"fulltext_coverage",false),null);
        var candidate=parentSpan(parent,cursor,chars);int end=candidate.source().anchor().path("span_end").asInt();
        return new Result(List.of(candidate),Map.of("parent_status","SUCCEEDED","total_chars",total,"span_start",cursor,"span_end",end,"fulltext_coverage",false),end<total?end:null);
    }

    private Candidate parentSpan(KnowledgeRepository.ParentChunk parent,int start,int chars) {
        var total=parent.content().codePointCount(0,parent.content().length());int end=Math.min(total,start+chars);
        var text=parent.content().substring(parent.content().offsetByCodePoints(0,start),parent.content().offsetByCodePoints(0,end));
        var anchor=mapper.createObjectNode().put("parent_chunk_id",parent.parentId().toString()).put("parent_content_sha256",sha(parent.content()))
            .put("span_start",start).put("span_end",end).put("offset_unit","unicode_codepoint");
        var source=source(new DocumentRef(parent.documentId(),parent.publicationId()),parent.versionId(),parent.parentId().toString(),anchor,parent.originalName(),text);
        return new Candidate(text,source,0,start,"PARENT_CONTEXT");
    }

    private String parentText(SourceRef source) {
        var parent=repository.parentAtPublication(ActorContext.required().organizationId(),UUID.fromString(source.source_node_key()),UUID.fromString(source.publication_id()))
            .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        var anchor=source.anchor();int total=parent.content().codePointCount(0,parent.content().length());
        if(!parent.documentId().toString().equals(source.document_id())||!parent.versionId().toString().equals(source.version_id())||!parent.parentId().toString().equals(anchor.path("parent_chunk_id").asText())
            ||!sourceTitleMatches(source,parent.originalName())||!sha(parent.content()).equals(anchor.path("parent_content_sha256").asText())
            ||!anchor.path("span_start").isIntegralNumber()||!anchor.path("span_end").isIntegralNumber())throw new ApiException(ApiErrorCode.VERSION_CONFLICT,"父块来源或跨度无效");
        int start=anchor.path("span_start").asInt(-1),end=anchor.path("span_end").asInt(-1);
        if(start<0||end<=start||end>total||end-start>6000)throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var expected=parentSpan(parent,start,end-start);
        if(!locatorAnchor(expected.source().anchor()).equals(locatorAnchor(anchor))||!sha(expected.text()).equals(source.content_sha256()))throw new ApiException(ApiErrorCode.VERSION_CONFLICT,"父块片段哈希不匹配");
        return expected.text();
    }

    private String evidenceId(SourceRef source) {return com.jsd.aird.shared.evidence.EvidenceProtocol.evidenceId(mapper.valueToTree(source));}

    @Override public SourceRef indexedSource(com.jsd.aird.kb.api.KnowledgeSearchFacade.SearchHit hit) {return indexedSources(List.of(hit)).get(hit.chunkId());}

    @Override public Map<UUID,SourceRef> indexedSources(List<com.jsd.aird.kb.api.KnowledgeSearchFacade.SearchHit> hits) {
        var actor=ActorContext.required();var refs=new LinkedHashMap<UUID,DocumentRef>();
        for(var hit:hits)if(!refs.containsKey(hit.documentId())) {
            var doc=repository.findDocument(actor.organizationId(),hit.documentId()).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
            refs.put(hit.documentId(),new DocumentRef(hit.documentId(),doc.currentPublicationId()));
        }
        authorize(List.copyOf(refs.values()));
        var versions=new HashMap<UUID,UUID>();for(var ref:refs.values())versions.put(ref.document_id(),governance.publishedContent(ref.document_id(),ref.publication_id()).publication().versionId());
        var hydrated=new HashMap<UUID,KnowledgeRepository.SearchRow>();
        if(!hits.isEmpty())repository.loadSearchRows(actor.organizationId(),hits.stream().map(com.jsd.aird.kb.api.KnowledgeSearchFacade.SearchHit::chunkId).toList()).forEach(r->hydrated.put(r.chunkId(),r));
        var result=new LinkedHashMap<UUID,SourceRef>();
        for(var hit:hits) {
            if(!hit.versionId().equals(versions.get(hit.documentId())))throw new ApiException(ApiErrorCode.VERSION_CONFLICT,"检索来源版本已变化");
            var row=hydrated.get(hit.chunkId());
            if(row==null||!row.documentId().equals(hit.documentId())||!row.versionId().equals(hit.versionId())||!row.content().equals(hit.content()))throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
            var anchor=row!=null&&row.provenance()!=null?row.provenance():repository.findChunkAnchor(actor.organizationId(),hit.chunkId()).orElse(null);
            var locator=mapper.createObjectNode().put("chunkId",hit.chunkId().toString());
            if(anchor!=null) {if(anchor.primaryAnchorJson()!=null)try{locator.setAll((com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(anchor.primaryAnchorJson()));}catch(Exception ignored){}
                locator.set("sourceNodeKeys",mapper.valueToTree(anchor.sourceNodeKeys()));}
            result.put(hit.chunkId(),source(refs.get(hit.documentId()),hit.versionId(),hit.chunkId().toString(),locator,hit.title(),hit.content()));
        }
        return result;
    }

    private SourceRef source(DocumentRef ref, UUID version, String node, JsonNode anchor, String title, String text) {
        if(bibliography!=null) {
            var accepted=bibliography.find(ActorContext.required().organizationId(),version,null).orElse(null);
            if(accepted!=null&&accepted.documentId().equals(ref.document_id())&&!accepted.accepted().path("title").asText().isBlank()) {
                var located=(com.fasterxml.jackson.databind.node.ObjectNode)anchor.deepCopy();
                located.put("bibliography_revision",accepted.revision());anchor=located;title=accepted.accepted().path("title").asText();
            }
        }
        return new SourceRef("KB_PUBLICATION", ref.document_id().toString(), version.toString(), ref.publication_id().toString(), node, sha(text), anchor, title, null);
    }

    private JsonNode locatorAnchor(JsonNode anchor) {
        if(anchor==null||!anchor.has("bibliography_revision"))return anchor;
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)anchor.deepCopy();result.remove("bibliography_revision");return result;
    }
    private boolean sourceTitleMatches(SourceRef source,String legacyTitle) {
        if(!source.anchor().has("bibliography_revision"))return Objects.equals(legacyTitle,source.title());
        var revision=source.anchor().get("bibliography_revision");
        if(bibliography==null||!revision.isInt()||revision.asInt()<1)return false;
        return bibliography.find(ActorContext.required().organizationId(),UUID.fromString(source.version_id()),revision.asInt())
            .filter(r->r.documentId().toString().equals(source.document_id()))
            .map(r->r.accepted().path("title").asText().equals(source.title())).orElse(false);
    }

    private String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
