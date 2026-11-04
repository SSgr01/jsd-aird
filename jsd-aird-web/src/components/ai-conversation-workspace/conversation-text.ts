/** Hide presentation metadata, keeping the stored response and citation bindings intact. */
export function cleanConversationText(value: string): string {
  let fence: string | undefined;
  return value
    .split('\n')
    .map((line) => {
      const marker = line.match(/^\s*(`{3,}|~{3,})/);
      const token = marker?.[1];
      if (token) {
        if (!fence) fence = token;
        else if (token[0] === fence[0] && token.length >= fence.length) fence = undefined;
        return line;
      }
      if (fence) return line;
      const text = line.replace(/&(?:#x0*20|#0*32|nbsp);/gi, ' ');
      // Only a standalone status field is redundant. Explanations about incomplete work stay.
      if (
        /^\s*(?:[-*]\s+)?(?:\*\*)?任务状态(?:\*\*)?\s*[：:]\s*(?:\*\*)?`?(?:阶段性结果|部分完成|已完成|需要补充|COMPLETE|PARTIAL|NEEDS_INPUT)`?(?:\*\*)?\s*(?:[（(][^）)\n]*[）)])?\s*$/.test(
          text,
        )
      )
        return '';
      return text
        .replace(/[（(]\s*(?:\*\*)?证据\s*ID\s*[：:]\s*`?[a-zA-Z0-9_-]+`?(?:\*\*)?\s*[）)]/gi, '')
        .replace(/(?:\*\*)?证据\s*ID(?:\*\*)?\s*[：:]\s*`?[a-zA-Z0-9_-]+`?/gi, '')
        .replace(/`(?:[a-f0-9]{20,64}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})`/gi, '');
    })
    .join('\n')
    .trim();
}
