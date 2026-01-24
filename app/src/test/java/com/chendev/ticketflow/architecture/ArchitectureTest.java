package com.chendev.ticketflow.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

// ArchUnit rules that keep domain boundaries honest. Production code only.
// user and security are excluded from cycle checks, security is cross-cutting infrastructure, not a bounded context.
@AnalyzeClasses(
        packages = "com.chendev.ticketflow",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule no_business_domain_cycles =
            slices().matching("com.chendev.ticketflow.(order|event|inventory)..")
                    .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule entities_must_not_depend_on_infrastructure =
            noClasses().that().resideInAnyPackage(
                            "..order.entity..", "..event.entity..",
                            "..inventory.entity..", "..user.entity..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule no_cross_domain_service_dependencies =
            slices().matching("com.chendev.ticketflow.(*).service..")
                    .should().notDependOnEachOther();

    @ArchTest
    static final ArchRule domain_services_must_not_depend_on_infrastructure =
            noClasses().that().resideInAnyPackage(
                            "..order.service..", "..event.service..",
                            "..inventory.service..", "..user.service..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure..");
}