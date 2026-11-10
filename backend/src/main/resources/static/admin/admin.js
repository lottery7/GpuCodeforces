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

function reveal(element) {
  const rect = element.getBoundingClientRect();
  const viewport = window.visualViewport;
  const top = viewport?.offsetTop ?? 0;
  const bottom = top + (viewport?.height ?? window.innerHeight);
  if (rect.top < top || rect.bottom > bottom) {
    element.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' });
  }
}

call('GET', '/session')
  .then(user => show($('who'), `Вы вошли как ${user.handle}. Операции доступны только администраторам (ADMIN_HANDLES).`))
  .catch(() => show($('who'), 'Войдите на сайте, затем откройте эту страницу снова.', true));

$('sync').addEventListener('click', async () => {
  if ($('sync').disabled) return;
  $('sync').disabled = true;
  $('sync-result').hidden = true;
  show($('sync-status'), 'Импорт...');
  try {
    show($('sync-result'), JSON.stringify(await call('POST', '/admin/problems/sync'), null, 2));
    show($('sync-status'), 'Импорт завершен.');
  } catch (error) {
    show($('sync-result'), error.message, true);
    show($('sync-status'), 'Импорт не выполнен.', true);
  } finally {
    $('sync').disabled = false;
    $('sync-result').hidden = false;
    reveal($('sync-result'));
  }
});

$('run').addEventListener('click', async () => {
  if ($('run').disabled) return;
  $('run').disabled = true;
  $('console-results').hidden = true;
  show($('run-status'), 'Выполняется...');
  show($('output'), '');
  try {
    const result = await call('POST', '/admin/console', { script: $('script').value });
    const failed = result.error != null;
    show($('result'), result.error ?? result.result ?? 'null', failed);
    show($('output'), result.output);
    show($('run-status'), failed ? 'Скрипт завершился с ошибкой.' : 'Выполнено.', failed);
  } catch (error) {
    show($('result'), error.message, true);
    show($('run-status'), 'Не удалось выполнить скрипт.', true);
  } finally {
    $('run').disabled = false;
    $('console-output').hidden = !$('output').textContent;
    $('console-results').hidden = false;
    reveal($('result'));
  }
});
