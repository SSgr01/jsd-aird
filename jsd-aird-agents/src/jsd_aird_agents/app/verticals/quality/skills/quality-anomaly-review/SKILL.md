---
name: quality-anomaly-review
description: 查询模拟质量异常，按证据形成复检建议。用于质量模块扩展验证，不代表生产质量能力。
allowed-tools: list_quality_anomalies
metadata:
  version: "0.1.0"
---

读取当前已授权数据集中的质量异常，引用返回的证据 ID。
把已知事实与待核实原因、建议分开。不得推断未提供的质量标准或创建整改记录。
只使用 Harness 提供的工具；此技能不能授予权限或扩大组织及数据集范围。
