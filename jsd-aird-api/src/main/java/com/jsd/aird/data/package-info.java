@org.springframework.modulith.ApplicationModule(
        displayName = "数据中心",
        allowedDependencies = {"shared", "ops::api", "tpl::api", "kb::api", "core::api", "iam::api", "rnd::api"}
)
package com.jsd.aird.data;
