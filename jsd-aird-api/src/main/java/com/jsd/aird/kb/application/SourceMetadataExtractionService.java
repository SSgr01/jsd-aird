package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.kb.application.port.BibliographyRepository.Page;
import com.jsd.aird.kb.application.port.BibliographyRepository.Source;
import com.jsd.aird.kb.application.port.MetadataExtractionCache;
import com.jsd.aird.kb.application.port.MetadataExtractionModel;
import com.jsd.aird.shared.evidence.EvidenceProtocol;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Service;

/** One bounded model call with persistent organization-scoped receipts and source checks. */
@Service
public class SourceMetadataExtractionService {
    private static final int MAX_CHARS=16000,MAX_NODES=80;
    private final MetadataExtractionCache cache;
    private final MetadataExtractionModel model;
    private final ObjectMapper json;
    public record Result(ObjectNode fields,ObjectNode provenance,String status,boolean cacheHit) { }
    private record Input(ArrayNode texts,List<Page> pages,String key) { }
    public SourceMetadataExtractionService(MetadataExtractionCache cache,MetadataExtractionModel model,ObjectMapper json) {
        this.cache=cache;this.model=model;this.json=json;
    }
    public boolean enabled(){return model.enabled();}
    public boolean privateEndpoint(){return model.privateEndpoint();}
    public Result extract(UUID organization,String fileHash,Source source,boolean allowed) {
        if(!enabled())return empty("DISABLED");
        if(!allowed)return empty("SKIPPED_PERMISSION");
        var input=input(fileHash,source);
        if(input.pages().isEmpty())return empty("MISSING_PARSE");
        var existing=cache.find(organization,input.key());
        if(existing.isPresent()) {
            var value=existing.get();
            return "SUCCEEDED".equals(value.status())?validate(value.response(),input,true):empty(value.status());
        }
        var owner=UUID.randomUUID();
        if(!cache.claim(organization,input.key(),owner,model.model(),model.policyHash()))return empty("RUNNING");
        JsonNode response;
        try {response=model.extract(input.texts(),owner);}
        catch(RuntimeException failure) {
            String status=failure.getCause() instanceof java.io.IOException||failure.getCause() instanceof InterruptedException?"UNKNOWN":"FAILED";
            cache.fail(organization,input.key(),owner,status);
            return empty(status);
        }
        // Do not retry the provider if persisting its response fails. The existing RUNNING
        // receipt prevents another charge and can be reconciled explicitly by an operator.
        cache.succeed(organization,input.key(),owner,response);
        return validate(response,input,false);
    }
    public boolean reset(UUID organization,String fileHash,Source source) {
        if(!enabled())return false;
        return cache.reset(organization,input(fileHash,source).key());
    }
    private Input input(String fileHash,Source source) {
        var texts=json.createArrayNode();var pages=new ArrayList<Page>();int length=0;
        for(var page:source.pages()) {
            int number=page.anchor()==null?0:page.anchor().path("page").asInt(page.anchor().path("pageNo").asInt(page.anchor().path("page_no").asInt(0)));
            if(number>3||length>=MAX_CHARS||pages.size()>=MAX_NODES)break;
            String text=Objects.toString(page.text(),"");
            var references=java.util.regex.Pattern.compile("(?im)^\\s*(?:#{1,6}\\s*)?(?:references|bibliography|参考文献)\\s*[:：]?\\s*$").matcher(text);
            boolean end=references.find();if(end)text=text.substring(0,references.start());
            text=text.substring(0,Math.min(text.length(),MAX_CHARS-length));
            if(!text.isBlank()) {
                texts.addObject().put("source",pages.size()).put("text",text);
                pages.add(new Page(text,page.anchor(),page.nodeKey(),page.nodeType()));length+=text.length();
            }
            if(end)break;
        }
        String key=EvidenceProtocol.sha(String.join("\n",Objects.toString(fileHash,""),Objects.toString(source.fingerprint(),""),model.policyHash(),texts.toString()));
        return new Input(texts,List.copyOf(pages),key);
    }
    private Result empty(String status){return new Result(json.createObjectNode(),json.createObjectNode(),status,false);}
    private Result validate(JsonNode response,Input input,boolean hit) {
        var fields=json.createObjectNode();var provenance=json.createObjectNode();
        if(response==null||!response.path("fields").isArray()||response.path("fields").size()>28)return empty("INVALID_RESPONSE");
        var duplicates=new HashSet<String>();
        for(var item:response.path("fields")) {
            String name=item.path("name").asText();
            if(!KnowledgeMetadataFields.MODEL.contains(name))continue;
            if(fields.has(name)||duplicates.contains(name)) {fields.remove(name);provenance.remove(name);duplicates.add(name);continue;}
            if(!item.path("source").isInt()||!item.path("value").isTextual()||!item.path("quote").isTextual())continue;
            int index=item.path("source").asInt();String value=item.path("value").asText().strip(),quote=item.path("quote").asText();
            if(index<0||index>=input.pages().size()||value.isBlank()||value.length()>4000||quote.isBlank()||quote.length()>2000)continue;
            var page=input.pages().get(index);
            if(normalize(quote).isBlank()||normalize(value).isBlank()||!normalize(page.text()).contains(normalize(quote)))continue;
            boolean inference="document_kind".equals(name);
            if(inference&&value.length()>100)continue;
            if(!inference&&!normalize(quote).contains(normalize(value)))continue;
            if("publication_year".equals(name)) {
                if(!value.matches("(?:1[6-9]|20|21)\\d{2}|2200"))continue;
                fields.put(name,Integer.parseInt(value));
            } else if("doi".equals(name)) {
                String doi=BibliographyExtractor.doi(value);
                if(!doi.matches("10\\.[0-9]{4,9}/[^\\s]+"))continue;
                fields.put(name,doi);
            } else fields.put(name,value);
            var origin=json.createObjectNode().put("source",inference?"model_inference_from_original":"model_extraction_from_original")
                    .put("excerpt",quote).put("policyHash",model.policyHash()).put("cacheHit",hit);
            if(page.anchor()!=null) {
                var anchor=page.anchor().deepCopy();
                if(anchor instanceof ObjectNode object&&!object.has("page")) {
                    int number=object.path("pageNo").asInt(object.path("page_no").asInt(0));
                    if(number>0)object.put("page",number);
                }
                origin.set("anchor",anchor);
            }
            if(page.nodeKey()!=null)origin.put("sourceNodeKey",page.nodeKey());
            provenance.set(name,origin);
        }
        var extra=json.createArrayNode();var extraOrigins=json.createArrayNode();var labels=new HashSet<String>();
        if(response.path("extra_fields").isArray()&&response.path("extra_fields").size()<=KnowledgeMetadataFields.MAX_EXTRA_FIELDS) {
            for(var item:response.path("extra_fields")) {
                if(!item.path("label").isTextual()||!item.path("value").isTextual()||!item.path("quote").isTextual()||!item.path("source").isInt())continue;
                String label=item.path("label").asText().strip(),value=item.path("value").asText().strip(),quote=item.path("quote").asText();
                int index=item.path("source").asInt();
                if(label.isBlank()||label.length()>KnowledgeMetadataFields.MAX_EXTRA_LABEL||value.isBlank()||value.length()>KnowledgeMetadataFields.MAX_EXTRA_VALUE
                        ||quote.isBlank()||quote.length()>2000||index<0||index>=input.pages().size())continue;
                var page=input.pages().get(index);
                if(normalize(quote).isBlank()||normalize(value).isBlank()||!normalize(page.text()).contains(normalize(quote))||!normalize(quote).contains(normalize(value)))continue;
                if(!labels.add(normalize(label).toLowerCase(Locale.ROOT)))continue;
                extra.addObject().put("label",label).put("value",value);
                var origin=extraOrigins.addObject().put("label",label).put("value",value).put("excerpt",quote)
                        .put("source","model_extraction_from_original").put("policyHash",model.policyHash()).put("cacheHit",hit);
                if(page.anchor()!=null)origin.set("anchor",page.anchor().deepCopy());
                if(page.nodeKey()!=null)origin.put("sourceNodeKey",page.nodeKey());
            }
        }
        if(!extra.isEmpty()) {
            fields.set("extra_fields",extra);
            provenance.set("extra_fields",json.createObjectNode().put("source","model_extraction_from_original").set("entries",extraOrigins));
        }
        return new Result(fields,provenance,fields.isEmpty()?"NO_FIELDS":"SUCCEEDED",hit);
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\u200B\\u00AD]","").replaceAll("\\s+"," ").strip();
    }
}
