import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import SplitWorkspace from '../src/components/SplitWorkspace';

const workspace = () => render(<SplitWorkspace><section>statement</section><section>editor</section></SplitWorkspace>);
const border = () => screen.getByRole('separator', { name: 'Граница между условием и редактором' });
const share = () => Number(border().getAttribute('aria-valuenow'));

describe('the border between the statement and the editor', () => {
  it('moves by the arrow keys and keeps the panes in their order', () => {
    const view = workspace();
    const initial = share();
    fireEvent.keyDown(border(), { key: 'ArrowRight' });
    expect(share()).toBeGreaterThan(initial);
    fireEvent.keyDown(border(), { key: 'ArrowLeft' });
    fireEvent.keyDown(border(), { key: 'ArrowLeft' });
    expect(share()).toBeLessThan(initial);
    expect(view.container.textContent).toBe('statementeditor');
  });

  it('stops at the ends of its range', () => {
    workspace();
    fireEvent.keyDown(border(), { key: 'Home' });
    const narrowest = share();
    fireEvent.keyDown(border(), { key: 'ArrowLeft' });
    expect(share()).toBe(narrowest);
    expect(narrowest).toBe(Number(border().getAttribute('aria-valuemin')));
    fireEvent.keyDown(border(), { key: 'End' });
    fireEvent.keyDown(border(), { key: 'ArrowRight' });
    expect(share()).toBe(Number(border().getAttribute('aria-valuemax')));
  });

  it('is remembered for the next problem and is put back by a double click', () => {
    const first = workspace();
    const initial = share();
    fireEvent.keyDown(border(), { key: 'ArrowRight' });
    const moved = share();
    first.unmount();

    workspace();
    expect(share()).toBe(moved);
    fireEvent.doubleClick(border());
    expect(share()).toBe(initial);
  });

  it('ignores a stored split that is out of its range', () => {
    const first = workspace();
    const initial = share();
    first.unmount();
    localStorage.setItem('gpu-forces:problem-split', '99');

    workspace();
    expect(share()).toBe(initial);
  });
});
