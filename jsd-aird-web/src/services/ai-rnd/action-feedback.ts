import { HttpError } from '@/services/http/errors'

const messages: Record<string, string> = {
  MODEL_NOT_PRODUCTION_ELIGIBLE: '此模型尚未达到启用条件。请查看模型验证结果。',
  NO_ACTIVE_MODEL: '还没有启用模型。请到模型中心完成训练、检查结果并启用模型。',
  TRAINING_POLICY_NOT_APPROVED: '当前模型的训练配置不完整，请在建模工具中重新选择数据和 X/Y 后训练。',
  MATERIAL_DICTIONARY_REQUIRED: '该模型缺少材料信息，请在建模工具中重新训练并注册。',
  MATERIAL_DICTIONARY_NOT_FROZEN: '该模型的材料信息不完整，请在建模工具中重新训练并注册。',
  SOURCE_MAPPING_REQUIRED: '该模型缺少结果列信息，请在建模工具中重新选择 X/Y 并训练。',
  DATA_SCHEMA_INVALID: '模型字段信息不完整或数据类型不匹配，请在建模工具中检查 X/Y 并重新训练。',
  TARGET_VERSION_UNPUBLISHED: '该模型尚未完成注册，请在建模结果页注册后重试。',
  INPUT_SCHEME_EMPTY: '至少选择一项实验前能够确定的输入信息。',
  OUT_OF_DOMAIN: '这组实验条件超出已有记录范围，预测可能不准确。请核对数值和单位；确认无误后安排实验验证。',
  COMPUTE_UNAVAILABLE: '计算服务暂不可用，请稍后重试。',
  NO_FEASIBLE_CANDIDATE: '当前范围内没有有效的新方案。请核对 Y 的单位和 X 选择，并补充相近实验后重试。',
  INSUFFICIENT_DATA: '符合条件的完整实验记录不足，请补齐记录或调整 X/Y。',
  INVALID_PREDICTION_RESULT: '模型给出了不合理的结果，未展示数值。请核对输入和单位，并补充相近实验后重新训练。',
  RESOURCE_CONFLICT: '当前已有一项实验优化正在执行，请等待完成后再提交。',
  MODEL_ARTIFACT_UNAVAILABLE: '模型文件暂时不可用，请联系管理员检查模型状态。',
  MODEL_CONTRACT_MISMATCH: '模型输入设置已变化，请刷新页面后重试。',
  VERSION_CONFLICT: '配置版本已更新，请刷新后重新保存。',
  MODEL_VERSION_CHANGED: '模型版本已经更新，请刷新页面后重新提交。',
}

export function aiActionFeedback(error: unknown, fallback = '操作未完成，请重试') {
  const code = error instanceof HttpError ? error.code : error && typeof error === 'object' && 'code' in error && typeof error.code === 'string' ? error.code : ''
  if (code && messages[code]) return messages[code]
  if (error instanceof HttpError && ['SYS_VALIDATION_ERROR', 'VALIDATION_ERROR'].includes(code) && error.message) return error.message
  return fallback
}
