(function () {
  const M = window.AppModel;
  const app = document.getElementById('app');
  const pageSize = 10;
  const pending = ['queued', 'compiling', 'checking', 'measuring'];
  const statusNames = {
    queued: 'В очереди', compiling: 'Компиляция', checking: 'Проверка', measuring: 'Замер времени',
    accepted: 'Принято', compile: 'Ошибка компиляции', wrong: 'Неверный ответ', runtime: 'Ошибка выполнения',
    timeout: 'Превышено время', memory: 'Превышена память', system: 'Ошибка системы',
    'measurement-error': 'Корректно, замер недоступен'
  };
  const difficultyNames = {easy: 'Легкая', medium: 'Средняя', hard: 'Сложная'};
  const difficultyClass = d => d === 'easy' ? 'difficulty-easy' : d === 'medium' ? 'difficulty-medium' : d === 'hard' ? 'difficulty-hard' : '';
  const difficultyTag = d => `<span class="difficulty ${difficultyClass(d)}">${escape(difficultyNames[d] || d)}</span>`;
  const view = {previewOpen: false, listState: 'filled', outcome: 'accepted', runOutcome: 'accepted', connection: 'online', contestValues: {}, authorQuery: '', contestPicker: {q: '', difficulty: 'all', tag: 'all', visibility: 'all', selectedOnly: false}, runs: {}, undo: {}, formMessages: {}, authMode: 'login', authReturn: ''};
  const navIcons = {
    '/problems': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6v4H9z"/><path d="M4 7h16v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z"/><path d="M9 12h6M9 16h6"/></svg>',
    '/contests': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6"/><path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18"/><path d="M4 22h16"/><path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22"/><path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22"/><path d="M18 2H6v7a6 6 0 0 0 12 0V2Z"/></svg>',
    '/submissions': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2 11 13"/><path d="M22 2 15 22l-4-9-9-4Z"/></svg>',
    '/rating': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"/><path d="M7 15l3-3 3 3 4-6"/></svg>'
  };
  const jobs = new Map();
  let toastTimer;
  let lastPhase;

  function escape(value) {
    return String(value ?? '').replace(/[&<>"']/g, char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]));
  }

  function route() {
    const raw = location.hash.slice(1) || '/';
    const separator = raw.indexOf('?');
    return {path: separator < 0 ? raw : raw.slice(0, separator), params: new URLSearchParams(separator < 0 ? '' : raw.slice(separator + 1))};
  }

  function url(path, values = {}) {
    const params = new URLSearchParams();
    Object.entries(values).forEach(([key, value]) => {
      if (value !== null && value !== undefined && value !== '' && value !== 'all') params.set(key, value);
    });
    return path + (params.size ? '?' + params.toString() : '');
  }

  function link(path, text, className = 'text-link') {
    return `<a href="#${escape(path)}" class="${escape(className)}">${escape(text)}</a>`;
  }

  function header(title, description = '', actions = '') {
    return `<div class="page-head"><div><h1>${escape(title)}</h1>${description ? `<p class="muted">${escape(description)}</p>` : ''}</div>${actions ? `<div class="inline">${actions}</div>` : ''}</div>`;
  }

  function date(value) {
    return new Date(value).toLocaleString('ru-RU', {timeZone: 'Europe/Moscow', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit'});
  }

  function time(value) {
    return Number.isFinite(Number(value)) && value !== null ? Number(value).toFixed(4).replace(/0$/, '') : '-';
  }

  function accuracyText(tolerance) {
    const value = typeof tolerance === 'string' ? tolerance : JSON.stringify(tolerance);
    const match = /^abs\(actual - expected\) <= ([\de.+-]+) \+ ([\de.+-]+) \* abs\(expected\)$/.exec(value);
    return match ? `Абсолютный допуск: ${match[1]}, относительный: ${match[2]}.` : value;
  }

  function verdict(submission) {
    const tone = submission.status === 'accepted' ? 'accepted' : pending.includes(submission.status) ? 'pending' : 'failed';
    return `<span class="verdict ${tone}">${escape(statusNames[submission.status] || submission.status)}</span>`;
  }

  function notify(message) {
    const toast = document.getElementById('toast');
    toast.textContent = message;
    toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {toast.hidden = true;}, 3500);
  }

  function formError(message) {
    const target = document.getElementById('form-message');
    if (target) {
      target.textContent = message;
      target.hidden = !message;
      target.className = 'error';
      if (message) target.focus();
    }
  }

  function navigate(path, replace = false) {
    saveEditor();
    if (replace) {
      history.replaceState(null, '', '#' + path);
      render();
    } else if (location.hash === '#' + path) render();
    else location.hash = path;
  }

  function patchQuery(patch, replace = false) {
    const current = route();
    navigate(url(current.path, {...Object.fromEntries(current.params), ...patch}), replace);
  }

  function contextOf(params) {
    return params.get('contest') || 'practice';
  }

  function problemUrl(id, context, tab = 'statement') {
    return url('/problem/' + id, {contest: context === 'practice' ? null : context, tab: tab === 'statement' ? null : tab});
  }

  function userName(id) {
    return M.users().find(user => user.id === id)?.handle || id;
  }

  function allBest(problemId, userId) {
    const current = M.problem(problemId).benchmark;
    return M.submissions({problemId, userId}).filter(item => M.canCompare(item) && item.profileId === current.profileId && item.testVersion === current.testVersion && item.benchmarkId === current.id).sort((a, b) => a.timeMs - b.timeMs || a.id - b.id)[0] || null;
  }

  function pagination(total, params) {
    const pages = Math.ceil(total / pageSize);
    if (pages <= 1) return '';
    const page = Math.min(pages, Math.max(1, Number(params.get('page')) || 1));
    return `<nav class="pagination" aria-label="Страницы списка">${Array.from({length: pages}, (_, index) => link(url(route().path, {...Object.fromEntries(params), page: index + 1}), index + 1, page === index + 1 ? 'active' : '')).join('')}</nav>`;
  }

  function listState(content, emptyText = 'Записей пока нет.') {
    if (view.listState === 'loading') return '<div class="empty" role="status">Загрузка...</div>';
    if (view.listState === 'error') return '<div class="error" role="alert">Не удалось загрузить список. <button class="text-link" data-action="retry-list">Повторить</button></div>';
    if (view.listState === 'empty') return `<div class="empty">${escape(emptyText)}</div>`;
    return content;
  }

  function shell(path, content) {
    const current = M.user();
    const entries = [['/problems', 'Задачи'], ['/contests', 'Соревнования'], ['/submissions', 'Посылки'], ['/rating', 'Рейтинг']];
    const active = target => path === target || (target === '/problems' && path.startsWith('/problem/')) || (target === '/submissions' && path.startsWith('/submission/')) || (target === '/contests' && (path.startsWith('/contest/') || path.startsWith('/contests/')));
    const navigation = entries.map(([target, label]) => `<a href="#${target}" ${active(target) ? 'class="active" aria-current="page"' : ''}><span class="nav-icon" aria-hidden="true">${navIcons[target] || ''}</span><span>${label}</span></a>`).join('');
    const account = current ? link('/profile', current.handle) : '<button class="text-link" data-action="signin">Войти</button>';
    const footer = new URLSearchParams(location.search).get('presentation') === '1' ? '' : `<footer class="site-footer"><details class="prototype-tools" ${view.previewOpen ? 'open' : ''}><summary>Состояния шаблонов</summary>${prototypePanel(path)}</details></footer>`;
    return `<header class="site-header"><div class="header-inner">${link('/', 'GPU Forces', 'brand')}<nav class="main-nav" aria-label="Основная навигация">${navigation}</nav><div class="account">${account}</div><details class="mobile-nav"><summary>Меню</summary><nav aria-label="Мобильная навигация">${navigation}${account}</nav></details></div></header><main class="container" id="content" tabindex="-1">${view.connection === 'offline' ? '<div class="error" role="alert">Связь потеряна. Новые отправки недоступны; показаны последние полученные результаты. <button class="text-link" data-action="reconnect">Подключиться снова</button></div>' : ''}${content}</main>${footer}`;
  }

  function prototypePanel(path) {
    const current = M.user();
    const role = current?.id || 'guest';
    const personas = [['guest', 'Гость'], ['alex_cuda', 'Участник'], ['new_cuda', 'Новый аккаунт']];
    if (current && !personas.some(([id]) => id === current.id)) personas.push([current.id, current.handle]);
    const supportsList = ['/problems', '/submissions', '/contests', '/rating'].includes(path) || path.startsWith('/problem/') && route().params.get('tab') === 'submissions';
    const option = (value, label, selected) => `<option value="${value}" ${value === selected ? 'selected' : ''}>${label}</option>`;
    const selectedSubmission = path.startsWith('/submission/') ? M.submission(path.split('/')[2]) : null;
    const contestId = path.startsWith('/contest/') ? path.split('/')[2] : route().params.get('contest');
    return `<div class="prototype-panel"><label>Участник<select data-preview="user" aria-label="Режим участника">${personas.map(([id, label]) => option(id, label, role)).join('')}</select></label>${supportsList ? `<label>Список<select data-preview="list" aria-label="Состояние списка">${[['filled', 'Заполнен'], ['loading', 'Загрузка'], ['empty', 'Пусто'], ['error', 'Ошибка']].map(([id, label]) => option(id, label, view.listState)).join('')}</select></label>` : ''}<label>Новая посылка<select data-preview="outcome" aria-label="Результат новой посылки">${[['accepted', 'Принято'], ['faster', 'Быстрее рекорда'], ['slower', 'Медленнее рекорда'], ...Object.entries(statusNames).filter(([key]) => !pending.includes(key) && key !== 'accepted'), ['stale', 'Старый профиль']].map(([id, label]) => option(id, label, view.outcome)).join('')}</select></label><label>Проверка примера<select data-preview="run" aria-label="Результат проверки примера">${[['accepted', 'Успех'], ['wrong', 'Неверный ответ'], ['compile', 'Ошибка компиляции']].map(([id, label]) => option(id, label, view.runOutcome)).join('')}</select></label><label>Соединение<select data-preview="connection" aria-label="Состояние соединения">${[['online', 'Есть связь'], ['offline', 'Нет связи']].map(([id, label]) => option(id, label, view.connection)).join('')}</select></label>${contestId && M.contest(contestId) ? `<label>Фаза соревнования<select data-preview="phase" aria-label="Фаза соревнования" data-id="${contestId}">${[['upcoming', 'До начала'], ['live', 'Идет'], ['finished', 'Завершено']].map(([id, label]) => option(id, label, M.phase(contestId))).join('')}</select></label>` : ''}${selectedSubmission ? `<label>Состояние посылки<select data-preview="submission" aria-label="Состояние посылки" data-id="${selectedSubmission.id}">${Object.entries(statusNames).map(([id, label]) => option(id, label, selectedSubmission.status)).join('')}</select></label>` : ''}</div>`;
  }

  function bank(params, showStats = true) {
    const target = params.get('user') ? M.findUser(params.get('user')) : M.user();
    const q = params.get('q') || '';
    const difficulty = params.get('difficulty') || 'all';
    const status = params.get('status') || 'all';
    const tag = params.get('tag') || 'all';
    const sort = params.get('sort') || 'code';
    const hasFilters = q || difficulty !== 'all' || status !== 'all' || tag !== 'all';
    let items = M.problems().map(problem => ({...problem, personal: target ? allBest(problem.id, target.id) : null, solved: target ? M.solved(problem.id, target.id) : false})).filter(problem => `${problem.title} ${problem.code} ${problem.tags.join(' ')}`.toLowerCase().includes(q.toLowerCase()) && (difficulty === 'all' || problem.difficulty === difficulty) && (tag === 'all' || problem.tags.includes(tag)) && (status === 'all' || (status === 'solved' ? problem.solved : !problem.solved)));
    const order = {easy: 0, medium: 1, hard: 2};
    items.sort((a, b) => sort === 'difficulty' ? order[a.difficulty] - order[b.difficulty] : sort === 'time' ? (a.personal?.timeMs ?? Infinity) - (b.personal?.timeMs ?? Infinity) : String(a.code).localeCompare(String(b.code), undefined, {numeric: true}));
    const page = Math.min(Math.max(1, Math.ceil(items.length / pageSize)), Math.max(1, Number(params.get('page')) || 1));
    const visible = items.slice((page - 1) * pageSize, page * pageSize);
    const allTags = [...new Set(M.problems().flatMap(problem => problem.tags))];
    const options = (values, selected) => values.map(([value, label]) => `<option value="${escape(value)}" ${selected === value ? 'selected' : ''}>${escape(label)}</option>`).join('');
    const headLink = (label, value) => link(url('/problems', {...Object.fromEntries(params), sort: value, page: null}), label, '');
    const rows = visible.map(problem => `<tr><td>${problem.solved ? '<span class="accepted" aria-label="Решена">✓</span>' : ''}</td><td>${link('/problem/' + problem.id, problem.code + '. ' + problem.title, 'task-link')}<div class="subline">${problem.tags.map(value => link(url('/problems', {...Object.fromEntries(params), tag: value, page: null}), value, 'tag')).join(' / ')}</div></td><td>${difficultyTag(problem.difficulty)}</td><td>${M.users().filter(user => M.solved(problem.id, user.id)).length}</td>${target ? `<td class="mono">${problem.personal ? link('/submission/' + problem.personal.id, time(problem.personal.timeMs), '') : '-'}</td>` : ''}</tr>`).join('');
    const table = rows ? `<div class="panel table-wrap"><table class="data-table problem-table${target ? '' : ' guest'}"><thead><tr><th scope="col" aria-label="Решена"></th><th scope="col">${headLink('Задача', 'code')}</th><th scope="col">${headLink('Сложность', 'difficulty')}</th><th scope="col">Решили</th>${target ? `<th scope="col">${headLink('Личный рекорд, мс', 'time')}</th>` : ''}</tr></thead><tbody>${rows}</tbody></table></div>${pagination(items.length, params)}` : '<div class="empty">Задачи не найдены. Измените запрос или сбросьте фильтры.</div>';
    const totalCount = M.problems().length;
    const solvedCount = target ? M.problems().filter(problem => M.solved(problem.id, target.id)).length : 0;
    const percent = totalCount ? Math.round(solvedCount / totalCount * 100) : 0;
    const streak = target ? M.streak(target.id) : 0;
    const cards = showStats ? `<div class="progress-cards">
      <section class="panel progress-card">
        <span class="progress-card-label">Решено задач</span>
        <span class="progress-card-value"><strong>${solvedCount}</strong><span class="muted"> из ${totalCount}</span></span>
        <div class="progress-track" role="progressbar" aria-valuenow="${percent}" aria-valuemin="0" aria-valuemax="100" aria-label="Прогресс решения задач"><div class="progress-fill" style="width:${percent}%"></div></div>
        <span class="progress-card-caption small muted">${percent}% задач из пула</span>
      </section>
      <section class="panel progress-card streak-card">
        <span class="progress-card-label"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3 1.072-2.143 2.5-3.5 3.5-3.5S15 7 16 8s.5 1.5.5 2.5c0 1.38-.5 2.5-.5 2.5s1-1 1.5-2.5c.5 1.5 1 3 1 4.5A6 6 0 1 1 9 11c.5.5 1 1.5 1 2.5 0 .64-.042 1.073-.5 1z"/></svg>Серия</span>
        <span class="progress-card-value"><strong>${streak}</strong><span class="muted"> дн.</span></span>
        <span class="progress-card-caption small muted">дней подряд с принятым решением${target ? '' : ' (войдите, чтобы считать)'}</span>
      </section>
    </div>` : '';
    const toolbar = `<div class="toolbar"><div class="search"><input type="search" id="problem-search" data-query="q" aria-label="Найти задачу" placeholder="Название или номер задачи" value="${escape(q)}"></div><select data-filter="difficulty" aria-label="Сложность">${options([['all', 'Сложность'], ...Object.entries(difficultyNames)], difficulty)}</select>${target ? `<select data-filter="status" aria-label="Статус решения">${options([['all', 'Статус'], ['solved', 'Решена'], ['unsolved', 'Не решена']], status)}</select>` : ''}<select data-filter="tag" aria-label="Тема">${options([['all', 'Тема'], ...allTags.map(value => [value, value])], tag)}</select>${hasFilters ? link(url('/problems', {user: params.get('user')}), 'Сбросить', 'filter-reset') : ''}</div>`;
    const list = listState(table, 'В банке пока нет задач.');
    const body = showStats ? `<div class="problems-layout"><div class="problems-main">${toolbar}${list}</div><aside class="progress-stack">${cards}</aside></div>` : `<div class="problems-main">${toolbar}${list}</div>`;
    return `${header(target && params.has('user') ? 'Задачи: ' + target.handle : 'Задачи')}${body}`;
  }

  function submissionTable(items, {inProblem = false, showUser = false} = {}) {
    if (!items.length) return '<div class="empty">Посылок пока нет.</div>';
    return `<div class="panel table-wrap"><table class="data-table"><thead><tr><th scope="col">Посылка</th>${inProblem ? '' : '<th scope="col">Задача</th>'}${showUser ? '<th scope="col">Участник</th>' : ''}<th scope="col">Вердикт</th><th scope="col">Время GPU, мс</th><th scope="col">Отправлено, МСК</th></tr></thead><tbody>${items.map(item => {
      const problem = M.problem(item.problemId, item.contestId || null);
      return `<tr><td class="mono">${link('/submission/' + item.id, '#' + item.id, '')}</td>${inProblem ? '' : `<td>${link(problemUrl(item.problemId, item.contestId || 'practice'), problem?.title || item.problemId)}<div class="subline">${item.contestId ? escape(M.contest(item.contestId)?.title || item.contestId) : 'Практика'}</div></td>`}${showUser ? `<td>${link('/profile?user=' + encodeURIComponent(item.userId), userName(item.userId))}</td>` : ''}<td>${verdict(item)}${item.status === 'accepted' && !M.canCompare(item) ? '<div class="subline">Старый профиль проверки</div>' : ''}</td><td class="mono">${item.status === 'accepted' ? time(item.timeMs) : '-'}</td><td>${date(item.createdAt)}</td></tr>`;
    }).join('')}</tbody></table></div>`;
  }

  function mainPage() {
    const current = M.user();
    const latest = M.submissions({}).slice(0, 8);
    const submissionsList = latest.length ? submissionTable(latest, {showUser: true}) : '<div class="panel"><div class="empty">Посылок пока нет.</div></div>';
    const upcoming = M.contests().filter(item => M.phase(item.id) === 'upcoming').sort((a, b) => new Date(a.startAt) - new Date(b.startAt))[0] || null;
    let contestBlock;
    if (upcoming) {
      const minutes = Math.max(0, Math.round((new Date(upcoming.endAt) - new Date(upcoming.startAt)) / 60000));
      const hours = Math.floor(minutes / 60);
      const rest = minutes % 60;
      const durationText = `${hours ? hours + ' ч' : ''}${hours && rest ? ' ' : ''}${rest ? rest + ' мин' : ''}` || '0 мин';
      const meta = [['Начало', date(upcoming.startAt) + ' МСК'], ['Длительность', durationText], ['Задачи', String(upcoming.problemIds.length)]].map(([label, value]) => `<li><span>${label}</span><span>${escape(value)}</span></li>`).join('');
      const register = !upcoming.registrationOpen ? '<p class="muted contest-register">Регистрация закрыта</p>' : current && M.joined(upcoming.id, current.id) ? '<p class="success contest-register">Вы зарегистрированы</p>' : `<button class="btn primary contest-register" data-action="join-contest" data-id="${escape(upcoming.id)}">Зарегистрироваться</button>`;
      contestBlock = `<div class="panel dashboard-contest"><h2>Ближайший контест</h2><div class="dashboard-contest-title">${link('/contest/' + encodeURIComponent(upcoming.id), upcoming.title, 'task-link')}</div><ul class="contest-meta">${meta}</ul><div class="contest-countdown">До начала <time data-countdown="${escape(upcoming.startAt)}">--:--:--</time></div>${register}</div>`;
    } else {
      contestBlock = '<div class="panel dashboard-contest"><h2>Ближайший контест</h2><p class="muted">Ближайших контестов пока нет.</p></div>';
    }
    const top = M.users().filter(user => user.rating !== null && user.rating !== undefined).sort((a, b) => b.rating - a.rating || a.handle.localeCompare(b.handle));
    const rankOf = value => top.findIndex(item => item.rating === value) + 1;
    const ownIndex = current ? top.findIndex(user => user.id === current.id) : -1;
    let ratingTitle = 'Лучшие по рейтингу';
    let ratingUsers;
    if (ownIndex >= 0) {
      ratingTitle = 'Ваш рейтинг';
      ratingUsers = top.slice(Math.max(0, Math.min(top.length - 5, ownIndex - 2)), Math.max(5, Math.min(top.length, ownIndex + 3))).slice(0, 5);
    } else {
      ratingUsers = top.slice(0, 5);
    }
    const ratingRows = ratingUsers.map(user => `<tr${user.id === current?.id ? ' class="own-row"' : ''}><td>${rankOf(user.rating)}</td><td>${link('/profile?user=' + encodeURIComponent(user.id), user.handle)}${user.id === current?.id ? ' <span class="small muted">вы</span>' : ''}</td><td class="mono">${user.rating}</td></tr>`);
    const ratingsBlock = top.length ? `<div class="panel"><h2>${ratingTitle}</h2><div class="table-wrap"><table class="data-table"><thead><tr><th scope="col">Место</th><th scope="col">Участник</th><th scope="col">Рейтинг</th></tr></thead><tbody>${ratingRows.join('')}</tbody></table></div></div>` : '<div class="panel"><h2>Ваш рейтинг</h2><p class="muted">Рейтингов пока нет.</p></div>';
    return `${header('Главная')}<div class="dashboard"><section class="dashboard-main"><div class="dashboard-head"><h2>Последние посылки</h2>${link('/submissions', 'Все посылки')}</div>${submissionsList}</section><aside class="dashboard-side">${contestBlock}${ratingsBlock}</aside></div>`;
  }

  function submissions(params, problem = null, context = 'practice') {
    const current = M.user();
    const target = params.get('user') ? M.findUser(params.get('user')) : current;
    if (!target) return `${header('Мои посылки')}<div class="empty">Войдите, чтобы посмотреть свои посылки. ${link('/auth?return=' + encodeURIComponent('/submissions'), 'Войти')}</div>`;
    const filters = {userId: target.id};
    if (problem) {filters.problemId = problem.id; filters.contestId = context === 'practice' ? null : context;}
    else {
      if (params.get('problem')) filters.problemId = params.get('problem');
      if (params.get('contest')) filters.contestId = params.get('contest') === 'practice' ? null : params.get('contest');
    }
    let items = M.submissions(filters);
    const selectedStatus = params.get('verdict') || 'all';
    if (selectedStatus !== 'all') items = items.filter(item => item.status === selectedStatus);
    const page = Math.min(Math.max(1, Math.ceil(items.length / pageSize)), Math.max(1, Number(params.get('page')) || 1));
    const option = (value, label, selected) => `<option value="${value}" ${value === selected ? 'selected' : ''}>${escape(label)}</option>`;
    const filterProblems = new Map(M.problems().map(item => [item.id, item]));
    M.submissions({userId: target.id}).forEach(item => {
      const source = M.problem(item.problemId, item.contestId || null);
      if (source && !filterProblems.has(source.id)) filterProblems.set(source.id, source);
    });
    const controls = `<div class="toolbar">${problem ? '' : `<select data-filter="problem" aria-label="Задача">${option('all', 'Все задачи', params.get('problem') || 'all')}${Array.from(filterProblems.values(), item => option(item.id, item.title, params.get('problem'))).join('')}</select><select data-filter="contest" aria-label="Контекст посылки">${option('all', 'Практика и контесты', params.get('contest') || 'all')}${option('practice', 'Практика', params.get('contest'))}${M.contests().map(item => option(item.id, item.title, params.get('contest'))).join('')}</select>`}<select data-filter="verdict" aria-label="Вердикт">${option('all', 'Все вердикты', selectedStatus)}${Object.entries(statusNames).map(([key, label]) => option(key, label, selectedStatus)).join('')}</select></div>`;
    return `${problem ? '' : header(target.id === current?.id ? 'Мои посылки' : 'Посылки: ' + target.handle)}${controls}${listState(submissionTable(items.slice((page - 1) * pageSize, page * pageSize), {inProblem: !!problem}))}${pagination(items.length, params)}`;
  }

  function leaders(problem, context) {
    const rows = M.leaderboard(problem.id, context === 'practice' ? null : context);
    return `<p class="small muted">${escape(context === 'practice' ? 'Практика' : M.contest(context)?.title)} / ${escape(problem.benchmark.gpu)} / ${escape(problem.benchmark.caseLabel)} / ${escape(problem.benchmark.testVersion)}</p>${rows.length ? `<div class="panel table-wrap"><table class="data-table"><thead><tr><th scope="col">Место</th><th scope="col">Участник</th><th scope="col">Лучшее время, мс</th><th scope="col">Посылка</th></tr></thead><tbody>${rows.map(row => `<tr class="${row.user.id === M.user()?.id ? 'own-row' : ''}"><td>${row.rank}</td><td>${link('/profile?user=' + encodeURIComponent(row.user.id), row.user.handle)}</td><td class="mono">${time(row.submission.timeMs)}</td><td>${link('/submission/' + row.submission.id, '#' + row.submission.id)}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty">Сопоставимых принятых решений пока нет.</div>'}`;
  }

  function runKey(problemId, context) {
    return (M.user()?.id || 'guest') + ':' + problemId + ':' + context;
  }

  function consolePanel(body) {
    return `<section class="run-console" aria-label="Вывод проверки"><div class="tool-window-head">Вывод</div><div class="tool-window-body">${body}</div></section>`;
  }

  function runResult(problemId, context) {
    const result = view.runs[runKey(problemId, context)];
    if (!result) return '';
    let body;
    if (result.code !== M.draft(problemId, context).code) body = '<div class="notice">Код изменен. Проверьте пример снова.</div>';
    else if (result.status === 'running') body = '<div class="notice" role="status">Проверяем пример...</div>';
    else if (result.status === 'accepted') body = '<div class="success" role="status">Пример пройден (демо).</div>';
    else body = `<div class="error" role="status">${result.status === 'compile' ? 'Ошибка компиляции на примере (демо).' : 'Ответ на примере не совпал (демо).'}</div>`;
    return consolePanel(body);
  }

  function problemScreen(id, params) {
    const problem = M.problem(id, params.get('contest') || null);
    if (!problem) return notFound('Задача не найдена');
    const context = contextOf(params);
    const contest = context === 'practice' ? null : M.contest(context);
    if (context !== 'practice' && (!contest || !contest.problemIds.includes(id))) return notFound('Задача не найдена в этом контесте');
    const phase = contest ? M.phase(context) : null;
    if (phase === 'upcoming') return `${header(contest.title)}<p>Задачи откроются после начала соревнования.</p>${link('/contest/' + context, 'К соревнованию')}`;
    const tab = params.get('tab') || 'statement';
    const current = M.user();
    const draft = M.draft(id, context);
    const canSubmit = !contest || phase === 'live' && current && M.joined(context);
    const practiceAvailable = !!M.problem(id);
    const primaryAction = phase === 'finished' ? practiceAvailable ? '<button class="btn primary" data-action="practice-problem">Продолжить в практике</button>' : '' : !current ? '<button class="btn primary" data-action="submit">Войти и отправить</button>' : !canSubmit ? '<button class="btn primary" data-action="join-contest" data-id="' + context + '">Участвовать</button>' : '<button class="btn primary" data-action="submit">Отправить</button>';
    const statement = `<section class="statement statement-pane" id="statement" aria-label="Условие задачи"><div class="tool-window-head">Условие</div><div class="statement-body"><p>${escape(problem.statement)}</p><p class="formula">${escape(problem.formula)}</p><h2>Контракт решения</h2><pre class="example">${escape(problem.signature)};</pre><p>${escape(problem.contract || 'Указатели указывают на память GPU. Обвязка передает данные и размеры.')}</p><p class="small">Размеры входа: ${escape(problem.limits.input || '1 <= N <= 2^24')}.</p><p class="small">${escape(accuracyText(problem.tolerance))}</p><h2>Пример</h2><pre class="example">${escape(problem.example)}</pre><details class="methodology"><summary>Среда и замер</summary><p>${escape(problem.benchmark.gpu)}. ${escape(problem.benchmark.caseLabel)}. ${problem.benchmark.warmups} прогревов, ${problem.benchmark.runs} прогонов. Метрика: ${escape(problem.benchmark.method)}, мс. Эталон: ${time(problem.benchmark.referenceMs)} мс. Передачи CPU/GPU не входят в замер. Профиль частот фиксирован.</p><p class="small muted">${escape(problem.benchmark.compiler)} / ${escape(problem.benchmark.profileId)} / ${escape(problem.benchmark.testVersion)}</p></details></div></section>`;
    const editor = `<section class="editor-section" id="editor"><div class="panel editor-panel"><div class="editor-title"><span class="editor-file-tab active"><span class="file-tab-mark" aria-hidden="true">CU</span><span>solution.cu</span></span><label class="text-link file-button">Выбрать файл<input id="code-file" type="file" accept=".cu,.cpp" aria-label="Загрузить код из файла"></label></div><div class="code-surface"><div class="editor-gutter" aria-hidden="true"><div class="gutter-content"></div></div><div class="editor-code-area"><div class="code-active-line" aria-hidden="true"></div><pre class="code-highlight" aria-hidden="true"><code></code></pre><textarea id="solution-code" class="code-editor" data-problem="${id}" data-context="${context}" data-owner="${current?.id || 'guest'}" aria-label="Код решения на C++ и CUDA" spellcheck="false" autocapitalize="off" autocomplete="off" wrap="off">${escape(draft.code)}</textarea></div></div><div class="editor-actions"><button class="text-link" data-action="reset-code">Восстановить шаблон</button><button class="btn secondary run-btn" data-action="run"><svg width="13" height="13" viewBox="0 0 16 16" aria-hidden="true" fill="currentColor"><path d="M4 2.5v11l9-5.5z"/></svg>Проверить пример</button>${primaryAction}</div><div class="editor-status"><span class="status-left save-state" id="save-state">${M.storageError() ? 'Черновик только в этой вкладке' : 'Черновик сохранен'}</span><span class="status-right"><span class="cursor-position" aria-live="off"></span><span>CUDA / UTF-8</span></span></div></div><div id="run-result" class="run-result">${runResult(id, context)}</div>${view.undo[runKey(id, context)] ? '<div class="notice" id="reset-notice">Код заменен. <button class="text-link" data-action="undo-reset">Отменить</button></div>' : ''}<a href="#statement" class="back-to-statement" data-anchor="statement">К условию</a></section>`;
    const tabs = `<nav class="tabs problem-tabs" aria-label="Разделы задачи">${[['statement', 'Условие и решение'], ['submissions', contest ? 'Мои посылки в контесте' : 'Мои посылки'], ['leaders', 'Лидеры']].map(([value, label]) => link(problemUrl(id, context, value), label, 'tab' + (tab === value ? ' active' : ''))).join('')}</nav>`;
    const metadata = `<div class="problem-meta"><span>${difficultyTag(problem.difficulty)}</span><span>Время: ${problem.limits.timeSeconds} с</span><span>Память GPU: ${problem.limits.memoryMb} MB</span></div>`;
    return `<div class="breadcrumbs">${link(contest ? '/contest/' + context : '/problems', contest ? contest.title : 'Практика')}${contest && phase === 'finished' ? ' / Завершено' : ''}</div>${header(problem.code + '. ' + problem.title, '', tab === 'statement' ? '<a class="editor-anchor text-link" href="#editor" data-anchor="editor">К редактору</a>' : '')}${metadata}${tabs}${phase === 'finished' ? `<p class="notice">${practiceAvailable ? 'Соревнование завершено. Новые решения можно отправлять в практике.' : 'Соревнование завершено. Прием решений закрыт.'}</p>` : ''}${tab === 'statement' ? `<div class="problem-workspace">${statement}${editor}</div>` : tab === 'submissions' ? submissions(params, problem, context) : leaders(problem, context)}`;
  }

  function submissionScreen(id) {
    const item = M.submission(id);
    if (!item) return notFound('Посылка не найдена');
    const problem = M.problem(item.problemId, item.contestId || null);
    if (!problem) return notFound('Посылка недоступна');
    const context = item.contestId || 'practice';
    const own = M.user()?.id === item.userId;
    const benchmark = item.benchmarkSnapshot || problem.benchmark;
    const eligible = M.canCompare(item);
    const accepted = item.status === 'accepted';
    const earlier = M.submissions({userId: item.userId, problemId: item.problemId, contestId: item.contestId || null}).filter(candidate => candidate.id !== item.id && M.canCompare(candidate) && new Date(candidate.createdAt) <= new Date(item.createdAt)).sort((a, b) => a.timeMs - b.timeMs)[0];
    let comparison = '';
    if (eligible && earlier) {
      const delta = (item.timeMs / earlier.timeMs - 1) * 100;
      comparison = `<p class="comparison">${delta < -.01 ? 'Новый личный рекорд: быстрее на ' + Math.abs(delta).toFixed(1) + '%.' : delta > .01 ? 'Медленнее личного рекорда на ' + delta.toFixed(1) + '%.' : 'Повторяет личный рекорд.'} Предыдущий: ${time(earlier.timeMs)} мс.</p>`;
    }
    const steps = pending.includes(item.status) ? `<div class="submission-steps" aria-label="Этапы проверки">${['Очередь', 'Компиляция', 'Корректность', 'Замер GPU'].map((label, index) => `<span class="${index < pending.indexOf(item.status) ? 'done' : index === pending.indexOf(item.status) ? 'current' : ''}">${label}</span>`).join('')}</div>` : '';
    const diagnosis = !accepted && !pending.includes(item.status) ? `<pre class="diagnostic">${escape(formatDiagnostic(item.diagnostic) || diagnostic(item.status))}</pre>${own && ['system', 'measurement-error'].includes(item.status) ? `<button class="btn secondary" data-action="recheck" data-id="${item.id}">Повторить проверку</button>` : ''}` : '';
    const codeOpen = !accepted && !pending.includes(item.status);
    const source = own ? `<details class="disclosure" ${codeOpen ? 'open' : ''}><summary>Код решения</summary><pre class="source-code">${window.EditorView ? window.EditorView.highlight(item.code) : escape(item.code)}</pre></details>` : '<p class="muted small">Исходный код доступен автору посылки.</p>';
    const editAction = own ? `<button class="btn secondary" data-action="edit-submission" data-id="${item.id}">Открыть в редакторе</button>` : '';
    const testCount = accepted || item.status === 'measurement-error' ? `${item.passedTests || item.totalTests || 24}/${item.totalTests || 24}` : '';
    const settings = [['GPU', benchmark.gpu], ['Размер входа', benchmark.caseLabel], ['Профиль', item.profileId], ['Версия тестов', item.testVersion], ['Компилятор', benchmark.compiler], ['Прогревы / прогоны', benchmark.warmups + ' / ' + benchmark.runs], ['Метрика', benchmark.method + ', мс']];
    return `${header('Посылка #' + item.id, '', editAction)}<p class="breadcrumbs">${link(problemUrl(item.problemId, context), problem.title)} / ${link('/profile?user=' + encodeURIComponent(item.userId), userName(item.userId))} / ${item.contestId ? link('/contest/' + item.contestId, M.contest(item.contestId).title) : 'Практика'} / ${date(item.createdAt)} МСК</p><section class="submission-summary" aria-live="polite"><h2>${verdict(item)}${testCount ? ' <span class="muted">' + testCount + '</span>' : ''}</h2>${accepted ? `<p class="metrics">Время GPU: <strong class="mono">${time(item.timeMs)} мс</strong></p>${comparison}${!eligible ? '<p class="notice">Результат получен на старом профиле проверки и не участвует в текущей таблице.</p>' : ''}` : ''}${steps}${diagnosis}</section><p class="small muted">${escape(benchmark.gpu)} / ${escape(benchmark.caseLabel)} / ${escape(item.testVersion)}</p>${accepted ? link(problemUrl(item.problemId, context, 'leaders'), 'Таблица лидеров') : ''}${source}<details class="disclosure"><summary>Среда и методика замера</summary><table class="settings-table"><tbody>${settings.map(([label, value]) => `<tr><th scope="row">${escape(label)}</th><td>${escape(value)}</td></tr>`).join('')}</tbody></table><p class="small muted">Время выполнения ядра. Передача CPU/GPU не учитывается. Профиль частот фиксирован.</p></details>`;
  }

  function formatDiagnostic(value) {
    if (!value) return '';
    if (typeof value === 'string') return value;
    return [value.message, value.test ? 'Тест: ' + value.test : '', value.line ? 'Строка: ' + value.line : '', value.absoluteError ? 'Абсолютное отклонение: ' + value.absoluteError : '', value.relativeError ? 'Относительное отклонение: ' + value.relativeError : ''].filter(Boolean).join('\n');
  }

  function diagnostic(status) {
    return ({compile: 'solution.cu(4): error: identifier "idx" is undefined', wrong: 'Ответ не прошел проверку точности на тесте 7. Данные скрытого теста недоступны.', runtime: 'CUDA error: an illegal memory access was encountered', timeout: 'Превышен лимит выполнения. Проверьте циклы и синхронизацию.', memory: 'Превышен лимит GPU-памяти.', system: 'GPU-воркер недоступен. Ошибка инфраструктуры, результат решения не определен.', 'measurement-error': 'Корректность проверена. Замер не завершился из-за сбоя GPU-воркера.'})[status] || '';
  }

  function notFound(message = 'Страница не найдена') {
    return `${header(message)}${link('/problems', 'К задачам')}`;
  }

  function saveEditor() {
    const editor = document.getElementById('solution-code');
    if (editor && editor.dataset.owner === (M.user()?.id || 'guest')) M.saveDraft(editor.dataset.problem, editor.dataset.context, editor.value);
  }

  function focusSnapshot() {
    const element = document.activeElement;
    return element?.id ? {id: element.id, start: element.selectionStart, end: element.selectionEnd, top: element.scrollTop, left: element.scrollLeft, direction: element.selectionDirection, value: element.value} : null;
  }

  function render(preserveFocus = false) {
    const focused = preserveFocus ? focusSnapshot() : null;
    const oldContestForm = document.querySelector('[data-form="contest"]');
    const contestScroll = oldContestForm ? {id: oldContestForm.dataset.id || 'new', settings: oldContestForm.querySelector('.contest-settings')?.scrollTop || 0, tasks: oldContestForm.querySelector('.contest-problem-list')?.scrollTop || 0} : null;
    const oldEditor = document.getElementById('solution-code');
    const editorSize = oldEditor ? {problem: oldEditor.dataset.problem, context: oldEditor.dataset.context, owner: oldEditor.dataset.owner, height: oldEditor.style.height, top: oldEditor.scrollTop, left: oldEditor.scrollLeft, start: oldEditor.selectionStart, end: oldEditor.selectionEnd, direction: oldEditor.selectionDirection} : null;
    const current = route();
    const path = current.path;
    let content;
    if (path === '/') content = mainPage();
    else if (path === '/auth') content = bank(new URLSearchParams(), false);
    else if (path === '/problems') content = bank(current.params);
    else if (path === '/submissions') content = submissions(current.params);
    else if (path.startsWith('/problem/')) content = problemScreen(decodeURIComponent(path.split('/')[2]), current.params);
    else if (path.startsWith('/submission/')) content = submissionScreen(path.split('/')[2]);
    else content = window.ExtraScreens.render(path, {M, user: M.user(), params: current.params, escape, header, link, view, renderList: listState}) || notFound();
    app.innerHTML = shell(path, content);
    if (path === '/auth') openAuth(current.params.get('mode') || 'login', current.params.get('return') || location.hash.slice(1));
    if (M.storageError()) document.getElementById('content').insertAdjacentHTML('afterbegin', '<div class="error" role="alert">Хранилище браузера недоступно. Изменения сохранятся только до обновления страницы.</div>');
    document.title = 'GPU Forces - ' + (document.querySelector('h1')?.textContent || 'Платформа');
    document.querySelector('.prototype-tools')?.addEventListener('toggle', event => {view.previewOpen = event.target.open;});
    const nextEditor = document.getElementById('solution-code');
    if (nextEditor && editorSize && nextEditor.dataset.problem === editorSize.problem && nextEditor.dataset.context === editorSize.context && nextEditor.dataset.owner === editorSize.owner) {
      if (editorSize.height) nextEditor.style.height = editorSize.height;
      nextEditor.setSelectionRange(editorSize.start, editorSize.end, editorSize.direction || 'none');
      nextEditor.scrollTop = editorSize.top;
      nextEditor.scrollLeft = editorSize.left;
    }
    if (focused) {
      const element = document.getElementById(focused.id);
      if (element) {
        if (focused.id === 'problem-search') element.value = focused.value;
        element.focus({preventScroll: true});
        if (element.setSelectionRange && focused.start !== null) element.setSelectionRange(focused.start, focused.end, focused.direction || 'none');
        element.scrollTop = focused.top;
        element.scrollLeft = focused.left;
      }
    }
    const nextContestForm = document.querySelector('[data-form="contest"]');
    if (contestScroll && nextContestForm && (nextContestForm.dataset.id || 'new') === contestScroll.id) {
      const settings = nextContestForm.querySelector('.contest-settings');
      const tasks = nextContestForm.querySelector('.contest-problem-list');
      if (settings) settings.scrollTop = contestScroll.settings;
      if (tasks) tasks.scrollTop = contestScroll.tasks;
    }
    nextContestForm?.querySelector('.contest-settings')?.addEventListener('scroll', positionAuthorResults);
    positionAuthorResults();
    window.EditorView?.mount(document.getElementById('solution-code'));
    updateCountdowns();
  }

  function refreshData() {
    const path = route().path;
    if (!path.startsWith('/auth') && path !== '/contests/new' && !/^\/contest\/[^/]+\/edit$/.test(path)) {saveEditor(); render(true);}
  }

  function resetAccountViews() {
    view.contestValues = {};
    view.authorQuery = '';
    resetContestPicker();
    view.runs = {};
    view.undo = {};
  }

  function resetContestPicker() {
    view.contestPicker = {q: '', difficulty: 'all', tag: 'all', visibility: 'all', selectedOnly: false};
  }

  function requestLogin(returnTo) {
    openAuth('login', returnTo || location.hash.slice(1) || '');
  }

  function openAuth(mode, returnTo) {
    view.authMode = ['login', 'register', 'reset'].includes(mode) ? mode : 'login';
    view.authReturn = returnTo || '';
    const dialog = document.getElementById('auth-dialog');
    dialog.innerHTML = window.ExtraScreens.authDialog({M, user: M.user(), params: new URLSearchParams(), escape, header, link, view, renderList: listState});
    if (dialog.open) {
      dialog.querySelector('.form input')?.focus({preventScroll: true});
    } else {
      dialog.showModal();
      dialog.querySelector('.form input')?.focus({preventScroll: true});
    }
  }

  function closeAuth() {
    const dialog = document.getElementById('auth-dialog');
    if (dialog.open) dialog.close();
  }

  function currentProblem() {
    const current = route();
    return {problem: M.problem(current.path.split('/')[2], current.params.get('contest') || null), context: contextOf(current.params)};
  }

  function sendSolution() {
    saveEditor();
    const {problem, context} = currentProblem();
    if (!problem) return;
    if (!M.user()) {requestLogin(location.hash.slice(1)); return;}
    if (view.connection === 'offline') {setEditorMessage('Нет связи. Черновик сохранен, попробуйте отправить после подключения.', 'error'); return;}
    const code = M.draft(problem.id, context).code;
    const result = M.submit({problemId: problem.id, contestId: context === 'practice' ? null : context, code});
    if (!result.ok) {setEditorMessage(result.error, 'error'); return;}
    M.updateSubmission(result.submission.id, {demoOutcome: view.outcome});
    navigate('/submission/' + result.submission.id);
    simulate(result.submission.id);
  }

  function simulate(id) {
    if (jobs.has(String(id))) clearInterval(jobs.get(String(id)));
    let index = Math.max(0, pending.indexOf(M.submission(id).status));
    const sequence = ['queued', 'compiling', 'checking', 'measuring', 'accepted'];
    const timer = setInterval(() => {
      const item = M.submission(id);
      if (!item || !pending.includes(item.status) || view.connection === 'offline') {
        if (!item || !pending.includes(item.status)) {clearInterval(timer); jobs.delete(String(id));}
        return;
      }
      const next = sequence[++index];
      if (!next || next === 'accepted') {
        const problem = M.problem(item.problemId, item.contestId || null);
        const best = M.best(item.problemId, {userId: item.userId, contestId: item.contestId || null});
        const outcome = item.demoOutcome || 'accepted';
        const accepted = ['accepted', 'faster', 'slower', 'stale'].includes(outcome);
        const base = best?.timeMs || problem.benchmark.referenceMs;
        const timeMs = accepted ? Number((outcome === 'faster' ? base * .85 : outcome === 'slower' ? base * 1.3 : problem.benchmark.referenceMs * .9).toFixed(3)) : null;
        M.updateSubmission(id, {status: accepted ? 'accepted' : outcome, timeMs, passedTests: accepted || outcome === 'measurement-error' ? 24 : outcome === 'wrong' ? 6 : 0, totalTests: 24, diagnostic: accepted ? '' : diagnostic(outcome), ...(outcome === 'stale' ? {profileId: 'a100-old'} : {})});
        clearInterval(timer);
        jobs.delete(String(id));
      } else M.updateSubmission(id, {status: next});
      refreshData();
    }, 650);
    jobs.set(String(id), timer);
  }

  function setEditorMessage(text, tone = 'notice') {
    const target = document.getElementById('run-result');
    if (target) target.innerHTML = consolePanel(`<div class="${tone}" role="status">${escape(text)}</div>`);
  }

  function runExample() {
    saveEditor();
    const {problem, context} = currentProblem();
    if (view.connection === 'offline') {setEditorMessage('Нет связи. Пример не отправлен на проверку.', 'error'); return;}
    const key = runKey(problem.id, context);
    const code = M.draft(problem.id, context).code;
    const owner = M.user()?.id || 'guest';
    const outcome = view.runOutcome;
    view.runs[key] = {code, status: 'running'};
    document.getElementById('run-result').innerHTML = runResult(problem.id, context);
    setTimeout(() => {
      view.runs[key] = {code, status: outcome};
      const editor = document.getElementById('solution-code');
      if (editor?.dataset.owner === owner && editor.dataset.problem === problem.id && editor.dataset.context === context) document.getElementById('run-result').innerHTML = runResult(problem.id, context);
    }, 700);
  }

  function contestData(form) {
    const data = new FormData(form);
    const key = form.dataset.id || 'new';
    const previous = view.contestValues[key]?.problemIds || M.contest(key)?.problemIds || [];
    const visible = new Set(Array.from(form.querySelectorAll('input[name="problemIds"]'), input => input.value));
    const checked = new Set(data.getAll('problemIds'));
    const selected = new Set(previous.filter(id => !visible.has(id) || checked.has(id)));
    checked.forEach(id => selected.add(id));
    return {title: data.get('title'), startAt: data.get('startAt'), durationMinutes: Number(data.get('durationMinutes')), gpuId: data.get('gpuId'), authorIds: Array.from(new Set(data.getAll('authorIds'))), problemIds: Array.from(selected)};
  }

  function updateContestFilter(target) {
    const form = target.closest('[data-form="contest"]');
    if (!form) return;
    view.contestValues[form.dataset.id || 'new'] = contestData(form);
    view.contestPicker[target.dataset.contestFilter] = target.type === 'checkbox' ? target.checked : target.value;
    const list = form.querySelector('.contest-problem-list');
    if (list) list.scrollTop = 0;
    render(true);
  }

  function updateAuthorQuery(target) {
    const form = target.closest('[data-form="contest"]');
    if (!form) return;
    view.contestValues[form.dataset.id || 'new'] = contestData(form);
    view.authorQuery = target.value;
    render(true);
  }

  function positionAuthorResults() {
    const results = document.getElementById('contest-author-results');
    const search = document.getElementById('contest-author-search');
    const settings = document.querySelector('.contest-settings');
    if (!results || !search || !settings) return;
    if (!window.matchMedia('(min-width: 800px)').matches) {
      results.style.top = '';
      results.style.bottom = '';
      results.style.maxHeight = '';
      return;
    }
    if (!results.getClientRects().length) return;
    const bounds = settings.getBoundingClientRect();
    const input = search.getBoundingClientRect();
    const field = search.closest('.contest-authors').getBoundingClientRect();
    const below = Math.max(0, bounds.bottom - input.bottom - 8);
    const above = Math.max(0, input.top - bounds.top - 8);
    const upward = below < 180 && above > below;
    results.style.top = upward ? 'auto' : '100%';
    results.style.bottom = upward ? field.bottom - input.top + 4 + 'px' : 'auto';
    results.style.maxHeight = Math.max(48, Math.min(180, upward ? above : below)) + 'px';
  }

  function changeContestAuthor(action, userId) {
    const form = document.querySelector('[data-form="contest"]');
    if (!form || !M.users().some(user => user.id === userId)) return;
    const key = form.dataset.id || 'new';
    const values = contestData(form);
    if (action === 'remove' && values.authorIds.length <= 1) return;
    values.authorIds = action === 'add' ? Array.from(new Set([...values.authorIds, userId])) : values.authorIds.filter(id => id !== userId);
    view.contestValues[key] = values;
    if (action === 'add') view.authorQuery = '';
    render();
    const search = document.getElementById('contest-author-search');
    search?.focus({preventScroll: true});
    search?.scrollIntoView({block: 'nearest', inline: 'nearest'});
  }

  function saveContest(form) {
    const id = form.dataset.id || null;
    const values = contestData(form);
    const startAt = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(values.startAt || '') ? values.startAt + '+03:00' : values.startAt;
    const result = M.saveContest(id, {...values, startAt});
    if (!result.ok) {formError(result.error); return;}
    delete view.contestValues[id || 'new'];
    view.authorQuery = '';
    resetContestPicker();
    navigate('/contest/' + result.contest.id);
    notify(id ? 'Контест сохранен.' : 'Контест создан.');
  }

  function updateCountdowns() {
    document.querySelectorAll('[data-countdown]').forEach(element => {
      const seconds = Math.max(0, Math.ceil((new Date(element.dataset.countdown) - Date.now()) / 1000));
      const h = Math.floor(seconds / 3600), m = Math.floor(seconds % 3600 / 60), s = seconds % 60;
      element.textContent = [h, m, s].map(value => String(value).padStart(2, '0')).join(':');
    });
    const current = route();
    const id = current.path.startsWith('/contest/') ? current.path.split('/')[2] : current.params.get('contest');
    const phase = id && M.contest(id) ? M.phase(id) : null;
    const changed = lastPhase && phase && phase !== lastPhase;
    lastPhase = phase;
    if (changed) {saveEditor(); render(true);}
  }

  document.addEventListener('click', event => {
    const authLink = event.target.closest('a[href^="#/auth"], a[href^="#auth"], a[href^="auth"]');
    if (authLink) {
      event.preventDefault();
      const raw = authLink.getAttribute('href').replace(/^#/, '');
      const sep = raw.indexOf('?');
      const params = new URLSearchParams(sep < 0 ? '' : raw.slice(sep + 1));
      openAuth(params.get('mode') || 'login', params.get('return') || location.hash.slice(1));
      return;
    }
    const anchor = event.target.closest('[data-anchor], .skip-link');
    if (anchor) {event.preventDefault(); const target = document.getElementById(anchor.dataset.anchor || 'content'); target?.scrollIntoView({behavior: 'smooth'}); target?.focus({preventScroll: true}); return;}
    const actionElement = event.target.closest('[data-action]');
    if (!actionElement) return;
    const action = actionElement.dataset.action;
    if (action === 'submit') sendSolution();
    else if (action === 'signin') openAuth('login', location.hash.slice(1));
    else if (action === 'close-auth') closeAuth();
    else if (action === 'auth-mode') openAuth(actionElement.dataset.mode || 'login', view.authReturn);
    else if (action === 'add-contest-author') changeContestAuthor('add', actionElement.dataset.id);
    else if (action === 'remove-contest-author') changeContestAuthor('remove', actionElement.dataset.id);
    else if (action === 'reset-contest-filters') {
      const form = document.querySelector('[data-form="contest"]');
      if (form) view.contestValues[form.dataset.id || 'new'] = contestData(form);
      const list = form?.querySelector('.contest-problem-list');
      if (list) list.scrollTop = 0;
      resetContestPicker();
      render(true);
    }
    else if (action === 'run') runExample();
    else if (action === 'logout') {saveEditor(); M.logout(); resetAccountViews(); navigate('/problems');}
    else if (action === 'reset-code') {const {problem, context} = currentProblem(); view.undo[runKey(problem.id, context)] = M.resetDraft(problem.id, context); render();}
    else if (action === 'undo-reset') {const {problem, context} = currentProblem(); const key = runKey(problem.id, context); M.restoreDraft(problem.id, context, view.undo[key]); delete view.undo[key]; render();}
    else if (action === 'retry-list') {view.listState = 'filled'; render();}
    else if (action === 'reset-contests-filter') {
      const phase = route().params.get('phase') || 'now';
      navigate(url('/contests', {phase: phase === 'now' ? null : phase}));
    }
    else if (action === 'reconnect') {view.connection = 'online'; render();}
    else if (action === 'join-contest') {
      if (!M.user()) {requestLogin(location.hash.slice(1)); return;}
      const result = M.joinContest(actionElement.dataset.id);
      if (!result.ok) {if (route().path.startsWith('/problem/')) setEditorMessage(result.error, 'error'); else {view.formMessages.contest = result.error; render(true);} return;}
      delete view.formMessages.contest;
      render(true);
    } else if (action === 'login-return') requestLogin(actionElement.dataset.return || location.hash.slice(1));
    else if (action === 'edit-submission') {
      const item = M.submission(actionElement.dataset.id);
      if (item?.userId !== M.user()?.id) return;
      const context = item.contestId && (M.phase(item.contestId) === 'live' || !M.problem(item.problemId)) ? item.contestId : 'practice';
      view.undo[runKey(item.problemId, context)] = M.draft(item.problemId, context);
      M.saveDraft(item.problemId, context, item.code);
      navigate(problemUrl(item.problemId, context));
    } else if (action === 'practice-problem') {
      const {problem, context} = currentProblem();
      if (!M.problem(problem.id)) return;
      M.saveDraft(problem.id, 'practice', M.draft(problem.id, context).code);
      navigate(problemUrl(problem.id, 'practice'));
    } else if (action === 'recheck') {
      const item = M.submission(actionElement.dataset.id);
      if (item.userId !== M.user()?.id) return;
      M.updateSubmission(item.id, {status: 'queued', timeMs: null, diagnostic: '', demoOutcome: view.outcome});
      render(); simulate(item.id);
    }
  });

  document.addEventListener('input', event => {
    if (event.target.dataset.authorQuery) {updateAuthorQuery(event.target); return;}
    if (event.target.dataset.contestFilter) {
      if (event.target.dataset.contestFilter === 'q') updateContestFilter(event.target);
      return;
    }
    if (event.target.id === 'solution-code') {
      saveEditor();
      const {problem, context} = currentProblem();
      document.getElementById('run-result').innerHTML = runResult(problem.id, context);
      document.getElementById('save-state').textContent = M.storageError() ? 'Черновик только в этой вкладке' : 'Черновик сохранен';
      delete view.undo[runKey(problem.id, context)];
      document.getElementById('reset-notice')?.remove();
    }
    if (event.target.dataset.query) {
      const current = route();
      history.replaceState(null, '', '#' + url(current.path, {...Object.fromEntries(current.params), [event.target.dataset.query]: event.target.value, page: null}));
      render(true);
    }
    if (event.target.closest('[data-form="contest"]')) {
      const form = event.target.closest('[data-form="contest"]');
      view.contestValues[form.dataset.id || 'new'] = contestData(form);
      const count = document.getElementById('contest-selected-count');
      if (count) count.textContent = 'Выбрано: ' + view.contestValues[form.dataset.id || 'new'].problemIds.length;
      if (event.target.name === 'problemIds' && view.contestPicker.selectedOnly) render(true);
    }
  });

  document.addEventListener('keydown', event => {
    if (event.target.id !== 'contest-author-search') return;
    if (event.key === 'Escape') {
      event.preventDefault();
      event.target.value = '';
      updateAuthorQuery(event.target);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const form = event.target.closest('[data-form="contest"]');
      if (!form) return;
      const query = event.target.value.trim().toLowerCase();
      if (!query) return;
      const selected = new Set(contestData(form).authorIds);
      const matches = M.users().filter(user => !selected.has(user.id) && user.handle.toLowerCase().includes(query));
      const candidate = matches.find(user => user.handle.toLowerCase() === query) || (matches.length === 1 ? matches[0] : null);
      if (candidate) changeContestAuthor('add', candidate.id);
    }
  });

  document.addEventListener('focusin', event => {
    if (event.target.closest('.contest-authors')) positionAuthorResults();
  });
  window.addEventListener('resize', positionAuthorResults);

  document.addEventListener('change', async event => {
    const target = event.target;
    if (target.dataset.contestFilter) {updateContestFilter(target); return;}
    if (target.dataset.filter) {patchQuery({[target.dataset.filter]: target.value, page: null}); return;}
    if (target.id === 'code-file') {
      const file = target.files[0];
      if (!file) return;
      if (!/\.(cu|cpp)$/i.test(file.name)) {setEditorMessage('Выберите файл .cu или .cpp.', 'error'); return;}
      const {problem, context} = currentProblem();
      const owner = M.user()?.id || 'guest';
      const code = await file.text();
      if (owner !== (M.user()?.id || 'guest')) return;
      view.undo[runKey(problem.id, context)] = M.draft(problem.id, context);
      M.saveDraft(problem.id, context, code);
      render(); return;
    }
    if (!target.dataset.preview) return;
    saveEditor();
    view.previewOpen = true;
    if (target.dataset.preview === 'user') {M.setSession(target.value === 'guest' ? null : target.value); resetAccountViews();}
    if (target.dataset.preview === 'list') view.listState = target.value;
    if (target.dataset.preview === 'outcome') view.outcome = target.value;
    if (target.dataset.preview === 'run') view.runOutcome = target.value;
    if (target.dataset.preview === 'connection') view.connection = target.value;
    if (target.dataset.preview === 'phase') M.setContestPhase(target.dataset.id, target.value);
    if (target.dataset.preview === 'submission') {
      const id = target.dataset.id;
      if (jobs.has(String(id))) {clearInterval(jobs.get(String(id))); jobs.delete(String(id));}
      const item = M.submission(id);
      const problem = M.problem(item.problemId, item.contestId || null);
      M.updateSubmission(id, {status: target.value, timeMs: target.value === 'accepted' ? problem.benchmark.referenceMs * .9 : null, diagnostic: diagnostic(target.value)});
    }
    render();
  });

  document.addEventListener('submit', event => {
    const form = event.target;
    const name = form.dataset.form;
    if (!name) return;
    event.preventDefault();
    const values = Object.fromEntries(new FormData(form));
    if (name === 'login' || name === 'register') {
      const requestedReturn = view.authReturn;
      if (requestedReturn?.startsWith('/') && !requestedReturn.startsWith('//')) M.setReturnTo(requestedReturn);
      const result = name === 'login' ? M.login(values.identifier) : M.register({handle: values.handle, email: values.email});
      if (!result.ok) {formError(result.error); return;}
      resetAccountViews();
      form.reset();
      closeAuth();
      const target = M.takeReturnTo() || requestedReturn || '/profile';
      navigate(target.startsWith('/') && !target.startsWith('//') ? target : '/profile');
    } else if (name === 'reset') {
      const message = document.getElementById('form-message');
      message.hidden = false; message.className = 'notice'; message.textContent = 'В рабочем сервисе здесь будет отправлено письмо для восстановления доступа. В прототипе письма не отправляются.';
    } else if (name === 'rating-search') patchQuery({q: values.q, page: null});
    else if (name === 'contest') saveContest(form);
  });

  window.addEventListener('hashchange', () => {saveEditor(); lastPhase = null; render(); document.getElementById('content')?.focus({preventScroll: true}); window.scrollTo({top: 0, behavior: 'instant'});});
  if (!location.hash) history.replaceState(null, '', '#/');
  render();
  M.submissions().filter(item => pending.includes(item.status)).forEach(item => simulate(item.id));
  setInterval(updateCountdowns, 1000);
})();
