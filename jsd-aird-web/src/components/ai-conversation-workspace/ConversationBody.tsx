import { MarkdownContent } from '@/components/markdown/MarkdownContent';
import { StreamingMarkdownContent } from '@/components/markdown/StreamingMarkdownContent';

/** Both profiles render the same body; transport and citation mapping stay in adapters. */
export function ConversationBody({
  text,
  streaming = false,
}: {
  text: string;
  streaming?: boolean;
}) {
  return streaming ? <StreamingMarkdownContent value={text} /> : <MarkdownContent value={text} />;
}
