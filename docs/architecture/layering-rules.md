# 模块内部分层规则

Spring Modulith负责模块发现、循环依赖、模块内部访问和显式模块依赖校验。模块内部的分层方向由项目约定和ArchUnit测试共同约束。

## 推荐依赖方向

```text
adapter/in -> application -> domain
infrastructure -> application/domain 定义的 Port 或 Repository
```

必须满足：

- `domain` 不依赖 `application`、`adapter`、`infrastructure`、Spring 或 MyBatis。
- `application` 不依赖具体 Mapper、数据库实现或其他 `infrastructure` 类型。
- `adapter/in` 只调用应用用例，不直接调用 Mapper。
- `infrastructure` 实现 `application` 或 `domain` 定义的出站接口。
- 其他模块不得访问本模块的内部层次。

## 分层按复杂度使用

`rnd`、`quality`、`mfg`、`tpl`、`mdm` 等规则密集模块可采用完整分层。简单查询、导出、运维或配置模块允许省略 `domain`。

禁止为了目录完整而创建没有真实职责的 `DomainService`、`Factory`、`Repository`、`Converter` 或事件。

项目不创建 `adapter/out`，避免与 `infrastructure` 重复。数据库、对象存储、消息和外部客户端实现统一放入所属模块的 `infrastructure`。
