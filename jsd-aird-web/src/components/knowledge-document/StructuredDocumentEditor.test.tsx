import { render, screen, waitFor } from '@testing-library/react';
import type { StructuredDocument } from '@/services/knowledge';
import { StructuredDocumentEditor } from './StructuredDocumentEditor';

it('does not report content edits when approval or permissions change editability', async () => {
  const onChange = vi.fn();
  const value: StructuredDocument = { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '涂层固化条件' }] }] };
  const { rerender } = render(<StructuredDocumentEditor value={value} onChange={onChange} />);
  await screen.findByText('涂层固化条件');
  const editor = screen.getByLabelText('完整解析结果');
  await waitFor(() => expect(editor).toHaveAttribute('contenteditable', 'true'));
  onChange.mockClear();
  rerender(<StructuredDocumentEditor value={value} onChange={onChange} disabled />);
  await waitFor(() => expect(editor).toHaveAttribute('contenteditable', 'false'));
  expect(onChange).not.toHaveBeenCalled();
  rerender(<StructuredDocumentEditor value={value} onChange={onChange} />);
  await waitFor(() => expect(editor).toHaveAttribute('contenteditable', 'true'));
  expect(onChange).not.toHaveBeenCalled();
  expect(screen.getByText('涂层固化条件')).toBeInTheDocument();
});
