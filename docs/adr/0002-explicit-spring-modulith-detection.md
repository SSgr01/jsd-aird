# ADR-0002：Spring Modulith显式模块检测

## 状态

已接受。

## 决策

使用`explicitly-annotated`检测策略。只有带`@ApplicationModule`的包是应用模块。

## 影响

`bootstrap`和`platform`不会被误识别为业务模块。新增业务模块必须显式声明，并通过`ApplicationModules.verify()`。
