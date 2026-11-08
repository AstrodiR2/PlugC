# Claude Plugins

Плагины для Android-приложения **Claude** — без патчинга APK.

[![Telegram](https://img.shields.io/badge/Telegram-%40PluginClaude-26A5E4?logo=telegram&logoColor=white)](https://t.me/PluginClaude)
[![Latest release](https://img.shields.io/github/v/release/AstrodiR2/claude-plugins?label=release)](https://github.com/AstrodiR2/claude-plugins/releases/latest)
[![Build](https://github.com/AstrodiR2/claude-plugins/actions/workflows/build.yml/badge.svg)](https://github.com/AstrodiR2/claude-plugins/actions/workflows/build.yml)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#требования)

**Claude Plugins** — это Xposed/LSPosed-модуль, который добавляет в настройки приложения Claude отдельный раздел **«Плагины»**. В нём можно включать и выключать JS-плагины, смотреть их статус и логи, импортировать новые плагины одним нажатием.

> [!IMPORTANT]
> Модуль работает только с официальным Android-приложением Claude (`com.anthropic.claude`). Для веб-версии и Claude Code он не подходит.

**Новости, обновления и готовые плагины — в Telegram-канале [@PluginClaude](https://t.me/PluginClaude).**

---

## Навигация

- [Что это](#что-это)
- [Возможности](#возможности)
- [Требования](#требования)
- [Quick Start](#quick-start)
  - [Вариант A. Root уже есть](#вариант-a-root-уже-есть)
  - [Вариант B. Root с нуля: DirtyFrag + KernelSU](#вариант-b-root-с-нуля-dirtyfrag--kernelsu)
  - [Установка плагина](#установка-плагина)
- [Написать свой плагин](#написать-свой-плагин)
- [FAQ](#faq)
- [Сборка из исходников](#сборка-из-исходников)
- [Ссылки](#ссылки)
- [Дисклеймер](#дисклеймер)

## Что это

Claude Plugins не заменяет и не модифицирует APK Claude. Модуль внедряется в процесс приложения через LSPosed и добавляет в настройки Claude новый пункт **«Плагины»**.

Плагины — это JavaScript-файлы, которые выполняются в песочнице [QuickJS](https://github.com/cashapp/quickjs-android) внутри процесса Claude. Плагин получает доступ только к небольшому API: логи, всплывающие сообщения и собственное хранилище. Никакого доступа к Android API, файлам или сети у него нет.

## Возможности

- Пункт **«Плагины»** в настройках Claude (сразу после раздела Billing).
- Импорт плагинов из `.zip` или одиночного `.js`.
- Чтение `manifest.json`: название, версия, описание, автор.
- Включение и выключение плагина переключателем.
- Статус плагина: запущен, остановлен, ошибка.
- Экран плагина с логом и кнопками **«Скачать лог»** / **«Скопировать лог»**.
- Долгое нажатие на карточку: **«Открыть с помощью…»**, **«Поделиться»**, **«Удалить»**.
- Вкладка **«Каталог»** — пока заглушка, каталог ещё не реализован.

## Требования

| Что | Зачем |
| --- | --- |
| Android 8.0+ (API 26) | Минимальная версия модуля |
| Официальное приложение Claude | Модуль внедряется только в `com.anthropic.claude` |
| Root | KernelSU, Magisk или APatch |
| LSPosed или Vector | Для загрузки Xposed-модуля |
| Zygisk Next | Нужен для LSPosed на KernelSU |
| Интернет | Для скачивания APK и плагинов |

Root можно получить и без ПК: в инструкции ниже используется **DirtyFrag** — временный root через уязвимость ядра. Полная перезагрузка телефона сбрасывает такой root, поэтому для применения модулей используется мягкая перезагрузка (soft restart).

## Quick Start

Есть два пути:

- **Вариант A** — у тебя уже есть root и LSPosed. Займёт пару минут.
- **Вариант B** — root с нуля через DirtyFrag + KernelSU. Займёт 15–30 минут.

### Вариант A. Root уже есть

1. Скачай APK модуля со страницы [Releases](https://github.com/AstrodiR2/claude-plugins/releases/latest) или из [Telegram-канала](https://t.me/PluginClaude).
2. Установи APK как обычное приложение.
3. Открой **LSPosed → Модули** и включи **Claude Plugins**.
4. Проверь область действия (Scope): должен быть выбран **Claude** (`com.anthropic.claude`). Если его нет — добавь вручную.
5. Полностью закрой Claude (свайп из недавних) и открой заново.
6. Зайди в Claude → **Настройки** → **Плагины**.

Если пункт не появился — смотри [FAQ](#faq).

### Вариант B. Root с нуля: DirtyFrag + KernelSU

> [!WARNING]
> Root и особенно эксплуатация уязвимости — риск для устройства и данных. Сделай резервную копию важного. DirtyFrag работает не на всех ядрах: если ядро новое или уязвимость уже закрыта, root получить не выйдет. Подробности и список поддерживаемых KMI — в [README DirtyFrag](https://github.com/mitschud/DirtyFrag).

#### Шаг 1. Скачай всё нужное

- [DirtyFrag](https://github.com/mitschud/DirtyFrag/releases/latest) — временный root.
- [KernelSU Manager](https://github.com/tiann/KernelSU/releases/latest).
  - **Samsung:** нужен KernelSU от diabl0w — [diabl0w/KernelSU](https://github.com/diabl0w/KernelSU/releases). Официальный KernelSU на Samsung не подойдёт.
- [Zygisk Next](https://github.com/Dr-TSNG/ZygiskNext/releases/latest) — свежая версия под твой Android.
- [LSPosed](https://github.com/LSPosed/LSPosed/releases/latest) — Zygisk-версия.
  - На Android 15+ официальный LSPosed может не работать — используй актуальный форк [Vector](https://github.com/JingMatrix/Vector/releases/latest).
- **Claude Plugins APK** — [Releases](https://github.com/AstrodiR2/claude-plugins/releases/latest) или [Telegram](https://t.me/PluginClaude).
- Приложение **Claude** из Google Play.

#### Шаг 2. Получи root через DirtyFrag

1. Установи APK **DirtyFrag** и **KernelSU Manager**.
2. Открой **DirtyFrag**.
3. В карточке **SU Manager** выбери **KernelSU**.
4. Нажми **Run exploit** и подожди. Запуск может занять до ~70 секунд — не надо жать кнопку повторно.
5. Когда появится **verified**, нажми кнопку **Manager**. Откроется KernelSU.
6. Если KernelSU спросит про root для DirtyFrag — разреши. В KernelSU должно быть **Working** (root работает).

#### Шаг 3. Установи Zygisk Next и LSPosed

7. Открой **KernelSU → три точки → Soft reboot** (мягкая перезагрузка). Дождись загрузки.
8. Скачай **Zygisk Next** и **LSPosed** (или **Vector** для Android 15+).
9. В KernelSU открой **Модули → Установить из хранилища** и установи сначала **Zygisk Next**.
10. Снова **KernelSU → три точки → Soft reboot**.
11. Установи модулем **LSPosed** (Zygisk-версию) и опять сделай **Soft reboot**.
12. Открой **LSPosed** (из уведомления, ярлыка или карточки модуля в KernelSU). Если он предложит включиться — нажми **«Включить»** и подожди ~20 секунд, пока откроется главное меню.

> [!TIP]
> Используй именно **Soft reboot**, а не обычную перезагрузку: полная перезагрузка убивает временный root DirtyFrag. Если всё-таки перезагрузил телефон полностью — просто запусти DirtyFrag заново.

#### Шаг 4. Установи и включи модуль

13. Установи APK **Claude Plugins**.
14. Открой **LSPosed → Модули** и включи **Claude Plugins**.
15. В области действия (Scope) выбери **Claude** (`com.anthropic.claude`).
16. Сделай **KernelSU → три точки → Soft reboot** (или полностью закрой Claude).
17. Открой **Claude → Настройки → Плагины**. Готово.

### Установка плагина

1. Открой **Claude → Настройки → Плагины**.
2. Нажми **«Добавить файл»**.
3. Выбери `.zip` с плагином или одиночный `.js`.
4. Включи переключатель на карточке плагина — он запустится.
5. Нажми на карточку, чтобы посмотреть статус и лог.
6. Долгое нажатие на карточку: открыть файл, поделиться, удалить.

Плагин можно установить и вручную: положи папку с `manifest.json` и `main.js` в `files/plugins/` внутри данных Claude. Но проще и безопаснее использовать импорт через интерфейс.

## Написать свой плагин

Минимальный плагин — это архив с двумя файлами:

```text
my-plugin/
├── manifest.json
└── main.js
```

`manifest.json`:

```json
{
  "name": "Мой плагин",
  "version": "1.0.0",
  "description": "Что делает плагин",
  "author": "Твоё имя"
}
```

`main.js`:

```js
claude.log("Плагин запущен");

claude.toast("Привет из плагина!");

claude.storage.set("launch_count", "1");
const count = claude.storage.get("launch_count");
claude.log("launch_count =", count);
```

### API

| Метод | Описание |
| --- | --- |
| `claude.log(...args)` | Записывает строку в лог плагина |
| `claude.toast(message)` | Показывает всплывающее сообщение |
| `claude.storage.get(key)` | Читает строку из хранилища плагина (или `undefined`) |
| `claude.storage.set(key, value)` | Сохраняет строку в хранилище плагина |

Особенности:

- Код выполняется в **QuickJS** и не имеет доступа к `fetch`, `console`, Android API и файловой системе.
- `main.js` выполняется при включении плагина и при запуске Claude (один раз за процесс).
- API пока минимальный: событий, хуков и сетевых запросов нет.
- Если в архиве есть общая папка верхнего уровня, она автоматически убирается при импорте.
- Если импортировать одиночный `.js`, `manifest.json` создастся автоматически.
- Если `main.js` нет, плагин перейдёт в статус **«Ошибка: нет main.js»**.
- Архив должен быть `.zip` или `.js`. Другие форматы не принимаются.

### Как собрать архив

```sh
cd my-plugin
zip -r ../my-plugin.zip manifest.json main.js
```

Импортируй `my-plugin.zip` через **«Добавить файл»**.

## FAQ

<details>
<summary><b>Пункт «Плагины» не появился в настройках Claude</b></summary>

Проверь по порядку:

1. Модуль **Claude Plugins** включён в LSPosed.
2. В области действия (Scope) выбран **Claude** (`com.anthropic.claude`).
3. Claude полностью закрыт и открыт заново (или сделан soft reboot).
4. Версия Claude совместима с модулем. Модуль завязан на внутренние классы приложения и может ломаться после обновлений Claude.
5. Посмотри лог LSPosed: ищи строки с тегом `ClaudePlugins`. Там будет причина, если хук не установился.

</details>

<details>
<summary><b>Почему вкладка «Каталог» пишет «не реализован»?</b></summary>

Каталог плагинов пока не готов. Сейчас плагины добавляются вручную через **«Добавить файл»**. Следи за обновлениями в [Telegram-канале](https://t.me/PluginClaude).

</details>

<details>
<summary><b>После обновления Claude плагины пропали</b></summary>

Скорее всего, Anthropic изменил внутренние классы приложения. Модуль использует внутренние имена (`settings.q`, перечисление Billing и т. п.), поэтому обновление Claude может его сломать.

Что делать:

1. Проверь, есть ли новая версия модуля в [Releases](https://github.com/AstrodiR2/claude-plugins/releases) или в [Telegram](https://t.me/PluginClaude).
2. Если новой версии нет — сообщи о проблеме в канал, приложив версию Claude и лог LSPosed.

</details>

<details>
<summary><b>Root пропал после перезагрузки телефона (DirtyFrag)</b></summary>

Так и должно быть: DirtyFrag даёт **временный** root, который исчезает после полной перезагрузки. Запусти DirtyFrag заново, выбери KernelSU и нажми **Run exploit**. Для применения модулей используй **Soft reboot** в KernelSU, а не обычную перезагрузку.

</details>

<details>
<summary><b>DirtyFrag не получает root</b></summary>

Причины обычно такие:

- ядро слишком новое;
- производитель уже закрыл уязвимость;
- ядро не входит в список поддерживаемых KMI.

Исправления нет — это ограничение уязвимости. Подробности в [FAQ DirtyFrag](https://github.com/mitschud/DirtyFrag#faq).

</details>

<details>
<summary><b>Плагин не запускается, статус «Ошибка»</b></summary>

1. Открой карточку плагина — там будет текст ошибки и лог.
2. Проверь, что в архиве есть `main.js` и `manifest.json` в корне (или в одной общей папке).
3. Проверь синтаксис JS: QuickJS поддерживает современный JavaScript, но не поддерживает `fetch`, `console` и API Android.
4. Если ошибка непонятна — скопируй лог кнопкой **«Скопировать лог»** и пришли в [Telegram-канал](https://t.me/PluginClaude).

</details>

<details>
<summary><b>Где посмотреть лог модуля, если плагин вообще не появился?</b></summary>

- Лог плагина: карточка плагина → **«Скачать лог»** или **«Скопировать лог»**.
- Лог LSPosed: **LSPosed → Логи**, ищи тег `ClaudePlugins`.
- Лог KernelSU: **KernelSU → Журнал** (если проблема с установкой модулей).

</details>

<details>
<summary><b>Можно ли ставить чужие плагины?</b></summary>

Да, но только из доверенных источников. Плагин не получает доступ к Android API и сети, но выполняется внутри процесса Claude и может читать/изменять своё хранилище и делать то, что позволяет JS. Не устанавливай плагины, в коде которых не уверен.

</details>

<details>
<summary><b>Работает ли модуль с Claude Code или веб-версией?</b></summary>

Нет. Модуль работает только с Android-приложением Claude (`com.anthropic.claude`). Claude Code, веб-версия и десктопное приложение не поддерживаются.

</details>

<details>
<summary><b>Нужен ли интернет для работы плагинов?</b></summary>

Для работы самого модуля и плагинов интернет не нужен. Он нужен только для скачивания APK, плагинов и обновлений.

</details>

<details>
<summary><b>Какие версии Android поддерживаются?</b></summary>

Модуль собран с `minSdk 26`, то есть Android 8.0 и выше, и `targetSdk 34`. Реальная совместимость зависит от LSPosed/Vector и версии Claude. На Android 15+ рекомендуется Vector вместо официального LSPosed.

</details>

<details>
<summary><b>Это безопасно?</b></summary>

Сам модуль не отправляет данные наружу и не содержит сетевого кода. Но root и эксплуатация уязвимости — это всегда риск: возможны сбои, потеря данных и проблемы с банковскими приложениями. Делай backup и понимай, на что идёшь.

</details>

## Сборка из исходников

Нужны JDK 17, Android SDK 34 и Gradle 8.9.

```sh
git clone https://github.com/AstrodiR2/claude-plugins.git
cd claude-plugins
gradle assembleDebug
```

APK появится здесь:

```text
app/build/outputs/apk/debug/app-debug.apk
```

CI собирает проект на каждый push: [Actions](https://github.com/AstrodiR2/claude-plugins/actions). В артефакте `module-debug` лежит `app-debug.apk`.

## Ссылки

- Telegram-канал: [@PluginClaude](https://t.me/PluginClaude)
- Релизы: [github.com/AstrodiR2/claude-plugins/releases](https://github.com/AstrodiR2/claude-plugins/releases)
- Сборки: [github.com/AstrodiR2/claude-plugins/actions](https://github.com/AstrodiR2/claude-plugins/actions)
- Проблемы: [github.com/AstrodiR2/claude-plugins/issues](https://github.com/AstrodiR2/claude-plugins/issues)
- DirtyFrag: [github.com/mitschud/DirtyFrag](https://github.com/mitschud/DirtyFrag)
- KernelSU: [github.com/tiann/KernelSU](https://github.com/tiann/KernelSU)
- Zygisk Next: [github.com/Dr-TSNG/ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext)
- LSPosed: [github.com/LSPosed/LSPosed](https://github.com/LSPosed/LSPosed)
- Vector (LSPosed для Android 15+): [github.com/JingMatrix/Vector](https://github.com/JingMatrix/Vector)

## Дисклеймер

Проект не связан с Anthropic. Claude — товарный знак Anthropic.

Вы используете модуль, root и сторонние плагины на свой страх и риск. Автор не несёт ответственности за сломанные устройства, потерянные данные, заблокированные банковские приложения и другие последствия. Соблюдай законы своей страны.
