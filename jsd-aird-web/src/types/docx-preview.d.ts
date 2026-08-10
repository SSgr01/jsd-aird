declare module 'docx-preview' {
  export interface DocxPreviewOptions {
    breakPages?: boolean;
    renderHeaders?: boolean;
    renderFooters?: boolean;
    renderFootnotes?: boolean;
    renderEndnotes?: boolean;
    useBase64URL?: boolean;
  }

  export function renderAsync(
    blob: Blob,
    bodyContainer: HTMLElement,
    styleContainer: HTMLElement,
    options?: DocxPreviewOptions,
  ): Promise<unknown>;
}
