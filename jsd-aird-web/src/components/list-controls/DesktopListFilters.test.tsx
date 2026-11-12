import { fireEvent, render, screen } from '@testing-library/react';
import { Button, Input, Space } from 'antd';
import { useState } from 'react';
import { DesktopListFilters } from './DesktopListFilters';
import { useDesktopLayout } from '@/hooks/use-desktop-layout';

vi.mock('@/hooks/use-desktop-layout', () => ({ useDesktopLayout: vi.fn() }));

describe('DesktopListFilters', () => {
  afterEach(() => vi.clearAllMocks());

  it('keeps original search, advanced values and reset callbacks when filters are collapsed', () => {
    vi.mocked(useDesktopLayout).mockReturnValue(true);
    const searchChanged = vi.fn();
    const reset = vi.fn();
    function Example() {
      const [owner, setOwner] = useState('陈晓');
      return <DesktopListFilters labels={['负责人']} advancedAfter={0}><Space>
        <Input aria-label="搜索名称" value="" onChange={searchChanged} />
        <Input aria-label="负责人" value={owner} onChange={event => setOwner(event.target.value)} />
        <Button onClick={reset}>重置</Button>
      </Space></DesktopListFilters>;
    }
    render(<Example />);
    fireEvent.change(screen.getByRole('textbox', { name: '搜索名称' }), { target: { value: '树脂' } });
    expect(searchChanged).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { expanded: false }));
    fireEvent.change(screen.getByRole('textbox', { name: '负责人' }), { target: { value: '李明' } });
    fireEvent.click(screen.getByRole('button', { expanded: true }));
    expect(screen.getByRole('textbox', { name: '负责人' })).toHaveValue('李明');
    expect(screen.getByText('负责人：李明')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /重\s*置/ }));
    expect(reset).toHaveBeenCalledTimes(1);
  });

  it('keeps the original layout on smaller screens', () => {
    vi.mocked(useDesktopLayout).mockReturnValue(false);
    render(<DesktopListFilters><div data-testid="original-layout"><Input aria-label="名称" /><Button>重置</Button></div></DesktopListFilters>);
    expect(screen.getByTestId('original-layout')).toBeInTheDocument();
    expect(document.querySelector('.list-filter-bar')).toBeNull();
  });
});
