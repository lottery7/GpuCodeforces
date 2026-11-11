# Макеты интерфейса

[Открыть прототип](https://gpu-forces-demo-9f6d2a.well-tang-6818.chatgpt.site) без входа в ChatGPT.

Кликабельные HTML-макеты в стиле IntelliJ IDEA/CLion: задачи, соревнования, посылки, рейтинг, профиль и создание контеста. В банке задач — прогресс решения и серия дней. Соревнования сведены в одну фильтруемую таблицу (по умолчанию текущие и будущие), ваши контесты показаны вверху; внутри есть поиск по названию. Контест открывается со списком задач, личными статусами и лучшим временем.

## Локальный просмотр

Откройте [index.html](index.html) в браузере. Установка и сборка не нужны. Можно запустить сервер из корня репозитория:

```sh
python3 -m http.server 4173 --bind 127.0.0.1
```

Адрес: http://127.0.0.1:4173/docs/frontend/mockups/

Демо-вход: `alex_cuda` с историей или `new_cuda` с пустым аккаунтом. Пароль произвольный, от 8 символов. Данные сохраняются в браузере, проверка на GPU имитируется.

Для снимков используйте `index.html?presentation=1`: служебная панель будет скрыта.

## Скриншоты

[Галерея](screenshots/index.html). Все снимки имеют размер 1920x1080.

| Экран | Снимок |
| --- | --- |
| Задачи | [01-problems.jpg](screenshots/01-problems.jpg) |
| Соревнования | [02-contests.jpg](screenshots/02-contests.jpg) |
| Посылки | [03-submissions.jpg](screenshots/03-submissions.jpg) |
| Рейтинг | [04-rating.jpg](screenshots/04-rating.jpg) |
| Профиль | [05-profile.jpg](screenshots/05-profile.jpg) |
| Задача и редактор | [06-problem.jpg](screenshots/06-problem.jpg) |
| Посылки задачи | [07-problem-submissions.jpg](screenshots/07-problem-submissions.jpg) |
| Лидеры задачи | [08-problem-leaders.jpg](screenshots/08-problem-leaders.jpg) |
| Создание контеста | [09-create-contest.jpg](screenshots/09-create-contest.jpg) |
| Контест и статусы задач | [10-contest.jpg](screenshots/10-contest.jpg) |

Шрифт JetBrains Mono включен вместе с [лицензией](assets/fonts/OFL.txt).
