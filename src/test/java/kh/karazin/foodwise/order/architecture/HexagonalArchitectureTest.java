package kh.karazin.foodwise.order.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Hexagonal architecture rules for this service — the project-wide template
 * (see ADR 0014; calibrated on the fw-cart-service pilot). To reuse it in
 * another service, copy it and change {@link #BASE_PACKAGE}.
 *
 * <p>Enforced rules:
 * <ul>
 *   <li>{@code domain} is pure Java — no Spring, JPA, Jackson, and no dependency
 *       on {@code application} / {@code adapter} / {@code config}. The only
 *       sanctioned exception is {@code Money} from fw-common: the rule checks
 *       direct dependencies, and Money's {@code @Embeddable} is a dependency of
 *       Money itself, not of domain classes.</li>
 *   <li>{@code application} never depends on {@code adapter} / {@code config}.</li>
 *   <li>Web controllers, Kafka listeners and JPA entities exist only inside
 *       their respective adapter packages.</li>
 * </ul>
 */
class HexagonalArchitectureTest {

    private static final String BASE_PACKAGE = "kh.karazin.foodwise.order";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
    }

    @Test
    void domain_does_not_depend_on_frameworks_or_outer_layers() {
        noClasses().that().resideInAPackage(BASE_PACKAGE + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..",
                        "tools.jackson..",
                        BASE_PACKAGE + ".application..",
                        BASE_PACKAGE + ".adapter..",
                        BASE_PACKAGE + ".config..")
                .because("the domain model must stay pure Java, framework-free and inner-layer only")
                .check(productionClasses);
    }

    @Test
    void application_does_not_depend_on_adapters() {
        noClasses().that().resideInAPackage(BASE_PACKAGE + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE_PACKAGE + ".adapter..",
                        BASE_PACKAGE + ".config..")
                .because("use cases talk to the outside world only through ports")
                .check(productionClasses);
    }

    @Test
    void rest_controllers_live_only_in_adapters() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage(BASE_PACKAGE + ".adapter.in.rest..")
                .because("HTTP is a driving adapter concern")
                .check(productionClasses);
    }

    @Test
    void kafka_listeners_live_only_in_adapters() {
        methods().that().areAnnotatedWith(KafkaListener.class)
                .should().beDeclaredInClassesThat().resideInAPackage(BASE_PACKAGE + ".adapter.in.kafka..")
                .because("messaging is a driving adapter concern")
                .check(productionClasses);
    }

    @Test
    void persistence_entities_live_only_in_adapters() {
        classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideInAPackage(BASE_PACKAGE + ".adapter.out.persistence..")
                .because("JPA is a driven adapter concern")
                .check(productionClasses);
    }
}
