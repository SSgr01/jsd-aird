package com.jsd.aird;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.jsd.aird")
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_INDEPENDENT = noClasses()
            .that().resideInAnyPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..",
                    "..adapter..",
                    "..infrastructure..",
                    "org.springframework..",
                    "com.baomidou..",
                    "org.apache.ibatis.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule APPLICATION_DOES_NOT_USE_ADAPTERS_OR_IMPLEMENTATIONS = noClasses()
            .that().resideInAnyPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..",
                    "..infrastructure..",
                    "com.baomidou..",
                    "org.apache.ibatis.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule INBOUND_ADAPTERS_DO_NOT_USE_INFRASTRUCTURE_DIRECTLY = noClasses()
            .that().resideInAnyPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..")
            .allowEmptyShould(true);
}

