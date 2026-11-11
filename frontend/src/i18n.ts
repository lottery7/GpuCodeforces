import { useSyncExternalStore } from 'react';
import { en, enPatterns } from './locales/en';

export type Language = 'ru' | 'en';
/** A language is named in itself, whatever the interface speaks now. */
export const languages: { id: Language; short: string; title: string }[] = [
  { id: 'ru', short: 'RU', title: 'Русский' },
  { id: 'en', short: 'EN', title: 'English' },
];

const STORAGE_KEY = 'gpu-forces:language';
const listeners = new Set<() => void>();

function stored(): Language {
  // without storage the interface stays in the language it was written in
  try { return localStorage.getItem(STORAGE_KEY) === 'en' ? 'en' : 'ru'; } catch { return 'ru'; }
}

let current: Language = stored();
document.documentElement.lang = current;

export const getLanguage = () => current;

export function setLanguage(next: Language) {
  if (next === current) return;
  current = next;
  document.documentElement.lang = next;
  // the choice of a visitor without storage lives until the page is closed
  try { localStorage.setItem(STORAGE_KEY, next); } catch { /* see above */ }
  listeners.forEach(listener => listener());
}

/**
 * Subscribes a component to the language. App does it, and every page under it is rendered again on a switch
 * without losing its state; t() itself reads the language of the moment it is called.
 */
export function useLanguage(): Language {
  return useSyncExternalStore(listener => { listeners.add(listener); return () => listeners.delete(listener); }, getLanguage);
}

const fill = (text: string, values: readonly unknown[]) =>
  values.length ? text.replace(/\{(\d+)\}/g, (match, index) => Number(index) < values.length ? String(values[Number(index)]) : match) : text;

/**
 * The interface is written in Russian, and a Russian text is its own key: in English it is looked up in locales/en.ts.
 * `{0}`, `{1}` in the text stand for [values]. A text without a translation is shown as it is.
 */
export function t(source: string, values: readonly unknown[] = []): string {
  return fill(current === 'ru' ? source : en[source] ?? source, values);
}

/**
 * A message the backend wrote, which is Russian: an exact one is translated as any text, one with a name or a number
 * inside by its pattern. Texts the backend composes at length (contest rules, the judge's environment, notifications)
 * are not translated here.
 */
export function serverText(message: string): string {
  if (current === 'ru') return message;
  if (message in en) return en[message];
  for (const [pattern, translation] of enPatterns) if (pattern.test(message)) return message.replace(pattern, translation);
  return message;
}

/** Labels kept at module level: read through this, a label comes in the language of the moment it is read. */
export function translated<T extends Record<string, string>>(labels: T): T {
  return new Proxy(labels, {
    get(target, key, receiver) {
      const value: unknown = Reflect.get(target, key, receiver);
      return typeof value === 'string' ? t(value) : value;
    },
  });
}

/** Locale of dates and numbers in the current language. */
export const locale = () => current === 'ru' ? 'ru-RU' : 'en-GB';
