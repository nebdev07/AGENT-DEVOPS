package com.nebdev.devopsagent;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Monolithe modulaire (ADR-047), vérifié à chaque build :
 * <ul>
 *   <li>le moteur d'exécution (packages historiques) ne connaît pas le SaaS ;</li>
 *   <li>les modules SaaS n'ont pas de dépendance circulaire ;</li>
 *   <li>l'identité ne dépend d'aucun module métier ; la facturation, le Managed et l'IA ne pilotent pas SSH ;</li>
 *   <li>seul l'adaptateur {@code saas.engine} parle au moteur.</li>
 * </ul>
 */
class ArchitectureTest {

    static JavaClasses classes;

    @BeforeAll
    static void load() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.nebdev.devopsagent");
    }

    @Test
    void engineDoesNotDependOnSaas() {
        noClasses().that().resideInAnyPackage("..devopsagent.discovery..", "..devopsagent.stack..",
                        "..devopsagent.ssh..", "..devopsagent.audit..", "..devopsagent.risk..", "..devopsagent.state..",
                        "..devopsagent.platform..", "..devopsagent.api..", "..devopsagent.journal..")
                .should().dependOnClassesThat().resideInAPackage("..devopsagent.saas..")
                .check(classes);
    }

    @Test
    void saasModulesAreFreeOfCycles() {
        slices().matching("com.nebdev.devopsagent.saas.(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    void identityDependsOnNoBusinessModule() {
        noClasses().that().resideInAPackage("..saas.identity..")
                .should().dependOnClassesThat().resideInAnyPackage("..saas.server..", "..saas.operation..",
                        "..saas.billing..", "..saas.managed..", "..saas.ai..", "..saas.engine..")
                .orShould().dependOnClassesThat().resideInAPackage("..saas.organization..")
                .check(classes);
    }

    @Test
    void onlyTheEngineAdapterTalksToTheExecutionEngine() {
        noClasses().that().resideInAPackage("..saas..").and().resideOutsideOfPackage("..saas.engine..")
                .and().resideOutsideOfPackage("..saas.credential..")
                .and().resideOutsideOfPackage("..saas.deployment..")
                .should().dependOnClassesThat().resideInAnyPackage("..devopsagent.ssh..", "..devopsagent.stack..",
                        "..devopsagent.discovery..")
                .check(classes);
    }

    @Test
    void commercialModulesNeverReachSsh() {
        noClasses().that().resideInAnyPackage("..saas.billing..", "..saas.managed..", "..saas.ai..")
                .should().dependOnClassesThat().resideInAnyPackage("..devopsagent.ssh..", "..saas.engine..")
                .check(classes);
    }
}
