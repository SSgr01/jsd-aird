import { describe, expect, it, vi } from 'vitest';
import { bindSheetWheelFocus } from './sheet-wheel-focus';

describe('sheet wheel focus', () => {
  it('restores workbook focus before the canvas listener without consuming native scrolling', () => {
    const host = document.createElement('div');
    const canvas = document.createElement('canvas');
    canvas.id = 'univer-sheet-main-canvas-workbook';
    host.append(canvas);
    const focus = vi.fn();
    const dispose = bindSheetWheelFocus(host, focus);
    canvas.addEventListener('wheel', () => expect(focus).toHaveBeenCalledTimes(1), { once: true });
    const wheel = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 120 });
    canvas.dispatchEvent(wheel);
    expect(wheel.defaultPrevented).toBe(false);
    dispose();
    canvas.dispatchEvent(new WheelEvent('wheel', { bubbles: true }));
    expect(focus).toHaveBeenCalledTimes(1);
  });

  it('does not steal focus from formula editors, toolbar fields or zoom gestures', () => {
    const host = document.createElement('div');
    const formula = document.createElement('canvas');
    formula.id = 'univer-doc-main-canvas';
    const sheet = document.createElement('canvas');
    sheet.id = 'univer-sheet-main-canvas-workbook';
    const input = document.createElement('input');
    host.append(formula, sheet, input);
    const focus = vi.fn();
    const dispose = bindSheetWheelFocus(host, focus);
    formula.dispatchEvent(new WheelEvent('wheel', { bubbles: true }));
    input.dispatchEvent(new WheelEvent('wheel', { bubbles: true }));
    sheet.dispatchEvent(new WheelEvent('wheel', { bubbles: true, ctrlKey: true }));
    expect(focus).not.toHaveBeenCalled();
    dispose();
  });
});
