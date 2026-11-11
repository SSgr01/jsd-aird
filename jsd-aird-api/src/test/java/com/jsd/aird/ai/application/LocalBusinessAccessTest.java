package com.jsd.aird.ai.application;

import com.jsd.aird.shared.business.BusinessReadPort;
import com.jsd.aird.shared.security.Actor;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LocalBusinessAccessTest {
    private static final String ENABLED = "app.ai.harness.business-local-access=true";
    private final BusinessReadPort reader = mock(BusinessReadPort.class);
    private final Actor admin = new Actor(UUID.randomUUID(), UUID.randomUUID(), "local-admin", "SYSTEM_ADMIN");

    private ApplicationContextRunner context(String... profiles) {
        when(reader.resourceTypes()).thenReturn(Set.of("SHIPMENT", "QUALITY_COA"));
        return new ApplicationContextRunner().withUserConfiguration(LocalBusinessAccess.class)
            .withBean(BusinessReadPort.class, () -> reader)
            .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
    }

    @Test void localConnectionRequiresAnExplicitSwitch() {
        context("local").run(context -> assertThat(context).doesNotHaveBean(LocalBusinessAccess.class));
        context("local").withPropertyValues(ENABLED).run(context -> assertThat(context).hasSingleBean(LocalBusinessAccess.class));
    }

    @Test void productionAndNonlocalProfilesNeverInstallTheFallback() {
        for (var profiles : List.of(new String[]{}, new String[]{"prod"}, new String[]{"local", "prod"}))
            context(profiles).withPropertyValues(ENABLED).run(context -> assertThat(context).doesNotHaveBean(LocalBusinessAccess.class));
    }

    @Test void localAdminCanReadExportAndUseExistingComputationsButCannotWriteBusinessRecords() {
        context("local").withPropertyValues(ENABLED).run(context -> {
            var access = context.getBean(LocalBusinessAccess.class);
            assertThat(access.allowed(admin, "SHIPMENT", "SEARCH")).isTrue();
            assertThat(access.allowed(admin, "QUALITY_COA", "READ")).isTrue();
            assertThat(access.allowed(admin, "QUALITY_COA", "EXPORT")).isTrue();
            assertThat(access.allowed(admin, "AI_PREDICTION", "EXECUTE")).isTrue();
            assertThat(access.allowed(admin, "AI_FORMULA_DESIGN", "READ")).isTrue();
            assertThat(access.allowed(admin, "AI_EXPERIMENT_OPTIMIZATION", "EXECUTE")).isTrue();
            assertThat(access.allowed(admin, "SHIPMENT", "WRITE")).isFalse();
            assertThat(access.allowed(admin, "SHIPMENT", "EXECUTE")).isFalse();
            assertThat(access.allowed(admin, "UNREGISTERED", "READ")).isFalse();
        });
    }

    @Test void missingIdentityAndNonadministratorUsersRemainClosed() {
        context("local").withPropertyValues(ENABLED).run(context -> {
            var access = context.getBean(LocalBusinessAccess.class);
            assertThat(access.allowed(null, "SHIPMENT", "READ")).isFalse();
            assertThat(access.allowed(new Actor(null, admin.userId(), "incomplete", "SYSTEM_ADMIN"), "SHIPMENT", "READ")).isFalse();
            assertThat(access.allowed(new Actor(admin.organizationId(), null, "incomplete", "SYSTEM_ADMIN"), "SHIPMENT", "READ")).isFalse();
            assertThat(access.allowed(new Actor(admin.organizationId(), admin.userId(), "member", "MEMBER"), "SHIPMENT", "READ")).isFalse();
            assertThat(access.allowed(new Actor(admin.organizationId(), admin.userId(), "legacy-label", "ADMIN"), "SHIPMENT", "READ")).isFalse();
        });
    }
}
