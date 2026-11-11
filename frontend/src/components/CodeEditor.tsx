import { useMemo, useState } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { cpp } from '@codemirror/lang-cpp';
import { redo, undo } from '@codemirror/commands';
import { StreamLanguage } from '@codemirror/language';
import { gas } from '@codemirror/legacy-modes/mode/gas';
import { verilog } from '@codemirror/legacy-modes/mode/verilog';
import { EditorView, keymap, type KeyBinding } from '@codemirror/view';
import { t } from '../i18n';

const historyShortcuts = keymap.of([
  { key: 'Ctrl-z', run: undo, preventDefault: true },
  { key: 'Ctrl-Shift-z', run: redo, preventDefault: true },
] satisfies KeyBinding[]);

/** Highlighting, the editor's accessible name and the status line of each language of the judge. */
const languages: Record<string, { mode: () => ReturnType<typeof cpp> | StreamLanguage<unknown> | null; label: string; status: string }> = {
  cuda: { mode: cpp, label: 'Код решения на C++ и CUDA', status: 'CUDA' },
  c: { mode: cpp, label: 'Код решения на C', status: 'C' },
  cpp: { mode: cpp, label: 'Код решения на C++', status: 'C++' },
  systemverilog: { mode: () => StreamLanguage.define(verilog), label: 'Код схемы на SystemVerilog', status: 'SystemVerilog' },
  asm: { mode: () => StreamLanguage.define(gas), label: 'Код решения на ассемблере RISC-V', status: 'RISC-V assembly' },
};

export const editorLabel = (language: string) => t((languages[language] ?? languages.cuda).label);

const editorTheme = EditorView.theme({
  // sizes follow the 1.2x scale of styles.css
  '&': { backgroundColor: '#1e1f22', color: '#bcbec4', fontSize: 'var(--font-sm)' },
  '.cm-content': { fontFamily: 'var(--mono)', padding: '14.4px 0', caretColor: '#dfe1e5' },
  '.cm-gutters': { backgroundColor: '#1e1f22', color: '#6f737a', borderRight: '1.2px solid #393b40' },
  '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: '#282b30' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: '#2e436e !important' },
  '.cm-cursor': { borderLeftColor: '#dfe1e5' },
  '.cm-scroller': { overflow: 'auto', fontFamily: 'var(--mono)' },
  '&.cm-focused': { outline: 'none' },
}, { dark: true });

export default function CodeEditor({ value, onChange, readOnly = false, language = 'cuda', label, status, fit = false }: {
  value: string;
  onChange?: (value: string) => void;
  readOnly?: boolean;
  /** Language id of the judge: `cuda`, `c`, `cpp`, `systemverilog`, `asm`. */
  language?: string;
  label?: string;
  status?: string;
  fit?: boolean;
}) {
  const [cursor, setCursor] = useState({ line: 1, column: 1 });
  const settings = languages[language] ?? languages.cuda;
  const name = label ?? t(settings.label);
  const extensions = useMemo(() => {
    const mode = settings.mode();
    return [...(mode ? [mode] : []), editorTheme, historyShortcuts, EditorView.contentAttributes.of({ 'aria-label': name, spellcheck: 'false' })];
  }, [settings, name]);
  return <>
    <div className={`code-editor${fit ? ' code-editor-fit' : ''}`}>
    <CodeMirror
      value={value}
      height={fit ? '100%' : 'clamp(180px, 42dvh, 440px)'}
      minHeight={fit ? '0' : '180px'}
      theme="dark"
      extensions={extensions}
      editable={!readOnly}
      readOnly={readOnly}
      indentWithTab
      basicSetup={{ foldGutter: false, highlightActiveLine: !readOnly }}
      onChange={onChange}
      onUpdate={update => {
        if (!update.selectionSet && !update.docChanged) return;
        const position = update.state.selection.main.head;
        const line = update.state.doc.lineAt(position);
        setCursor({ line: line.number, column: position - line.from + 1 });
      }}
    />
    </div>
    {!readOnly && <div className="editor-status"><span role={status ? 'status' : undefined}>{status || `${settings.status} / UTF-8`}</span><span>{t('Строка')} {cursor.line}{t(', столбец')} {cursor.column}</span></div>}
  </>;
}
