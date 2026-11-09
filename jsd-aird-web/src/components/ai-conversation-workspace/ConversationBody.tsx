import { StreamingMarkdownContent } from '@/components/markdown/StreamingMarkdownContent';
import { cleanConversationText } from './conversation-text';

/** Both profiles render the same body; transport and citation mapping stay in adapters. */
export function ConversationBody({
  text,
  streaming = false,
}: {
  text: string;
  streaming?: boolean;
}) {
  const displayText = cleanConversationText(text);
  return <StreamingMarkdownContent value={displayText} isStreaming={streaming} />;
}
