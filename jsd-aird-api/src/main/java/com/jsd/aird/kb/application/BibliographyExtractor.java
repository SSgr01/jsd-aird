package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.kb.application.port.BibliographyRepository.Page;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Conservative extraction from saved source text. No inferred fields and no external calls here. */
@Component
public class BibliographyExtractor {
    private final ObjectMapper json;
    private static final Pattern DOI = Pattern.compile("(?i)\\b10\\.[0-9]{4,9}/[-._;()/:a-z0-9]+$");
    private static final Pattern DOI_IN_TEXT = Pattern.compile("(?i)\\b10\\.[0-9]{4,9}/[-._;()/:a-z0-9]+");
    private static final Pattern REFERENCES = Pattern.compile("(?im)^\\s*(?:#{1,6}\\s*)?(?:references|bibliography|参考文献)\\s*[:：]?\\s*$");
    public record Extracted(String type, ObjectNode fields, ObjectNode provenance, String frontText,
                            List<String> titles, String doi, Integer year) { }
    public BibliographyExtractor(ObjectMapper json) { this.json=json; }

    public Extracted extract(List<Page> pages) {
        var fields=json.createObjectNode(); var provenance=json.createObjectNode();
        var selected=new ArrayList<Page>(); var front=new StringBuilder();
        for (var page: pages) {
            // Anchors use one-based page numbers. When no page is saved, use only the leading text.
            int number=page.anchor().path("page").asInt(page.anchor().path("pageNo").asInt(page.anchor().path("page_no").asInt(0)));
            if (number>3 || front.length()>=16000) break;
            var text=Objects.toString(page.text(),""); var references=REFERENCES.matcher(text);
            boolean end=references.find();
            if(end) text=text.substring(0,references.start());
            if(text.length()>16000-front.length()) text=text.substring(0,16000-front.length());
            if(!text.isBlank()) { selected.add(new Page(text,page.anchor(),page.nodeKey(),page.nodeType())); front.append(text).append('\n'); }
            if(end) break;
        }
        String text=front.toString();
        var titles=new LinkedHashSet<String>();
        for (var page:selected) for(var line:page.text().split("\\R")) {
            String clean=line.replaceFirst("^\\s*#{1,6}\\s*", "").strip();
            if(clean.length()<8 || clean.length()>500 || clean.matches("(?i).*(?:https?://|doi:|issn|copyright|all rights reserved|abstract|摘要|references).*")) continue;
            if(clean.matches("(?i)(?:research article|review article|original research|open access|published.*|received.*|accepted.*|frontiers in .*|journal of .*)"))continue;
            if(titles.size()<12) titles.add(clean);
        }
        boolean privateText=Pattern.compile("(?i)内部|保密|客户(?:名称|资料|编号)|confidential|internal use|customer (?:name|report)|test report|检测报告|展会资料").matcher(text).find();
        boolean patent=Pattern.compile("(?i)(?:[（(]\\s*11\\s*[)）].{0,50}(?:CN|US|EP|JP|WO)\\s*\\d|中华人民共和国国家知识产权局|united states patent|专利申请公布|授权公告号)").matcher(text).find();
        boolean abstractLabel=Pattern.compile("(?im)(?:^|\\n)\\s*(?:#{1,6}\\s*)?(?:abstract|摘要)\\b|【摘要】").matcher(text).find();
        boolean journal=Pattern.compile("(?i)research article|review article|original (?:article|research)|journal of|published (?:online|in)|accepted.{0,40}20[0-9]{2}|frontiers in|scientific reports|期刊|收稿日期|中图分类号").matcher(text).find();
        // Some journals print an unlabelled abstract (for example Nature Communications).
        // Require a title heading, a DOI and a publication imprint or the article/open-access scaffold.
        boolean heading=selected.stream().anyMatch(p->"heading".equalsIgnoreCase(p.nodeType())&&p.text().strip().length()>=20)
            ||Pattern.compile("(?m)^#{1,6}\\s+[^\\n]{20,}").matcher(text).find();
        boolean imprint=Pattern.compile("(?i)nature\\s+communications|npg asia materials|nature\\.com|springer|wiley[–-]vch|advanced materials|academic\\.oup\\.com|sciencedirect\\.com|pubs\\.acs\\.org|macromolecules\\s*(?:\\d|[−–-])|chem\\.\\s*mater\\.\\s*(?:19|20)\\d{2}|rsc\\.li/|pubs\\.rsc\\.org|journals\\.cambridge\\.org").matcher(text).find();
        String compact=normalize(text);
        boolean articleScaffold=(compact.startsWith("article")||compact.startsWith("researcharticle"))&&compact.contains("openaccess")
            &&Pattern.compile("(?i)(?:university|institute|department|laboratory|大学|研究所)").matcher(text).find();
        boolean publicScaffold=heading&&DOI_IN_TEXT.matcher(text).find()&&(imprint||articleScaffold);
        String type=patent?"PATENT":privateText?"INTERNAL":(abstractLabel&&journal||publicScaffold)?"PAPER":"UNKNOWN";
        var dois=new LinkedHashSet<String>(); var dm=DOI_IN_TEXT.matcher(text);
        while(dm.find()) { String value=doi(dm.group().replaceFirst("[.,;]+$","")); if(DOI.matcher(value).matches())dois.add(value); }
        String doi=dois.size()==1?dois.iterator().next():null;
        Integer year=null;
        var ym=Pattern.compile("(?i)(?:published(?: online)?|publication date|发表日期|出版日期)\\s*[:：]?[^\\n]{0,35}?\\b((?:19|20)\\d{2})\\b").matcher(text);
        if(ym.find())year=Integer.valueOf(ym.group(1));
        publicationDate(selected,fields,provenance);
        if(fields.has("publication_date"))year=Integer.valueOf(fields.path("publication_date").asText().substring(0,4));
        if(type.equals("PATENT")) {
            labeled(selected,fields,provenance,"title","(?:54|发明名称|Title)");
            labeled(selected,fields,provenance,"patent_number","(?:11|公开号|公布号|授权公告号|Publication number)");
            labeled(selected,fields,provenance,"application_number","(?:21|申请号|Application number)");
            labeled(selected,fields,provenance,"applicants","(?:71|申请人|专利权人|Applicant)");
            labeled(selected,fields,provenance,"inventors","(?:72|发明人|Inventor)");
            labeled(selected,fields,provenance,"application_date","(?:22|申请日|Date of filing)");
            labeled(selected,fields,provenance,"publication_date","(?:43|45|公开日|公告日|Date of publication)");
            labeled(selected,fields,provenance,"ipc","(?:51|Int\\.\\s*Cl\\.|IPC)");
            labeled(selected,fields,provenance,"cpc","CPC");
            labeled(selected,fields,provenance,"abstract","(?:57|摘要|Abstract)");
        } else {
            labeled(selected,fields,provenance,"title","(?:题名|标题|Title)");
            labeled(selected,fields,provenance,"authors","(?:作者|Authors?)");
            labeled(selected,fields,provenance,"abstract","(?:摘要|Abstract)");
            if(type.equals("PAPER")&&doi!=null)putLocated(selected,fields,provenance,"doi",doi);
        }
        return new Extracted(type,fields,provenance,text,List.copyOf(titles),doi,year);
    }
    private void publicationDate(List<Page> pages,ObjectNode fields,ObjectNode provenance) {
        var pattern=Pattern.compile("(?i)(?:published(?:\\s+online)?|publication\\s+date|出版日期|发表日期)\\s*[:：]?\\s*(\\d{1,2}\\s+[a-z]{3,9}\\s+(?:19|20)\\d{2}|(?:19|20)\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2})");
        for(var page:pages) {
            var match=pattern.matcher(page.text());
            while(match.find()) {
                String value=match.group(1).replaceAll("\\s+"," ");
                for(var format:List.of("d MMMM uuuu","d MMM uuuu","uuuu-M-d")) try {
                    var formatter=new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(format)
                        .toFormatter(Locale.ENGLISH).withResolverStyle(java.time.format.ResolverStyle.STRICT);
                    var date=java.time.LocalDate.parse(value.replace('/','-').replace('.','-'),formatter);
                    fields.put("publication_date",date.toString());provenance.set("publication_date",location(page,match.group()));return;
                } catch(java.time.format.DateTimeParseException ignored) { /* Keep incomplete or invalid dates unset. */ }
            }
        }
    }
    private void labeled(List<Page> pages,ObjectNode fields,ObjectNode provenance,String key,String label) {
        var pattern=Pattern.compile("(?im)^\\s*(?:#{1,6}\\s*)?(?:[（(]\\s*"+label+"\\s*[)）]|"+label+")\\s*[:：]?\\s+([^\\n]{2,1500})");
        for(var p:pages) { var m=pattern.matcher(p.text()); if(m.find()) { fields.put(key,m.group(1).strip()); provenance.set(key,location(p,m.group(0))); return; } }
    }
    private void putLocated(List<Page> pages,ObjectNode fields,ObjectNode provenance,String key,String value) {
        for(var p:pages) if(doi(p.text()).contains(value)) { fields.put(key,value); provenance.set(key,location(p,value)); return; }
    }
    private ObjectNode location(Page page,String excerpt) {
        var value=json.createObjectNode().put("source","saved_original").put("excerpt",excerpt);
        if(page.nodeKey()!=null)value.put("sourceNodeKey",page.nodeKey());
        value.set("anchor",page.anchor()); return value;
    }
    public static String normalize(String s) {
        return Normalizer.normalize(s,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
    }
    public static String doi(String s) { return s.toLowerCase(Locale.ROOT).replaceFirst("^(?:https?://(?:dx\\.)?doi.org/|doi:\\s*)","").strip(); }

    /** Exactly one compatible record; approximate matches never pass this gate. */
    public Optional<JsonNode> match(Extracted local,JsonNode candidates) {
        var exact=new ArrayList<JsonNode>();
        for(var item:candidates) {
            if(!"PAPER".equals(local.type())||item.path("is_retracted").asBoolean())continue;
            String title=normalize(item.path("title").asText());
            if(title.length()<12)continue;
            boolean titleMatch=local.titles().stream().anyMatch(t->normalize(t).equals(title));
            // A PDF title can span adjacent source nodes. Exact normalized text before the abstract is also usable.
            var front=local.frontText().split("(?im)^\\s*(?:#{1,6}\\s*)?(?:abstract|摘要)\\b",2)[0];
            titleMatch |= normalize(front).contains(title);
            if(!titleMatch)continue;
            boolean doiMatch=local.doi()!=null&&doi(item.path("doi").asText()).equals(local.doi());
            if(local.doi()!=null&&!doiMatch)continue;
            int year=item.path("publication_year").asInt(0);
            boolean authorMatch=false;
            for(var author:item.path("authors")) {
                var name=normalize(author.path("name").asText());
                if(name.length()>=5&&normalize(front).contains(name))authorMatch=true;
            }
            if(!doiMatch&&!local.fields().path("authors").asText().isBlank()&&item.path("authors").size()>0) {
                boolean consistent=false;
                for(var author:item.path("authors")) {
                    var name=normalize(author.path("name").asText());
                    if(name.length()>=5&&normalize(local.fields().path("authors").asText()).contains(name))consistent=true;
                }
                if(!consistent)continue;
            }
            // Online and issue years can differ too. A year is supporting evidence, not a veto
            // when the exact title and DOI (or an author) already identify the same paper.
            if(doiMatch || (local.year()!=null && local.year()==year) || authorMatch)exact.add(item);
        }
        var unique=new LinkedHashMap<String,JsonNode>();
        for(var item:exact)unique.put(item.path("id").asText(item.path("doi").asText(item.toString())),item);
        return unique.size()==1?Optional.of(unique.values().iterator().next()):Optional.empty();
    }
}
