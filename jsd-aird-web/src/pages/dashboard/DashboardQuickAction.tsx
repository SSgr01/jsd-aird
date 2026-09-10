import {
  BranchesOutlined,
  CloseOutlined,
  ExperimentOutlined,
  FundProjectionScreenOutlined,
  PlusOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import { message } from 'antd';
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router-dom';

import './dashboard-quick-action.css';

type Direction = 'up' | 'down' | 'left' | 'right';

interface QuickMenuItem {
  key: string;
  label: string;
  icon: ReactNode;
  color: string;
  to?: string;
  onClick?: () => void;
}

interface DragState {
  pointerId: number;
  startX: number;
  startY: number;
  originX: number;
  originY: number;
  moved: boolean;
}

const QUICK_MENU: QuickMenuItem[] = [
  {
    key: 'create-project',
    label: '新建项目',
    icon: <FundProjectionScreenOutlined />,
    color: '#1f6feb',
    to: '/projects/list?create=1',
  },
  {
    key: 'create-experiment',
    label: '新建实验',
    icon: <ExperimentOutlined />,
    color: '#1f9d55',
    to: '/experiments/list?create=1',
  },
  {
    key: 'ai-qa',
    label: 'AI 问答',
    icon: <RobotOutlined />,
    color: '#7c3aed',
    to: '/assistant',
  },
  {
    key: 'ai-prescription',
    label: 'AI 配方预测',
    icon: <BranchesOutlined />,
    color: '#f59e0b',
    to: '/assistant/formula-prediction',
  },
];

const FAB_SIZE = 56;
const VIEWPORT_EDGE = 12;
const DRAG_THRESHOLD = 6;

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

export function DashboardQuickAction() {
  const navigate = useNavigate();
  const rootRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<DragState | null>(null);
  const longPressTimerRef = useRef<number | null>(null);

  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState(() => ({
    x: typeof window === 'undefined' ? 32 : Math.max(VIEWPORT_EDGE, window.innerWidth - FAB_SIZE - 32),
    y: typeof window === 'undefined' ? 360 : Math.max(VIEWPORT_EDGE, window.innerHeight - FAB_SIZE - 32),
  }));
  const [dragging, setDragging] = useState(false);

  // 选择菜单展开方向（默认向上，避免遮挡底部内容）
  const menuDirection: Direction = useMemo(() => {
    if (typeof window === 'undefined') return 'up';
    return position.y > window.innerHeight / 2 ? 'up' : 'down';
  }, [position.y]);

  // 选择菜单水平方向（避免越过屏幕中线遮挡）
  const menuAlign: 'left' | 'right' = useMemo(() => {
    if (typeof window === 'undefined') return 'left';
    return position.x > window.innerWidth / 2 ? 'right' : 'left';
  }, [position.x]);

  const keepInViewport = useCallback((x: number, y: number) => {
    const maxX = window.innerWidth - FAB_SIZE - VIEWPORT_EDGE;
    const maxY = window.innerHeight - FAB_SIZE - VIEWPORT_EDGE;
    return {
      x: clamp(x, VIEWPORT_EDGE, Math.max(VIEWPORT_EDGE, maxX)),
      y: clamp(y, VIEWPORT_EDGE, Math.max(VIEWPORT_EDGE, maxY)),
    };
  }, []);

  const handlePointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (event.button !== 0) return;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    dragRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      originX: position.x,
      originY: position.y,
      moved: false,
    };
    // 长按 300ms 也视作进入"可拖动"提示，便于移动端
    if (longPressTimerRef.current !== null) {
      window.clearTimeout(longPressTimerRef.current);
    }
    longPressTimerRef.current = window.setTimeout(() => {
      // 仅做提示，不阻塞后续点击
    }, 300);
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const state = dragRef.current;
    if (!state || state.pointerId !== event.pointerId) return;
    const dx = event.clientX - state.startX;
    const dy = event.clientY - state.startY;
    if (!state.moved && Math.hypot(dx, dy) < DRAG_THRESHOLD) return;
    state.moved = true;
    setDragging(true);
    setPosition(keepInViewport(state.originX + dx, state.originY + dy));
  };

  const endDrag = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const state = dragRef.current;
    if (!state || state.pointerId !== event.pointerId) return;
    const wasMoved = state.moved;
    dragRef.current = null;
    setDragging(false);
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    if (!wasMoved) {
      // 单击 -> 切换菜单
      setOpen((prev) => !prev);
    } else if (open) {
      // 拖动后顺手关闭菜单，避免误触
      setOpen(false);
    }
  };

  const handleCancel = (event: ReactPointerEvent<HTMLButtonElement>) => {
    dragRef.current = null;
    setDragging(false);
    event.currentTarget.releasePointerCapture?.(event.pointerId);
  };

  // 全局点击/滚动收起菜单（仅在非拖动状态下生效）
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const onScroll = () => setOpen(false);
    document.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('scroll', onScroll, true);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('scroll', onScroll, true);
    };
  }, [open]);

  // 视口变化时把按钮吸回可视范围
  useEffect(() => {
    const handler = () => {
      setPosition((prev) => keepInViewport(prev.x, prev.y));
    };
    window.addEventListener('resize', handler);
    return () => window.removeEventListener('resize', handler);
  }, [keepInViewport]);

  useEffect(
    () => () => {
      if (longPressTimerRef.current !== null) {
        window.clearTimeout(longPressTimerRef.current);
      }
    },
    [],
  );

  const handleMenuSelect = (item: QuickMenuItem) => {
    setOpen(false);
    if (item.onClick) {
      item.onClick();
      return;
    }
    if (item.to) {
      message.success(`即将打开：${item.label}`);
      navigate(item.to);
      return;
    }
    message.info(`已选择：${item.label}`);
  };

  const rootStyle: CSSProperties = {
    transform: `translate3d(${position.x}px, ${position.y}px, 0)`,
  };

  const menuClass = [
    'dashboard-quick-action-menu',
    `is-${menuDirection}`,
    `align-${menuAlign}`,
    open ? 'is-open' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div
      ref={rootRef}
      className={`dashboard-quick-action${dragging ? ' is-dragging' : ''}${open ? ' is-open' : ''}`}
      style={rootStyle}
      aria-hidden={false}
    >
      <div className={menuClass} role="menu" aria-hidden={!open}>
        {QUICK_MENU.map((item) => (
          <button
            key={item.key}
            type="button"
            role="menuitem"
            className="dashboard-quick-action-item"
            onClick={() => handleMenuSelect(item)}
            style={{ ['--qa-tint' as string]: item.color }}
          >
            <span className="dashboard-quick-action-icon" aria-hidden="true">
              {item.icon}
            </span>
            <span className="dashboard-quick-action-label">{item.label}</span>
          </button>
        ))}
      </div>

      <button
        type="button"
        className="dashboard-quick-action-fab"
        style={{ cursor: dragging ? 'grabbing' : 'grab', touchAction: 'none' }}
        aria-label={open ? '关闭快捷操作' : '打开快捷操作'}
        aria-expanded={open}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={endDrag}
        onPointerCancel={handleCancel}
      >
        <span className={`dashboard-quick-action-fab-icon${open ? ' is-open' : ''}`} aria-hidden="true">
          {open ? <CloseOutlined /> : <PlusOutlined />}
        </span>
        <span className="dashboard-quick-action-fab-ring" aria-hidden="true" />
      </button>
    </div>
  );
}
