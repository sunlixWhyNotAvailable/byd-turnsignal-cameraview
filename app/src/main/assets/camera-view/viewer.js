(() => {
  const WEB_REVISION = 7;
  const REVISION_HEADER = 'X-Byd-Viewer-Revision';
  const app = document.getElementById('app');
  // Keep the video on page composition only for desktop Firefox; mobile keeps its existing path.
  const desktopFirefox = /\bFirefox\/\d+/.test(navigator.userAgent)
    && /\b(?:Windows NT|Macintosh|X11)\b/.test(navigator.userAgent)
    && !/Android|Mobile|Tablet|iPhone|iPad|iPod/i.test(navigator.userAgent)
    && !/Android|iPhone|iPad|iPod|arm|aarch/i.test(navigator.platform)
    && !(navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  document.documentElement.classList.toggle('firefox-video-composition', desktopFirefox);
  const bootstrapNode = document.getElementById('viewer-bootstrap');
  const fallbackBootstrap = { locale: 'en', theme: 'dark', provider: 'tailscale', passwordRequired: true };
  let bootstrap = fallbackBootstrap;
  try { bootstrap = { ...fallbackBootstrap, ...JSON.parse(bootstrapNode?.textContent || '{}') }; }
  catch { /* Keep the public page usable if bootstrap data is malformed. */ }

  const locale = ['en', 'uk', 'ru', 'zh'].includes(bootstrap.locale) ? bootstrap.locale : 'en';
  const provider = ['tailscale', 'zrok'].includes(bootstrap.provider) ? bootstrap.provider : 'tailscale';
  const localeTags = { en: 'en-US', uk: 'uk-UA', ru: 'ru-RU', zh: 'zh-CN' };
  const text = {
    en: {
      title: 'Remote cameras', subtitle: 'Secure camera viewer', loginTitle: 'Camera access', loginHint: 'Sign in to view cameras and recordings.', password: 'Password', signIn: 'Continue',
      live: 'Live', archive: 'Recordings', end: 'End session', composition: 'Composition', front: 'Front', rear: 'Rear', left: 'Left', right: 'Right',
      combined: 'Combined', exterior: 'Exterior', interior: 'Interior', cabin: 'Cabin', driver: 'Driver',
      startLive: 'Start live viewing', resumeLive: 'Open live stream', waiting: 'Waiting for the first displayed frame…', preparing: 'Preparing cameras…', viewing: 'Viewing live', connected: 'Connected · waiting for video', ready: 'Signed in · viewing is off',
      lowBattery: 'Viewing is paused by the battery protection limit.', ended: 'This session has ended.', replacedTitle: 'Session ended', replaced: 'Another device connected.', errorTitle: 'Connection unavailable', error: 'The camera connection could not be completed.', retry: 'Try again', signInAgain: 'Sign in again',
      invalidPassword: 'Incorrect password.', busy: 'The session is in use. Try again shortly.', retryLater: 'Please wait before trying again.', unauthorized: 'This session is no longer authorized.',
      chargeUnavailable: 'Battery protection data unavailable:', soc: 'SOC', voltage: '12 V', chargeSuffix: 'Protection is unavailable for the missing readings.',
      battery12v: '12 V battery', voltsUnit: 'V', batteryCurrent: 'Current vehicle state', batteryUnavailable: 'Current data unavailable', batteryPartial: 'Some readings unavailable',
      selectView: 'Choose camera view', selectLayout: 'Choose camera layout', selectCamera: 'Choose individual camera', expand: 'Full screen', minimize: 'Exit full screen', loading: 'Loading recordings…', loadError: 'Recordings could not be loaded.', retryLoad: 'Reload recordings',
      clips: 'Recordings', tablet: 'Tablet', sd: 'SD card', noRecordings: 'No recordings for this day.', archiveError: 'This recording is unavailable.', back: 'Back to recordings',
      yellowEvents: 'Yellow events', redEvents: 'Red events', duration: 'Duration', allRecordings: 'All recordings',
      play: 'Play', pause: 'Pause', restart: 'Restart', previous: 'Previous recording', next: 'Next recording', position: 'Playback position', speed: 'Playback speed',
      previousMonth: 'Previous month', nextMonth: 'Next month',
      streamError: 'Live video could not be played.', noScript: 'JavaScript is required to view cameras.',
      modes: 'Car modes', showOnTablet: 'Show on tablet', recordCameras: 'Record cameras', modeEnabled: 'Enabled', modeDisabled: 'Disabled',
      modeLoading: 'Loading settings…', modeUnavailable: 'Unavailable', modeLastKnown: 'Last known', modeSaving: 'Saving…',
      modeApplyPending: 'Saved; applying…', modeApplyError: 'The app could not apply this setting.', modeUpdateError: 'Could not update this setting.',
      modePermissionError: 'Permission is required. Change it in the app settings.',
      modeNote: 'Modes run under app conditions and battery protection.',
      updateRequired: 'The viewer has changed. Refresh this page to continue.'
    },
    uk: {
      title: 'Віддалені камери', subtitle: 'Захищений перегляд камер', loginTitle: 'Доступ до камер', loginHint: 'Увійдіть, щоб переглядати камери й записи.', password: 'Пароль', signIn: 'Продовжити',
      live: 'Наживо', archive: 'Архів записів', end: 'Завершити сеанс', composition: 'Композиція', front: 'Перед', rear: 'Зад', left: 'Ліво', right: 'Право',
      combined: 'Усі камери', exterior: 'Зовнішні', interior: 'Внутрішні', cabin: 'Салон', driver: 'Водій',
      startLive: 'Почати перегляд', resumeLive: 'Відкрити відео', waiting: 'Очікуємо перше відображене зображення…', preparing: 'Готуємо камери…', viewing: 'Перегляд наживо', connected: 'Підключено · очікуємо відео', ready: 'Вхід виконано · перегляд вимкнено',
      lowBattery: 'Перегляд призупинено через обмеження захисту акумулятора.', ended: 'Цей сеанс завершено.', replacedTitle: 'Сеанс завершено', replaced: 'Під’єднався інший пристрій.', errorTitle: 'З’єднання недоступне', error: 'Не вдалося встановити з’єднання з камерами.', retry: 'Спробувати знову', signInAgain: 'Увійти знову',
      invalidPassword: 'Неправильний пароль.', busy: 'Сеанс зайнятий. Спробуйте трохи пізніше.', retryLater: 'Зачекайте перед наступною спробою.', unauthorized: 'Цей сеанс більше не авторизований.',
      chargeUnavailable: 'Дані захисту акумулятора недоступні:', soc: 'SOC', voltage: '12 В', chargeSuffix: 'Захист за відсутніми показниками недоступний.',
      battery12v: 'АКБ 12 V', voltsUnit: 'V', batteryCurrent: 'Поточний стан авто', batteryUnavailable: 'Поточні дані недоступні', batteryPartial: 'Частина показників недоступна',
      selectView: 'Виберіть зображення камери', selectLayout: 'Виберіть компонування камер', selectCamera: 'Виберіть окрему камеру', expand: 'На весь екран', minimize: 'Вийти з повного екрана', loading: 'Завантаження записів…', loadError: 'Не вдалося завантажити записи.', retryLoad: 'Завантажити записи знову',
      clips: 'Записи', tablet: 'Планшет', sd: 'SD-карта', noRecordings: 'За цей день записів немає.', archiveError: 'Цей запис недоступний.', back: 'До записів',
      yellowEvents: 'Жовті події', redEvents: 'Червоні події', duration: 'Тривалість', allRecordings: 'Усі записи',
      play: 'Відтворити', pause: 'Пауза', restart: 'З початку', previous: 'Попередній запис', next: 'Наступний запис', position: 'Позиція відтворення', speed: 'Швидкість відтворення',
      previousMonth: 'Попередній місяць', nextMonth: 'Наступний місяць',
      streamError: 'Не вдалося відтворити відео наживо.', noScript: 'Для перегляду камер потрібен JavaScript.',
      modes: 'Режими авто', showOnTablet: 'Показ на планшеті', recordCameras: 'Запис камер', modeEnabled: 'Увімкнено', modeDisabled: 'Вимкнено',
      modeLoading: 'Завантаження налаштувань…', modeUnavailable: 'Недоступно', modeLastKnown: 'Останній стан', modeSaving: 'Збереження…',
      modeApplyPending: 'Збережено; застосовуємо…', modeApplyError: 'Застосунок не зміг застосувати цей параметр.', modeUpdateError: 'Не вдалося змінити параметр.',
      modePermissionError: 'Потрібен дозвіл. Змініть його в налаштуваннях застосунку.',
      modeNote: 'Режими працюють за умовами застосунку та захистом заряду.',
      updateRequired: 'Версія переглядача змінилася. Оновіть сторінку, щоб продовжити.'
    },
    ru: {
      title: 'Удалённые камеры', subtitle: 'Защищённый просмотр камер', loginTitle: 'Доступ к камерам', loginHint: 'Войдите, чтобы смотреть камеры и записи.', password: 'Пароль', signIn: 'Продолжить',
      live: 'Эфир', archive: 'Архив записей', end: 'Завершить сеанс', composition: 'Композиция', front: 'Перед', rear: 'Зад', left: 'Слева', right: 'Справа',
      combined: 'Все камеры', exterior: 'Внешние', interior: 'Внутренние', cabin: 'Салон', driver: 'Водитель',
      startLive: 'Начать просмотр', resumeLive: 'Открыть видео', waiting: 'Ожидаем первый отображённый кадр…', preparing: 'Подготовка камер…', viewing: 'Просмотр в эфире', connected: 'Подключено · ожидание видео', ready: 'Вход выполнен · просмотр выключен',
      lowBattery: 'Просмотр приостановлен из-за ограничения защиты аккумулятора.', ended: 'Этот сеанс завершён.', replacedTitle: 'Сеанс завершён', replaced: 'Подключилось другое устройство.', errorTitle: 'Подключение недоступно', error: 'Не удалось подключиться к камерам.', retry: 'Повторить', signInAgain: 'Войти снова',
      invalidPassword: 'Неверный пароль.', busy: 'Сеанс занят. Повторите попытку позже.', retryLater: 'Подождите перед следующей попыткой.', unauthorized: 'Этот сеанс больше не авторизован.',
      chargeUnavailable: 'Данные защиты аккумулятора недоступны:', soc: 'SOC', voltage: '12 В', chargeSuffix: 'Защита по отсутствующим показателям недоступна.',
      battery12v: 'АКБ 12 V', voltsUnit: 'V', batteryCurrent: 'Текущее состояние авто', batteryUnavailable: 'Текущие данные недоступны', batteryPartial: 'Часть показателей недоступна',
      selectView: 'Выберите вид камеры', selectLayout: 'Выберите компоновку камер', selectCamera: 'Выберите отдельную камеру', expand: 'На весь экран', minimize: 'Выйти из полного экрана', loading: 'Загрузка записей…', loadError: 'Не удалось загрузить записи.', retryLoad: 'Загрузить записи снова',
      clips: 'Записи', tablet: 'Планшет', sd: 'SD-карта', noRecordings: 'За этот день записей нет.', archiveError: 'Эта запись недоступна.', back: 'К записям',
      yellowEvents: 'Жёлтые события', redEvents: 'Красные события', duration: 'Длительность', allRecordings: 'Все записи',
      play: 'Воспроизвести', pause: 'Пауза', restart: 'С начала', previous: 'Предыдущая запись', next: 'Следующая запись', position: 'Позиция воспроизведения', speed: 'Скорость воспроизведения',
      previousMonth: 'Предыдущий месяц', nextMonth: 'Следующий месяц',
      streamError: 'Не удалось воспроизвести видео в эфире.', noScript: 'Для просмотра камер требуется JavaScript.',
      modes: 'Режимы авто', showOnTablet: 'Показ на планшете', recordCameras: 'Запись камер', modeEnabled: 'Включено', modeDisabled: 'Выключено',
      modeLoading: 'Загрузка настроек…', modeUnavailable: 'Недоступно', modeLastKnown: 'Последнее состояние', modeSaving: 'Сохранение…',
      modeApplyPending: 'Сохранено; применяется…', modeApplyError: 'Приложение не смогло применить этот параметр.', modeUpdateError: 'Не удалось изменить параметр.',
      modePermissionError: 'Требуется разрешение. Измените его в настройках приложения.',
      modeNote: 'Режимы работают с учётом условий приложения и защиты заряда.',
      updateRequired: 'Версия просмотрщика изменилась. Обновите страницу, чтобы продолжить.'
    },
    zh: {
      title: '远程摄像头', subtitle: '安全摄像头查看器', loginTitle: '摄像头访问', loginHint: '登录以查看摄像头和录像。', password: '密码', signIn: '继续',
      live: '实时', archive: '录像', end: '结束会话', composition: '全景', front: '前方', rear: '后方', left: '左侧', right: '右侧',
      combined: '组合画面', exterior: '车外', interior: '车内', cabin: '座舱', driver: '驾驶员',
      startLive: '开始实时查看', resumeLive: '打开实时视频', waiting: '正在等待首帧显示…', preparing: '正在准备摄像头…', viewing: '正在实时查看', connected: '已连接 · 等待视频', ready: '已登录 · 查看未启动',
      lowBattery: '电池保护限制已暂停查看。', ended: '此会话已结束。', replacedTitle: '会话已结束', replaced: '另一台设备已连接。', errorTitle: '连接不可用', error: '无法完成摄像头连接。', retry: '重试', signInAgain: '重新登录',
      invalidPassword: '密码错误。', busy: '会话正在使用，请稍后重试。', retryLater: '请稍候再试。', unauthorized: '此会话已不再授权。',
      chargeUnavailable: '电池保护数据不可用：', soc: 'SOC', voltage: '12 V', chargeSuffix: '缺少读数时无法启用保护。',
      battery12v: '12 V 电池', voltsUnit: 'V', batteryCurrent: '车辆当前状态', batteryUnavailable: '当前数据不可用', batteryPartial: '部分读数不可用',
      selectView: '选择摄像头画面', selectLayout: '选择摄像头布局', selectCamera: '选择单个摄像头', expand: '全屏', minimize: '退出全屏', loading: '正在加载录像…', loadError: '无法加载录像。', retryLoad: '重新加载录像',
      clips: '录像', tablet: '平板', sd: 'SD 卡', noRecordings: '这一天没有录像。', archiveError: '此录像不可用。', back: '返回录像列表',
      yellowEvents: '黄色事件', redEvents: '红色事件', duration: '时长', allRecordings: '全部录像',
      play: '播放', pause: '暂停', restart: '重新开始', previous: '上一段录像', next: '下一段录像', position: '播放位置', speed: '播放速度',
      previousMonth: '上个月', nextMonth: '下个月',
      streamError: '无法播放实时视频。', noScript: '查看摄像头需要启用 JavaScript。',
      modes: '车辆模式', showOnTablet: '在平板上显示', recordCameras: '录制摄像头', modeEnabled: '已开启', modeDisabled: '已关闭',
      modeLoading: '正在加载设置…', modeUnavailable: '不可用', modeLastKnown: '上次状态', modeSaving: '正在保存…',
      modeApplyPending: '已保存；正在应用…', modeApplyError: '应用无法启用此设置。', modeUpdateError: '无法更新此设置。',
      modePermissionError: '需要权限。请在应用设置中更改。',
      modeNote: '模式按应用条件和电量保护规则运行。',
      updateRequired: '查看器已更新。请刷新页面以继续。'
    }
  }[locale];
  const cameras = ['composition', 'front', 'rear', 'left', 'right'];
  function normalizeInteriorCameraMask(value) {
    return Number.isInteger(value) && value >= 0 && value <= 3 ? value : null;
  }
  function liveCameras() {
    const mask = state.interiorCameras;
    const views = [...cameras];
    if (mask) views.push('combined', 'interior');
    if (mask & 1) views.push('cabin');
    if (mask & 2) views.push('driver');
    return views;
  }
  const preferredMode = () => {
    try { return sessionStorage.getItem('viewer.mode') === 'archive' ? 'archive' : 'live'; }
    catch { return 'live'; }
  };
  const rememberMode = (mode) => {
    try { sessionStorage.setItem('viewer.mode', mode === 'archive' ? 'archive' : 'live'); }
    catch { /* Tab preference is optional and never contains credentials. */ }
  };
  const state = {
    authenticated: false, replaced: false, mode: 'live', status: 'ready', view: 'composition', wantsLive: false,
    interiorCameras: 0,
    liveFailed: false, firstLiveFrame: false, archiveView: 'composition', recordings: [], archiveLoaded: false,
    archiveLoading: false, archiveError: false, selectedDay: null, month: null, selectedRecord: null,
    playerError: false, playerFrameReported: false, missingCharge: 0, hls: null, poll: null, mainKey: '', seeking: false,
    localFirstFrame: false, liveRequest: null, liveStreamId: null, liveSelectionRevision: 0, livePlaylist: null,
    battery: null, batteryExpiresAt: 0,
    modes: { available: null, tabletEnabled: null, recordingEnabled: null, applyState: null, error: null },
    lastKnownModes: { tabletEnabled: null, recordingEnabled: null }, modePending: null, modeError: null, modesOpen: false
  };
  const cameraLabels = Object.fromEntries(cameras.map((key) => [key, text[key]]));
  let hlsScriptPromise;
  let actionGeneration = 0;
  let mediaGeneration = 0;
  let controlQueue = Promise.resolve();
  let modeQueue = Promise.resolve();
  let modeWriteGeneration = 0;
  let statusRequestGeneration = 0;
  let liveFrameToken = 0;
  let archiveFrameToken = 0;
  let archiveResizeObserver = null;
  let contractBlocked = false;

  function checkRevision(revision, fromServer = false) {
    if (contractBlocked) return false;
    if (Number(revision) === WEB_REVISION) {
      if (fromServer) {
        try { sessionStorage.removeItem('viewer.contractReload'); } catch { /* Optional tab storage. */ }
      }
      return true;
    }
    contractBlocked = true;
    ++actionGeneration;
    clearInterval(state.poll); state.poll = null;
    state.authenticated = false;
    stopLiveMedia();
    const archiveVideo = document.getElementById('archive-video');
    if (archiveVideo) { archiveVideo.pause(); archiveVideo.removeAttribute('src'); archiveVideo.load(); }
    renderLogin(text.updateRequired);
    // One reload per target revision; unavailable storage must not create a reload loop.
    const target = /^[1-9][0-9]{0,5}$/.test(String(revision)) ? String(revision) : 'legacy';
    try {
      if (sessionStorage.getItem('viewer.contractReload') !== target) {
        sessionStorage.setItem('viewer.contractReload', target);
        window.location.reload();
      }
    } catch { /* Keep the explicit refresh message when tab storage is unavailable. */ }
    return false;
  }

  document.documentElement.lang = locale;
  document.documentElement.dataset.theme = ['dark', 'light'].includes(bootstrap.theme) ? bootstrap.theme : 'dark';

  const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[character]));
  const iconPaths = {
    camera: '<path d="M14 5h-2l-1.5-2h-5L4 5H2a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2l-2-2h-2"/><circle cx="10" cy="12" r="3"/>',
    video: '<rect x="3" y="5" width="13" height="14" rx="2"/><path d="m16 10 5-3v10l-5-3"/>',
    archive: '<rect x="3" y="4" width="18" height="17" rx="2"/><path d="M3 9h18M8 3v3m8-3v3m-6 8h4"/>',
    stop: '<rect x="5" y="5" width="14" height="14" rx="2"/>',
    play: '<path d="m7 4 13 8-13 8z"/>', pause: '<path d="M7 5h4v14H7zm7 0h4v14h-4z"/>',
    maximize: '<path d="M8 3H5a2 2 0 0 0-2 2v3m13-5h3a2 2 0 0 1 2 2v3M3 16v3a2 2 0 0 0 2 2h3m8 0h3a2 2 0 0 0 2-2v-3"/>',
    minimize: '<path d="M8 3v5H3m13-5v5h5M3 16h5v5m8 0v-5h5"/>',
    left: '<path d="m14 5-7 7 7 7"/>', right: '<path d="m10 5 7 7-7 7"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    calendar: '<rect x="3" y="4" width="18" height="17" rx="2"/><path d="M16 2v4M8 2v4M3 9h18"/>',
    grid: '<rect x="3" y="3" width="8" height="8" rx="1"/><rect x="13" y="3" width="8" height="8" rx="1"/><rect x="3" y="13" width="8" height="8" rx="1"/><rect x="13" y="13" width="8" height="8" rx="1"/>',
    chevronLeft: '<path d="m15 18-6-6 6-6"/>', chevronRight: '<path d="m9 18 6-6-6-6"/>',
    wifi: '<path d="M5 12.5a11 11 0 0 1 14 0M8 16a6 6 0 0 1 8 0m-5 3h2"/>',
    warning: '<path d="M10.3 3.9 1.8 18.5A1.7 1.7 0 0 0 3.3 21h17.4a1.7 1.7 0 0 0 1.5-2.5L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4m0 4h.01"/>',
    rotate: '<path d="M3 12a9 9 0 1 0 2.6-6.4L3 8"/><path d="M3 3v5h5"/>',
    skipBack: '<path d="M5 5v14m14-14L8 12l11 7z"/>', skipForward: '<path d="M19 5v14M5 5l11 7-11 7z"/>',
    empty: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M8 14h.01M12 14h.01M16 14h.01"/>',
    sliders: '<path d="M4 21v-7m0-4V3m8 18v-9m0-4V3m8 18v-5m0-4V3"/><path d="M2 14h4m4-6h4m4 8h4"/>',
    chevronDown: '<path d="m6 9 6 6 6-6"/>',
    battery: '<rect x="2" y="6" width="18" height="12" rx="2"/><path d="M22 10v4M6 10v4m4-4v4"/>',
    zap: '<path d="m13 2-10 12h8l-1 8 11-12h-8z"/>'
  };
  const icon = (name) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${iconPaths[name] || iconPaths.camera}</svg>`;
  const clock = (seconds) => {
    const safe = Math.max(0, Math.floor(Number(seconds) || 0));
    const hours = Math.floor(safe / 3600);
    return hours ? `${hours}:${String(Math.floor(safe / 60) % 60).padStart(2, '0')}:${String(safe % 60).padStart(2, '0')}`
      : `${Math.floor(safe / 60)}:${String(safe % 60).padStart(2, '0')}`;
  };
  const safeId = (value) => typeof value === 'string' && /^[A-Za-z0-9_-]{1,256}$/.test(value);
  const dateParts = (day) => /^\d{4}-\d{2}-\d{2}$/.test(day || '') ? day.split('-').map(Number) : null;
  const localDate = (day) => {
    const parts = dateParts(day);
    return parts ? new Date(parts[0], parts[1] - 1, parts[2], 12) : new Date();
  };
  const formatDate = (day, options = { day: 'numeric', month: 'long' }) => new Intl.DateTimeFormat(localeTags[locale], options).format(localDate(day));
  const dayLabel = (day) => formatDate(day, { day: '2-digit', month: '2-digit', year: 'numeric' });
  const monthKey = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
  const todayKey = () => `${new Date().getFullYear()}-${String(new Date().getMonth() + 1).padStart(2, '0')}-${String(new Date().getDate()).padStart(2, '0')}`;
  const addMonth = (value, delta) => {
    const [year, month] = (value || monthKey(new Date())).split('-').map(Number);
    return monthKey(new Date(year, month - 1 + delta, 1, 12));
  };

  async function api(path, options = {}) {
    if (contractBlocked) throw new Error('viewer_update_required');
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
    const response = await fetch(path, {
      credentials: 'same-origin', cache: 'no-store', ...options, signal: controller.signal,
      headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}),
        [REVISION_HEADER]: String(WEB_REVISION) }
    });
    if (!checkRevision(response.headers.get(REVISION_HEADER), true)) throw new Error('viewer_update_required');
    const bodyText = await response.text();
    let body = {};
    try { body = bodyText ? JSON.parse(bodyText) : {}; } catch { body = {}; }
    if (!response.ok) {
      const failure = new Error(body.status || `HTTP ${response.status}`);
      failure.statusCode = response.status;
      failure.body = body;
      throw failure;
    }
    return body;
    } finally { clearTimeout(timeout); }
  }
  const post = (path, body = {}) => {
    const send = () => api(path, { method: 'POST', body: JSON.stringify(body) });
    if (path === '/modes') {
      const pending = modeQueue.then(send, send);
      modeQueue = pending.catch(() => {});
      return pending;
    }
    if (!['/live', '/archive/open', '/archive/close', '/stop', '/session'].includes(path)) return send();
    const pending = controlQueue.then(send, send);
    controlQueue = pending.catch(() => {});
    return pending;
  };

  function pageHeader() {
    return `<header class="head"><span class="brandmark">${icon('camera')}</span><div><div class="brand">BYD Extend</div><div class="subtitle">${escapeHtml(text.subtitle)} · ${escapeHtml(provider)}</div></div></header>`;
  }

  function renderLogin(message = '') {
    applyBatterySnapshot(null);
    app.classList.remove('is-expanded', 'has-player');
    if (contractBlocked) {
      app.innerHTML = `${pageHeader()}<div class="login-wrap"><section class="login-card" role="alert"><p>${escapeHtml(text.updateRequired)}</p></section></div>`;
      return;
    }
    app.innerHTML = `${pageHeader()}<div class="login-wrap"><section class="login-card" aria-labelledby="login-title"><h1 id="login-title">${escapeHtml(text.loginTitle)}</h1><p>${escapeHtml(text.loginHint)}</p><form id="login-form">${bootstrap.passwordRequired ? `<label>${escapeHtml(text.password)}<input id="password" type="password" maxlength="1024" autocomplete="off" required></label>` : ''}<p class="error-note" id="login-error" role="alert">${escapeHtml(message)}</p><button class="primary" type="submit">${escapeHtml(text.signIn)}</button></form></section></div>`;
  }

  function cameraButton(view, selected, archive = false) {
    const glyph = ['combined', 'composition'].includes(view) ? 'grid' : view === 'interior' ? 'camera' : '';
    const label = view === 'composition' && !archive ? text.exterior : text[view];
    return `<button type="button" data-action="camera" data-view="${view}" aria-pressed="${selected === view}"${view === 'composition' ? ` data-composition="true"` : ''}>${glyph ? icon(glyph) : ''}${escapeHtml(label)}</button>`;
  }

  function cameraTabs(selected, archive = false, order = null) {
    if (!archive) {
      const layouts = state.interiorCameras ? ['combined', 'composition', 'interior'] : ['composition'];
      const individualCameras = liveCameras().filter((view) => !['combined', 'interior', 'composition'].includes(view));
      return `<div class="camera-controls"><div class="live-layouts" role="group" aria-label="${escapeHtml(text.selectLayout)}">${layouts.map((view) => cameraButton(view, selected)).join('')}</div><div class="camera-tabs live-cameras" role="group" aria-label="${escapeHtml(text.selectCamera)}">${individualCameras.map((view) => cameraButton(view, selected)).join('')}</div></div>`;
    }
    const views = !Array.isArray(order) || order.length !== 4
      ? ['composition'] : ['composition', ...order.map((direction) => cameras[direction + 1])];
    return `<div class="camera-tabs" role="group" aria-label="${escapeHtml(text.selectView)}">${views.map((view) => cameraButton(view, selected, true)).join('')}</div>`;
  }

  function refreshLiveCameraTabs() {
    const tabs = document.querySelector('#main-panel > .camera-controls');
    if (tabs) tabs.outerHTML = cameraTabs(state.view);
  }

  function chargeNote() {
    const mask = Number(state.missingCharge) || 0;
    if (!mask) return '';
    const missing = [];
    if (mask & 1) missing.push(text.soc);
    if (mask & 2) missing.push(text.voltage);
    return missing.length ? `<p class="charge-note">${escapeHtml(text.chargeUnavailable)} ${escapeHtml(missing.join(' / '))}. ${escapeHtml(text.chargeSuffix)}</p>` : '';
  }

  function batteryPanel() {
    return `<section class="telemetry" aria-label="${escapeHtml(text.batteryCurrent)}"><span class="telemetry-item">${icon('battery')}<span class="telemetry-label">${escapeHtml(text.soc)}</span><strong id="battery-soc">—</strong></span><span class="telemetry-item">${icon('zap')}<span class="telemetry-label">${escapeHtml(text.battery12v)}</span><strong id="battery-voltage">—</strong></span><span class="telemetry-note" id="battery-note">${escapeHtml(text.batteryUnavailable)}</span></section>`;
  }

  function applyBatterySnapshot(battery, requestedAt = Date.now()) {
    state.battery = battery && typeof battery === 'object' ? battery : null;
    const lifetime = state.battery?.validForMs;
    // Include request transit time; never extend an old helper sample on each HTTP poll.
    state.batteryExpiresAt = Number.isFinite(lifetime) && lifetime > 0
      ? requestedAt + Math.min(lifetime, 15000) : 0;
    updateBattery();
  }

  function updateBattery() {
    const socNode = document.getElementById('battery-soc');
    const voltageNode = document.getElementById('battery-voltage');
    const note = document.getElementById('battery-note');
    if (!socNode || !voltageNode || !note) return;
    const value = state.authenticated && !state.replaced && state.status !== 'ended' &&
      Date.now() < state.batteryExpiresAt ? state.battery : null;
    const mask = value?.missingMetrics;
    const validMask = Number.isInteger(mask) && mask >= 0 && mask <= 3;
    const soc = validMask && !(mask & 1) && Number.isInteger(value.socPercent) &&
      value.socPercent >= 0 && value.socPercent <= 100 ? value.socPercent : null;
    const voltage = validMask && !(mask & 2) && Number.isFinite(value.voltageVolts) &&
      value.voltageVolts > 0 && value.voltageVolts <= 20 ? value.voltageVolts : null;
    socNode.textContent = soc === null ? '—' : `${soc} %`;
    voltageNode.textContent = voltage === null ? '—' : `${new Intl.NumberFormat(localeTags[locale], {
      minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(voltage)} ${text.voltsUnit}`;
    note.textContent = soc === null && voltage === null ? text.batteryUnavailable
      : soc === null || voltage === null ? `${text.batteryCurrent} · ${text.batteryPartial}` : text.batteryCurrent;
  }

  function modePanel() {
    return `<aside class="mode-panel" aria-label="${escapeHtml(text.modes)}"><h2 class="mode-heading mode-desktop-heading">${icon('sliders')}${escapeHtml(text.modes)}</h2><details class="mode-disclosure" id="mode-disclosure"${state.modesOpen ? ' open' : ''}><summary><span class="mode-summary-heading">${icon('sliders')}<span>${escapeHtml(text.modes)}<small>${escapeHtml(text.showOnTablet)} · ${escapeHtml(text.recordCameras)}</small></span></span>${icon('chevronDown')}</summary><div class="mode-content"><label class="mode-row" for="mode-tablet"><span class="mode-label"><strong>${escapeHtml(text.showOnTablet)}</strong><small id="mode-state-tablet"></small></span><input class="mode-toggle" id="mode-tablet" type="checkbox" data-mode="tablet" aria-describedby="mode-state-tablet"></label><label class="mode-row" for="mode-recording"><span class="mode-label"><strong>${escapeHtml(text.recordCameras)}</strong><small id="mode-state-recording"></small></span><input class="mode-toggle" id="mode-recording" type="checkbox" data-mode="recording" aria-describedby="mode-state-recording"></label><p class="mode-note">${escapeHtml(text.modeNote)}</p><p class="mode-feedback" id="mode-feedback" role="status" aria-live="polite"></p></div></details></aside>`;
  }

  function resetModes() {
    state.modes = { available: null, tabletEnabled: null, recordingEnabled: null, applyState: null, error: null };
    state.modePending = null;
    state.modeError = null;
    state.lastKnownModes = { tabletEnabled: null, recordingEnabled: null };
    ++modeWriteGeneration;
    ++statusRequestGeneration;
  }

  function applyModeSnapshot(modes) {
    if (!modes || typeof modes !== 'object') {
      state.modes = { available: null, tabletEnabled: null, recordingEnabled: null, applyState: null, error: null };
      return;
    }
    const available = typeof modes.available === 'boolean' ? modes.available : null;
    state.modes = {
      available,
      tabletEnabled: available && typeof modes.tabletEnabled === 'boolean' ? modes.tabletEnabled : null,
      recordingEnabled: available && typeof modes.recordingEnabled === 'boolean' ? modes.recordingEnabled : null,
      applyState: ['applied', 'pending', 'error'].includes(modes.applyState) ? modes.applyState : null,
      error: typeof modes.error === 'string' ? modes.error : null
    };
    for (const field of ['tabletEnabled', 'recordingEnabled']) {
      if (typeof state.modes[field] === 'boolean') state.lastKnownModes[field] = state.modes[field];
    }
  }

  function modeValueLabel(field) {
    if (state.modePending === (field === 'tabletEnabled' ? 'tablet' : 'recording')) return text.modeSaving;
    const value = state.modes[field];
    if (state.modes.available === true && typeof value === 'boolean') return value ? text.modeEnabled : text.modeDisabled;
    const lastKnown = state.lastKnownModes[field];
    if (typeof lastKnown === 'boolean') return `${text.modeUnavailable} · ${text.modeLastKnown.toLowerCase()}: ${lastKnown ? text.modeEnabled : text.modeDisabled}`;
    return state.modes.available === null ? text.modeLoading : text.modeUnavailable;
  }

  function modeFeedback() {
    if (state.modePending) return text.modeSaving;
    const code = state.modeError || (state.modes.applyState === 'error' ? state.modes.error || 'apply_error' : null);
    if (code) {
      const message = code === 'permission_required' ? text.modePermissionError
        : code === 'modes_unavailable' ? text.modeUnavailable
          : state.modeError ? text.modeUpdateError : text.modeApplyError;
      return `${message}${code === 'permission_required' ? '' : ` (${escapeHtml(code)})`}`;
    }
    if (state.modes.applyState === 'pending') return text.modeApplyPending;
    if (state.modes.available !== true) return state.modes.available === null ? text.modeLoading : text.modeUnavailable;
    return '';
  }

  function updateModePanel() {
    const panel = document.querySelector('.mode-panel');
    if (!panel) return;
    const controlsEnabled = state.authenticated && !state.replaced && !['ended', 'replaced'].includes(state.status) &&
      state.modes.available === true && !state.modePending;
    for (const [mode, field] of [['tablet', 'tabletEnabled'], ['recording', 'recordingEnabled']]) {
      const input = panel.querySelector(`[data-mode="${mode}"]`);
      if (!input) continue;
      const known = state.modes.available === true && typeof state.modes[field] === 'boolean';
      input.checked = known && state.modes[field];
      input.indeterminate = !known;
      input.disabled = !controlsEnabled || !known;
      const label = panel.querySelector(`#mode-state-${mode}`);
      if (label && label.textContent !== modeValueLabel(field)) label.textContent = modeValueLabel(field);
    }
    const feedback = panel.querySelector('#mode-feedback');
    const message = modeFeedback();
    if (feedback && feedback.textContent !== message) feedback.textContent = message;
    feedback?.classList.toggle('is-error', Boolean(state.modeError || state.modes.applyState === 'error'));
  }

  function syncModeDisclosure() {
    const disclosure = document.getElementById('mode-disclosure');
    if (disclosure) disclosure.open = !window.matchMedia('(max-width: 800px)').matches || state.modesOpen;
  }
  window.addEventListener('resize', syncModeDisclosure);

  async function changeMode(input) {
    const mode = input.dataset.mode;
    const field = mode === 'tablet' ? 'tabletEnabled' : mode === 'recording' ? 'recordingEnabled' : null;
    if (!field) return;
    const enabled = input.checked;
    if (!state.authenticated || state.modePending || state.modes.available !== true || typeof state.modes[field] !== 'boolean') {
      updateModePanel();
      return;
    }
    state.modePending = mode;
    state.modeError = null;
    const generation = ++modeWriteGeneration;
    ++statusRequestGeneration;
    updateModePanel();
    let refresh = false;
    try {
      const result = await post('/modes', { mode, enabled });
      if (generation !== modeWriteGeneration || !state.authenticated) return;
      if (result.modes && typeof result.modes === 'object') {
        applyModeSnapshot(result.modes);
        state.modeError = null;
      } else {
        state.modeError = 'mode_state_unavailable';
        refresh = true;
      }
    } catch (failure) {
      if (generation !== modeWriteGeneration) return;
      if (failure.statusCode === 401) handleUnauthorized(failure);
      else {
        console.warn('Could not update remote camera mode', failure);
        if (failure.body?.modes && typeof failure.body.modes === 'object') applyModeSnapshot(failure.body.modes);
        state.modeError = failure.body?.modes?.error || failure.body?.status || 'request_failed';
        refresh = true;
      }
    } finally {
      if (generation === modeWriteGeneration) {
        state.modePending = null;
        ++modeWriteGeneration;
        ++statusRequestGeneration;
        updateModePanel();
      }
    }
    if (refresh && state.authenticated && !state.replaced) await pollStatus();
  }

  function connectionLabel() {
    if (state.replaced || state.status === 'replaced') return text.replaced;
    if (state.status === 'low_battery') return text.lowBattery;
    if (state.status === 'preparing') return text.preparing;
    if (state.status === 'connected') return state.firstLiveFrame ? text.viewing : text.connected;
    if (state.status === 'error') return text.errorTitle;
    if (state.status === 'ended') return text.ended;
    return text.ready;
  }

  function statusClass() {
    if (state.replaced) return 'replaced';
    return ['connected', 'preparing', 'low_battery', 'error', 'ended'].includes(state.status) ? state.status : 'ready';
  }

  function renderShell() {
    if (contractBlocked) { renderLogin(); return; }
    app.classList.remove('is-expanded');
    app.classList.remove('has-player');
    app.innerHTML = `${pageHeader()}<div class="statusrow"><div class="connection" id="connection" data-state="ready" role="status"><span class="dot"></span><span id="connection-label"></span></div><button class="quiet" type="button" data-action="end">${icon('stop')}${escapeHtml(text.end)}</button></div>${batteryPanel()}<div class="viewer-workspace"><section class="viewer-main"><nav class="nav" aria-label="${escapeHtml(text.title)}"><button type="button" data-action="tab" data-mode="live" aria-pressed="true">${icon('video')}${escapeHtml(text.live)}</button><button type="button" data-action="tab" data-mode="archive" aria-pressed="false">${icon('archive')}${escapeHtml(text.archive)}</button></nav><main class="content" id="main-panel"></main></section>${modePanel()}</div>`;
    const disclosure = document.getElementById('mode-disclosure');
    syncModeDisclosure();
    disclosure.addEventListener('toggle', (event) => {
      if (window.matchMedia('(max-width: 800px)').matches) state.modesOpen = event.currentTarget.open;
    });
    updateStatusBar();
    renderMain(true);
  }

  function updateStatusBar() {
    updateBattery();
    const connection = document.getElementById('connection');
    if (!connection) return;
    connection.dataset.state = statusClass();
    const label = document.getElementById('connection-label');
    if (label) label.textContent = connectionLabel();
    const main = document.getElementById('main-panel');
    if (main && state.mode === 'live' && ['low_battery', 'error', 'ended', 'replaced'].includes(state.status)) {
      const gate = main.querySelector('.gate');
      const gateNote = gate?.querySelector('.charge-note');
      if (gateNote && !state.missingCharge) gateNote.remove();
      else if (gateNote) gateNote.outerHTML = chargeNote();
      else if (gate && state.missingCharge) gate.insertAdjacentHTML('beforeend', chargeNote());
    }
    document.querySelectorAll('.nav [data-action="tab"]').forEach((tab) => tab.setAttribute('aria-pressed', String(tab.dataset.mode === state.mode)));
    updateModePanel();
  }

  function liveGate() {
    let title = text.ready, message = state.localFirstFrame ? text.viewing : text.ready, buttonLabel = text.startLive;
    let buttonAction = 'start-live', iconName = 'video';
    if (state.replaced || state.status === 'replaced') {
      title = text.replacedTitle; message = text.replaced; buttonLabel = text.signInAgain; buttonAction = 'signin-again'; iconName = 'warning';
    } else if (state.status === 'low_battery') {
      title = text.lowBattery; message = text.lowBattery; buttonLabel = ''; buttonAction = ''; iconName = 'warning';
    } else if (state.status === 'error') {
      title = text.errorTitle; message = text.error; buttonLabel = text.retry; iconName = 'wifi';
    } else if (state.status === 'ended') {
      title = text.ended; message = text.ended; buttonLabel = text.signInAgain; buttonAction = 'signin-again'; iconName = 'warning';
    } else if (state.wantsLive && state.status === 'preparing') {
      title = text.preparing; message = text.preparing; buttonLabel = ''; buttonAction = ''; iconName = 'camera';
    } else if (state.wantsLive && state.status === 'connected') {
      title = text.connected; message = text.waiting; buttonLabel = ''; buttonAction = ''; iconName = 'camera';
    } else if (state.status === 'connected') {
      title = text.connected; message = text.connected; buttonLabel = text.resumeLive; iconName = 'video';
    }
    const button = buttonLabel ? `<button class="primary" type="button" data-action="${buttonAction}">${icon(iconName)}${escapeHtml(buttonLabel)}</button>` : '';
    return `<section class="gate" role="status">${icon(iconName)}<h2>${escapeHtml(title)}</h2><p>${escapeHtml(message)}</p>${button}${chargeNote()}</section>`;
  }

  function liveMain() {
    if (state.wantsLive && (state.status === 'connected' || state.livePlaylist) && !state.liveFailed && !state.replaced) {
      return `${cameraTabs(state.view)}<section class="viewer" aria-label="${escapeHtml(text.live)}"><video id="live-video" class="live-video" muted playsinline preload="none" aria-label="${escapeHtml(text.live)}"></video><div class="viewer-foot"><span class="muted" id="live-caption">${escapeHtml(text.waiting)}</span><div class="viewer-actions"><button class="icon-button" type="button" data-action="fullscreen" aria-label="${escapeHtml(text.expand)}" title="${escapeHtml(text.expand)}">${icon('maximize')}</button></div></div></section>`;
    }
    return `${cameraTabs(state.view)}${liveGate()}`;
  }

  function recordingOrder(record) {
    const order = record?.cameraOrder;
    return Array.isArray(order) && order.length === 4 && order.every((value) => Number.isInteger(value) && value >= 0 && value < 4) && new Set(order).size === 4 ? order : null;
  }

  function dayRecordings(day) { return state.recordings.filter((record) => record.day === day); }

  function calendarHtml() {
    const [year, month] = (state.month || monthKey(new Date())).split('-').map(Number);
    const start = new Date(year, month - 1, 1, 12);
    const daysInMonth = new Date(year, month, 0, 12).getDate();
    const offset = (start.getDay() + 6) % 7;
    const monthTitle = new Intl.DateTimeFormat(localeTags[locale], { month: 'long', year: 'numeric' }).format(start);
    const headings = Array.from({ length: 7 }, (_, index) => new Intl.DateTimeFormat(localeTags[locale], { weekday: 'short' }).format(new Date(2024, 0, 1 + index, 12)));
    const days = Array.from({ length: daysInMonth }, (_, index) => {
      const day = `${state.month}-${String(index + 1).padStart(2, '0')}`;
      const hasRecords = state.recordings.some((record) => record.day === day);
      return `<button class="day${hasRecords ? ' has-record' : ''}" type="button" data-action="date" data-day="${day}" aria-pressed="${state.selectedDay === day}" aria-label="${escapeHtml(dayLabel(day))}${hasRecords ? `, ${escapeHtml(text.clips)}` : ''}">${index + 1}</button>`;
    }).join('');
    return `<aside class="calendar" aria-label="${escapeHtml(text.archive)}"><div class="month"><button type="button" data-action="month" data-delta="-1" aria-label="${escapeHtml(text.previousMonth)}">${icon('chevronLeft')}</button><strong>${escapeHtml(monthTitle)}</strong><button type="button" data-action="month" data-delta="1" aria-label="${escapeHtml(text.nextMonth)}">${icon('chevronRight')}</button></div><div class="days">${headings.map((heading) => `<span class="weekday">${escapeHtml(heading)}</span>`).join('')}${'<span></span>'.repeat(offset)}${days}</div></aside>`;
  }

  function summaryHtml() {
    const tablet = state.recordings.filter((record) => record.storage === 'tablet').length;
    const sd = state.recordings.filter((record) => record.storage === 'sd').length;
    return `<div class="summary-cards"><div class="summary-card"><strong>${state.recordings.length}</strong><span>${escapeHtml(text.allRecordings)}</span></div><div class="summary-card"><strong>${tablet}</strong><span>${escapeHtml(text.tablet)}</span></div><div class="summary-card"><strong>${sd}</strong><span>${escapeHtml(text.sd)}</span></div></div>`;
  }

  function recordCounts(record) {
    const events = Array.isArray(record.events) ? record.events : [];
    const yellow = Number.isFinite(record.yellowCount) ? record.yellowCount : events.filter((event) => event.level === 'yellow').length;
    const red = Number.isFinite(record.redCount) ? record.redCount : events.filter((event) => event.level === 'red').length;
    return { yellow: Math.max(0, yellow), red: Math.max(0, red) };
  }

  function recordingCardsHtml(records) {
    if (!records.length) return `<div class="empty">${icon('empty')}<p>${escapeHtml(text.noRecordings)}</p></div>`;
    return `<div class="clips">${records.map((record) => {
      const counts = recordCounts(record);
      const storage = record.storage === 'sd' ? text.sd : text.tablet;
      const dateTime = `${dayLabel(record.day)}${record.time ? ` · ${record.time}` : ''}`;
      return `<button class="clip" type="button" data-action="record" data-id="${escapeHtml(record.id)}"><div class="poster"><span class="poster-fallback">${icon('camera')}</span><img src="/archive/thumbnail/${escapeHtml(record.id)}" loading="lazy" alt=""><span class="poster-play"><span>${icon('play')}</span></span></div><div class="clip-details"><div class="clip-title">${escapeHtml(record.name)}</div><div class="clip-datetime">${icon('calendar')}${escapeHtml(dateTime)}</div><div class="events">${counts.yellow ? `<span aria-label="${escapeHtml(text.yellowEvents)}"><b class="event-dot"></b>${counts.yellow}</span>` : ''}${counts.red ? `<span aria-label="${escapeHtml(text.redEvents)}"><b class="event-dot red"></b>${counts.red}</span>` : ''}</div><div class="clip-meta"><span>${icon('clock')}${escapeHtml(clock(Number(record.durationMs) / 1000))}</span><span>${icon(record.storage === 'sd' ? 'archive' : 'camera')}${escapeHtml(storage)}</span></div></div></button>`;
    }).join('')}</div>`;
  }

  function markerHtml(record) {
    const duration = Number(record.durationMs);
    if (!Number.isFinite(duration) || duration <= 0 || !Array.isArray(record.events)) return '';
    return record.events.map((event) => {
      if (!Number.isFinite(event.startMs) || !Number.isFinite(event.endMs) || event.endMs < event.startMs || event.endMs < 0 || event.startMs > duration) return '';
      const start = Math.max(0, Math.min(100, event.startMs / duration * 100));
      const end = Math.max(start, Math.min(100, event.endMs / duration * 100));
      return `<span class="marker${event.level === 'red' ? ' red' : ''}" style="left:min(${start}%,calc(100% - 2px));width:max(2px,${end - start}%)" aria-hidden="true"></span>`;
    }).join('');
  }

  function playerHtml(record) {
    const duration = Math.max(0, Number(record.durationMs) / 1000 || 0);
    const order = recordingOrder(record);
    const views = order ? cameraTabs(state.archiveView, true, order) : cameraTabs('composition', true, null);
    const counts = recordCounts(record);
    const sameDay = dayRecordings(record.day);
    const previousDisabled = sameDay[0]?.id === record.id;
    const nextDisabled = sameDay[sameDay.length - 1]?.id === record.id;
    const speeds = [0.25, 0.5, 1, 1.25, 1.5, 2, 2.5, 3];
    return `<section class="archive-player has-record" aria-label="${escapeHtml(record.name)}"><div class="record-head"><button class="quiet" type="button" data-action="back">${icon('left')}${escapeHtml(text.back)}</button><span class="record-name">${escapeHtml(record.name)}</span></div>${views}<div class="viewer" id="archive-viewer"><div class="archive-viewer"><video id="archive-video" class="archive-video" playsinline preload="metadata" aria-label="${escapeHtml(record.name)}"></video></div><div class="play-controls"><div class="timeline"><div class="timeline-track"><div class="timeline-fill" id="timeline-fill"></div>${markerHtml(record)}</div><input class="seek" id="seek" type="range" min="0" max="${duration}" step="0.1" value="0" aria-label="${escapeHtml(text.position)}"></div><div class="time-row"><span id="current-time">0:00</span><span id="total-time">${escapeHtml(clock(duration))}</span></div><div class="transport"><button type="button" data-action="previous" aria-label="${escapeHtml(text.previous)}" title="${escapeHtml(text.previous)}"${previousDisabled ? ' disabled' : ''}>${icon('skipBack')}</button><button class="play" type="button" data-action="play" aria-label="${escapeHtml(text.play)}" title="${escapeHtml(text.play)}">${icon('play')}</button><button type="button" data-action="next" aria-label="${escapeHtml(text.next)}" title="${escapeHtml(text.next)}"${nextDisabled ? ' disabled' : ''}>${icon('skipForward')}</button><button type="button" data-action="restart" aria-label="${escapeHtml(text.restart)}" title="${escapeHtml(text.restart)}">${icon('rotate')}</button><select id="playback-speed" aria-label="${escapeHtml(text.speed)}">${speeds.map((speed) => `<option value="${speed}"${speed === 1 ? ' selected' : ''}>×${new Intl.NumberFormat(localeTags[locale], { maximumFractionDigits: 2 }).format(speed)}</option>`).join('')}</select><button class="icon-button" type="button" data-action="fullscreen" aria-label="${escapeHtml(text.expand)}" title="${escapeHtml(text.expand)}">${icon('maximize')}</button></div><p id="archive-error" class="error-note" role="alert">${state.playerError ? escapeHtml(text.archiveError) : ''}</p></div></div></section>`;
  }

  function archiveHtml() {
    if (state.archiveLoading) return `<div class="empty">${escapeHtml(text.loading)}</div>`;
    if (state.archiveError) return `<div class="empty">${escapeHtml(text.loadError)}<button class="secondary" type="button" data-action="retry-archive">${escapeHtml(text.retryLoad)}</button></div>`;
    const record = state.recordings.find((item) => item.id === state.selectedRecord);
    const items = dayRecordings(state.selectedDay || '');
    return `<div class="archive-layout">${calendarHtml()}<section class="archive-list" aria-label="${escapeHtml(text.archive)}">${summaryHtml()}${record ? playerHtml(record) : `<div class="list-heading"><h2>${escapeHtml(formatDate(state.selectedDay || '', { day: 'numeric', month: 'long', year: 'numeric' }))}</h2><span class="muted tiny">${items.length} ${escapeHtml(text.clips.toLowerCase())}</span></div>${recordingCardsHtml(items)}`}</section></div>`;
  }

  function mainKey() {
    if (state.replaced) return 'replaced';
    if (state.mode === 'archive') return 'archive';
    if (state.wantsLive && !state.liveFailed && (state.status === 'connected' || state.livePlaylist)) return 'live';
    return `gate:${state.status}:${state.wantsLive}`;
  }

  function renderMain(force = false) {
    if (contractBlocked) { renderLogin(); return; }
    const main = document.getElementById('main-panel');
    if (!main) return;
    const key = mainKey();
    if (key === 'live' && main.querySelector('#live-video')) {
      state.mainKey = key;
      if (force) refreshLiveCameraTabs();
      updateStatusBar();
      startLiveMedia();
      return;
    }
    if (!force && key === state.mainKey) return;
    state.mainKey = key;
    stopLiveMedia();
    disconnectArchiveResizeObserver();
    app.classList.toggle('has-player', state.mode === 'archive' && Boolean(state.selectedRecord));
    if (state.replaced || state.status === 'ended') main.innerHTML = liveGate();
    else if (state.mode === 'archive') main.innerHTML = archiveHtml();
    else if (key === 'live') main.innerHTML = liveMain();
    else main.innerHTML = `${cameraTabs(state.view)}${liveGate()}`;
    updateStatusBar();
    if (key === 'live') startLiveMedia();
    if (state.mode === 'archive' && state.selectedRecord) attachArchivePlayer();
  }

  function normalizeArchive(payload) {
    const raw = Array.isArray(payload?.recordings) ? payload.recordings : [];
    const recordings = raw.filter((record) => safeId(record.id) && Number.isFinite(record.startEpochMs) && Number.isFinite(record.durationMs) && record.durationMs >= 0 && dateParts(record.day)).map((record) => ({
      ...record,
      cameraOrder: recordingOrder(record),
      events: Array.isArray(record.events) ? record.events.filter((event) => event && Number.isFinite(event.startMs) && Number.isFinite(event.endMs) && ['yellow', 'red'].includes(event.level)) : []
    })).sort((a, b) => b.startEpochMs - a.startEpochMs);
    const latest = dateParts(payload?.latestDay) ? payload.latestDay : recordings[0]?.day || null;
    return { recordings, latest };
  }

  async function loadArchive() {
    const action = actionGeneration;
    state.archiveLoading = true;
    state.archiveError = false;
    renderMain(true);
    try {
      const result = await api('/archive');
      if (action !== actionGeneration || state.mode !== 'archive') return;
      const catalog = normalizeArchive(result);
      state.recordings = catalog.recordings;
      state.selectedDay = catalog.latest || todayKey();
      state.month = state.selectedDay.slice(0, 7);
      state.archiveLoaded = true;
    } catch (failure) {
      if (action !== actionGeneration) return;
      if (failure.statusCode === 401) handleUnauthorized(failure);
      else {
        console.warn('Could not load camera archive catalog', failure);
        state.archiveError = true;
      }
    } finally {
      if (action !== actionGeneration) return;
      state.archiveLoading = false;
      renderMain(true);
    }
  }

  async function openArchive() {
    if (state.mode === 'archive') return;
    const action = ++actionGeneration;
    if (!await closeArchivePlayer() || action !== actionGeneration) return;
    const selectionRevision = ++state.liveSelectionRevision;
    try {
      const result = await post('/archive/open', { revision: selectionRevision });
      if (action !== actionGeneration) return;
      if (result.selectionRevision !== selectionRevision || result.mode !== 'archive') {
        await pollStatus();
        return;
      }
      stopLiveMedia();
      state.mode = 'archive';
      rememberMode('archive');
      state.status = result.status || 'ready';
      state.wantsLive = false;
      state.localFirstFrame = false;
      state.liveRequest = null;
      state.liveStreamId = null;
      state.livePlaylist = null;
      state.mainKey = '';
      renderMain(true);
      if (!state.archiveLoaded) await loadArchive();
    } catch (failure) {
      if (action !== actionGeneration) return;
      if (failure.statusCode === 401) handleUnauthorized(failure);
      else {
        console.warn('Could not enter camera archive', failure);
        state.status = 'error';
        state.wantsLive = false;
        state.liveFailed = true;
        state.mainKey = '';
        updateStatusBar();
        renderMain(true);
      }
    }
  }

  async function startLive(view = state.view) {
    if (state.replaced) return;
    if (!state.authenticated) { await signIn(); return; }
    const previousView = state.view;
    const previousStatus = state.status;
    const previousLiveFailed = state.liveFailed;
    const previousSelectionRevision = state.liveSelectionRevision;
    const newLive = state.mode !== 'live' || !state.wantsLive;
    const action = ++actionGeneration;
    state.mode = 'live';
    rememberMode('live');
    state.view = liveCameras().includes(view) ? view : 'composition';
    state.wantsLive = true;
    state.liveFailed = false;
    state.status = 'preparing';
    if (newLive) {
      state.firstLiveFrame = false;
      state.localFirstFrame = false;
      state.liveRequest = null;
      state.liveStreamId = null;
      state.livePlaylist = null;
      state.mainKey = '';
      stopLiveMedia();
    }
    const selectionRevision = ++state.liveSelectionRevision;
    renderMain(true);
    try {
      const result = await post('/live', { view: state.view, revision: selectionRevision });
      if (action !== actionGeneration || selectionRevision !== state.liveSelectionRevision) return;
      if (typeof result.request !== 'string' || !/^[0-9a-f-]{36}$/.test(result.request))
        throw new Error('Missing live media request');
      if (result.selectionRevision !== selectionRevision || typeof result.streamId !== 'string'
          || !/^[0-9a-f-]{36}$/.test(result.streamId)) throw new Error('Missing live stream identity');
      const streamChanged = state.liveStreamId !== result.streamId;
      if (streamChanged) {
        stopLiveMedia();
        state.livePlaylist = null;
        state.liveStreamId = result.streamId;
        state.firstLiveFrame = false;
        state.localFirstFrame = false;
        state.mainKey = '';
      }
      state.liveRequest = result.request;
      state.status = result.status === 'ready' || !result.status ? 'preparing' : result.status;
      if (['error', 'ended'].includes(state.status)) state.wantsLive = false;
      renderMain(true);
      if (state.status === 'connected') startLiveMedia();
      updateStatusBar();
    } catch (failure) {
      if (action !== actionGeneration || selectionRevision !== state.liveSelectionRevision) return;
      if (failure.statusCode === 401) handleUnauthorized(failure);
      else if (!newLive) {
        console.warn('Could not switch live camera view', failure);
        state.view = previousView;
        state.status = previousStatus;
        state.liveFailed = previousLiveFailed;
        if (failure.statusCode) state.liveSelectionRevision = previousSelectionRevision;
        refreshLiveCameraTabs();
        updateStatusBar();
      }
      else {
        console.warn('Could not start live camera view', failure);
        const lowBattery = failure.body?.status === 'low_battery';
        state.status = lowBattery ? 'low_battery' : 'error';
        state.liveFailed = !lowBattery;
        if (!lowBattery) state.wantsLive = false;
        stopLiveMedia();
        renderMain(true);
      }
    }
  }

  async function selectLiveView(view) {
    if (!liveCameras().includes(view)) return;
    if (view === state.view) return;
    if (state.wantsLive) await startLive(view);
    else { state.view = view; renderMain(true); }
  }

  async function openRecording(id) {
    const action = ++actionGeneration;
    if (!state.recordings.some((record) => record.id === id)) return;
    if (!await closeArchivePlayer() || action !== actionGeneration) return;
    if (state.replaced) return;
    state.selectedRecord = id;
    state.archiveView = 'composition';
    state.playerError = false;
    state.playerFrameReported = false;
    state.mainKey = '';
    renderMain(true);
  }

  async function closeArchivePlayer({ leave = false } = {}) {
    const action = actionGeneration;
    const video = document.getElementById('archive-video');
    if (!state.selectedRecord && !video && !leave) return true;
    if (video) video.pause();
    try {
      const selectionRevision = leave ? ++state.liveSelectionRevision : null;
      await post('/archive/close', leave ? { leave: true, revision: selectionRevision } : {});
      if (action !== actionGeneration) return false;
    }
    catch (failure) {
      if (action !== actionGeneration) return false;
      if (failure.statusCode === 401) handleUnauthorized(failure);
      else {
        console.warn('Could not close camera archive playback', failure);
        state.status = 'error';
        state.playerError = true;
        const note = document.getElementById('archive-error');
        if (note) note.textContent = text.archiveError;
        updateStatusBar();
      }
      return false;
    }
    archiveFrameToken++;
    disconnectArchiveResizeObserver();
    if (video) {
      video.removeAttribute('src');
      video.load();
    }
    state.selectedRecord = null;
    state.playerFrameReported = false;
    state.playerError = false;
    if (leave) {
      state.liveRequest = null;
      state.liveStreamId = null;
      state.livePlaylist = null;
    }
    return true;
  }

  function applyArchiveCrop() {
    const video = document.getElementById('archive-video');
    if (!video) return;
    const record = state.recordings.find((item) => item.id === state.selectedRecord);
    const order = recordingOrder(record);
    const direction = cameras.indexOf(state.archiveView) - 1;
    const cell = order && direction >= 0 ? order.indexOf(direction) : -1;
    video.classList.toggle('cropped', cell >= 0);
    if (cell < 0) {
      video.style.removeProperty('width');
      video.style.removeProperty('height');
      video.style.removeProperty('left');
      video.style.removeProperty('top');
      video.style.removeProperty('clip-path');
      return;
    }

    const sourceWidth = video.videoWidth;
    const sourceHeight = video.videoHeight;
    const viewport = document.querySelector('.archive-viewer');
    const bounds = viewport?.getBoundingClientRect();
    if (!sourceWidth || !sourceHeight || !bounds?.width || !bounds?.height) return;

    const crop = cameraRectForCell(cell, sourceWidth, sourceHeight);
    if (!crop) return;
    const scale = Math.min(bounds.width / crop.width, bounds.height / crop.height);
    const offsetX = (bounds.width - crop.width * scale) / 2;
    const offsetY = (bounds.height - crop.height * scale) / 2;
    video.style.width = `${sourceWidth * scale}px`;
    video.style.height = `${sourceHeight * scale}px`;
    video.style.left = `${offsetX - crop.left * scale}px`;
    video.style.top = `${offsetY - crop.top * scale}px`;
    video.style.clipPath = `inset(${crop.top * scale}px ${(sourceWidth - crop.right) * scale}px ${(sourceHeight - crop.bottom) * scale}px ${crop.left * scale}px)`;
  }

  function cameraRectForCell(cell, outputWidth, outputHeight) {
    if (!Number.isInteger(cell) || cell < 0 || cell >= 4 || outputWidth < 2 || outputHeight < 2) return null;
    const cellWidth = Math.floor(outputWidth / 2);
    const cellHeight = Math.floor(outputHeight / 2);
    // Mirrors MotionCompositionLayout.cameraRectForCell's 1920:1300 source camera fit.
    const cameraWidth = 1920;
    const cameraHeight = 1300;
    let imageWidth;
    let imageHeight;
    if (cellWidth * cameraHeight <= cellHeight * cameraWidth) {
      imageWidth = cellWidth;
      imageHeight = Math.floor(cellWidth * cameraHeight / cameraWidth);
    } else {
      imageHeight = cellHeight;
      imageWidth = Math.floor(cellHeight * cameraWidth / cameraHeight);
    }
    if (!imageWidth || !imageHeight) return null;
    const left = Math.floor((outputWidth - imageWidth * 2) / 2) + cell % 2 * imageWidth;
    const top = Math.floor((outputHeight - imageHeight * 2) / 2) + Math.floor(cell / 2) * imageHeight;
    return { left, top, right: left + imageWidth, bottom: top + imageHeight, width: imageWidth, height: imageHeight };
  }

  function disconnectArchiveResizeObserver() {
    if (archiveResizeObserver) archiveResizeObserver.disconnect();
    archiveResizeObserver = null;
  }

  function clearArchivePlayerLocally() {
    archiveFrameToken++;
    disconnectArchiveResizeObserver();
    const video = document.getElementById('archive-video');
    if (video) {
      video.pause();
      video.removeAttribute('src');
      video.load();
    }
    state.selectedRecord = null;
    state.playerFrameReported = false;
    state.playerError = false;
  }

  function reconcileViewerMode(data) {
    const nextMode = data.mode;
    if (!['live', 'archive'].includes(nextMode)) return false;
    if (nextMode !== state.mode) {
      stopLiveMedia();
      clearArchivePlayerLocally();
      state.mode = nextMode;
      rememberMode(nextMode);
      state.mainKey = '';
      state.localFirstFrame = false;
      if (nextMode === 'archive') {
        state.wantsLive = false;
        state.liveRequest = null;
        state.liveStreamId = null;
        state.livePlaylist = null;
      } else {
        state.wantsLive = true;
        state.liveFailed = false;
        state.liveRequest = null;
        state.liveStreamId = null;
        state.livePlaylist = null;
      }
    }
    if (nextMode === 'live' && liveCameras().includes(data.view)) state.view = data.view;
    return true;
  }

  function attachArchivePlayer() {
    const video = document.getElementById('archive-video');
    const record = state.recordings.find((item) => item.id === state.selectedRecord);
    if (!video || !record) return;
    video.src = `/archive/video/${record.id}`;
    video.playbackRate = Number(document.getElementById('playback-speed')?.value) || 1;
    applyArchiveCrop();
    const viewport = document.querySelector('.archive-viewer');
    if (viewport && typeof ResizeObserver === 'function') {
      archiveResizeObserver = new ResizeObserver(applyArchiveCrop);
      archiveResizeObserver.observe(viewport);
    }
    video.load();
    const play = video.play();
    if (play?.catch) play.catch(() => {});
  }

  function updateArchiveControls() {
    const video = document.getElementById('archive-video');
    if (!video) return;
    const duration = Number.isFinite(video.duration) && video.duration > 0 ? video.duration : Math.max(0, Number(state.recordings.find((item) => item.id === state.selectedRecord)?.durationMs) / 1000 || 0);
    const seek = document.getElementById('seek');
    if (seek) {
      if (duration > 0) seek.max = duration;
      if (!state.seeking) seek.value = Number.isFinite(video.currentTime) ? video.currentTime : 0;
    }
    const position = state.seeking && seek ? Number(seek.value) : Number.isFinite(video.currentTime) ? video.currentTime : 0;
    const current = document.getElementById('current-time');
    if (current) current.textContent = clock(position);
    const total = document.getElementById('total-time');
    if (total) total.textContent = clock(duration);
    const fill = document.getElementById('timeline-fill');
    if (fill) fill.style.width = duration > 0 ? `${Math.max(0, Math.min(100, position / duration * 100))}%` : '0%';
    const button = document.querySelector('[data-action="play"]');
    if (button) {
      button.innerHTML = icon(video.paused ? 'play' : 'pause');
      button.setAttribute('aria-label', video.paused ? text.play : text.pause);
      button.title = video.paused ? text.play : text.pause;
    }
  }

  function fullscreenTarget() { return document.querySelector('.archive-player') || document.querySelector('.viewer'); }
  function updateFullscreenButton() {
    const button = document.querySelector('[data-action="fullscreen"]');
    if (!button) return;
    const expanded = Boolean(document.fullscreenElement || app.classList.contains('is-expanded'));
    const label = expanded ? text.minimize : text.expand;
    button.innerHTML = icon(expanded ? 'minimize' : 'maximize');
    button.setAttribute('aria-label', label);
    button.title = label;
  }
  async function toggleFullscreen() {
    const target = fullscreenTarget();
    if (!target) return;
    if (document.fullscreenElement) {
      await document.exitFullscreen().catch(() => {});
      return;
    }
    if (app.classList.contains('is-expanded')) {
      app.classList.remove('is-expanded');
      updateFullscreenButton();
      return;
    }
    if (target.requestFullscreen) {
      await target.requestFullscreen().catch(() => { app.classList.add('is-expanded'); updateFullscreenButton(); });
    } else {
      app.classList.add('is-expanded');
      updateFullscreenButton();
    }
  }

  async function loadHlsLibrary() {
    if (window.Hls) return window.Hls;
    if (!hlsScriptPromise) hlsScriptPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = '/hls.js';
      script.onload = () => window.Hls ? resolve(window.Hls) : reject(new Error('HLS library did not expose its player'));
      script.onerror = () => reject(new Error('HLS library could not be loaded'));
      document.head.appendChild(script);
    });
    return hlsScriptPromise.catch((failure) => { hlsScriptPromise = null; throw failure; });
  }

  function reportFirstFrame(video, mode) {
    const token = mode === 'live' ? ++liveFrameToken : ++archiveFrameToken;
    const onFrame = () => {
      if (token !== (mode === 'live' ? liveFrameToken : archiveFrameToken) || !state.authenticated || state.replaced) return;
      if (mode === 'live') {
        if (!state.wantsLive) return;
        state.firstLiveFrame = true;
        state.localFirstFrame = true;
        updateStatusBar();
        const caption = document.getElementById('live-caption');
        if (caption) caption.textContent = text.viewing;
      } else if (state.playerFrameReported || !state.selectedRecord) return;
      if (mode === 'archive') state.playerFrameReported = true;
      post('/diagnostic', { event: 'first_frame', mode }).catch((failure) => console.warn('Could not report first displayed frame', failure));
    };
    if (typeof video.requestVideoFrameCallback === 'function') video.requestVideoFrameCallback(onFrame);
  }

  async function startLiveMedia() {
    const video = document.getElementById('live-video');
    if (!video || video.dataset.started === 'true' || state.liveFailed || !state.livePlaylist) return;
    const playlist = state.livePlaylist;
    const generation = ++mediaGeneration;
    const current = () => generation === mediaGeneration && video.isConnected &&
      state.authenticated && !state.replaced && state.mode === 'live' && state.wantsLive;
    video.dataset.started = 'true';
    reportFirstFrame(video, 'live');
    const fail = (cause, category = 'play') => {
      if (!current()) return;
      console.warn('Live playback failed', category, cause?.name || cause?.type || 'media');
      ++actionGeneration;
      clearInterval(state.poll); state.poll = null;
      state.liveFailed = true; state.wantsLive = false; state.status = 'error';
      // The cause and teardown travel together; a separate request races session invalidation.
      post('/stop', { origin: 'playback_error', category, mediaCode: video.error?.code,
        exception: cause?.error?.name || cause?.name, hlsType: cause?.type, hlsDetails: cause?.details,
        fragment: cause?.frag?.sn, httpStatus: cause?.response?.code, clientRevision: WEB_REVISION }).catch(() => {});
      state.authenticated = false;
      stopLiveMedia();
      renderMain(true);
    };
    video.onerror = () => fail(video.error, video.error?.code === 3 ? 'decode' : 'load');
    try {
      if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = playlist;
        await video.play();
        return;
      }
      const Hls = await loadHlsLibrary();
      if (!current()) return;
      if (!Hls || !Hls.isSupported()) { fail(null, 'hls'); return; }
      const hls = new Hls({ liveSyncDurationCount: 2, maxBufferLength: 8,
        xhrSetup: (xhr) => xhr.setRequestHeader(REVISION_HEADER, String(WEB_REVISION)) });
      state.hls = hls;
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        if (current()) video.play().catch((error) => fail(error));
      });
      hls.on(Hls.Events.ERROR, (_, data) => { if (data.fatal) fail(data, 'hls'); });
      hls.loadSource(playlist);
      hls.attachMedia(video);
    } catch (failure) { fail(failure); }
  }

  function stopLiveMedia() {
    mediaGeneration++;
    liveFrameToken++;
    if (state.hls) {
      state.hls.destroy();
      state.hls = null;
    }
    const video = document.getElementById('live-video');
    if (video) {
      video.pause();
      video.onerror = null;
      video.removeAttribute('src');
      delete video.dataset.started;
      video.load();
    }
  }

  function handleUnauthorized(failure) {
    applyBatterySnapshot(null);
    actionGeneration++;
    stopLiveMedia();
    const video = document.getElementById('archive-video');
    if (video) { video.pause(); video.removeAttribute('src'); video.load(); }
    clearInterval(state.poll);
    state.poll = null;
    state.replaced = failure.body?.status === 'replaced';
    state.status = state.replaced ? 'replaced' : 'ended';
    state.wantsLive = false;
    state.liveRequest = null;
    state.liveStreamId = null;
    state.livePlaylist = null;
    state.selectedRecord = null;
    state.mainKey = '';
    updateStatusBar();
    renderMain(true);
  }

  async function pollStatus() {
    updateBattery();
    if (!state.authenticated || state.replaced) return;
    const requestedAt = Date.now();
    const action = actionGeneration;
    const modeWrite = modeWriteGeneration;
    const request = ++statusRequestGeneration;
    try {
      const previous = state.status;
      const data = await api('/status');
      if (action !== actionGeneration || !state.authenticated || modeWrite !== modeWriteGeneration || request !== statusRequestGeneration) return;
      if (data.status === 'replaced') {
        handleUnauthorized({ body: { status: 'replaced' } });
        return;
      }
      const selectionRevision = data.selectionRevision;
      const currentSelectionSnapshot = Number.isSafeInteger(selectionRevision)
        && selectionRevision >= state.liveSelectionRevision;
      const interiorCameras = normalizeInteriorCameraMask(data.interiorCameras);
      if (interiorCameras !== null && interiorCameras !== state.interiorCameras) {
        state.interiorCameras = interiorCameras;
        if (!liveCameras().includes(state.view)) state.view = 'composition';
        if (currentSelectionSnapshot && data.mode === 'live' && liveCameras().includes(data.view)) state.view = data.view;
        if (state.mode === 'live' && state.wantsLive && currentSelectionSnapshot && data.mode !== 'archive') {
          await startLive(state.view);
          return;
        }
        if (state.mode === 'live') refreshLiveCameraTabs();
      }
      if (!state.modePending) applyModeSnapshot(data.modes);
      updateModePanel();
      if (!['ready', 'preparing', 'connected', 'low_battery', 'error', 'ended'].includes(data.status)) {
        applyBatterySnapshot(null);
        console.warn('Unexpected camera viewer status', data.status);
        return;
      }
      applyBatterySnapshot(data.battery, requestedAt);
      if (currentSelectionSnapshot && reconcileViewerMode(data)) {
        state.liveSelectionRevision = selectionRevision;
      }
      if (state.mode === 'live' && state.wantsLive) {
        if (!Number.isSafeInteger(selectionRevision) || selectionRevision < state.liveSelectionRevision) return;
        if (selectionRevision > state.liveSelectionRevision) state.liveSelectionRevision = selectionRevision;
        state.liveRequest = typeof data.request === 'string' && /^[0-9a-f-]{36}$/.test(data.request)
          ? data.request : null;
        if (liveCameras().includes(data.view)) state.view = data.view;
        if (data.streamId && !/^[0-9a-f-]{36}$/.test(data.streamId)) {
          console.warn('Camera viewer returned an invalid live stream identity');
        }
        const streamId = typeof data.streamId === 'string' && /^[0-9a-f-]{36}$/.test(data.streamId)
          ? data.streamId : null;
        if (streamId !== state.liveStreamId) {
          stopLiveMedia();
          state.liveStreamId = streamId;
          state.livePlaylist = null;
          state.firstLiveFrame = false;
          state.localFirstFrame = false;
          state.mainKey = '';
        }
        const playlist = streamId && data.playlist === `/media/${streamId}/live.m3u8`
          ? data.playlist : null;
        if (playlist !== state.livePlaylist) state.mainKey = '';
        state.livePlaylist = playlist;
        if (data.status === 'connected' && !state.livePlaylist) data.status = 'preparing';
      }
      state.status = data.status;
      if (state.status === 'ready' && state.wantsLive) state.status = previous === 'connected' ? 'connected' : 'preparing';
      state.missingCharge = Number(data.missingCharge) || 0;
      if (['error', 'ended'].includes(state.status)) state.wantsLive = false;
      if (state.status === 'ready' && !state.wantsLive) state.liveFailed = false;
      if (state.status !== previous) state.mainKey = '';
      updateStatusBar();
      renderMain();
      if (state.mode === 'archive' && !state.archiveLoaded && !state.archiveLoading) await loadArchive();
    } catch (failure) {
      if (action !== actionGeneration || !state.authenticated || modeWrite !== modeWriteGeneration || request !== statusRequestGeneration) return;
      applyBatterySnapshot(null);
      if (failure.statusCode === 401) handleUnauthorized(failure);
      else console.warn('Camera viewer status poll failed', failure);
    }
  }

  async function endSession() {
    applyBatterySnapshot(null);
    const action = ++actionGeneration;
    clearInterval(state.poll);
    state.poll = null;
    stopLiveMedia();
    await closeArchivePlayer();
    if (action !== actionGeneration) return;
    try { await post('/stop', { origin: 'manual' }); }
    catch (failure) { console.warn('Could not stop camera viewer session', failure); }
    if (action !== actionGeneration) return;
    state.authenticated = false;
    state.replaced = false;
    state.status = 'ended';
    state.wantsLive = false;
    state.mode = 'live';
    state.liveRequest = null;
    state.liveStreamId = null;
    state.liveSelectionRevision = 0;
    state.livePlaylist = null;
    state.archiveLoaded = false;
    state.recordings = [];
    state.selectedDay = null;
    state.month = null;
    state.firstLiveFrame = false;
    state.localFirstFrame = false;
    resetModes();
    renderLogin(text.ended);
  }

  async function signIn() {
    ++actionGeneration;
    clearInterval(state.poll);
    state.poll = null;
    state.authenticated = false;
    resetModes();
    state.archiveLoaded = false;
    state.recordings = [];
    state.selectedRecord = null;
    renderLogin();
  }

  function loginError(failure) {
    const status = failure.body?.status;
    if (status === 'unauthorized') return text.invalidPassword;
    if (status === 'busy') return text.busy;
    if (status === 'retry') return text.retryLater;
    return text.error;
  }

  async function submitLogin(form) {
    const password = document.getElementById('password');
    const secret = password?.value || '';
    if (password) password.value = '';
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    const error = document.getElementById('login-error');
    if (error) error.textContent = '';
    try {
      const result = await post('/session', { password: secret });
      if (result.status !== 'ready') throw Object.assign(new Error(result.status || 'session unavailable'), { body: result });
      resetModes();
      state.authenticated = true;
      state.replaced = false;
      state.archiveLoaded = false;
      state.recordings = [];
      state.selectedRecord = null;
      state.mode = 'live';
      state.status = 'ready';
      state.wantsLive = false;
      state.liveFailed = false;
      state.liveRequest = null;
      state.liveStreamId = null;
      state.liveSelectionRevision = 0;
      state.livePlaylist = null;
      state.firstLiveFrame = false;
      state.localFirstFrame = false;
      state.interiorCameras = 0;
      try {
        const current = await api('/status');
        state.interiorCameras = normalizeInteriorCameraMask(current.interiorCameras) ?? 0;
        if (Number.isSafeInteger(current.selectionRevision) && current.selectionRevision >= 0)
          state.liveSelectionRevision = current.selectionRevision;
      } catch (failure) {
        if (failure.statusCode === 401 || contractBlocked) {
          state.authenticated = false;
          throw failure;
        }
        console.warn('Initial camera selection unavailable; using exterior composition', failure);
      }
      state.view = state.interiorCameras ? 'combined' : 'composition';
      renderShell();
      if (preferredMode() === 'archive') await openArchive();
      else await startLive();
      if (state.authenticated && !state.replaced) await pollStatus();
      if (state.authenticated && !state.replaced) state.poll = setInterval(pollStatus, 2000);
    } catch (failure) {
      console.warn('Camera viewer sign-in failed', failure);
      const message = loginError(failure);
      if (error) error.textContent = message;
      else renderLogin(message);
    } finally {
      if (button.isConnected) button.disabled = false;
    }
  }

  app.addEventListener('submit', (event) => {
    if (event.target.id !== 'login-form') return;
    event.preventDefault();
    submitLogin(event.target);
  });

  app.addEventListener('click', async (event) => {
    const button = event.target.closest('button[data-action]');
    if (!button || button.disabled) return;
    const action = button.dataset.action;
    if (action === 'tab') {
      if (button.dataset.mode === 'archive') await openArchive();
      else {
        const action = ++actionGeneration;
        const wasArchive = state.mode === 'archive';
        if (!await closeArchivePlayer({ leave: wasArchive }) || action !== actionGeneration) return;
        if (state.replaced) return;
        state.mode = 'live';
        rememberMode('live');
        await startLive(state.view);
      }
      document.querySelectorAll('.nav [data-action="tab"]').forEach((tab) => tab.setAttribute('aria-pressed', String(tab.dataset.mode === state.mode)));
      return;
    }
    if (action === 'end') { await endSession(); return; }
    if (action === 'start-live') { await startLive(); return; }
    if (action === 'signin-again') { await signIn(); return; }
    if (action === 'retry-archive') { state.archiveLoaded = false; await loadArchive(); return; }
    if (action === 'camera') {
      if (state.mode === 'archive') {
        const record = state.recordings.find((item) => item.id === state.selectedRecord);
        if (!record || !recordingOrder(record) && button.dataset.view !== 'composition') return;
        state.archiveView = button.dataset.view;
        document.querySelectorAll('.archive-player .camera-tabs button').forEach((tab) => tab.setAttribute('aria-pressed', String(tab.dataset.view === state.archiveView)));
        applyArchiveCrop();
      } else await selectLiveView(button.dataset.view);
      return;
    }
    if (action === 'record') { await openRecording(button.dataset.id); return; }
    if (action === 'date') {
      ++actionGeneration;
      if (state.selectedRecord && !await closeArchivePlayer()) return;
      state.selectedDay = button.dataset.day;
      state.month = state.selectedDay.slice(0, 7);
      state.mainKey = '';
      renderMain(true);
      return;
    }
    if (action === 'month') {
      ++actionGeneration;
      if (state.selectedRecord && !await closeArchivePlayer()) return;
      state.month = addMonth(state.month, Number(button.dataset.delta));
      state.selectedDay = `${state.month}-01`;
      state.mainKey = '';
      renderMain(true);
      return;
    }
    if (action === 'back') {
      ++actionGeneration;
      if (!await closeArchivePlayer()) return;
      state.mainKey = '';
      renderMain(true);
      return;
    }
    if (action === 'play') {
      const video = document.getElementById('archive-video');
      if (video?.paused) video.play().catch(() => {}); else video?.pause();
      updateArchiveControls();
      return;
    }
    if (action === 'restart') {
      const video = document.getElementById('archive-video');
      if (video) video.currentTime = 0;
      updateArchiveControls();
      return;
    }
    if (action === 'previous' || action === 'next') {
      const list = dayRecordings(state.selectedDay || '');
      const index = list.findIndex((record) => record.id === state.selectedRecord);
      const next = list[index + (action === 'next' ? 1 : -1)];
      if (next) await openRecording(next.id);
      return;
    }
    if (action === 'fullscreen') { await toggleFullscreen(); return; }
  });

  app.addEventListener('input', (event) => {
    if (event.target.id !== 'seek') return;
    state.seeking = true;
    const video = document.getElementById('archive-video');
    if (!video) return;
    const value = Number(event.target.value);
    if (Number.isFinite(value)) video.currentTime = value;
    updateArchiveControls();
  });
  app.addEventListener('change', async (event) => {
    if (event.target.matches('input[data-mode]')) { await changeMode(event.target); return; }
    if (event.target.id === 'seek') { state.seeking = false; updateArchiveControls(); return; }
    if (event.target.id === 'playback-speed') {
      const video = document.getElementById('archive-video');
      if (video) video.playbackRate = Number(event.target.value) || 1;
    }
  });
  app.addEventListener('timeupdate', (event) => { if (event.target.id === 'archive-video') updateArchiveControls(); }, true);
  app.addEventListener('durationchange', (event) => { if (event.target.id === 'archive-video') updateArchiveControls(); }, true);
  app.addEventListener('seeked', (event) => { if (event.target.id === 'archive-video') updateArchiveControls(); }, true);
  app.addEventListener('play', (event) => { if (event.target.id === 'archive-video') updateArchiveControls(); }, true);
  app.addEventListener('pause', (event) => { if (event.target.id === 'archive-video') updateArchiveControls(); }, true);
  app.addEventListener('error', (event) => {
    if (event.target.id === 'archive-video') {
      console.warn('Archive video playback failed', event.target.error || event);
      state.playerError = true;
      const note = document.getElementById('archive-error');
      if (note) note.textContent = text.archiveError;
    }
    if (event.target instanceof HTMLImageElement && event.target.closest('.poster')) event.target.hidden = true;
  }, true);
  app.addEventListener('loadedmetadata', (event) => {
    if (event.target.id !== 'archive-video') return;
    applyArchiveCrop();
    updateArchiveControls();
    reportFirstFrame(event.target, 'archive');
  }, true);
  document.addEventListener('fullscreenchange', () => {
    if (!document.fullscreenElement) app.classList.remove('is-expanded');
    applyArchiveCrop();
    updateFullscreenButton();
  });
  window.addEventListener('resize', applyArchiveCrop);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) updateBattery();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && app.classList.contains('is-expanded') && !document.fullscreenElement) {
      app.classList.remove('is-expanded');
      updateFullscreenButton();
    }
  });
  window.addEventListener('pagehide', () => {
    clearInterval(state.poll);
    disconnectArchiveResizeObserver();
    stopLiveMedia();
    if (state.authenticated && navigator.sendBeacon) navigator.sendBeacon('/stop', new Blob(['{"origin":"pagehide"}'], { type: 'application/json' }));
  });

  if (checkRevision(bootstrap.webRevision)) {
    renderLogin();
  }
})();
