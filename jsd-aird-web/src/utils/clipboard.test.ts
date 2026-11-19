import { copyText } from './clipboard';

const clipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
const execCommand = Object.getOwnPropertyDescriptor(document, 'execCommand');

afterEach(() => {
  if (clipboard) Object.defineProperty(navigator, 'clipboard', clipboard);
  else Reflect.deleteProperty(navigator, 'clipboard');
  if (execCommand) Object.defineProperty(document, 'execCommand', execCommand);
  else Reflect.deleteProperty(document, 'execCommand');
  document.body.replaceChildren();
});

it('uses native clipboard when available', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
  await copyText('回答');
  expect(writeText).toHaveBeenCalledWith('回答');
});

it.each([undefined, { writeText: vi.fn().mockRejectedValue(new Error('denied')) }])(
  'copies on HTTP or permission denial and restores focus and selection', async value => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value });
    const button = document.createElement('button');
    document.body.append(button); button.focus();
    const command = vi.fn(() => {
      expect((document.activeElement as HTMLTextAreaElement).value).toBe('回答');
      return true;
    });
    Object.defineProperty(document, 'execCommand', { configurable: true, value: command });
    await copyText('回答');
    expect(command).toHaveBeenCalledWith('copy');
    expect(document.activeElement).toBe(button);
    expect(document.querySelector('textarea')).toBeNull();
  },
);

it('reports unavailable copying and cleans up temporary elements', async () => {
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
  Object.defineProperty(document, 'execCommand', { configurable: true, value: () => false });
  await expect(copyText('回答')).rejects.toThrow('请选中文字复制');
  expect(document.querySelector('textarea')).toBeNull();
});
