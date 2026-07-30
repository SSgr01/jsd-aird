# 业务模块模板

以下结构是复杂模块在出现首个真实用例时的参考，不在初始化阶段批量创建空目录：

```text
rnd
├── package-info.java
├── api
│   ├── package-info.java
│   ├── RndFacade.java
│   ├── dto
│   └── event
├── adapter
│   └── in
│       ├── web
│       ├── event
│       └── file
├── application
│   ├── command
│   ├── query
│   ├── service
│   └── port
├── domain
│   ├── model
│   ├── service
│   ├── event
│   └── repository
└── infrastructure
    ├── persistence
    │   ├── dataobject
    │   ├── mapper
    │   ├── repository
    │   ├── converter
    │   └── typehandler
    ├── storage
    ├── messaging
    └── client
```

模块公开接口包必须显式声明：

```java
@NamedInterface("api")
package com.jsd.aird.rnd.api;

import org.springframework.modulith.NamedInterface;
```

依赖该接口的模块在自身 `@ApplicationModule` 中声明：

```java
@ApplicationModule(allowedDependencies = {"shared", "rnd::api"})
```

HTTP Controller、事件监听器和文件上传入口属于 `adapter/in`，不等同于跨模块公开 API。
