import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import { afterEach, describe, expect, it, vi } from 'vitest';

const html = readFileSync(new URL('../../backend/src/main/resources/static/admin/index.html', import.meta.url), 'utf8');
const script = readFileSync(new URL('../../backend/src/main/resources/static/admin/admin.js', import.meta.url), 'utf8');
const windows = [];

function open(fetcher, outside = true) {
  const dom = new JSDOM(html, { url: 'http://localhost/admin/', runScripts: 'outside-only' });
  windows.push(dom.window);
  dom.window.fetch = fetcher;
  const scroll = vi.fn();
  dom.window.HTMLElement.prototype.scrollIntoView = scroll;
  dom.window.HTMLElement.prototype.getBoundingClientRect = () => ({ top: outside ? 1000 : 100, bottom: outside ? 1180 : 280 });
  dom.window.eval(script);
  return { document: dom.window.document, scroll };
}

afterEach(() => {
  windows.splice(0).forEach(window => window.close());
});

describe('Обратная связь администрирования', () => {
  it('не прокручивает страницу при фоновой проверке входа', async () => {
    const fetcher = vi.fn().mockResolvedValue(Response.json({ message: 'Нет сессии.' }, { status: 401 }));
    const { document, scroll } = open(fetcher);
    await vi.waitFor(() => expect(document.getElementById('who').textContent).toContain('Войдите на сайте'));
    expect(scroll).not.toHaveBeenCalled();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it.each([
    { button: 'run', path: '/api/admin/console', target: 'result', status: 'run-status' },
    { button: 'sync', path: '/api/admin/problems/sync', target: 'sync-result', status: 'sync-status' },
  ])('показывает ошибку $button и блокирует повторный запрос до ответа', async ({ button, path, target, status }) => {
    let finish;
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ handle: 'admin' })).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const { document, scroll } = open(fetcher);
    const action = document.getElementById(button);
    action.click();
    action.click();
    expect(action.disabled).toBe(true);
    expect(fetcher.mock.calls.filter(([url]) => url === path)).toHaveLength(1);
    expect(scroll).not.toHaveBeenCalled();

    finish(Response.json({ message: 'Операция недоступна.' }, { status: 503 }));
    await vi.waitFor(() => expect(action.disabled).toBe(false));
    const result = document.getElementById(target);
    expect(result.textContent).toBe('Операция недоступна.');
    expect(result.hidden).toBe(false);
    expect(result.closest('[hidden]')).toBeNull();
    expect(result.classList.contains('error')).toBe(true);
    expect(document.getElementById(status).getAttribute('role')).toBe('status');
    expect(document.getElementById(status).classList.contains('error')).toBe(true);
    expect(scroll).toHaveBeenCalledTimes(1);
    expect(scroll.mock.instances[0]).toBe(result);
    expect(scroll).toHaveBeenCalledWith({ block: 'nearest', inline: 'nearest', behavior: 'auto' });
    expect(fetcher.mock.calls.filter(([url]) => url === path)).toHaveLength(1);
  });

  it('показывает ошибку Groovy и полный вывод без прокрутки уже видимого результата', async () => {
    const output = 'Строка вывода\n'.repeat(1000);
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ handle: 'admin' })).mockResolvedValueOnce(Response.json({ error: 'Ошибка скрипта.', result: null, output }));
    const { document, scroll } = open(fetcher, false);
    document.getElementById('run').click();
    await vi.waitFor(() => expect(document.getElementById('run').disabled).toBe(false));
    expect(document.getElementById('result').textContent).toBe('Ошибка скрипта.');
    expect(document.getElementById('result').classList.contains('error')).toBe(true);
    expect(document.getElementById('output').textContent).toBe(output);
    expect(document.getElementById('console-output').hidden).toBe(false);
    expect(document.getElementById('run-status').textContent).toBe('Скрипт завершился с ошибкой.');
    expect(document.getElementById('output').closest('[role="status"], [aria-live]')).toBeNull();
    expect(scroll).not.toHaveBeenCalled();
  });
});
