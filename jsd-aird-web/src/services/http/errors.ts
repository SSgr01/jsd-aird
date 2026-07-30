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
