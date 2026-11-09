package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.port.BibliographyRepository.Page;
import java.util.List;
import org.junit.jupiter.api.Test;

class BibliographyExtractorTest {
    private final ObjectMapper json=new ObjectMapper();
    private final BibliographyExtractor extractor=new BibliographyExtractor(json);
    private BibliographyExtractor.Extracted extract(String text) {
        return extractor.extract(List.of(new Page(text,json.createObjectNode().put("page",1),"node-1","heading")));
    }
    private String paper() { return "Research Article\nPrecise Hydrogel Experimental Methods\nJane Smith\nPublished online: 2018\nDOI: 10.1234/hydrogel\nAbstract\nA study of water content."; }
    private com.fasterxml.jackson.databind.node.ArrayNode candidate() {
        var array=json.createArrayNode();var value=array.addObject().put("id","https://openalex.org/W123")
            .put("title","Precise Hydrogel Experimental Methods").put("doi","https://doi.org/10.1234/hydrogel").put("publication_year",2018);
        value.putArray("authors").addObject().put("name","Jane Smith");return array;
    }
    @Test void acceptsDoiOnlyWithMatchingTitleAndKeepsOriginalLocation() {
        var local=extract(paper());assertThat(local.type()).isEqualTo("PAPER");
        assertThat(local.provenance().path("doi").path("anchor").path("page").asInt()).isEqualTo(1);
        assertThat(extractor.match(local,candidate())).isPresent();
        var wrong=candidate();((com.fasterxml.jackson.databind.node.ObjectNode)wrong.get(0)).put("title","Completely different paper");
        assertThat(extractor.match(local,wrong)).isEmpty();
    }
    @Test void ignoresReferenceDoisAndLaterPages() {
        var local=extract(paper()+"\nReferences\n10.9999/other");assertThat(local.doi()).isEqualTo("10.1234/hydrogel");
        var pages=List.of(new Page(paper(),json.createObjectNode().put("page",1),"1","heading"),
            new Page("10.9999/other",json.createObjectNode().put("page",8),"2","paragraph"));
        assertThat(extractor.extract(pages).doi()).isEqualTo("10.1234/hydrogel");
    }
    @Test void ignoresDateDifferencesWhenIdentityMatchesButRejectsAmbiguousOrFuzzyTitles() {
        var wrong=candidate();((com.fasterxml.jackson.databind.node.ObjectNode)wrong.get(0)).put("publication_year",2017);
        assertThat(extractor.match(extract(paper()),wrong)).isPresent();
        var noDoi=paper().replace("DOI: 10.1234/hydrogel\n","");
        assertThat(extractor.match(extract(noDoi),wrong)).isPresent();
        assertThat(extractor.match(extract(noDoi.replace("Jane Smith\n","")),wrong)).isEmpty();
        var duplicate=candidate();duplicate.add(((com.fasterxml.jackson.databind.node.ObjectNode)candidate().get(0).deepCopy()).put("id","https://openalex.org/W999"));
        assertThat(extractor.match(extract(paper()),duplicate)).isEmpty();
        var fuzzy=candidate();((com.fasterxml.jackson.databind.node.ObjectNode)fuzzy.get(0)).put("title","Precise Hydrogels Experimental Methods");
        assertThat(extractor.match(extract(paper()),fuzzy)).isEmpty();
    }
    @Test void withoutDoiRequiresExactTitlePlusAuthorOrYear() {
        var text=paper().replace("DOI: 10.1234/hydrogel\n","");
        assertThat(extractor.match(extract(text),candidate())).isPresent();
        assertThat(extractor.match(extract(text.replace("Jane Smith\n","").replace("Published online: 2018\n","")),candidate())).isEmpty();
    }
    @Test void internalAndAmbiguousDocumentsCannotBeClassifiedAsPublicFromAnEmbeddedDoi() {
        assertThat(extract("Confidential customer report\n"+paper()).type()).isEqualTo("INTERNAL");
        assertThat(extract("Product brochure\n10.1234/hydrogel").type()).isEqualTo("UNKNOWN");
    }
    @Test void recognizesUnlabelledJournalAbstractButKeepsPrivateAndIncompleteSourcesLocal() {
        String journal="A R T I C L E\nOPEN ACCESS\n# Precise Hydrogel Experimental Methods\nJane Smith\nDepartment of Materials, Example University\nNature Communications\nhttps://doi.org/10.1234/hydrogel\nWe investigate self-healing hydrogels.";
        assertThat(extract(journal).type()).isEqualTo("PAPER");
        assertThat(extractor.match(extract(journal),candidate())).isPresent();
        assertThat(extract("Confidential customer report\n"+journal).type()).isEqualTo("INTERNAL");
        assertThat(extract("Advanced Materials\nCover image\nNovember 2018").type()).isEqualTo("UNKNOWN");
        assertThat(extract("# Product brochure with an embedded DOI\n10.1234/hydrogel").type()).isEqualTo("UNKNOWN");
        String scaffold=journal.replace("Nature Communications\n","");
        assertThat(extract(scaffold).type()).isEqualTo("PAPER");
    }
    @Test void usesVerifiedDoiAndTitleOverOcrAuthorsButKeepsAuthorChecksWithoutDoi() {
        assertThat(extractor.match(extract(paper()+"\nAuthors: Someone Else"),candidate())).isPresent();
        assertThat(extractor.match(extract(paper().replace("DOI: 10.1234/hydrogel\n","")+"\nAuthors: Someone Else"),candidate())).isEmpty();
    }
    @Test void recognizesAcsRscAndCambridgePublicationImprintsWithoutMakingAnEmbeddedDoiPublic() {
        for(var imprint:List.of("DOI: 10.1021/acs.macromol.5b00210 Macromolecules −","DOI: 10.1039/d4nj01387e\nrsc.li/njc","DOI: 10.1557/jmr.2010.35\nhttp://journals.cambridge.org")) {
            String source="# A complete research article with its original title\nJane Smith\n"+imprint;
            assertThat(extract(source).type()).isEqualTo("PAPER");
            assertThat(extract("Confidential customer report\n"+source).type()).isEqualTo("INTERNAL");
        }
    }
    @Test void extractsExplicitPublicationDayWithoutInventingMissingOrInvalidDays() {
        var dated=extract(paper().replace("Published online: 2018","Published online 17 March 2018"));
        assertThat(dated.fields().path("publication_date").asText()).isEqualTo("2018-03-17");
        assertThat(dated.provenance().path("publication_date").path("anchor").path("page").asInt()).isEqualTo(1);
        assertThat(extract(paper()).fields().has("publication_date")).isFalse();
        assertThat(extract(paper().replace("Published online: 2018","Published: 2018-2-30")).fields().has("publication_date")).isFalse();
    }
    @Test void patentFieldsComeOnlyFromExplicitInidTextAndNeverContainLegalStatus() {
        var local=extract("中华人民共和国国家知识产权局\n(11) CN1234567A\n(54) 一种耐水性材料\n(21) 202210123456\n(22) 2022.03.01\n(71) 杰事达材料公司\n(72) 张三\n(57) 一种新材料的制备方法");
        assertThat(local.type()).isEqualTo("PATENT");assertThat(local.fields().path("patent_number").asText()).isEqualTo("CN1234567A");
        assertThat(local.fields().path("title").asText()).isEqualTo("一种耐水性材料");assertThat(local.fields().has("legal_status")).isFalse();
    }
}
