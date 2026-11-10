'use strict';

const element = id => document.getElementById(id);
const labels = {
  requestsPerMinute: 'Запросов в минуту на IP',
  authRequestsPerMinute: 'Попыток входа в минуту на IP',
  callbackRequestsPerMinute: 'Обратных запросов в минуту на тестировщика',
  maxQueuedSubmissions: 'Всего незавершённых посылок',
  maxPendingPerUser: 'Незавершённых посылок на пользователя',
  maxInFlight: 'Одновременных посылок на тестировщика (1–3)',
  judgeTimeoutSeconds: 'Таймаут проверки, секунд',
  maxAttempts: 'Попыток назначения посылки',
  inboxCapacity: 'Размер буфера результатов',
  pingIntervalSeconds: 'Интервал ping, секунд',
  pingTimeoutSeconds: 'Ожидание pong, секунд',
  exampleWaitSeconds: 'Ожидание результата примера, секунд',
  discoveryIntervalSeconds: 'Интервал обнаружения репозиториев, секунд',
  repositoryIntervalSeconds: 'Интервал проверки репозитория, секунд',
  githubRequestsPerMinute: 'Запросов к GitHub в минуту',
};
let version = null;

async function send(path, method = 'GET', body) {
  const response = await fetch('/api' + path, {
    method, credentials: 'same-origin',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const data = response.status === 204 ? null : await response.json();
  return { response, data };
}

async function call(path, method, body) {
  const { response, data } = await send(path, method, body);
  if (!response.ok) throw new Error(data?.message || 'Ошибка запроса: ' + response.status);
  return data;
}

async function busy(form, action, output) {
  const buttons = form.querySelectorAll('button');
  buttons.forEach(button => { button.disabled = true; });
  output.textContent = '';
  try { await action(); }
  catch (error) { output.textContent = error.message; }
  finally { buttons.forEach(button => { button.disabled = false; }); }
}

async function loadSettings() {
  const snapshot = await call('/admin/settings');
  version = snapshot.version;
  element('fields').replaceChildren();
  for (const [key, value] of Object.entries(snapshot.values)) {
    const label = document.createElement('label');
    label.textContent = labels[key] || key;
    const input = document.createElement('input');
    input.type = 'number'; input.step = '1'; input.min = '1';
    input.name = key; input.value = value; input.required = true;
    label.append(input); element('fields').append(label);
  }
  element('settings-status').textContent = 'Загружена версия ' + version + '.';
}

async function refresh() {
  const { response, data } = await send('/admin/access');
  element('login').hidden = response.status !== 401;
  element('logout').hidden = !response.ok;
  element('bootstrap').hidden = !response.ok || data.admin || !data.bootstrapAvailable;
  element('workspace').hidden = !response.ok || !data.admin;
  if (response.status === 401) element('status').textContent = 'Войдите в свой аккаунт.';
  else if (!response.ok) throw new Error(data?.message || 'Не удалось проверить доступ.');
  else if (!data.admin) element('status').textContent = 'У текущего аккаунта нет прав администратора.';
  else { element('status').textContent = 'Доступ администратора подтверждён.'; await loadSettings(); }
}

element('login').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget;
  void busy(form, async () => {
    try {
      await call('/session', 'POST', {
        identifier: form.elements.identifier.value.trim(), password: form.elements.password.value,
      });
    } finally { form.elements.password.value = ''; }
    await refresh();
  }, element('status'));
});

element('bootstrap').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget;
  void busy(form, async () => {
    try { await call('/admin/bootstrap', 'POST', { token: form.elements.token.value }); }
    finally { form.elements.token.value = ''; }
    await refresh();
  }, element('status'));
});

element('logout').addEventListener('click', async () => {
  try {
    await call('/session', 'DELETE');
    element('console-output').textContent = '';
    await refresh();
  } catch (error) { element('status').textContent = error.message; }
});

element('settings-form').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget;
  void busy(form, async () => {
    const values = Object.fromEntries([...form.querySelectorAll('input')].map(input => [input.name, +input.value]));
    const saved = await call('/admin/settings', 'PUT', { version, values });
    version = saved.version;
    element('settings-status').textContent = 'Сохранена версия ' + version + '.';
  }, element('settings-status'));
});

element('reload').addEventListener('click', () => {
  void busy(element('settings-form'), loadSettings, element('settings-status'));
});

element('console-form').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget;
  void busy(form, async () => {
    const { response, data } = await send('/admin/console', 'POST', { script: form.elements.script.value });
    if (![200, 422, 504].includes(response.status)) throw new Error(data?.message || 'Ошибка консоли.');
    const parts = [data.output, data.result, data.error, data.truncated ? 'Вывод обрезан.' : ''];
    element('console-output').textContent = parts.filter(value => value !== null && value !== '').join('\n');
  }, element('console-output'));
});

void refresh().catch(error => { element('status').textContent = error.message; });
