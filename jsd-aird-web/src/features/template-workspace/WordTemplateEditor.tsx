import { BoldOutlined, ItalicOutlined, UnderlineOutlined } from '@ant-design/icons';
import { Button, Divider, Input, Select, Tag, Tooltip } from 'antd';
import { forwardRef, useEffect, useImperativeHandle, useMemo, useState } from 'react';

import type { BindingRole, DocumentStructure, EditorHandle, TemplateBinding } from './types';

interface Props {
  snapshot: Record<string, unknown>;
  documentStructure?: DocumentStructure;
  editable: boolean;
  onDirty: () => void;
  onEditorValue: (binding: TemplateBinding, value: unknown) => void;
  bindings: TemplateBinding[];
  onPatch?: (operation: WordPatchOperation) => void;
}

export interface WordPatchOperation {
  type:
    | 'INSERT_CONTENT_CONTROL'
    | 'REPLACE_CONTENT_CONTROL'
    | 'REPLACE_TEXT'
    | 'REPLACE_TABLE_CELL'
    | 'SET_RUN_STYLE'
    | 'SET_PARAGRAPH_ALIGNMENT'
    | 'SET_PARAGRAPH_STYLE'
    | 'ADD_TABLE_ROW'
    | 'DELETE_TABLE_ROW';
  targetId: string;
  text?: string;
  alignment?: string;
  bold?: boolean;
  italic?: boolean;
  underline?: boolean;
  color?: string;
  fontSize?: number;
  fontFamily?: string;
  rowIndex?: number;
  baseText?: string;
  baseStructureHash?: string;
  markerId?: string;
  tag?: string;
  alias?: string;
  role?: BindingRole;
  dataPath?: string;
}

export const WordTemplateEditor = forwardRef<EditorHandle, Props>(function WordTemplateEditor(
  { snapshot, documentStructure, editable, onDirty, onEditorValue, onPatch },
  ref,
) {
  const [selectedControl, setSelectedControl] = useState<string>();
  const [selectedBlock, setSelectedBlock] = useState<string>();
  const [controlDraft, setControlDraft] = useState('');
  const controls = documentStructure?.contentControls ?? [];
  const blocks = documentStructure?.blocks ?? [];
  const selected = useMemo(
    () => controls.find((item) => item.nodeId === selectedControl),
    [controls, selectedControl],
  );
  const selectedBlockModel = useMemo(
    () => blocks.find((item) => item.id === selectedBlock),
    [blocks, selectedBlock],
  );

  useEffect(() => setControlDraft(selected?.text ?? ''), [selected]);

  const emit = (operation: WordPatchOperation) => {
    onPatch?.({
      ...operation,
      baseStructureHash: operation.baseStructureHash ?? documentStructure?.structureHash,
    });
    onDirty();
  };

  useImperativeHandle(ref, () => ({
    getSnapshot: () => snapshot,
    readBinding: (binding) => controls.find((item) => item.nodeId === binding.markerId)?.text,
    writeBinding: (binding, value) => {
      const markerId = binding.markerId ?? binding.locator?.nodeId as string | undefined;
      if (markerId) {
        const text = typeof value === 'string' ? value : JSON.stringify(value ?? '') ?? '';
        emit({ type: 'REPLACE_CONTENT_CONTROL', targetId: markerId, text });
        onEditorValue(binding, value);
      }
      return Promise.resolve();
    },
    focusBinding: (binding) => setSelectedControl(binding.markerId),
    insertWordControl: (role: BindingRole, fieldCode: string) => {
      return Promise.reject(
        new Error('原生 Word 内容控件需在 DOCX 中预先创建（' + role + ':' + fieldCode + '）'),
      );
    },
  }), [controls, onDirty, onEditorValue, snapshot]);

  return (
    <div className="word-template-editor" aria-label="原生 Word 模板编辑器">
      <div className="word-template-toolbar">
        <Tooltip title={editable ? '加粗' : '只读'}><Button disabled={!editable || !selected} icon={<BoldOutlined />} onClick={() => selected && emit({ type: 'SET_RUN_STYLE', targetId: selected.nodeId, bold: true })} /></Tooltip>
        <Tooltip title="斜体"><Button disabled={!editable || !selected} icon={<ItalicOutlined />} onClick={() => selected && emit({ type: 'SET_RUN_STYLE', targetId: selected.nodeId, italic: true })} /></Tooltip>
        <Tooltip title="下划线"><Button disabled={!editable || !selected} icon={<UnderlineOutlined />} onClick={() => selected && emit({ type: 'SET_RUN_STYLE', targetId: selected.nodeId, underline: true })} /></Tooltip>
        <Select
          size="small"
          placeholder="字号"
          disabled={!editable || !selected}
          options={[10, 11, 12, 14, 16, 18, 20, 24].map((value) => ({ label: `${value}pt`, value }))}
          onChange={(value: number) => selected && emit({ type: 'SET_RUN_STYLE', targetId: selected.nodeId, fontSize: value })}
          style={{ width: 82 }}
        />
        <Input
          aria-label="字体颜色"
          type="color"
          disabled={!editable || !selected}
          onChange={(event) => selected && emit({ type: 'SET_RUN_STYLE', targetId: selected.nodeId, color: event.target.value })}
          style={{ width: 38, padding: 2 }}
        />
        <Button disabled={!editable || !selectedBlockModel} onClick={() => selectedBlockModel && emit({ type: 'SET_PARAGRAPH_STYLE', targetId: selectedBlockModel.id, alignment: 'left' })}>左对齐</Button>
        <Button disabled={!editable || !selectedBlockModel} onClick={() => selectedBlockModel && emit({ type: 'SET_PARAGRAPH_STYLE', targetId: selectedBlockModel.id, alignment: 'center' })}>居中</Button>
        <Button disabled={!editable || !selectedBlockModel} onClick={() => selectedBlockModel && emit({ type: 'SET_PARAGRAPH_STYLE', targetId: selectedBlockModel.id, alignment: 'right' })}>右对齐</Button>
        <Divider type="vertical" />
        <Tag color={editable ? 'processing' : 'default'}>{editable ? '受控原生编辑' : '只读预览'}</Tag>
      </div>
      <div className="word-template-page">
        {blocks.map((block) => (
          <div key={block.id} className={'word-template-block word-' + block.type.toLowerCase()}>
            {block.type === 'TABLE' ? (
              <div className="word-template-table" onClick={() => { setSelectedBlock(block.id); setSelectedControl(undefined); }}>
                {(block.rows ?? []).map((row) => (
                  <div key={row.id} className="word-template-table-row">
                    {(row.cells ?? []).map((cell) => (
                      <Input
                        key={cell.id}
                        size="small"
                        disabled={!editable || cell.editable === false}
                        defaultValue={cell.text}
                        onBlur={(event) => emit({ type: 'REPLACE_TABLE_CELL', targetId: cell.id, text: event.target.value, baseText: cell.text })}
                      />
                    ))}
                    <Button
                      size="small"
                      danger
                      disabled={!editable || (block.rows?.length ?? 0) <= 1}
                      onClick={() => emit({ type: 'DELETE_TABLE_ROW', targetId: block.id, rowIndex: block.rows?.findIndex((item) => item.id === row.id) })}
                    >
                      删除行
                    </Button>
                  </div>
                ))}
                {!block.rows?.length && (block.text || '空表格')}
                {editable && block.rows?.length ? (
                  <Button
                    size="small"
                    onClick={() => emit({ type: 'ADD_TABLE_ROW', targetId: block.id, rowIndex: block.rows?.length })}
                  >
                    新增行
                  </Button>
                ) : null}
              </div>
            ) : (
              <p onClick={() => { setSelectedBlock(block.id); setSelectedControl(undefined); }}>{block.text || '\u00a0'}</p>
            )}
          </div>
        ))}
        {!blocks.length && <p className="word-template-empty">Word 结构尚未生成</p>}
        {controls.map((control) => (
          <button
            key={control.nodeId}
            type="button"
            className={selectedControl === control.nodeId ? 'word-control is-selected' : 'word-control'}
            disabled={!editable}
            onClick={() => { setSelectedControl(control.nodeId); setSelectedBlock(undefined); }}
          >
            {control.text || control.alias || control.tag || '内容控件'}
          </button>
        ))}
        {selected && (
          <Input.TextArea
            className="word-control-editor"
            value={controlDraft}
            disabled={!editable}
            autoSize={{ minRows: 1, maxRows: 4 }}
            onChange={(event) => setControlDraft(event.target.value)}
            onBlur={() => emit({ type: 'REPLACE_CONTENT_CONTROL', targetId: selected.nodeId, text: controlDraft, baseText: selected.text })}
            placeholder="编辑内容控件文本"
          />
        )}
      </div>
    </div>
  );
});
