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

/** 从未知错误中提取可读消息（优先使用后端返回的 message），失败时回退到 fallback。 */
export function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}
