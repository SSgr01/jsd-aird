export class HttpError extends Error {
  public constructor(
    message: string,
    public readonly code: string,
    public readonly status?: number,
    public readonly traceId?: string,
  ) {
    super(message);
    this.name = 'HttpError';
  }
}

/** 页面初始化查询没有可见数据时，将拒绝结果按正常空状态处理。 */
export function isAccessDenied(error: unknown): boolean {
  return error instanceof HttpError && error.status === 403 && error.code !== 'CSRF_TOKEN_INVALID';
}

export function httpErrorMessage(status: number | undefined, code: string | undefined, backendMessage: string | undefined, transportMessage: string | undefined): string {
  if (status === 403 && code !== 'CSRF_TOKEN_INVALID') {
    return backendMessage && !/^request failed with status code 403$/i.test(backendMessage)
      ? backendMessage
      : '当前用户没有操作权限';
  }
  return backendMessage || transportMessage || '请求失败';
}

/** 从未知错误中提取可读消息（优先使用后端返回的 message），失败时回退到 fallback。 */
export function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}
