/* Stage T5: Настройки — порт showSettingsMenu (settings_menu.dart +
   settings/{charge,data,system,developer}_pane.dart из capy e39777c).
   Проверяет: floating CategoryMenu (скрим, панель 1040×720, рельс 320px,
   поиск + пустое состояние, группы с правилом, подсветка selectionFill),
   ChargePane (ёмкость → MoneyKeypad → settings.apply, цена, применение
   ставки, дисклеймер, тумблеры ext/roem), DataPane (4 тумблера tri-state
   + Bluetooth, хранилище → formatStorageBytes, retention, wipe →
   showConfirmDialog), SystemPane (О приложении: версия/разработчик/
   тэглайн), DeveloperPane (gate → инженерные экраны → лаборатории),
   удалённую вкладку «Синхронизация», actionDone arb-фразы, read-back.
   Исключено по требованию: displays, system-update (обновления — вкладка
   «Обновления» ГУ), developer-projection.
   Хук-сервер: копия ui/ на :8080. */
const { chromium } = require('playwright-core');
const sleep = ms => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
function chk(name, cond, extra) {
  if (cond) { pass++; console.log('чек', name + ':', 'OK'); }
  else { fail++; console.log('чек', name + ':', 'FAIL ' + (extra !== undefined ? JSON.stringify(extra) : '')); }
}
async function clickSync(p, sel, tries) {
  for (let i = 0; i < (tries || 40); i++) {
    if (await p.evaluate(s => !!(document.querySelector(s) && (document.querySelector(s).click(), true)), sel)) return true;
    await sleep(120);
  }
  return false;
}
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  p.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1300);

  chk('открытие без ошибок JS', errs.length === 0, errs.slice(0, 3));
  await clickSync(p, '.tab[data-id="telemetry"]');
  await sleep(700);

  /* ── вкладки: «Синхронизация» удалена ── */
  let r = await p.evaluate(() => ({
    tabs: Array.from(document.querySelectorAll('[data-ttab]')).map(b => b.textContent),
    gear: !!document.querySelector('[data-tgear]')
  }));
  chk('вкладки: Поездки/Зарядка/История, без «Синхронизация»', r.tabs.length === 3 && !r.tabs.includes('Синхронизация'), r.tabs);
  chk('кнопка-шестерёнка (на месте telTab===4)', r.gear, r);

  /* ── открытие меню ── */
  await clickSync(p, '[data-tgear]');
  await sleep(500);
  r = await p.evaluate(() => {
    const panel = document.getElementById('telSetPanel');
    const rail = panel ? panel.children[0] : null;
    const rules = panel ? Array.from(panel.querySelectorAll('#telSetRailList > div')).filter(d => d.style.height === '1px') : [];
    const sel = document.querySelector('#telSetRoot [data-telcat="charge"]');
    return {
      root: !!document.getElementById('telSetRoot'),
      panel: !!panel,
      w: panel ? panel.offsetWidth : 0,
      h: panel ? panel.offsetHeight : 0,
      railW: rail ? rail.offsetWidth : 0,
      detailCanvas: panel ? getComputedStyle(panel.children[1]).backgroundColor : null,
      cats: Array.from(document.querySelectorAll('#telSetRoot [data-telcat]')).map(e => e.textContent.trim()),
      icons: document.querySelectorAll('#telSetRoot [data-telcat] svg').length,
      rules: rules.length,
      search: !!document.getElementById('telSetSearch'),
      placeholder: (document.getElementById('telSetSearch') || {}).placeholder,
      selBg: sel ? sel.style.background : null,
      scrim: !!document.querySelector('[data-telsettingsclose]'),
      gearOn: document.querySelector('[data-tgear]').classList.contains('on')
    };
  });
  chk('меню открыто (telSetRoot + панель)', r.root && r.panel, r);
  chk('панель 1040×720 (categoryMenuWidth/Height)', r.w === 1040 && r.h === 720, [r.w, r.h]);
  chk('рельс 320px (categoryMenuRailWidth)', r.railW === 320, r.railW);
  chk('4 категории без «Экраны» (displays исключён)',
    r.cats.length === 4 && !r.cats.includes('Экраны') &&
    r.cats.join(',') === 'Зарядка,Данные,Система,Разработчик', r.cats);
  chk('иконки категорий (bolt/storage/memory/code)', r.icons === 4, r.icons);
  chk('правило между группами (Система | Разработчик)', r.rules === 1, r.rules);
  chk('поиск: поле + placeholder «Поиск»', r.search && r.placeholder === 'Поиск', r.placeholder);
  chk('initialSelected «Зарядка» (selectionFill)', /58, 47, 30/.test(r.selBg || '') || /rgba/.test(r.selBg || ''), r.selBg);
  chk('detail на фоне canvas', !!r.detailCanvas, r.detailCanvas);
  chk('скрим закрытия', r.scrim, r);
  chk('шестерёнка подсвечена (_settingsOpen)', r.gearOn, r);

  /* анимация входа 0.97→1 (showFloatingSurface) */
  r = await p.evaluate(() => {
    const t = document.getElementById('telSetPanel');
    return { transform: t.style.transform, opacity: t.style.opacity };
  });
  chk('вход завершён: scale(1), opacity 1', /scale\(1\)/.test(r.transform) && r.opacity === '1', r);

  /* ── ChargePane ── */
  r = await p.evaluate(() => {
    const d = document.getElementById('telSetDetail');
    const tiles = {};
    d.querySelectorAll('[data-telsoft]').forEach(e => { tiles[e.dataset.telsoft] = e.textContent; });
    return {
      cards: Array.from(d.querySelectorAll('.telCardTitle')).map(e => e.textContent),
      tiles, toggles: Array.from(d.querySelectorAll('[data-teltoggle]')).map(e => e.dataset.teltoggle),
      disclaimer: d.innerText.includes('Использование этой функции осуществляется на ваш собственный риск'),
      extTrack: (d.querySelector('[data-teltoggle="ext"]') || { querySelector: () => ({}) }).querySelector('span').style.background
    };
  });
  chk('карточки: Ёмкость/Отказ/Зарядка', r.cards.join(',') === 'Ёмкость батареи,Отказ от ответственности,Зарядка', r.cards);
  chk('плитка ёмкости 39,60 кВт·ч', r.tiles.capacity === '39,60 кВт·ч', r.tiles);
  chk('плитка цены ₽ 0,90 (демо rate)', r.tiles.rate === '₽ 0,90', r.tiles);
  chk('«Применить к зарядкам без цены» операбелен (rate задан)', r.tiles.applyprice === 'Применить к зарядкам без цены', r.tiles);
  chk('дисклеймер (warning + текст)', r.disclaimer, r);
  chk('тумблеры ext/roem', r.toggles.join(',') === 'ext,roem', r.toggles);
  chk('ext=ON из демо-фида (energy.draw)', /239, 168, 58/.test(r.extTrack), r.extTrack);

  /* клавиатура ёмкости → settings.apply pack_capacity_wh */
  const emitted = [];
  await p.evaluate(() => {
    window.__t5emit = window.__geelyEmit;
    window.__geelyEmit = function (ch, payload) { window.__t5log = window.__t5log || []; window.__t5log.push({ ch, payload }); if (window.__t5emit) window.__t5emit(ch, payload); };
  });
  await clickSync(p, '[data-telsoft="capacity"]');
  await sleep(400);
  r = await p.evaluate(() => ({
    kp: !!document.getElementById('telSetKeypad'),
    title: document.getElementById('telSetKeypad').innerText.slice(0, 40),
    display: (Array.from(document.querySelectorAll('#telSetKeypad span')).find(s => /39,60/.test(s.textContent)) || {}).textContent
  }));
  chk('клавиатура ёмкости (MoneyKeypadDialog)', r.kp && r.title.indexOf('Ёмкость батареи') === 0, r);
  chk('поле ввода: 39,60 (текущее значение)', r.display === '39,60', r);
  await clickSync(p, '#telSetKeypad [data-telkey="clear"]');
  await sleep(150);
  for (const d of ['4', '0', '0', '0']) { await clickSync(p, '#telSetKeypad [data-teldigit="' + d + '"]'); await sleep(90); }
  await clickSync(p, '#telSetKeypad [data-telkey="save"]');
  await sleep(400);
  r = await p.evaluate(() => {
    const d = document.getElementById('telSetDetail');
    const log = (window.__t5log || []).filter(e => e.ch === 'settings.apply');
    return {
      cap: (d.querySelector('[data-telsoft="capacity"]') || {}).textContent,
      status: d.innerText.includes('Ёмкость батареи установлена: 40,00 кВт·ч.'),
      emit: log.length ? log[log.length - 1].payload : null
    };
  });
  chk('ввод 40,00 → плитка обновлена', r.cap === '40,00 кВт·ч', r);
  chk('статус settingsPackCapacitySaved', r.status, r);
  chk('эмitten settings.apply pack_capacity_wh=40000', r.emit && r.emit.key === 'pack_capacity_wh' && r.emit.value === 40000, r.emit);

  /* клавиатура цены → default_charge_cost_per_kwh */
  await clickSync(p, '[data-telsoft="rate"]');
  await sleep(350);
  await clickSync(p, '#telSetKeypad [data-telkey="clear"]');
  await sleep(120);
  for (const d of ['1', '5', '0']) { await clickSync(p, '#telSetKeypad [data-teldigit="' + d + '"]'); await sleep(90); }
  await clickSync(p, '#telSetKeypad [data-telkey="save"]');
  await sleep(400);
  r = await p.evaluate(() => {
    const d = document.getElementById('telSetDetail');
    const log = (window.__t5log || []).filter(e => e.ch === 'settings.apply');
    return {
      rate: (d.querySelector('[data-telsoft="rate"]') || {}).textContent,
      status: d.innerText.includes('Сохранено: ₽ 1,50'),
      emit: log.length ? log[log.length - 1].payload : null
    };
  });
  chk('цена ₽ 1,50 → плитка', r.rate === '₽ 1,50', r);
  chk('статус settingsChargeCostSaved', r.status, r);
  chk('эмitten settings.apply default_charge_cost_per_kwh=1.5', r.emit && r.emit.key === 'default_charge_cost_per_kwh' && r.emit.value === 1.5, r.emit);

  /* применение ставки (демо: 3 зарядки) */
  await clickSync(p, '[data-telsoft="applyprice"]');
  await sleep(1200);
  chk('применение ставки: status-фраза', await p.evaluate(() =>
    document.getElementById('telSetDetail').innerText.includes('3 зарядок теперь имеют ставку по умолчанию.')));

  /* ── DataPane ── */
  await clickSync(p, '[data-telcat="data"]');
  await sleep(400);
  r = await p.evaluate(() => {
    const d = document.getElementById('telSetDetail');
    const tr = {};
    d.querySelectorAll('[data-teltoggle]').forEach(e => { tr[e.dataset.teltoggle] = e.querySelector('span').style.background; });
    return {
      cards: Array.from(d.querySelectorAll('.telCardTitle')).map(e => e.textContent),
      toggles: Array.from(d.querySelectorAll('[data-teltoggle]')).map(e => e.dataset.teltoggle),
      tr,
      storage: (d.querySelector('[data-telsoft="storage"]') || {}).textContent
    };
  });
  chk('карточки: Работа/Хранилище/Хранение/Удаление', r.cards.join(',') === 'Работа,Хранилище,Хранение,Удаление', r.cards);
  chk('4 тумблера: auto/gps/bt/cont (bt — новый)', r.toggles.join(',') === 'auto,gps,bt,cont', r.toggles);
  chk('Держать Bluetooth включённым (keepBluetoothOn)', await p.evaluate(() =>
    document.getElementById('telSetDetail').innerText.includes('Держать Bluetooth включённым')));
  chk('демо: auto=ON, gps=ON (draw), bt/cont=OFF (track)',
    /239, 168, 58/.test(r.tr.auto) && /239, 168, 58/.test(r.tr.gps) &&
    /228, 218, 202/.test(r.tr.bt) && /228, 218, 202/.test(r.tr.cont), r.tr);

  /* тумблер gps: read-back (демо подтверждает запрос) */
  await clickSync(p, '[data-teltoggle="gps"]');
  await sleep(300);
  r = await p.evaluate(() => {
    const g = document.querySelector('[data-teltoggle="gps"]');
    const log = (window.__t5log || []).filter(e => e.ch === 'settings.change');
    return { track: g.querySelector('span').style.background, emit: log.length ? log[0].payload : null, value: TEL.set.gpsEnabled };
  });
  chk('gps: тумблер переключился (amber→track)', /228, 218, 202/.test(r.track), r.track);
  chk('эмitten settings.change {key:"gps", value:false}', r.emit && r.emit.key === 'gps' && r.emit.value === false, r.emit);
  chk('read-back: TEL.set.gpsEnabled=false', r.value === false, r.value);
  await clickSync(p, '[data-teltoggle="gps"]');
  await sleep(250);

  /* хранилище: loading → value (демо 5,00 MB) */
  await clickSync(p, '[data-telsoft="storage"]');
  await sleep(200);
  const loading = await p.evaluate(() => (document.querySelector('[data-telsoft="storage"]') || {}).textContent);
  chk('хранилище: «Проверка…» (loading)', loading === 'Проверка…', loading);
  await sleep(700);
  chk('хранилище: «Использовано 5,00 MB» (formatStorageBytes)', await p.evaluate(() =>
    (document.querySelector('[data-telsoft="storage"]') || {}).textContent === 'Использовано 5,00 MB'));

  /* retention */
  await clickSync(p, '[data-telsoft="retention"]');
  await sleep(200);
  chk('retention: busy «ВЫПОЛНЯЕТСЯ»', await p.evaluate(() =>
    (document.querySelector('[data-telsoft="retention"]') || {}).textContent === 'ВЫПОЛНЯЕТСЯ'));
  await sleep(1100);
  chk('retention: v2SettingsRetentionDone', await p.evaluate(() =>
    document.getElementById('telSetDetail').innerText.includes('Очистка завершена: удалено кадров — 0, сырые данные хранятся 30 дн.')));

  /* wipe → confirm → отмена → подтверждение */
  await clickSync(p, '[data-telsoft="wipe"]');
  await sleep(350);
  r = await p.evaluate(() => ({
    cf: !!document.getElementById('telSetConfirm'),
    title: (document.getElementById('telSetConfirm').innerText.match(/^([^\n]+)/) || [])[1],
    btns: Array.from(document.querySelectorAll('#telSetConfirm [data-telsoft]')).map(e => e.textContent),
    msg: document.getElementById('telSetConfirm').innerText.includes('нельзя удалить по отдельности')
  }));
  chk('подтверждение: showConfirmDialog 480px', r.cf, r);
  chk('подтверждение: заголовок ОЧИСТИТЬ ИСТОРИЮ', r.title === 'ОЧИСТИТЬ ИСТОРИЮ', r.title);
  chk('подтверждение: ОТМЕНА / ОЧИСТИТЬ ВСЁ', r.btns.join(',') === 'ОТМЕНА,ОЧИСТИТЬ ВСЁ', r.btns);
  chk('подтверждение: destructive-текст', r.msg, r);
  await clickSync(p, '[data-telsoft="wipeCancel"]');
  await sleep(250);
  chk('отмена: диалог закрыт, меню живо', await p.evaluate(() =>
    !document.getElementById('telSetConfirm') && !!document.getElementById('telSetPanel')));
  await clickSync(p, '[data-telsoft="wipe"]');
  await sleep(300);
  await clickSync(p, '[data-telsoft="wipeConfirm"]');
  await sleep(1100);
  chk('wipe: v2SettingsWipeDone', await p.evaluate(() =>
    document.getElementById('telSetDetail').innerText.includes('Удалено: поездок — 0, зарядок — 0, кадров — 0.')));

  /* ── SystemPane (без update-карточек) ── */
  await clickSync(p, '[data-telcat="system"]');
  await sleep(350);
  r = await p.evaluate(() => {
    const d = document.getElementById('telSetDetail');
    return {
      txt: d.innerText,
      cards: Array.from(d.querySelectorAll('.telCardTitle')).map(e => e.textContent),
      noUpdate: !d.innerText.includes('Roadcast') && !d.innerText.includes('Обновл')
    };
  });
  chk('Система: только «О ПРИЛОЖЕНИИ»', r.cards.join(',') === 'О ПРИЛОЖЕНИИ', r.cards);
  chk('Система: версия «--» (браузер, PackageInfo нет)', r.txt.includes('--'), r.txt.slice(0, 60));
  chk('Система: Разработано @timhss', r.txt.includes('Разработано @timhss'), '');
  chk('Система: тэглайн «Сделано с любовью и кофе»', r.txt.includes('Сделано с любовью и кофе'), '');
  chk('Система: без карточек обновлений (system-update исключён)', r.noUpdate, r.txt.slice(0, 80));

  /* ── DeveloperPane ── */
  await clickSync(p, '[data-telcat="developer"]');
  await sleep(350);
  r = await p.evaluate(() => ({
    txt: document.getElementById('telSetDetail').innerText,
    eng: !!document.querySelector('[data-telsoft="trace"]'),
    noProjection: !document.getElementById('telSetDetail').innerText.includes('CarPlay / Android Auto (бета)')
  }));
  chk('Разработчик: только тумблер до разблокировки', !r.eng && r.txt.includes('Режим разработчика'), r.txt.slice(0, 60));
  chk('projection-строки нет (developer-projection исключён)', r.noProjection, '');
  await clickSync(p, '[data-teltoggle="dev"]');
  await sleep(350);
  r = await p.evaluate(() => ({
    eng: !!document.querySelector('[data-telsoft="trace"]'),
    tiles: Array.from(document.querySelectorAll('#telSetDetail [data-telsoft]')).map(e => e.dataset.telsoft),
    evf: !!document.querySelector('[data-teltoggle="evf"]'),
    card: document.getElementById('telSetDetail').innerText.includes('Инженерные экраны')
  }));
  chk('gate: инженерные экраны появились', r.eng && r.card, r);
  chk('плитки: resend/trace/signallab/sensorlab', r.tiles.join(',') === 'resend,trace,signallab,sensorlab', r.tiles);
  chk('тумблер «Файл журнала событий» (evf)', r.evf, r);
  await clickSync(p, '[data-telsoft="resend"]');
  await sleep(1200);
  chk('resend: v2SettingsResendHistoryDone', await p.evaluate(() =>
    document.getElementById('telSetDetail').innerText.includes('Отмечено записей: 0. Они выгрузятся при следующем sync.')));

  /* лаборатория: меню закрывается, открывается экран (как _open в Dart) */
  await clickSync(p, '[data-telsoft="trace"]');
  await sleep(600);
  r = await p.evaluate(() => ({
    menuGone: !document.getElementById('telSetRoot'),
    gearOff: !document.querySelector('[data-tgear]').classList.contains('on'),
    lab: !!document.getElementById('labBack')
  }));
  chk('trace: меню закрыто (порядок _open)', r.menuGone && r.gearOff, r);
  chk('trace: открыт экран лаборатории', r.lab, r);
  await clickSync(p, '#labBack');
  await sleep(400);
  await clickSync(p, '[data-tgear]');
  await sleep(400);
  chk('повторное открытие меню после лаборатории', await p.evaluate(() => !!document.getElementById('telSetPanel')));

  /* ── поиск по категориям ── */
  await p.fill('#telSetSearch', 'ыыы');
  await sleep(300);
  chk('поиск без совпадений: «Нет категории с таким названием.»', await p.evaluate(() =>
    document.getElementById('telSetRoot').innerText.includes('Нет категории с таким названием.')));
  await p.fill('#telSetSearch', 'дан');
  await sleep(300);
  r = await p.evaluate(() => Array.from(document.querySelectorAll('#telSetRoot [data-telcat]')).map(e => e.textContent.trim()));
  chk('поиск «дан»: осталась только «Данные»', r.join(',') === 'Данные', r);
  await p.fill('#telSetSearch', '');
  await sleep(300);
  r = await p.evaluate(() => Array.from(document.querySelectorAll('#telSetRoot [data-telcat]')).map(e => e.textContent.trim()));
  chk('очистка поиска: все 4 категории', r.length === 4, r);

  /* ── actionDone: arb-фразы по extra (как из моста) ── */
  await clickSync(p, '[data-telcat="data"]');
  await sleep(900); /* пережить демо-askStorage от смены категории */
  await p.evaluate(() => {
    window.dispatchEvent(new CustomEvent('native:telemetry.storage', { detail: { bytes: 1048576 } }));
  });
  await sleep(150);
  chk('telemetry.storage 1 МБ → «Использовано 1,00 MB»', await p.evaluate(() =>
    (document.querySelector('[data-telsoft="storage"]') || {}).textContent === 'Использовано 1,00 MB'));
  await p.evaluate(() => {
    window.dispatchEvent(new CustomEvent('native:telemetry.actionDone', { detail: { act: 'retention', ok: true, extra: { frames: 512, days: 30 } } }));
  });
  await sleep(300);
  chk('actionDone retention extra: «удалено кадров — 512»', await p.evaluate(() =>
    document.getElementById('telSetDetail').innerText.includes('Очистка завершена: удалено кадров — 512, сырые данные хранятся 30 дн.')));

  /* ── закрытие ── */
  await clickSync(p, '[data-telsettingsclose]');
  await sleep(300);
  r = await p.evaluate(() => ({
    gone: !document.getElementById('telSetRoot'),
    gearOff: !document.querySelector('[data-tgear]').classList.contains('on'),
    telemetryAlive: !!document.getElementById('telTripsHost') || !!document.querySelector('.telHead')
  }));
  chk('закрытие по скриму', r.gone, r);
  chk('подсветка шестерёнки снята', r.gearOff, r);
  chk('экран телеметрии под меню жив', r.telemetryAlive, r);

  /* повторное открытие/закрытие не ломает страницу */
  await clickSync(p, '[data-tgear]');
  await sleep(300);
  await clickSync(p, '[data-telsettingsclose]');
  await sleep(200);
  chk('повторное open/close без ошибок', await p.evaluate(() => !document.getElementById('telSetRoot')));

  chk('нет ошибок JS за сценарий', errs.length === 0, errs.slice(0, 3));
  await b.close();
  console.log('ИТОГ T5: pass=' + pass + ' fail=' + fail);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
