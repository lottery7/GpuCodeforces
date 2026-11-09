(function () {
  const labels = {
    queued: 'В очереди',
    compiling: 'Компиляция',
    checking: 'Проверка',
    measuring: 'Замер времени',
    accepted: 'Принято',
    compile: 'Ошибка компиляции',
    wrong: 'Неверный ответ',
    runtime: 'Ошибка выполнения',
    timeout: 'Превышено время',
    memory: 'Превышена память',
    system: 'Ошибка системы',
    'measurement-error': 'Замер недоступен'
  };
  const difficultyLabels = { easy: 'Легкая', medium: 'Средняя', hard: 'Сложная' };
  const dateFormatter = new Intl.DateTimeFormat('ru-RU', {
    timeZone: 'Europe/Moscow', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit'
  });

  function param(ctx, name, fallback = '') {
    return (typeof ctx.params?.get === 'function' ? ctx.params.get(name) : ctx.params?.[name]) || fallback;
  }

  function number(value, digits = 4) {
    if (!Number.isFinite(Number(value)) || value === null || value === '') return '-';
    const formatted = Number(value).toFixed(digits);
    return digits === 4 ? formatted.replace(/0$/, '') : formatted;
  }

  function signed(value) {
    return value === null || value === undefined ? '-' : `${value > 0 ? '+' : ''}${value}`;
  }

  function date(value) {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? '-' : dateFormatter.format(parsed);
  }

  function duration(contest) {
    const minutes = Math.max(0, Math.round((new Date(contest.endAt) - new Date(contest.startAt)) / 60000));
    const hours = Math.floor(minutes / 60);
    return `${hours ? `${hours} ч` : ''}${hours && minutes % 60 ? ' ' : ''}${minutes % 60 ? `${minutes % 60} мин` : ''}` || '0 мин';
  }

  function countdown(value) {
    const seconds = Math.max(0, Math.floor((new Date(value) - Date.now()) / 1000));
    const days = Math.floor(seconds / 86400);
    if (days) return `${days} д ${Math.floor(seconds % 86400 / 3600)} ч`;
    return [Math.floor(seconds / 3600), Math.floor(seconds % 3600 / 60), seconds % 60].map(part => String(part).padStart(2, '0')).join(':');
  }

  function table(headings, rows, label, className = '') {
    return `<div class="table-wrap"><table class="data-table ${className}" aria-label="${label}"><thead><tr>${headings.map(heading => `<th scope="col">${heading}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>`;
  }

  function tabs(ctx, entries, selected, label) {
    return `<nav class="tabs" aria-label="${label}">${entries.map(([id, title, path]) => ctx.link(path, title, `tab${id === selected ? ' active' : ''}`)).join('')}</nav>`;
  }

  function empty(title, description, action = '') {
    return `<div class="empty"><h2>${title}</h2>${description ? `<p class="muted">${description}</p>` : ''}${action}</div>`;
  }

  function message(ctx, key) {
    const value = ctx.view?.formMessages?.[key];
    const text = typeof value === 'string' ? value : value?.message || value?.text || '';
    const type = typeof value === 'object' && value?.type === 'success' ? 'success' : 'error';
    return `<div id="form-message" class="${type}" tabindex="-1" role="alert" aria-live="polite">${ctx.escape(text)}</div>`;
  }

  function verdict(status) {
    const tone = status === 'accepted' ? 'success' : ['queued', 'compiling', 'checking', 'measuring'].includes(status) ? 'muted' : 'error';
    return `<span class="verdict ${tone}">${labels[status] || '-'}</span>`;
  }

  function userLink(ctx, user) {
    return ctx.link(`/profile?user=${encodeURIComponent(user.id)}`, user.handle);
  }

  function registration(ctx, contest, phase) {
    if (phase === 'finished') return '<span class="muted">Завершен</span>';
    if (ctx.user && ctx.M.joined(contest.id, ctx.user.id)) return `<span class="success">${phase === 'upcoming' ? 'Вы зарегистрированы' : 'Вы участвуете'}</span>`;
    if (!contest.registrationOpen) return '<span class="muted">Регистрация закрыта</span>';
    return `<button type="button" class="btn secondary" data-action="join-contest" data-id="${ctx.escape(contest.id)}">Зарегистрироваться</button>`;
  }

  function contests(ctx) {
    const requested = param(ctx, 'tabs', param(ctx, 'tab', 'live'));
    const selected = ['live', 'upcoming', 'finished', 'mine'].includes(requested) ? requested : 'live';
    const mine = selected === 'mine';
    const query = param(ctx, 'q');
    const normalizedQuery = query.trim().toLowerCase();
    const listPath = tab => `/contests?${new URLSearchParams({tabs: tab, ...(query ? {q: query} : {})})}`;
    const createPath = ctx.user ? '/contests/new' : '/auth?return=%2Fcontests%2Fnew';
    const heading = ctx.header('Соревнования', '', ctx.link(createPath, 'Создать контест', 'btn primary'));
    const entries = [['live', 'Текущие', listPath('live')], ['upcoming', 'Будущие', listPath('upcoming')], ['finished', 'Завершенные', listPath('finished')]];
    if (ctx.user || mine) entries.push(['mine', 'Мои', listPath('mine')]);
    const nav = tabs(ctx, entries, selected, 'Список соревнований');
    if (mine && !ctx.user) return `${heading}${nav}${empty('Войдите в аккаунт', 'Здесь будут контесты, в которых вы указаны автором.', ctx.link(`/auth?return=${encodeURIComponent(listPath('mine'))}`, 'Войти', 'btn primary'))}`;
    const search = `<div class="toolbar"><div class="search"><input type="search" id="contest-search" data-query="q" aria-label="Найти соревнование" placeholder="Название контеста" value="${ctx.escape(query)}"></div>${query ? ctx.link(`/contests?tabs=${selected}`, 'Сбросить', 'filter-reset') : ''}</div>`;
    const items = ctx.M.contests().filter(item => (mine ? (item.authorIds || [item.ownerId]).includes(ctx.user.id) : ctx.M.phase(item.id) === selected) && item.title.toLowerCase().includes(normalizedQuery)).sort((a, b) => selected === 'finished' ? new Date(b.endAt) - new Date(a.endAt) : mine ? new Date(b.startAt) - new Date(a.startAt) : new Date(a.startAt) - new Date(b.startAt));
    if (!items.length) return `${heading}${nav}${search}${ctx.renderList(empty(normalizedQuery ? 'Соревнования не найдены' : mine ? 'У вас пока нет контестов' : 'Соревнований нет', normalizedQuery ? 'Измените запрос или выберите другую вкладку.' : mine ? 'Создайте первый контест.' : selected === 'live' ? 'Сейчас нет активных соревнований.' : selected === 'upcoming' ? 'Новые соревнования еще не объявлены.' : 'Завершенных соревнований пока нет.'), 'Соревнований пока нет.')}`;
    const phases = {upcoming: 'До начала', live: 'Идет', finished: 'Завершен'};
    const rows = items.map(contest => `<tr><td>${ctx.link(`/contest/${encodeURIComponent(contest.id)}`, contest.title)}</td><td><time datetime="${ctx.escape(contest.startAt)}">${date(contest.startAt)}</time></td><td>${duration(contest)}</td><td>${contest.problemIds.length}</td>${mine ? `<td>${phases[ctx.M.phase(contest.id)]}</td>` : selected !== 'finished' ? `<td>${registration(ctx, contest, selected)}</td>` : ''}</tr>`);
    return `${heading}${nav}${search}${ctx.renderList(table(['Название', 'Начало, МСК', 'Длительность', 'Задачи', ...(mine ? ['Статус'] : selected !== 'finished' ? ['Участие'] : [])], rows, 'Список соревнований', 'contest-list'), 'Соревнований пока нет.')}`;
  }

  function moscowInput(value) {
    if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value || '')) return value;
    const stamp = Date.parse(value);
    return Number.isFinite(stamp) ? new Date(stamp + 3 * 3600000).toISOString().slice(0, 16) : '';
  }

  function contestEditor(ctx, id) {
    const returnTo = id ? `/contest/${encodeURIComponent(id)}/edit` : '/contests/new';
    const title = id ? 'Настройки контеста' : 'Новый контест';
    if (!ctx.user) return `${ctx.header(title)}${empty('Войдите в аккаунт', 'Любой пользователь может создавать контесты.', ctx.link(`/auth?return=${encodeURIComponent(returnTo)}`, 'Войти', 'btn primary'))}`;
    const item = id ? ctx.M.contest(id) : null;
    if (id && !item) return `${ctx.header('Контест не найден')}${ctx.link('/contests?tabs=mine', 'К моим контестам', 'btn secondary')}`;
    if (id && !ctx.M.canEditContest(id)) return `${ctx.header('Настройки недоступны')}<p class="notice">Изменять контест могут его авторы до начала соревнования.</p>${ctx.link(`/contest/${encodeURIComponent(id)}`, 'К контесту', 'btn secondary')}`;
    const pending = ctx.view?.contestValues?.[id || 'new'];
    const values = pending || item || {};
    const creatorId = item?.ownerId || ctx.user.id;
    const authorIds = Array.from(new Set(Array.isArray(values.authorIds) ? values.authorIds : item?.authorIds || [creatorId]));
    const users = ctx.M.users();
    const selectedAuthors = authorIds.map(authorId => users.find(user => user.id === authorId)).filter(Boolean);
    const authorQuery = String(ctx.view?.authorQuery || '');
    const normalizedAuthorQuery = authorQuery.trim().toLowerCase();
    const matchedAuthors = normalizedAuthorQuery ? users.filter(user => user.handle.toLowerCase().includes(normalizedAuthorQuery)) : [];
    const authorResults = matchedAuthors.filter(user => !authorIds.includes(user.id)).sort((a, b) => Number(b.handle.toLowerCase().startsWith(normalizedAuthorQuery)) - Number(a.handle.toLowerCase().startsWith(normalizedAuthorQuery)) || a.handle.localeCompare(b.handle)).slice(0, 5);
    const selectedAuthorRows = selectedAuthors.map(user => `<div class="contest-selected-author"><input type="hidden" name="authorIds" value="${ctx.escape(user.id)}"><span>${userLink(ctx, user)}${user.id === creatorId ? ' <span class="small muted">(создатель)</span>' : ''}</span><button type="button" class="text-link" data-action="remove-contest-author" data-id="${ctx.escape(user.id)}" aria-label="Убрать ${ctx.escape(user.handle)}"${authorIds.length < 2 ? ' disabled title="Нужен хотя бы один автор"' : ''}>Убрать</button></div>`).join('');
    const authorSearchResults = normalizedAuthorQuery ? `<div id="contest-author-results" class="contest-author-results" aria-label="Результаты поиска авторов">${authorResults.length ? authorResults.map(user => `<div class="contest-author-result"><span>${userLink(ctx, user)}</span><button type="button" class="text-link" data-action="add-contest-author" data-id="${ctx.escape(user.id)}" aria-label="Добавить ${ctx.escape(user.handle)}">Добавить</button></div>`).join('') : `<p class="small muted" role="status">${matchedAuthors.length ? 'Все найденные пользователи уже добавлены.' : 'Пользователь не найден.'}</p>`}</div>` : '';
    const authors = `<fieldset class="contest-authors"><legend>Авторы</legend><p class="small muted" role="status">${authorIds.includes(ctx.user.id) ? 'Авторы могут изменять контест до начала.' : 'После сохранения вы потеряете право редактирования.'}</p><div class="contest-selected-authors">${selectedAuthorRows}</div><input id="contest-author-search" type="search" data-author-query="true" aria-label="Найти автора по нику" placeholder="Ник пользователя" value="${ctx.escape(authorQuery)}">${authorSearchResults}</fieldset>`;
    const profiles = ctx.M.gpuProfiles();
    const gpuId = values.gpuId || profiles[0]?.id || '';
    const start = moscowInput(values.startAt ?? new Date(Date.now() + 3600000).toISOString());
    const minutes = values.durationMinutes ?? (item ? Math.round((new Date(item.endAt) - new Date(item.startAt)) / 60000) : 120);
    const selected = new Set(Array.isArray(values.problemIds) ? values.problemIds : []);
    const problems = ctx.M.contestProblems();
    new Set([...(item?.problemIds || []), ...selected]).forEach(problemId => {
      if (problems.some(problem => problem.id === problemId)) return;
      const snapshot = item?.problemSnapshots?.[problemId];
      if (snapshot) problems.push({...snapshot, id: problemId, visibility: 'private'});
    });
    const filters = ctx.view?.contestPicker || {};
    const query = String(filters.q || '');
    const difficulty = filters.difficulty || 'all';
    const tag = filters.tag || 'all';
    const visibility = filters.visibility || 'all';
    const selectedOnly = Boolean(filters.selectedOnly);
    const tags = [...new Set(problems.flatMap(problem => problem.tags || []))].sort((a, b) => a.localeCompare(b));
    const visible = problems.filter(problem => (!query.trim() || (problem.code + ' ' + problem.title).toLowerCase().includes(query.trim().toLowerCase())) && (difficulty === 'all' || problem.difficulty === difficulty) && (tag === 'all' || problem.tags?.includes(tag)) && (visibility === 'all' || (problem.visibility || 'public') === visibility) && (!selectedOnly || selected.has(problem.id)));
    const choices = visible.map(problem => `<label class="problem-choice"><input type="checkbox" name="problemIds" value="${ctx.escape(problem.id)}"${selected.has(problem.id) ? ' checked' : ''}><span>${ctx.escape(problem.code + '. ' + problem.title)}<span class="small muted">${difficultyLabels[problem.difficulty] || ctx.escape(problem.difficulty)} / ${problem.visibility === 'private' ? 'Частная' : 'Публичная'}</span></span></label>`).join('');
    const options = (entries, value) => entries.map(([key, label]) => `<option value="${ctx.escape(key)}"${key === value ? ' selected' : ''}>${ctx.escape(label)}</option>`).join('');
    const activeFilters = query || difficulty !== 'all' || tag !== 'all' || visibility !== 'all' || selectedOnly;
    const picker = `<fieldset class="problem-choices"><legend>Задачи</legend><div class="toolbar contest-picker-summary"><span id="contest-selected-count" class="small muted" aria-live="polite">Выбрано: ${selected.size}</span><label class="checkbox-label"><input id="contest-selected-only" type="checkbox" data-contest-filter="selectedOnly"${selectedOnly ? ' checked' : ''}>Только выбранные</label></div><div class="toolbar contest-picker-filters"><div class="search"><input id="contest-problem-search" type="search" data-contest-filter="q" aria-label="Найти задачу для контеста" placeholder="Название или номер задачи" value="${ctx.escape(query)}"></div><select id="contest-problem-difficulty" data-contest-filter="difficulty" aria-label="Сложность задач">${options([['all', 'Сложность'], ...Object.entries(difficultyLabels)], difficulty)}</select><select id="contest-problem-tag" data-contest-filter="tag" aria-label="Тема задач">${options([['all', 'Тема'], ...tags.map(value => [value, value])], tag)}</select><select id="contest-problem-visibility" data-contest-filter="visibility" aria-label="Видимость задач">${options([['all', 'Все задачи'], ['public', 'Публичные'], ['private', 'Частные']], visibility)}</select>${activeFilters ? '<button class="text-link filter-reset" type="button" data-action="reset-contest-filters">Сбросить</button>' : ''}</div><div class="contest-problem-list">${choices || '<p class="muted">Нет задач по этим фильтрам.</p>'}</div></fieldset>`;
    const back = id ? `/contest/${encodeURIComponent(id)}` : '/contests?tabs=mine';
    return `${ctx.header(title, '', ctx.link(back, id ? 'К контесту' : 'Мои контесты', 'btn secondary'))}<form class="form contest-form contest-builder" data-form="contest" data-id="${ctx.escape(id || '')}"><div class="contest-settings"><label class="field"><span>Название</span><input id="contest-title" name="title" maxlength="100" required value="${ctx.escape(values.title || '')}"></label><div class="field-pair"><label class="field"><span>Начало (МСК)</span><input id="contest-start" name="startAt" type="datetime-local" required value="${ctx.escape(start)}"></label><label class="field"><span>Длительность, минут</span><input id="contest-duration" name="durationMinutes" type="number" min="1" step="1" required value="${ctx.escape(minutes)}"></label></div>${authors}<label class="field"><span>GPU</span><select id="contest-gpu" name="gpuId" required>${profiles.map(profile => `<option value="${ctx.escape(profile.id)}"${profile.id === gpuId ? ' selected' : ''}>${ctx.escape(profile.label)}</option>`).join('')}</select><span class="small muted">Одна GPU для всех задач. Настройки доступны до начала контеста.</span></label></div><div class="contest-task-picker">${picker}</div><div class="contest-builder-actions">${message(ctx, 'contest')}<button class="btn primary" type="submit"${problems.length ? '' : ' disabled'}>${id ? 'Сохранить' : 'Создать контест'}</button></div></form>`;
  }

  function contestResult(ctx, problemId, contestId) {
    const best = ctx.M.best(problemId, { userId: ctx.user.id, contestId });
    const latest = ctx.M.submissions({ userId: ctx.user.id, problemId, contestId })[0];
    if (!latest) return { status: '<span class="muted">Не решена</span>', time: '-' };
    const primary = best || latest;
    const pending = ['queued', 'compiling', 'checking', 'measuring'];
    const oldProfile = primary.status === 'accepted' && !ctx.M.canCompare(primary);
    const text = best ? 'Принято' : oldProfile ? 'Принято, старый профиль' : primary.status === 'measurement-error' ? 'Корректно, замер недоступен' : labels[primary.status] || primary.status;
    const tone = best ? 'accepted' : oldProfile ? 'neutral' : pending.includes(primary.status) ? 'pending' : 'failed';
    const primaryLink = ctx.link(`/submission/${primary.id}`, text, `verdict ${tone}`);
    const next = best && latest.id !== best.id && latest.status !== 'accepted' ? `<div class="small">${ctx.link(`/submission/${latest.id}`, `Последняя: ${labels[latest.status] || latest.status}`)}</div>` : '';
    return { status: `${primaryLink}${next}`, time: best ? ctx.link(`/submission/${best.id}`, number(best.timeMs)) : '-' };
  }

  function contestTasks(ctx, contest, phase) {
    if (phase === 'upcoming') return empty('Задачи откроются после начала', `Начало ${date(contest.startAt)} МСК.`);
    const rows = contest.problemIds.map((id, index) => {
      const problem = ctx.M.problem(id, contest.id);
      if (!problem) return '';
      const path = `/problem/${encodeURIComponent(id)}${phase === 'live' || !ctx.M.problem(id) ? `?contest=${encodeURIComponent(contest.id)}` : ''}`;
      const best = ctx.M.leaderboard(id, contest.id)[0]?.submission;
      const own = ctx.user ? contestResult(ctx, id, contest.id) : null;
      return `<tr><td>${ctx.link(path, `${String.fromCharCode(65 + index)}. ${problem.title}`)}</td><td>${difficultyLabels[problem.difficulty] || ctx.escape(problem.difficulty)}</td>${own ? `<td>${own.status}</td><td>${own.time}</td>` : ''}<td>${best && ctx.M.canCompare(best) ? number(best.timeMs) : '-'}</td></tr>`;
    });
    return `${phase === 'finished' ? '<p class="notice">Соревнование завершено. Публичные задачи можно решать в практике.</p>' : ''}${table(['Задача', 'Сложность', ...(ctx.user ? ['Ваш статус', 'Ваше лучшее время, мс'] : []), 'Рекорд контеста, мс'], rows, 'Задачи соревнования')}`;
  }

  function contestStandings(ctx, contest, phase) {
    if (phase === 'upcoming') return empty('Таблица появится после начала', 'Результатов соревнования пока нет.');
    const ranked = contest.scoring === 'speedup';
    const people = ctx.M.standings(contest.id);
    if (!people.length) return empty('Результатов пока нет', 'Таблица обновится после первых посылок.');
    const headings = [...(ranked ? ['Место'] : []), 'Участник', 'Решено', ...(ranked ? ['Сумма ускорений'] : []), ...contest.problemIds.map((id, index) => {
      const problem = ctx.M.problem(id, contest.id);
      const path = `/problem/${encodeURIComponent(id)}${phase === 'live' || !ctx.M.problem(id) ? `?contest=${encodeURIComponent(contest.id)}` : ''}`;
      return `<a href="#${ctx.escape(path)}" title="${ctx.escape(problem?.title || id)}">${String.fromCharCode(65 + index)}, мс</a>`;
    })];
    const rows = people.map(row => `<tr${ctx.user?.id === row.user.id ? ' class="own-row"' : ''}>${ranked ? `<td>${row.rank ?? '-'}</td>` : ''}<td>${userLink(ctx, row.user)}${ctx.user?.id === row.user.id ? ' <span class="small muted">вы</span>' : ''}</td><td>${row.solved}</td>${ranked ? `<td${row.score === null || row.score === undefined ? '' : ` title="Сумма ускорений: ${Number(row.score).toFixed(9)}x"`}>${row.score === null || row.score === undefined ? '-' : `${number(row.score)}x`}</td>` : ''}${contest.problemIds.map(id => {
      const submission = row.results[id];
      const comparable = submission && ctx.M.canCompare(submission);
      const value = comparable ? number(submission.timeMs) : '-';
      return `<td>${comparable && ctx.user?.id === row.user.id ? ctx.link(`/submission/${submission.id}`, value) : value}</td>`;
    }).join('')}</tr>`);
    return `<p class="small muted">${phase === 'finished' ? 'Итоговая таблица.' : 'Результаты обновляются после проверки посылок.'}</p>${table(headings, rows, phase === 'finished' ? 'Итоговая таблица соревнования' : 'Текущая таблица соревнования')}`;
  }

  function contestRules(ctx, contest) {
    const problems = contest.problemIds.map(id => ctx.M.problem(id, contest.id)).filter(Boolean);
    const gpu = [...new Set(problems.map(problem => (contest.benchmarks?.[problem.id] || problem.benchmark).gpu))].join(', ');
    const references = contest.scoring === 'speedup' && ctx.M.phase(contest.id) !== 'upcoming' ? table(['Задача', 'Размер входа', 'Эталон, мс'], problems.map(problem => {
      const benchmark = contest.benchmarks?.[problem.id] || problem.benchmark;
      return `<tr><td>${ctx.escape(problem.title)}</td><td>${ctx.escape(benchmark.caseLabel)}</td><td>${number(benchmark.referenceMs)}</td></tr>`;
    }), 'Эталонные времена соревнования') : '';
    return `<div class="stack"><p>Решения принимаются на C++/CUDA. Сначала проверяется корректность, затем измеряется время выполнения.</p><p>Конфигурация: ${ctx.escape(gpu)}. Для каждой задачи указаны размер входа, версия проверки и методика замера. Результаты разных конфигураций не сравниваются.</p>${contest.scoring === 'speedup' ? '<p>Демо-правило: выше участник с большим числом решенных задач. При равенстве сравнивается сумма ускорений относительно эталона: эталонное время / лучшее время участника. Одинаковые результаты получают одинаковое место.</p>' : '<p>Формула мест не задана. Таблица показывает число решенных задач и лучшее время по каждой задаче.</p>'}${references}<p>Конкурсная отправка доступна зарегистрированным участникам во время соревнования. После завершения публичные задачи можно решать в практике; эти посылки не меняют итоговую таблицу.</p><p class="small muted">Изменение рейтинга показывается после расчета результатов соревнования.</p></div>`;
  }

  function contest(ctx, id) {
    const item = ctx.M.contest(id);
    if (!item) return `${ctx.header('Соревнование не найдено')}${ctx.link('/contests', 'К соревнованиям', 'btn secondary')}`;
    const phase = ctx.M.phase(id);
    const selected = ['tasks', 'standings', 'rules'].includes(param(ctx, 'tab', 'tasks')) ? param(ctx, 'tab', 'tasks') : 'tasks';
    const base = `/contest/${encodeURIComponent(id)}`;
    const time = phase === 'finished' ? '<span>Завершен</span>' : `<span>${phase === 'upcoming' ? 'До начала' : 'До конца'} <time data-countdown="${ctx.escape(phase === 'upcoming' ? item.startAt : item.endAt)}" data-contest-id="${ctx.escape(id)}">${countdown(phase === 'upcoming' ? item.startAt : item.endAt)}</time></span>`;
    const nav = tabs(ctx, [['tasks', 'Задачи', `${base}?tab=tasks`], ['standings', 'Таблица', `${base}?tab=standings`], ['rules', 'Правила', `${base}?tab=rules`]], selected, 'Разделы соревнования');
    const content = selected === 'standings' ? contestStandings(ctx, item, phase) : selected === 'rules' ? contestRules(ctx, item) : contestTasks(ctx, item, phase);
    const gpu = ctx.M.gpuProfiles().find(profile => profile.id === item.gpuId)?.label || [...new Set(Object.values(item.benchmarks || {}).map(benchmark => benchmark.gpu))].join(', ');
    const authors = (item.authorIds || [item.ownerId]).map(authorId => ctx.M.users().find(user => user.id === authorId)).filter(Boolean);
    const actions = registration(ctx, item, phase) + (ctx.M.canEditContest(id) ? ctx.link(`${base}/edit`, 'Изменить', 'btn secondary') : '');
    return `${ctx.header(item.title, '', actions)}<div class="toolbar small"><span class="muted">${date(item.startAt)} - ${date(item.endAt)} МСК</span>${time}${gpu ? `<span class="muted">${ctx.escape(gpu)}</span>` : ''}${authors.length ? `<span class="muted">Авторы: ${authors.map(user => userLink(ctx, user)).join(', ')}</span>` : ''}</div>${message(ctx, 'contest')}${nav}${content}`;
  }

  function rating(ctx) {
    const query = param(ctx, 'q').trim();
    const all = ctx.M.users().filter(user => user.rating !== null && user.rating !== undefined).sort((a, b) => b.rating - a.rating || a.handle.localeCompare(b.handle));
    const filtered = all.filter(user => user.handle.toLowerCase().includes(query.toLowerCase()));
    const pageSize = 20;
    const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));
    const page = Math.max(1, Math.min(pageCount, Number.parseInt(param(ctx, 'page', '1'), 10) || 1));
    const items = filtered.slice((page - 1) * pageSize, page * pageSize);
    const form = `<form class="toolbar rating-search" data-form="rating-search"><input type="search" name="q" value="${ctx.escape(query)}" placeholder="Ник участника" aria-label="Найти участника"><button class="btn secondary" type="submit">Найти</button>${query ? ctx.link('/rating', 'Сбросить') : ''}</form>`;
    const rows = items.map(user => {
      const index = all.indexOf(user);
      const rank = all.findIndex(item => item.rating === user.rating) + 1;
      const contest = user.ratingContestId && ctx.M.contest(user.ratingContestId);
      return `<tr${user.id === ctx.user?.id ? ' class="own-row"' : ''}><td>${rank || index + 1}</td><td>${userLink(ctx, user)}${user.id === ctx.user?.id ? ' <span class="small muted">вы</span>' : ''}</td><td>${user.rating}</td><td>${contest && user.ratingDelta !== null && user.ratingDelta !== undefined ? ctx.link(`/contest/${encodeURIComponent(contest.id)}?tab=standings`, signed(user.ratingDelta)) : signed(user.ratingDelta)}</td></tr>`;
    });
    const pager = pageCount > 1 ? `<nav class="toolbar" aria-label="Страницы рейтинга">${page > 1 ? ctx.link(`/rating?q=${encodeURIComponent(query)}&page=${page - 1}`, 'Назад', 'btn secondary') : ''}<span class="small muted">${page} из ${pageCount}</span>${page < pageCount ? ctx.link(`/rating?q=${encodeURIComponent(query)}&page=${page + 1}`, 'Далее', 'btn secondary') : ''}</nav>` : '';
    return `${ctx.header('Рейтинг участников')}${form}${ctx.renderList((rows.length ? table(['Место', 'Участник', 'Рейтинг', 'После последнего контеста'], rows, 'Рейтинг участников') : empty('Участники не найдены', 'Измените запрос.')) + pager, 'Участников с рейтингом пока нет.')}`;
  }

  function submissionTime(ctx, item) {
    if (item.status !== 'accepted' || item.timeMs === null || item.timeMs === undefined) return '-';
    if (!ctx.M.canCompare(item)) return '<span class="small muted">Старый профиль</span>';
    return number(item.timeMs);
  }

  function profile(ctx) {
    const requested = param(ctx, 'user');
    const person = requested ? ctx.M.users().find(item => item.id === requested || item.handle === requested) : ctx.user;
    if (!person) return requested ? `${ctx.header('Участник не найден')}${ctx.link('/rating', 'К рейтингу', 'btn secondary')}` : `${ctx.header('Профиль')}${empty('Войдите в аккаунт', 'После входа здесь появятся ваши результаты.', ctx.link('/auth?return=%2Fprofile', 'Войти', 'btn primary'))}`;
    const data = ctx.M.profile(person.id);
    const userQuery = encodeURIComponent(person.id);
    const ratingContest = data.ratingContestId && ctx.M.contest(data.ratingContestId);
    const summary = `<div class="metrics"><span>Рейтинг: <strong>${data.rating === null || data.rating === undefined ? 'Еще не участвовал' : data.rating}</strong>${ratingContest && data.ratingDelta !== null && data.ratingDelta !== undefined ? ` <span class="small">${ctx.link(`/contest/${encodeURIComponent(ratingContest.id)}?tab=standings`, `${signed(data.ratingDelta)} после ${ratingContest.title}`)}</span>` : ''}</span><span>Решено: ${ctx.link(`/problems?user=${userQuery}&status=solved`, String(data.solved))}</span><span>Контесты: <strong>${data.contestCount}</strong></span></div>`;
    const recent = data.recentSubmissions || [];
    const recentRows = recent.slice(0, 5).map(item => {
      const problem = ctx.M.problem(item.problemId, item.contestId || undefined);
      const problemPath = `/problem/${encodeURIComponent(item.problemId)}${item.contestId ? `?contest=${encodeURIComponent(item.contestId)}` : ''}`;
      return `<tr><td>${ctx.link(`/submission/${item.id}`, `#${item.id}`)}</td><td>${ctx.link(problemPath, problem?.title || item.problemId)}</td><td>${verdict(item.status)}</td><td>${submissionTime(ctx, item)}</td><td><time datetime="${ctx.escape(item.createdAt)}">${date(item.createdAt)}</time></td></tr>`;
    });
    const history = data.contestHistory || [];
    const historyRows = history.map(item => {
      const contest = ctx.M.contest(item.contestId);
      return `<tr><td>${ctx.link(`/contest/${encodeURIComponent(item.contestId)}?tab=standings`, contest?.title || item.contestId)}</td><td>${item.phase === 'finished' ? item.rank ?? '-' : item.phase === 'upcoming' ? 'До начала' : 'Идет'}</td><td>${signed(item.ratingDelta)}</td><td>${item.ratingAfter ?? (item.phase === 'finished' ? 'Ожидает расчета' : '-')}</td></tr>`;
    });
    return `${ctx.header(person.handle, '', ctx.link(`/submissions?user=${userQuery}`, ctx.user?.id === person.id ? 'Мои посылки' : 'Посылки участника', 'btn secondary'))}${summary}<section class="stack"><h2>Последние посылки</h2>${recentRows.length ? table(['Посылка', 'Задача', 'Вердикт', 'Время GPU, мс', 'Отправлено, МСК'], recentRows, 'Последние посылки участника') : empty('Посылок пока нет', ctx.user?.id === person.id ? 'Выберите задачу и отправьте первое решение.' : 'Участник еще не отправлял решений.', ctx.user?.id === person.id ? ctx.link('/problems', 'Выбрать задачу', 'btn primary') : '')}<h2>Соревнования</h2>${historyRows.length ? table(['Соревнование', 'Место', 'Изменение рейтинга', 'Рейтинг после контеста'], historyRows, 'История соревнований участника') : '<p class="muted">Еще не участвовал в соревнованиях.</p>'}</section>`;
  }

  function auth(ctx) {
    const mode = ['login', 'register', 'reset'].includes(param(ctx, 'mode', 'login')) ? param(ctx, 'mode', 'login') : 'login';
    const returnTo = param(ctx, 'return');
    const suffix = returnTo ? `&return=${encodeURIComponent(returnTo)}` : '';
    const nav = tabs(ctx, [['login', 'Вход', `/auth?mode=login${suffix}`], ['register', 'Регистрация', `/auth?mode=register${suffix}`]], mode, 'Доступ к аккаунту');
    const password = mode !== 'reset' ? `<label class="field"><span>Пароль</span><input id="auth-password" type="password" name="password" required minlength="8" autocomplete="${mode === 'register' ? 'new-password' : 'current-password'}" aria-describedby="password-hint"><span id="password-hint" class="small muted">Не меньше 8 символов.</span></label>` : '';
    const fields = mode === 'login' ? `<label class="field"><span>Ник или почта</span><input name="identifier" required autocomplete="username" value="${ctx.escape(ctx.view?.authIdentifier || '')}"></label>` : `${mode === 'register' ? '<label class="field"><span>Ник участника</span><input name="handle" required minlength="3" maxlength="24" pattern="[A-Za-z0-9_]{3,24}" autocomplete="username" aria-describedby="handle-hint"><span id="handle-hint" class="small muted">Латинские буквы, цифры и _.</span></label>' : ''}<label class="field"><span>Почта</span><input name="email" type="email" required autocomplete="email"></label>`;
    return `${ctx.header(mode === 'reset' ? 'Восстановление доступа' : 'Аккаунт')}<div class="auth-form">${mode !== 'reset' ? nav : ''}<form class="form" data-form="${mode}">${fields}${password}${message(ctx, mode)}<button class="btn primary" type="submit">${mode === 'register' ? 'Зарегистрироваться' : mode === 'reset' ? 'Отправить инструкцию' : 'Войти'}</button></form>${mode === 'login' ? `<p>${ctx.link(`/auth?mode=reset${suffix}`, 'Забыли пароль?')}</p><p class="small muted">Демо-аккаунты: alex_cuda (с историей), new_cuda (пустой аккаунт). Пароль произвольный, от 8 символов. Он не сохраняется.</p>` : mode === 'reset' ? `<p>${ctx.link(`/auth?mode=login${suffix}`, 'Вернуться ко входу')}</p><p class="small muted">В прототипе письма не отправляются.</p>` : '<p class="small muted">Регистрация создает локальный демо-профиль. Пароль не сохраняется.</p>'}</div>`;
  }

  window.ExtraScreens = {
    render(route, ctx) {
      if (route === '/contests') return contests(ctx);
      if (route === '/contests/new') return contestEditor(ctx, null);
      if (/^\/contest\/[^/]+\/edit$/.test(route)) return contestEditor(ctx, decodeURIComponent(route.split('/')[2]));
      if (route.startsWith('/contest/')) return contest(ctx, decodeURIComponent(route.slice('/contest/'.length)));
      if (route === '/rating') return rating(ctx);
      if (route === '/profile') return profile(ctx);
      if (route === '/auth') return auth(ctx);
      return null;
    }
  };
})();
