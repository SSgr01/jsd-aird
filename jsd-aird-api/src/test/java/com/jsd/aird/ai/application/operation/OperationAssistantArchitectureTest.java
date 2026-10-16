package com.jsd.aird.ai.application.operation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class OperationAssistantArchitectureTest {
    @Test
    void operationHelpUsesPublicCrossModuleInterfacesAndApplicationPorts() {
        var classes = new ClassFileImporter().withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.jsd.aird.ai.application.operation");
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                "..infrastructure..", "..adapter..", "com.jsd.aird.kb.application..", "com.jsd.aird.kb.domain..",
                "com.jsd.aird.iam.application..", "com.jsd.aird.data.application..").check(classes);
    }
}
