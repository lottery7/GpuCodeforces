import { useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import { t } from '../i18n';

const STORAGE_KEY = 'gpu-forces:problem-split';
/** Percent of the width the statement takes; the default is the 0.95 : 1.05 split the page had before the border could be moved. */
const DEFAULT_SHARE = 47.5;
const MIN_SHARE = 25;
const MAX_SHARE = 70;
const KEY_STEP = 2;
/** The floors of the two columns in styles.css: the border does not go where the grid would not follow it. */
const STATEMENT_MIN_PX = 360;
const EDITOR_MIN_PX = 480;

const clamp = (share: number, low = MIN_SHARE, high = MAX_SHARE) => Math.min(high, Math.max(low, share));

function storedShare(): number {
  // without storage, or with a value nobody could have dragged to, the split is the default one
  try {
    const value = Number(localStorage.getItem(STORAGE_KEY));
    return value >= MIN_SHARE && value <= MAX_SHARE ? value : DEFAULT_SHARE;
  } catch { return DEFAULT_SHARE; }
}

function remember(share: number) {
  // the split of a visitor without storage lives until the page is closed
  try { localStorage.setItem(STORAGE_KEY, String(share)); } catch { /* see above */ }
}

/**
 * The statement and the editor of a problem side by side. The border between them is dragged, moved by the arrow keys
 * and put back by a double click; the split is the same for every problem and is kept in the browser.
 */
export default function SplitWorkspace({ children }: { children: [ReactNode, ReactNode] }) {
  const root = useRef<HTMLDivElement>(null);
  const [share, setShare] = useState(storedShare);
  const [dragging, setDragging] = useState(false);

  function drag(event: PointerEvent<HTMLDivElement>) {
    if (!dragging || !root.current) return;
    const rect = root.current.getBoundingClientRect();
    if (rect.width <= 0) return;
    const low = Math.max(MIN_SHARE, STATEMENT_MIN_PX / rect.width * 100);
    const high = Math.min(MAX_SHARE, 100 - EDITOR_MIN_PX / rect.width * 100);
    // a window too narrow for both floors leaves the split as it is
    // a tenth of a percent is finer than a pixel on any screen and keeps the stored number short
    if (low <= high) setShare(Math.round(clamp((event.clientX - rect.left) / rect.width * 100, low, high) * 10) / 10);
  }

  function release(event: PointerEvent<HTMLDivElement>) {
    if (!dragging) return;
    setDragging(false);
    event.currentTarget.releasePointerCapture(event.pointerId);
    remember(share);
  }

  function place(next: number) {
    setShare(next);
    remember(next);
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const next = event.key === 'ArrowLeft' ? clamp(share - KEY_STEP) : event.key === 'ArrowRight' ? clamp(share + KEY_STEP) : event.key === 'Home' ? MIN_SHARE : event.key === 'End' ? MAX_SHARE : null;
    if (next === null) return;
    event.preventDefault();
    place(next);
  }

  const style: CSSProperties & Record<'--statement-fr' | '--editor-fr', string> = { '--statement-fr': `${share}fr`, '--editor-fr': `${100 - share}fr` };
  return <div ref={root} className={`problem-workspace${dragging ? ' resizing' : ''}`} style={style}>
    {children[0]}
    <div className="workspace-splitter" role="separator" tabIndex={0} aria-orientation="vertical" aria-label={t('Граница между условием и редактором')} aria-valuemin={MIN_SHARE} aria-valuemax={MAX_SHARE} aria-valuenow={share} title={t('Перетащите, чтобы изменить ширину. Двойной щелчок вернёт исходную.')}
      onPointerDown={event => { event.preventDefault(); event.currentTarget.focus(); event.currentTarget.setPointerCapture(event.pointerId); setDragging(true); }}
      onPointerMove={drag} onPointerUp={release} onPointerCancel={release} onDoubleClick={() => place(DEFAULT_SHARE)} onKeyDown={onKeyDown} />
    {children[1]}
  </div>;
}
