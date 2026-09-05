package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PhysicalStructureFieldCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PhysicalStructureFieldCompiler compiler =
            new PhysicalStructureFieldCompiler(objectMapper);

    @Test
    void compilesFormLabelAndInputSurfacesFromWorkbookGeometry() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A4","value":"产品名称"},
                  {"address":"B4","mergedRange":"B4:C4","value":""},
                  {"address":"D4","value":"生产批号"},
                  {"address":"E4","mergedRange":"E4:F4","value":""},
                  {"address":"A6","mergedRange":"A6:A8","value":"不合格描述（品管部）"},
                  {"address":"B6","mergedRange":"B6:F8","value":"签名/日期："}
                ],"candidateCells":[
                  {"address":"A4","value":"产品名称"},
                  {"address":"B4","mergedRange":"B4:C4","value":"","bordered":true},
                  {"address":"D4","value":"生产批号"},
                  {"address":"E4","mergedRange":"E4:F4","value":"","bordered":true},
                  {"address":"A6","mergedRange":"A6:A8","value":"不合格描述（品管部）"},
                  {"address":"B6","mergedRange":"B6:F8","value":"签名/日期：","bordered":true}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"FORM_REGION","sheetId":"s1","range":"A4:F8","structure":{}}
                """);
        var parent = objectMapper.createObjectNode().put("kind", "FORM_REGION")
                .put("relationId", "r1").put("fieldId", "f1").put("bindingId", "b1")
                .put("candidateRef", "r1").put("regionId", "r1").put("blockId", "r1");
        parent.putObject("locator").put("sheetId", "s1").put("range", "A4:F8");

        var children = compiler.children(parent, region, facts);

        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactly("产品名称", "生产批号", "不合格描述（品管部）");
        assertThat(children).extracting(item -> item.payload().path("locator").path("valueRange").asText())
                .containsExactly("B4:C4", "E4:F4", "B6:F8");
        assertThat(children).allMatch(item ->
                "SCALAR".equals(item.payload().path("mappingKind").asText())
                        && !item.payload().path("locator").path("valueRange").asText().isBlank());
    }

    @Test
    void splitsSeveralLabelValuePairsOnOneRowWithoutCrossingTheNextLabel() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"B2","value":"填写人"},{"address":"D2","value":"填写日期"},
                  {"address":"F2","value":"环境温度"},{"address":"H2","value":"环境湿度"}
                ],"candidateCells":[
                  {"address":"B2","value":"填写人"},{"address":"C2","value":""},
                  {"address":"D2","value":"填写日期"},{"address":"E2","value":""},
                  {"address":"F2","value":"环境温度"},{"address":"G2","mergedRange":"G2:J2","value":""},
                  {"address":"H2","value":"环境湿度"},{"address":"I2","value":""}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"FORM_REGION","sheetId":"s1","range":"A1:J4","structure":{
                  "fieldSurfaces":[{"structure":{"labelRange":"F2","valueRange":"G2:J2"}}]
                }}
                """);
        var parent = formParent("s1", "A1:J4");

        var children = compiler.children(parent, region, facts);

        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactly("填写人", "填写日期", "环境温度", "环境湿度");
        assertThat(children).extracting(item -> item.payload().path("locator")
                        .path("valueRange").asText())
                .containsExactly("C2:C2", "E2:E2", "G2:G2", "I2:J2");
    }

    @Test
    void infersSameRowInputBandsWhenUnstyledBlankCellsAreAbsentFromCandidates() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"B2","value":"实验人"},{"address":"D2","value":"日期"},
                  {"address":"F2","value":"温度"},{"address":"H2","value":"湿度"}
                ],"candidateCells":[]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"FORM_REGION","sheetId":"s1","range":"A1:J3","structure":{}}
                """);

        var children = compiler.children(formParent("s1", "A1:J3"), region, facts);

        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactly("实验人", "日期", "温度", "湿度");
        assertThat(children).extracting(item -> item.payload().path("locator")
                        .path("valueRange").asText())
                .containsExactly("C2:C2", "E2:E2", "G2:G2", "I2:J2");
    }

    @Test
    void keepsAnEditableMergedInputSurfaceThatContainsADefaultValue() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A2","value":"目的"},
                  {"address":"B2","mergedRange":"B2:D2","value":"测试不同引发剂对黄变性的影响"}
                ],"candidateCells":[
                  {"address":"A2","value":"目的"},
                  {"address":"B2","mergedRange":"B2:D2","value":"测试不同引发剂对黄变性的影响","bordered":true}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"FORM_REGION","sheetId":"s1","range":"A1:H3","structure":{}}
                """);

        var children = compiler.children(formParent("s1", "A1:H3"), region, facts);

        assertThat(children).singleElement().satisfies(item -> {
            assertThat(item.payload().path("fieldName").asText()).isEqualTo("目的");
            assertThat(item.payload().path("locator").path("valueRange").asText())
                    .isEqualTo("B2:D2");
        });
    }

    @Test
    void expandsAdjacentBlankCandidatesIntoOneInputBand() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A4","value":"实验目的"},
                  {"address":"B4","value":"","inputCandidate":true}
                ],"candidateCells":[
                  {"address":"A4","value":"实验目的"},
                  {"address":"B4","value":"","inputCandidate":true},
                  {"address":"C4","value":"","inputCandidate":true},
                  {"address":"D4","value":"","inputCandidate":true}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"FORM_REGION","sheetId":"s1","range":"A1:D4","structure":{}}
                """);

        var children = compiler.children(formParent("s1", "A1:D4"), region, facts);

        assertThat(children).singleElement().satisfies(item ->
                assertThat(item.payload().path("locator").path("valueRange").asText())
                        .isEqualTo("B4:D4"));
    }

    @Test
    void doesNotLetAdjacentInlinePlaceholdersConsumeEachOther() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"F3","value":"膜厚："},
                  {"address":"G3","mergedRange":"G3:H3","value":"素材："}
                ],"candidateCells":[
                  {"address":"F3","value":"膜厚："},
                  {"address":"G3","mergedRange":"G3:H3","value":"素材："}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"FORM_REGION","sheetId":"s1","range":"A1:H3","structure":{}}
                """);

        var children = compiler.children(formParent("s1", "A1:H3"), region, facts);

        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactly("膜厚", "素材");
        assertThat(children).extracting(item -> item.payload().path("locator")
                        .path("valueRange").asText())
                .containsExactly("F3", "G3:H3");
    }

    @Test
    void compilesLeafInlineFieldsUnderVerticalFormGroupInsteadOfTheGroupContainer() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A5","mergedRange":"A5:A7","value":"方案分组"},
                  {"address":"B5","mergedRange":"B5:I5","value":"素材："},
                  {"address":"B6","mergedRange":"B6:I6","value":"施工方式："},
                  {"address":"B7","mergedRange":"B7:I7","value":"测试条件："}
                ],"candidateCells":[
                  {"address":"A5","mergedRange":"A5:A7","value":"方案分组"},
                  {"address":"B5","mergedRange":"B5:I5","value":"素材："},
                  {"address":"B6","mergedRange":"B6:I6","value":"施工方式："},
                  {"address":"B7","mergedRange":"B7:I7","value":"测试条件："}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"FORM_REGION","sheetId":"s1","range":"A5:J7","structure":{
                  "fieldSurfaces":[{"structure":{"labelRange":"A5:A7","valueRange":"B5:I7"}}]
                }}
                """);
        var parent = formParent("s1", "A5:J7");

        var children = compiler.children(parent, region, facts);

        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactly("素材", "施工方式", "测试条件");
        assertThat(children).extracting(item -> item.payload().path("locator")
                        .path("valueMode").asText())
                .containsOnly("INLINE");
        assertThat(children).noneMatch(item ->
                "方案分组".equals(item.payload().path("fieldName").asText()));
    }

    @Test
    void treatsMergedLabelAnchorAndFullMergeAsOnePhysicalField() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"F1","mergedRange":"F1:G1","value":"版本"},
                  {"address":"H1","mergedRange":"H1:I1","value":""}
                ],"candidateCells":[
                  {"address":"F1","mergedRange":"F1:G1","value":"版本"},
                  {"address":"H1","mergedRange":"H1:I1","value":"","bordered":true}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"FORM_REGION","sheetId":"s1","range":"F1:I1","structure":{
                  "fieldSurfaces":[{"structure":{"labelRange":"F1","valueRange":"H1:I1"}}]
                }}
                """);

        var children = compiler.children(formParent("s1", "F1:I1"), region, facts);

        assertThat(children).hasSize(1);
        assertThat(children.getFirst().payload().path("locator").path("labelRange").asText())
                .isEqualTo("F1:G1");
    }

    @Test
    void doesNotCompileLongInstructionTextAsAnEditableField() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A1","value":"注：原料投料偏差不能大于1%，补损溶剂除外。"},
                  {"address":"B1","value":""},
                  {"address":"A2","value":"备注"},{"address":"B2","value":""}
                ],"candidateCells":[
                  {"address":"A1","value":"注：原料投料偏差不能大于1%，补损溶剂除外。"},
                  {"address":"B1","value":"","bordered":true},
                  {"address":"A2","value":"备注"},{"address":"B2","value":"","bordered":true}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"FORM_REGION","sheetId":"s1","range":"A1:B2","structure":{}}
                """);

        var children = compiler.children(formParent("s1", "A1:B2"), region, facts);

        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactly("备注");
    }

    @Test
    void keepsRightEdgeInlineFieldsAndInfersBlankSignatureBands() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A23","mergedRange":"A23:E23","value":"包装物料："},
                  {"address":"F23","mergedRange":"F23:J23","value":"包装规格："},
                  {"address":"A27","value":"制单人："},
                  {"address":"D27","value":"完成人："},
                  {"address":"I27","value":"监管人："}
                ],"candidateCells":[
                  {"address":"A23","mergedRange":"A23:E23","value":"包装物料："},
                  {"address":"F23","mergedRange":"F23:J23","value":"包装规格："},
                  {"address":"A27","value":"制单人："},{"address":"C27","value":""},
                  {"address":"D27","value":"完成人："},{"address":"I27","value":"监管人："}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"FORM_REGION","sheetId":"s1","range":"A23:J28","structure":{}}
                """);

        var children = compiler.children(formParent("s1", "A23:J28"), region, facts);

        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactly("包装物料", "包装规格", "制单人", "完成人", "监管人");
        assertThat(children).extracting(item -> item.payload().path("locator")
                        .path("valueRange").asText())
                .containsExactly("A23:E23", "F23:J23", "B27:C27", "E27:H27", "J27:J27");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode formParent(String sheetId, String range) {
        var parent = objectMapper.createObjectNode().put("kind", "FORM_REGION")
                .put("relationId", "r1").put("fieldId", "f1").put("bindingId", "b1")
                .put("candidateRef", "r1").put("regionId", "r1").put("blockId", "r1");
        parent.putObject("locator").put("sheetId", sheetId).put("range", range);
        return parent;
    }

    @Test
    void compilesSimpleRowTableFieldsFromPhysicalHeadersWithoutSemanticModel() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A1","value":"物料名称"},{"address":"B1","value":"供应商"},
                  {"address":"A2","value":"树脂A"},{"address":"B2","value":"供应商1"}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"ROW_TABLE","sheetId":"s1","range":"A1:B3",
                 "structure":{"headerRange":"A1:B1","dataRange":"A2:B3","repeatAxis":"ROW"}}
                """);
        var parent = objectMapper.createObjectNode().put("kind", "ROW_TABLE")
                .put("relationId", "r1").put("fieldId", "f1").put("bindingId", "b1")
                .put("candidateRef", "r1").put("regionId", "r1").put("blockId", "r1")
                .put("candidateOnly", true).put("reviewRequired", true);
        parent.putObject("locator").put("sheetId", "s1").put("range", "A1:B3")
                .put("headerRange", "A1:B1").put("dataRange", "A2:B3");

        var children = compiler.children(parent, region, facts);

        assertThat(parent.path("columns")).hasSize(2);
        assertThat(children).hasSize(2);
        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactly("物料名称", "供应商");
        assertThat(children).allMatch(item ->
                "REPEAT_FIELD".equals(item.payload().path("mappingKind").asText())
                        && "ROW".equals(item.payload().path("repeatAxis").asText())
                        && item.payload().path("dataPath").asText()
                        .startsWith(parent.path("dataPath").asText() + "/*/"));
        assertThat(parent.path("tableModel").path("repeatAxis").asText()).isEqualTo("ROW");
        assertThat(parent.path("tableModel").path("columns")).hasSize(2);
    }

    @Test
    void compilesOneFieldForAMergedRowTableHeader() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A1","value":"序号"},
                  {"address":"B1","mergedRange":"B1:D1","value":"批号"},
                  {"address":"A2","value":1},
                  {"address":"B2","mergedRange":"B2:D2","value":""}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"ROW_TABLE","sheetId":"s1","range":"A1:D3",
                 "structure":{"headerRange":"A1:D1","dataRange":"A2:D3","repeatAxis":"ROW"}}
                """);
        var parent = objectMapper.createObjectNode().put("kind", "ROW_TABLE")
                .put("relationId", "r1").put("fieldId", "f1").put("bindingId", "b1")
                .put("candidateRef", "r1").put("regionId", "r1").put("blockId", "r1");
        parent.putObject("locator").put("sheetId", "s1").put("range", "A1:D3")
                .put("headerRange", "A1:D1").put("dataRange", "A2:D3");

        var children = compiler.children(parent, region, facts);

        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactly("序号", "批号");
        assertThat(children.get(1).payload().path("locator").path("labelRange").asText())
                .isEqualTo("B1:D1");
        assertThat(children.get(1).payload().path("locator").path("valueRange").asText())
                .isEqualTo("B2:D3");
    }

    @Test
    void givesIndependentComponentsDistinctRecordCollectionPaths() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A1","value":"属性"},{"address":"B1","value":"样品A"},
                  {"address":"A2","value":"粘度"},{"address":"B2","value":120}
                ]},{"id":"s2","semanticCells":[
                  {"address":"A1","value":"属性"},{"address":"B1","value":"样品B"},
                  {"address":"A2","value":"粘度"},{"address":"B2","value":130}
                ]}]}
                """);
        var firstRegion = objectMapper.readTree("""
                {"type":"COLUMN_TABLE","sheetId":"s1","range":"A1:B2",
                 "structure":{"headerRange":"A1:B1","dataRange":"A2:B2","repeatAxis":"COLUMN"}}
                """);
        var secondRegion = objectMapper.readTree("""
                {"type":"COLUMN_TABLE","sheetId":"s2","range":"A1:B2",
                 "structure":{"headerRange":"A1:B1","dataRange":"A2:B2","repeatAxis":"COLUMN"}}
                """);
        var first = tableParent("s1", "A1:B2", "first-relation", "first-binding");
        var second = tableParent("s2", "A1:B2", "second-relation", "second-binding");

        var firstChildren = compiler.children(first, firstRegion, facts);
        var secondChildren = compiler.children(second, secondRegion, facts);

        assertThat(first.path("dataPath").asText()).isNotEqualTo(second.path("dataPath").asText());
        assertThat(firstChildren).allMatch(item -> item.payload().path("dataPath").asText()
                .startsWith(first.path("dataPath").asText() + "/*/"));
        assertThat(secondChildren).allMatch(item -> item.payload().path("dataPath").asText()
                .startsWith(second.path("dataPath").asText() + "/*/"));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode tableParent(
            String sheetId, String range, String relationId, String bindingId
    ) {
        var parent = objectMapper.createObjectNode().put("kind", "COLUMN_TABLE")
                .put("relationId", relationId).put("fieldId", relationId).put("bindingId", bindingId)
                .put("candidateRef", relationId).put("regionId", relationId).put("blockId", relationId)
                .put("candidateOnly", false).put("reviewRequired", true);
        parent.putObject("locator").put("sheetId", sheetId).put("range", range)
                .put("headerRange", "A1:B1").put("dataRange", "A2:B2");
        return parent;
    }

    @Test
    void compilesColumnTableAttributesAndKeepsColumnRepeatGeometry() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A1","value":"属性"},{"address":"B1","value":"样品A"},{"address":"C1","value":"样品B"},
                  {"address":"A2","value":"物料名称"},{"address":"B2","value":"树脂A"},{"address":"C2","value":"树脂B"},
                  {"address":"A3","value":"密度"},{"address":"B3","value":1.05},{"address":"C3","value":1.02}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"COLUMN_TABLE","sheetId":"s1","range":"A1:C3",
                 "structure":{"headerRange":"A1:C1","dataRange":"A2:C3","repeatAxis":"COLUMN"}}
                """);
        var parent = objectMapper.createObjectNode().put("kind", "COLUMN_TABLE")
                .put("relationId", "r1").put("fieldId", "f1").put("bindingId", "b1")
                .put("candidateRef", "r1").put("regionId", "r1").put("blockId", "r1")
                .put("candidateOnly", true).put("reviewRequired", true);
        parent.putObject("locator").put("sheetId", "s1").put("range", "A1:C3")
                .put("headerRange", "A1:C1").put("dataRange", "A2:C3");

        var children = compiler.children(parent, region, facts);

        assertThat(children).hasSize(2);
        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactly("物料名称", "密度");
        assertThat(children).allMatch(item ->
                "COLUMN".equals(item.payload().path("repeatAxis").asText())
                        && item.payload().path("locator").path("valueRange").asText().contains(":"));
        assertThat(parent.path("tableModel").path("repeatAxis").asText()).isEqualTo("COLUMN");
        assertThat(parent.path("tableModel").path("columns")).hasSize(2);
    }

    @Test
    void keepsSampleIdentityRowInsideOneColumnRegion() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","semanticCells":[
                  {"address":"A4","mergedRange":"A4:B4","value":"引发剂"},
                  {"address":"C4","value":184},{"address":"D4","value":1173},{"address":"E4","value":2959},
                  {"address":"F4","value":"MBF"},{"address":"G4","value":"TPO"},{"address":"H4","value":"BP"},
                  {"address":"A5","mergedRange":"A5:A6","value":"UV固化性"},{"address":"B5","value":"耐油笔"},
                  {"address":"A7","value":"初始附着力"},{"address":"B7","value":"附着力"},
                  {"address":"A8","value":"水煮附着力"},{"address":"B8","value":"附着力"}
                ],"candidateCells":[
                  {"address":"A4","mergedRange":"A4:B4","value":"引发剂","hasBorder":true},
                  {"address":"C4","value":184,"hasBorder":true},{"address":"D4","value":1173,"hasBorder":true},
                  {"address":"E4","value":2959,"hasBorder":true},{"address":"F4","value":"MBF","hasBorder":true},
                  {"address":"G4","value":"TPO","hasBorder":true},{"address":"H4","value":"BP","hasBorder":true},
                  {"address":"A5","mergedRange":"A5:A6","value":"UV固化性","hasBorder":true},
                  {"address":"B5","value":"耐油笔","hasBorder":true},{"address":"A7","value":"初始附着力","hasBorder":true},
                  {"address":"B7","value":"附着力","hasBorder":true},{"address":"A8","value":"水煮附着力","hasBorder":true},
                  {"address":"B8","value":"附着力","hasBorder":true}
                ]}]}
                """);
        var region = objectMapper.readTree("""
                {"type":"COLUMN_TABLE","sheetId":"s1","range":"A4:H8",
                 "structure":{"headerRange":"A4:H4","dataRange":"A5:H8","repeatAxis":"COLUMN"}}
                """);
        var parent = tableParent("s1", "A4:H8", "identity-relation", "identity-binding");
        parent.putObject("locator").put("sheetId", "s1").put("range", "A4:H8")
                .put("dataRange", "A5:H8").put("headerRange", "A4:H4");

        var children = compiler.children(parent, region, facts);

        assertThat(children).extracting(item -> item.payload().path("fieldName").asText())
                .contains("引发剂", "耐油笔", "附着力");
        var identity = children.stream()
                .filter(item -> "引发剂".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow();
        assertThat(identity.payload().path("mappingKind").asText()).isEqualTo("REPEAT_FIELD");
        assertThat(identity.payload().path("locator").path("valueRange").asText()).isEqualTo("C4:H4");
        assertThat(identity.payload().path("locator").path("labelRange").asText()).isEqualTo("A4:B4");
        assertThat(identity.payload().path("locator").path("parentRange").asText()).isEqualTo("A4:H8");

        // Empty sample titles use the same physical grid and must keep the
        // identity field inside the column table instead of becoming a form
        // field or splitting the table into multiple regions.
        var blankFacts = (com.fasterxml.jackson.databind.node.ObjectNode) facts.deepCopy();
        for (var key : List.of("semanticCells", "candidateCells")) {
            for (var cell : blankFacts.path("sheets").get(0).withArray(key)) {
                if (cell.path("address").asText("").matches("[C-H]4")) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) cell).put("value", "");
                }
            }
        }
        var blankParent = tableParent("s1", "A4:H8", "blank-identity-relation", "blank-identity-binding");
        blankParent.putObject("locator").put("sheetId", "s1").put("range", "A4:H8")
                .put("dataRange", "A5:H8").put("headerRange", "A4:H4");
        var blankIdentity = compiler.children(blankParent, region, blankFacts).stream()
                .filter(item -> "引发剂".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow();
        assertThat(blankIdentity.payload().path("mappingKind").asText()).isEqualTo("REPEAT_FIELD");
        assertThat(blankIdentity.payload().path("locator").path("valueRange").asText()).isEqualTo("C4:H4");
    }
}
