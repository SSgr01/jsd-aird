import { HttpError } from '@/services/http/errors'

type Failure = { code?: string; message?: string }

const messages: Record<string, string> = {
  COMPUTE_UNAVAILABLE: '计算服务暂不可用，请稍后重试。',
  TRAINING_TIMEOUT: '训练用时过长，请减少 X 列或实验记录后重试。',
  AI_MODEL_ARTIFACT_INVALID: '训练文件无法保存或读取，请联系管理员检查训练服务。',
  FILE_NOT_READY: '训练文件暂时无法读取，请稍后重试。',
  INVALID_SCHEMA: '训练数据或结果未通过检查，请核对 X/Y 的数值、单位和缺失记录后重试。',
  NOT_FOUND: '这份数据或训练记录已不存在或无法读取，请返回重新选择数据。',
  FORBIDDEN: '当前账号没有访问权限，请联系管理员。',
  PERMISSION_DENIED: '当前账号没有训练权限，请联系管理员开通后重试。',
  UNAUTHORIZED: '登录已过期，请重新登录。',
  CSRF_TOKEN_INVALID: '页面状态已过期，请刷新后重新点击开始训练；已选数据和 X/Y 会保留。',
  HTTP_REQUEST_FAILED: '服务连接中断，请稍后重试。',
  INTERNAL_ERROR: '训练请求暂时无法提交，系统发生异常。请稍后重试；已选数据和 X/Y 会保留。',
}

export function workbenchFailureMessage(error: Failure | null | undefined, fallback = '训练没有完成，请检查 X/Y 和实验记录后重新训练。') {
  const code = error?.code?.startsWith('SYS_') ? error.code.slice(4) : error?.code
  if (code === 'VALIDATION_ERROR' && error?.message) return error.message
  return messages[code ?? ''] ?? fallback
}

export function workbenchRequestMessage(error: unknown) {
  if (error instanceof HttpError) return workbenchFailureMessage(error, '操作未完成，请刷新后重试。')
  return '服务连接中断，请稍后重试。'
}


export function workbenchRegistrationMessage(error: unknown) {
  if (error instanceof HttpError) {
    const code = error.code.startsWith('SYS_') ? error.code.slice(4) : error.code
    if (['FILE_NOT_READY', 'INVALID_SCHEMA', 'AI_MODEL_ARTIFACT_INVALID'].includes(code)) return '模型文件无法读取或未通过检查，请重新训练后再注册。'
    if (['FORBIDDEN', 'PERMISSION_DENIED', 'OPERATION_FORBIDDEN'].includes(code)) return '当前账号没有注册模型的权限。'
  }
  return workbenchRequestMessage(error)
}
