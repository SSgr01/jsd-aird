# ADR-0003：MyBatis-Plus持久化

## 状态

已接受。

## 决策

业务持久化采用MyBatis-Plus。Mapper、DO、Repository实现、Converter和TypeHandler必须位于所属业务模块的`infrastructure/persistence`。

## 约束

禁止将所有业务Mapper集中到全局技术包。应用层和领域层不得依赖具体Mapper。
