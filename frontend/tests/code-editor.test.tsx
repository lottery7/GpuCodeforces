import { useState } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import CodeEditor from '../src/components/CodeEditor';

const restorePlatform = vi.hoisted(() => {
  const descriptor = Object.getOwnPropertyDescriptor(navigator, 'platform');
  Object.defineProperty(navigator, 'platform', { configurable: true, value: 'MacIntel' });
  return () => {
    if (descriptor) Object.defineProperty(navigator, 'platform', descriptor);
    else Reflect.deleteProperty(navigator, 'platform');
  };
});

afterAll(restorePlatform);

const rangeDescriptors = new Map<string, PropertyDescriptor | undefined>();
beforeAll(() => {
  for (const [name, value] of Object.entries({ getClientRects: () => [], getBoundingClientRect: () => new DOMRect() })) {
    rangeDescriptors.set(name, Object.getOwnPropertyDescriptor(Range.prototype, name));
    Object.defineProperty(Range.prototype, name, { configurable: true, value });
  }
});
afterAll(() => {
  for (const [name, descriptor] of rangeDescriptors) {
    if (descriptor) Object.defineProperty(Range.prototype, name, descriptor);
    else Reflect.deleteProperty(Range.prototype, name);
  }
});

function Editor({ onChange, readOnly = false }: { onChange: (value: string) => void; readOnly?: boolean }) {
  const [value, setValue] = useState(readOnly ? 'int answer = 42;' : '');
  return <><CodeEditor value={value} readOnly={readOnly} onChange={next => { setValue(next); onChange(next); }} /><output aria-label="Текущее решение">{value}</output></>;
}

describe('История редактора', () => {
  it.each(['Control', 'Meta'] as const)('%s: отмена и повтор возвращают введенный код', async modifier => {
    const onChange = vi.fn();
    render(<Editor onChange={onChange} />);
    const editor = screen.getByRole('textbox', { name: 'Код решения на C++ и CUDA' });
    await userEvent.type(editor, 'return 1;');
    await waitFor(() => expect(screen.getByLabelText('Текущее решение')).toHaveTextContent('return 1;'));
    expect(onChange).toHaveBeenLastCalledWith('return 1;');
    const modifiers = modifier === 'Control' ? { ctrlKey: true } : { metaKey: true };

    fireEvent.keyDown(editor, { key: 'z', code: 'KeyZ', keyCode: 90, ...modifiers });
    await waitFor(() => expect(screen.getByLabelText('Текущее решение')).toBeEmptyDOMElement());
    expect(editor.textContent).toBe('');
    expect(onChange).toHaveBeenLastCalledWith('');
    fireEvent.keyDown(editor, { key: 'Z', code: 'KeyZ', keyCode: 90, shiftKey: true, ...modifiers });
    await waitFor(() => expect(screen.getByLabelText('Текущее решение')).toHaveTextContent('return 1;'));
    expect(editor.textContent).toBe('return 1;');
    expect(onChange).toHaveBeenLastCalledWith('return 1;');
  });

  it('не меняет исходник посылки при вводе и сочетаниях отмены', async () => {
    const onChange = vi.fn();
    render(<Editor onChange={onChange} readOnly />);
    const editor = screen.getByRole('textbox', { name: 'Код решения на C++ и CUDA' });
    await userEvent.type(editor, 'changed');
    for (const modifiers of [{ ctrlKey: true }, { metaKey: true }]) {
      fireEvent.keyDown(editor, { key: 'z', code: 'KeyZ', keyCode: 90, ...modifiers });
      fireEvent.keyDown(editor, { key: 'Z', code: 'KeyZ', keyCode: 90, shiftKey: true, ...modifiers });
    }
    expect(screen.getByLabelText('Текущее решение')).toHaveTextContent('int answer = 42;');
    expect(editor.textContent).toBe('int answer = 42;');
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('Язык редактора', () => {
  it.each([
    ['systemverilog', 'Код схемы на SystemVerilog', 'SystemVerilog / UTF-8'],
    ['asm', 'Код решения на ассемблере RISC-V', 'RISC-V assembly / UTF-8'],
  ])('%s: подпись и строка состояния по языку', (language, label, status) => {
    render(<CodeEditor value="module solve; endmodule" language={language} />);
    expect(screen.getByRole('textbox', { name: label })).toBeVisible();
    expect(screen.getByText(status)).toBeVisible();
  });
});
