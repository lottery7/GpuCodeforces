import { useMemo, useState } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { cpp } from '@codemirror/lang-cpp';
import { EditorView } from '@codemirror/view';

const editorTheme = EditorView.theme({
  '&': { backgroundColor: '#1e1f22', color: '#bcbec4', fontSize: '13px' },
  '.cm-content': { fontFamily: 'var(--mono)', padding: '12px 0', caretColor: '#dfe1e5' },
  '.cm-gutters': { backgroundColor: '#1e1f22', color: '#6f737a', borderRight: '1px solid #393b40' },
  '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: '#282b30' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: '#2e436e !important' },
  '.cm-cursor': { borderLeftColor: '#dfe1e5' },
  '.cm-scroller': { overflow: 'auto', fontFamily: 'var(--mono)' },
  '&.cm-focused': { outline: 'none' },
}, { dark: true });

export default function CodeEditor({ value, onChange, readOnly = false, label = 'Код решения на C++ и CUDA', status, fit = false }: {
  value: string;
  onChange?: (value: string) => void;
  readOnly?: boolean;
  label?: string;
  status?: string;
  fit?: boolean;
}) {
  const [cursor, setCursor] = useState({ line: 1, column: 1 });
  const extensions = useMemo(() => [cpp(), editorTheme, EditorView.contentAttributes.of({ 'aria-label': label, spellcheck: 'false' })], [label]);
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
    <div className="editor-status"><span role={status ? 'status' : undefined}>{status || 'CUDA / UTF-8'}</span><span>Строка {cursor.line}, столбец {cursor.column}</span></div>
  </>;
}
