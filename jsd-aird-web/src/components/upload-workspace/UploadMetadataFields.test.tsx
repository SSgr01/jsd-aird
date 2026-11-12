import { fireEvent, render, screen } from '@testing-library/react';
import { Form, Input } from 'antd';
import { useState } from 'react';
import { UploadMetadataFields } from './UploadMetadataFields';
import { useDesktopLayout } from '@/hooks/use-desktop-layout';

vi.mock('@/hooks/use-desktop-layout', () => ({ useDesktopLayout: vi.fn() }));

describe('UploadMetadataFields', () => {
  afterEach(() => vi.clearAllMocks());

  it('keeps optional metadata mounted and preserves it while toggling the section', () => {
    vi.mocked(useDesktopLayout).mockReturnValue(true);
    function Example() {
      const [source, setSource] = useState('供应商资料');
      return <UploadMetadataFields primaryLabels={['权限可见']} summary="来源信息"><Form component={false}>
        <Form.Item label="来源"><Input aria-label="资料来源" value={source} onChange={event => setSource(event.target.value)} /></Form.Item>
        <Form.Item label="权限可见"><Input aria-label="权限可见" value="研发部可见" readOnly /></Form.Item>
      </Form></UploadMetadataFields>;
    }
    render(<Example />);
    expect(screen.getByRole('textbox', { name: '权限可见' }).closest('.upload-workspace-required-fields')).toBeTruthy();
    const details = document.querySelector('details')!;
    details.open = true;
    fireEvent.change(screen.getByRole('textbox', { name: '资料来源' }), { target: { value: '客户提供' } });
    details.open = false;
    details.open = true;
    expect(screen.getByRole('textbox', { name: '资料来源' })).toHaveValue('客户提供');
  });

  it('retains the form order on smaller screens', () => {
    vi.mocked(useDesktopLayout).mockReturnValue(false);
    render(<UploadMetadataFields primaryLabels={['权限可见']} summary="来源信息"><Form component={false}>
      <Form.Item label="来源"><Input aria-label="资料来源" /></Form.Item>
      <Form.Item label="权限可见"><Input aria-label="权限可见" /></Form.Item>
    </Form></UploadMetadataFields>);
    expect(screen.getAllByRole('textbox').map(input => input.getAttribute('aria-label'))).toEqual(['资料来源', '权限可见']);
    expect(document.querySelector('details')).toBeNull();
  });
});
