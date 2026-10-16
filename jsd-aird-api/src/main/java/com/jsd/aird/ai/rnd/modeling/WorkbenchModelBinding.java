package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.prediction.PredictionRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;

/** Runtime request for the model and fields saved by the X/Y workbench. */
public final class WorkbenchModelBinding {
    private WorkbenchModelBinding() {}

    public static ObjectNode create(PredictionRepository.TargetBinding b,ObjectMapper json,
                                    FormulaModelArtifactStore artifacts,boolean formulaPrediction){
        if(!WorkbenchFormulaSupport.supports(b.contractVersion()))
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA,"请选择建模工具注册的模型");
        var out=json.createObjectNode().put("modelVersionId",b.modelId().toString());
        var target=out.putObject("target").put("id",b.targetVersionId().toString()).put("version",b.targetVersion())
            .put("sha256",b.targetHash()).put("code",b.targetCode()).put("valueType",b.valueType());
        if(b.unit()==null)target.putNull("unit");else target.put("unit",b.unit());
        target.set("classes",b.classes());target.putNull("positiveClass");
        target.set("observationSemantics",b.observationSemantics());
        var ref=artifacts.readRef("model",b.artifactKey(),b.artifactSha());
        out.putObject("modelBundle").put("url",ref.url()).put("sha256",ref.sha256());
        var scheme=(ObjectNode)b.frozenInputScheme().deepCopy();
        if(formulaPrediction)for(var field:scheme.path("fields")){
            var id=WorkbenchFormulaSupport.materialId(field);
            if(id!=null)((ObjectNode)field).withObject("encoding").put("formulaMaterialId",id);
        }
        out.set("inputScheme",scheme);
        var source=b.frozenDictionary();
        var dictionary=out.putObject("materialDictionary").put("id",source.path("id").asText())
            .put("version",source.path("version").asInt()).put("sha256",source.path("sha256").asText());
        var materials=dictionary.putArray("materials");
        for(var raw:source.path("materials"))materials.addObject().put("materialId",raw.path("materialId").asText())
            .put("code",raw.path("code").asText()).put("encoderIndex",raw.path("encoderIndex").asInt());
        out.set("applicabilityDomain",b.modelDomain());
        return out;
    }
}
