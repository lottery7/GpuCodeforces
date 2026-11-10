'use strict';

const $ = id => document.getElementById(id);

async function call(method, path, body) {
  const response = await fetch(`/api${path}`, {
    method,
    credentials: 'include',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = response.status === 204 ? null : await response.json();
  if (!response.ok) throw new Error(data?.message ?? `HTTP ${response.status}`);
  return data;
}

function show(element, text, error = false) {
  element.textContent = text;
  element.classList.toggle('error', error);
}

call('GET', '/session')
  .then(user => show($('who'), `Вы вошли как ${user.handle}. Операции доступны только администраторам (ADMIN_HANDLES).`))
  .catch(() => show($('who'), 'Войдите на сайте, затем откройте эту страницу снова.', true));

$('sync').addEventListener('click', async () => {
  show($('sync-result'), 'Импорт…');
  try {
    show($('sync-result'), JSON.stringify(await call('POST', '/admin/problems/sync'), null, 2));
  } catch (error) {
    show($('sync-result'), error.message, true);
  }
});

$('run').addEventListener('click', async () => {
  show($('result'), 'Выполняется…');
  show($('output'), '');
  try {
    const result = await call('POST', '/admin/console', { script: $('script').value });
    show($('result'), result.error ?? result.result ?? 'null', result.error !== null);
    show($('output'), result.output);
  } catch (error) {
    show($('result'), error.message, true);
  }
});
