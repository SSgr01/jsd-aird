package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

@Component
public class QualityPolicyEvaluator {
    public Result evaluate(JsonNode policy,JsonNode metrics,String domain,double coverage,
                           List<String> warningCodes,BigDecimal formulaTotal){
        var fallback=policy.path("defaultTrustLevel").asText();
        var explanation=policy.path("defaultExplanation").asText("采用已发布质量策略的默认可信等级");
        var rules=new java.util.ArrayList<JsonNode>();policy.path("rules").forEach(rules::add);
        rules.sort(java.util.Comparator.comparingInt(x->x.path("priority").asInt()));
        for(var rule:rules){if(matches(rule.path("when"),metrics,domain,coverage,warningCodes,formulaTotal))
            return new Result(rule.path("trustLevel").asText(),rule.path("explanation").asText());}
        return new Result(fallback,explanation);
    }
    private boolean matches(JsonNode when,JsonNode metrics,String domain,double coverage,List<String>warnings,BigDecimal total){
        if(when.has("domainStatuses")&&when.path("domainStatuses").isArray()){
            var ok=false;for(var x:when.path("domainStatuses"))if(domain.equals(x.asText()))ok=true;if(!ok)return false;}
        if(when.has("minimumEvidenceCoverage")&&coverage<when.path("minimumEvidenceCoverage").asDouble())return false;
        if(when.has("maximumEvidenceCoverage")&&coverage>when.path("maximumEvidenceCoverage").asDouble())return false;
        var actual=new HashSet<>(warnings);
        if(when.has("warningCodesAny")){var ok=false;for(var x:when.path("warningCodesAny"))if(actual.contains(x.asText()))ok=true;if(!ok)return false;}
        if(when.has("warningCodesAll"))for(var x:when.path("warningCodesAll"))if(!actual.contains(x.asText()))return false;
        var range=when.path("formulaTotal");
        if(!range.isMissingNode()&&!range.isNull()){
            if(total==null)return false;
            if(range.has("minimum")&&total.compareTo(range.path("minimum").decimalValue())<0)return false;
            if(range.has("maximum")&&total.compareTo(range.path("maximum").decimalValue())>0)return false;}
        for(var rule:when.path("validationMetrics")){
            var value=metrics.path(rule.path("metric").asText());if(!value.isNumber())return false;
            var actualValue=value.asDouble();var expected=rule.path("value").asDouble();var operator=rule.path("operator").asText();
            if(!switch(operator){case "LT"->actualValue<expected;case "LTE"->actualValue<=expected;case "GT"->actualValue>expected;case "GTE"->actualValue>=expected;case "EQ"->Math.abs(actualValue-expected)<1e-12;default->false;})return false;}
        return true;
    }
    public record Result(String level,String explanation) { }
}
