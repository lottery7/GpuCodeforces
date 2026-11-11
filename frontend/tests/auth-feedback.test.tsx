import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../src/api/client';
import { AuthDialogContext, AuthForm } from '../src/auth-dialog';

const session = vi.hoisted(() => ({ login: vi.fn(), register: vi.fn() }));
vi.mock('../src/auth', () => ({ useSession: () => session }));
const scrollIntoView = vi.fn();
const originalScroll = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');

function renderForm(mode: 'login' | 'register') {
  return render(<MemoryRouter initialEntries={['/login']}><AuthDialogContext.Provider value={() => {}}><AuthForm mode={mode} returnTo="/profile" onClose={() => {}} /></AuthDialogContext.Provider></MemoryRouter>);
}

beforeEach(() => {
  session.login.mockReset();
  session.register.mockReset();
  scrollIntoView.mockClear();
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, window.innerHeight + 100, 200, 40));
});

afterEach(() => {
  if (originalScroll) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScroll);
  else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
});

describe('Ошибки входа и регистрации', () => {
  it('показывает отказ входа рядом с кнопкой и сохраняет введенные данные', async () => {
    session.login.mockRejectedValue(new ApiError(401, 'Нужен вход'));
    renderForm('login');
    const identifier = screen.getByRole('textbox', { name: 'Ник или почта' });
    const password = screen.getByLabelText('Пароль');
    await userEvent.type(identifier, '  new_cuda  ');
    await userEvent.type(password, 'my-password');
    const button = screen.getByRole('button', { name: 'Войти' });
    await userEvent.click(button);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Неверный ник, почта или пароль.');
    expect(button.closest('form')).toContainElement(alert);
    expect(alert.nextElementSibling).toBe(button);
    expect(identifier).toHaveValue('  new_cuda  ');
    expect(password).toHaveValue('my-password');
    expect(button).toBeEnabled();
    expect(session.login).toHaveBeenCalledWith({ identifier: 'new_cuda', password: 'my-password' });
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(alert);
  });

  it('показывает ошибку регистрации у действия без потери заполненных полей', async () => {
    session.register.mockRejectedValue(new ApiError(400, 'Этот ник уже занят'));
    renderForm('register');
    await userEvent.type(screen.getByRole('textbox', { name: /^Ник участника/ }), 'new_cuda');
    await userEvent.type(screen.getByRole('textbox', { name: 'Почта' }), 'new@example.com');
    await userEvent.type(screen.getByLabelText(/^Пароль/), 'my-password');
    const button = screen.getByRole('button', { name: 'Зарегистрироваться' });
    await userEvent.click(button);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Этот ник уже занят');
    expect(alert.nextElementSibling).toBe(button);
    expect(screen.getByRole('textbox', { name: /^Ник участника/ })).toHaveValue('new_cuda');
    expect(screen.getByRole('textbox', { name: 'Почта' })).toHaveValue('new@example.com');
    expect(screen.getByLabelText(/^Пароль/)).toHaveValue('my-password');
    expect(button).toBeEnabled();
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(alert);
  });
});