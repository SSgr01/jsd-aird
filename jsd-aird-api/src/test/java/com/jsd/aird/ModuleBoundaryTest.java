package com.jsd.aird;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTest {

    @Test
    void verifiesApplicationModuleBoundaries() {
        ApplicationModules.of(JsdAirdApplication.class).verify();
    }
}

