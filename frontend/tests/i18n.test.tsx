import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import App from '../src/App';
import { api, ApiError } from '../src/api/client';
import { getLanguage, serverText, setLanguage, t } from '../src/i18n';
import { en } from '../src/locales/en';
import { limitLabels, scoreUnits, formatLimit, formatScore } from '../src/processors';
import { dateTime, difficultyLabels, formatLabels, guessLabels, statusLabels } from '../src/utils';

vi.mock('../src/auth', () => ({ useSession: () => ({ user: null, isLoading: false, error: null, refresh: () => {} }) }));

function sources(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => entry.isDirectory()
    ? entry.name === 'locales' ? [] : sources(join(directory, entry.name))
    : /\.tsx?$/.test(entry.name) && !entry.name.endsWith('.d.ts') ? [join(directory, entry.name)] : []);
}

/** Russian string literals of the interface code: texts given to t() and labels kept at module level. */
function interfaceTexts(): Set<string> {
  const texts = new Set<string>();
  for (const file of sources(join(__dirname, '../src'))) {
    const code = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
    for (const match of code.matchAll(/'((?:[^'\\\n]|\\.)*[А-Яа-яЁё](?:[^'\\\n]|\\.)*)'/g)) {
      texts.add(match[1].replace(/\\(['\\])/g, '$1').replace(/\\n/g, '\n'));
    }
  }
  return texts;
}

describe('Английский интерфейс', () => {
  it('знает перевод каждого текста интерфейса', () => {
    const texts = interfaceTexts();
    // the scan found the interface, not a handful of strings
    expect(texts.size).toBeGreaterThan(400);
    // the name of a language is shown in that language
    expect([...texts].filter(text => !(text in en) && text !== 'Русский')).toEqual([]);
  });

  it('подставляет значения и оставляет непереведенный текст как есть', () => {
    setLanguage('en');
    expect(t('Период взломов до {0} МСК.{1}', ['12 Nov, 10:00', ''])).toBe('Hack period until 12 Nov, 10:00 MSK.');
    expect(t('Текст без перевода {0}', [7])).toBe('Текст без перевода 7');
    setLanguage('ru');
    expect(t('Убрать {0}', ['alice'])).toBe('Убрать alice');
  });

  it('переводит подписи, единицы, даты и числа на языке момента чтения', () => {
    const limit = { value: 3_500_000_000, type: 'CYCLES' as const };
    expect([statusLabels.accepted, difficultyLabels.easy, formatLabels.guess, guessLabels.equal, scoreUnits.CYCLES, limitLabels.TIME_MS])
      .toEqual(['Принято', 'Легкая', 'Угадай, какой код быстрее', 'Одинаково', 'такты', 'Лимит времени']);
    expect(formatLimit(limit)).toBe('3,5·10⁹');
    setLanguage('en');
    expect([statusLabels.accepted, difficultyLabels.easy, formatLabels.guess, guessLabels.equal, scoreUnits.CYCLES, limitLabels.TIME_MS])
      .toEqual(['Accepted', 'Easy', 'Guess which code is faster', 'The same', 'cycles', 'Time limit']);
    expect(Object.values(difficultyLabels)).toEqual(['Easy', 'Medium', 'Hard']);
    expect(formatLimit(limit)).toBe('3.5·10⁹');
    expect(formatScore({ value: 1099052, type: 'WEIGHTED_INSTRUCTIONS' })).toBe('1,099,052');
    expect(dateTime('2026-11-11T20:12:00Z')).toBe('11 Nov, 23:12');
  });

  it('переводит сообщения сервера: точные и с именем внутри', () => {
    expect(serverText('Контест не найден')).toBe('Контест не найден');
    setLanguage('en');
    expect(serverText('Контест не найден')).toBe('Contest not found');
    expect(serverText('На процессоре riscv-rvv задача принимает c, cpp, asm, а не systemverilog')).toBe('On processor riscv-rvv the problem takes c, cpp, asm, not systemverilog');
    expect(serverText('Пароль длиннее 72 байт: сократите его')).toBe('The password is longer than 72 bytes: shorten it');
    // a text the backend composed stays as it came
    expect(serverText('alice приглашает вас на дуэль')).toBe('alice приглашает вас на дуэль');
  });
});

describe('Переключатель языка', () => {
  function open() {
    vi.spyOn(api, 'getRuntime').mockResolvedValue({ executionMode: 'gpu', gpuName: null });
    vi.spyOn(api, 'listLatestSubmissions').mockRejectedValue(new ApiError(400, 'Неверный запрос'));
    vi.spyOn(api, 'listContests').mockResolvedValue([]);
    vi.spyOn(api, 'listUsers').mockResolvedValue({ items: [], total: 0 });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/']}><App /></MemoryRouter></QueryClientProvider>);
  }

  it('переводит открытую страницу без перезагрузки и запоминает выбор', async () => {
    open();
    expect(await screen.findByRole('heading', { name: 'Последние посылки' })).toBeVisible();
    expect(await screen.findByText('Неверный запрос')).toBeVisible();
    expect(screen.getByRole('button', { name: 'RU' })).toHaveAttribute('aria-pressed', 'true');

    await userEvent.click(screen.getByRole('button', { name: 'EN' }));

    expect(screen.getByRole('heading', { name: 'Latest submissions' })).toBeVisible();
    expect(screen.queryByText('Последние посылки')).not.toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Problems' })[0]).toHaveAttribute('href', '/problems');
    // what the backend answered in Russian is translated where it is shown
    expect(screen.getByText('Bad request')).toBeVisible();
    expect(screen.getByRole('button', { name: 'EN' })).toHaveAttribute('aria-pressed', 'true');
    expect(document.documentElement.lang).toBe('en');
    expect(document.title).toBe('GPU Forces - Home');
    expect(localStorage.getItem('gpu-forces:language')).toBe('en');
    expect(getLanguage()).toBe('en');

    await userEvent.click(screen.getByRole('button', { name: 'RU' }));
    expect(screen.getByRole('heading', { name: 'Последние посылки' })).toBeVisible();
    expect(document.documentElement.lang).toBe('ru');
  });
});
