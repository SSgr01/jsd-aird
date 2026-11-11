@ApplicationModule(
        displayName = "生产与库存",
        allowedDependencies = {"quality::api", "mdm::api", "kb::api", "iam::api", "shared", "ops::api", "tpl::api"}
)
package com.jsd.aird.mfg;

import org.springframework.modulith.ApplicationModule;
