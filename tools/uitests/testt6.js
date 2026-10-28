/* Stage T6 — финальный приёмочный прогон всего приложения после порта V2
   (T1 топбар … T5 настройки). Проверяет сквозные сценарии, которые
   посьюитно не покрываются:
   - скелет: все 11 вкладок открываются и рисуются без ошибок JS;
   - полный цикл V2: Поездки (TMON live) → Зарядка → История (фуллскрин
     поездки + инсайт) → Настройки (4 категории), с монтированием и
     остановкой контроллеров при переключении вкладок;
   - кросс-экранная проводка: ёмкость/цена из настроек меняют глобальные
     packWh/rate (общие расчёты экранов, как settings.apply в машине);
   - гигиена после T5: вкладки телеметрии без «Синхронизации», старых
     setView-артефактов нет, вкладка «Обновления» жива после резки setAct,
     тумблеры Инструменты→Экран (imm/wake) не задеты;
   - стабильность: 5× open/close меню настроек не плодит обработчики
     (одно действие — один эмит).
   Хук-сервер: копия ui/ на :8080. */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
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
  chk('загрузка без ошибок JS', errs.length === 0, errs.slice(0, 3));

  /* ══ Файловые проверки (состав стадии) ══ */
  const ROOT = path.resolve(__dirname, '../..');
  const ui = f => fs.readFileSync(path.join(ROOT, 'app/src/main/assets/ui', f), 'utf8');
  chk('файл: tel-settings.js в проекте', fs.existsSync(path.join(ROOT, 'app/src/main/assets/ui/tel-settings.js')));
  chk('файл: tel-settings подключён после tel-history', /tel-history\.js"><\/script>\s*<script src="tel-settings\.js">/.test(ui('index.html')));
  chk('файл: размеры CategoryMenu (1040/720/320)', /1040px/.test(ui('tel-settings.js')) && /720px/.test(ui('tel-settings.js')) && /width:320px/.test(ui('tel-settings.js')));
  chk('файл: softActionTile поддерживает disabled (onPressed:null)', /data-disabled="1"/.test(ui('tel-ui.js')));
  const ub = fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/example/voiceapp3/ui/UiBridge.kt'), 'utf8');
  chk('файл: UiBridge.appBuild() (settingsAppVersion build)', /fun appBuild\(\)/.test(ub));
  const ad = fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/example/voiceapp3/ui/TelemetryUiAdapter.kt'), 'utf8');
  chk('файл: extra для arb-фраз actionDone (retention/wipe)', /"frames"/.test(ad) && /"trips"/.test(ad) && /"charges"/.test(ad));
  chk('файл: наборы testt1..testt6 в репо', ['t1','t2','t3','t4','t5'].every(t => fs.existsSync(path.join(ROOT, 'tools/uitests/test' + t + '.js'))));

  /* ══ Блок А: скелет — все вкладки ══ */
  const tabs = await p.evaluate(() => Array.from(document.querySelectorAll('.tab')).map(t => t.dataset.id));
  /* carplay/aa показывает только тумблер proj (Stage CPv2) — в дефолте их нет */
  chk('панель: 9 вкладок (carplay/aa за тумблером proj)', tabs.length === 9 && !tabs.includes('carplay') && !tabs.includes('aa'), tabs);
  for (const id of tabs) {
    await clickSync(p, '.tab[data-id="' + id + '"]');
    await sleep(450);
    const ok = await p.evaluate(() => {
      const c = document.getElementById('content');
      return !!c && c.innerText.trim().length > 20;
    });
    chk('вкладка «' + id + '» отрисовалась', ok && errs.length === 0, errs.slice(0, 2));
  }
  r_all: {
    await clickSync(p, '.tab[data-id="telemetry"]');
    await sleep(500);
    const full = await p.evaluate(() => document.querySelector('.main').classList.contains('full'));
    chk('телеметрия: full-режим шелла', full, full);
  }

  /* ══ Блок Б: полный цикл V2 ══ */
  /* Поездки (T2): контроллер жив на вкладке */
  await clickSync(p, '[data-ttab="0"]');
  await sleep(900);
  let r = await p.evaluate(() => ({
    polling: window.TMON ? TMON.isPolling : null,
    host: !!document.getElementById('telTripsHost'),
    donut: !!document.querySelector('#telTripsHost svg') || !!(document.getElementById('telTripsHost') && document.getElementById('telTripsHost').innerHTML.length > 500)
  }));
  chk('Поездки: TMON опрос активен (isActive)', r.polling === true, r);
  chk('Поездки: экран смонтирован', r.host, r);

  /* уход с вкладки — опрос остановлен (порт isActive=false) */
  await clickSync(p, '[data-ttab="2"]');
  await sleep(700);
  r = await p.evaluate(() => ({ polling: TMON.isPolling }));
  chk('Поездки→История: TMON опрос остановлен', r.polling === false, r);

  /* Зарядка (T3): ThreeColumnLayout */
  await clickSync(p, '[data-ttab="1"]');
  await sleep(900);
  r = await p.evaluate(() => {
    const h = document.getElementById('telChargeHost');
    const txt = h ? h.innerText : '';
    return {
      host: !!h,
      energy: /кВт·ч/.test(txt),
      limit: !!h && (h.querySelector('[data-tellimit]') || h.querySelector('svg') || /Лимит/.test(txt)) !== null,
      session: /Сеанс|кВт/.test(txt)
    };
  });
  chk('Зарядка: экран смонтирован (ThreeColumnLayout)', r.host && r.energy, r);

  /* История (T4): рельс + фуллскрин поездки + инсайт T4-доп */
  await clickSync(p, '[data-ttab="2"]');
  await sleep(900);
  r = await p.evaluate(() => ({
    cats: document.querySelectorAll('[data-telcat]').length,
    trips: document.querySelectorAll('[data-teltrip]').length
  }));
  chk('История: 3 категории рельса', r.cats === 3, r);
  chk('История: список поездок', r.trips === 3, r);
  await clickSync(p, '[data-teltrip="d1"]');
  await sleep(900);
  r = await p.evaluate(() => {
    const head = document.getElementById('telHistMosaic').closest('.telCard').querySelector('.telCardHead');
    return {
      chart: !!document.getElementById('telHistChart'),
      ring: !!document.getElementById('telHistRing'),
      mosaic: document.querySelectorAll('#telHistMosaic [data-telmosaic]').length,
      insight: /Дом/.test(head.innerText) && /Вт·ч\/км/.test(head.innerText)
    };
  });
  chk('История: фуллскрин поездки (график+кольцо+мозаика)', r.chart && r.ring && r.mosaic === 9, r);
  chk('История: _tripHeaderInsight в шапке (места+фраза)', r.insight, r);
  await clickSync(p, '[data-telcollapse]');
  await sleep(400);

  /* Настройки (T5): все категории */
  await clickSync(p, '[data-tgear]');
  await sleep(500);
  r = await p.evaluate(() => ({
    cats: Array.from(document.querySelectorAll('#telSetRoot [data-telcat]')).map(e => e.textContent.trim()),
    charge: !!document.getElementById('telSetDetail')
  }));
  chk('Настройки: меню открыто над Историей', r.cats.length === 4 && r.charge, r);

  /* ══ Блок В: кросс-экранная проводка ══ */
  /* ёмкость 40,00 → глобальный packWh (общие расчёты, как settings.apply) */
  await p.evaluate(() => {
    window.__t6log = [];
    window.__t6emit = window.__geelyEmit;
    window.__geelyEmit = function (ch, payload) { window.__t6log.push(ch); if (window.__t6emit) window.__t6emit(ch, payload); };
  });
  await clickSync(p, '[data-telsoft="capacity"]');
  await sleep(350);
  await clickSync(p, '#telSetKeypad [data-telkey="clear"]');
  await sleep(120);
  for (const d of ['4', '0', '0', '0']) { await clickSync(p, '#telSetKeypad [data-teldigit="' + d + '"]'); await sleep(80); }
  await clickSync(p, '#telSetKeypad [data-telkey="save"]');
  await sleep(400);
  r = await p.evaluate(() => ({
    tile: (document.querySelector('[data-telsoft="capacity"]') || {}).textContent,
    packWh: typeof packWh !== 'undefined' ? packWh : null,
    emits: window.__t6log
  }));
  chk('проводка: плитка 40,00 кВт·ч', r.tile === '40,00 кВт·ч', r);
  chk('проводка: глобальный packWh=40000 (расчёты экранов)', r.packWh === 40000, r);
  chk('проводка: settings.apply pack_capacity_wh', r.emits.indexOf('settings.apply') >= 0, r.emits);

  /* цена 1,50 → глобальный rate */
  await clickSync(p, '[data-telsoft="rate"]');
  await sleep(350);
  await clickSync(p, '#telSetKeypad [data-telkey="clear"]');
  await sleep(120);
  for (const d of ['1', '5', '0']) { await clickSync(p, '#telSetKeypad [data-teldigit="' + d + '"]'); await sleep(80); }
  await clickSync(p, '#telSetKeypad [data-telkey="save"]');
  await sleep(350);
  r = await p.evaluate(() => ({ rate: typeof rate !== 'undefined' ? rate : null }));
  chk('проводка: глобальный rate=1.5', r.rate === 1.5, r);

  /* лаборатория из настроек: порядок _open + возврат */
  await clickSync(p, '[data-telcat="developer"]');
  await sleep(300);
  await clickSync(p, '[data-teltoggle="dev"]');
  await sleep(300);
  await clickSync(p, '[data-telsoft="trace"]');
  await sleep(600);
  r = await p.evaluate(() => ({
    menuGone: !document.getElementById('telSetRoot'),
    lab: !!document.getElementById('labBack')
  }));
  chk('лаборатория: меню закрыто, trace открыт (порядок _open)', r.menuGone && r.lab, r);
  await clickSync(p, '#labBack');
  await sleep(500);
  await clickSync(p, '[data-tgear]');
  await sleep(400);
  chk('возврат: настройки reopened после лаборатории', await p.evaluate(() => !!document.getElementById('telSetPanel')));

  /* ══ Блок Г: гигиена после T5 ══ */
  await clickSync(p, '[data-telsettingsclose]');
  await sleep(300);
  r = await p.evaluate(() => ({
    noSetNav: !document.getElementById('setNav'),
    noSetSearch: !document.getElementById('setSearch'),
    noStorageTile: !document.getElementById('storageTile'),
    noSyncBtn: !document.getElementById('syncBtn'),
    tabs: Array.from(document.querySelectorAll('[data-ttab]')).map(b => b.textContent)
  }));
  chk('гигиена: вкладок телеметрии 3, без «Синхронизация»', r.tabs.join(',') === 'Поездки,Зарядка,История', r.tabs);
  chk('гигиена: старый setView-навигатор удалён', r.noSetNav && r.noSetSearch && r.noStorageTile && r.noSyncBtn, r);

  /* вкладка «Обновления» жива после резки setAct */
  await clickSync(p, '.tab[data-id="updates"]');
  await sleep(500);
  r = await p.evaluate(() => {
    const t = document.getElementById('content').innerText;
    return { roadcast: /ROADCAST/i.test(t), app: /EX2 · v1\.1\.0/.test(t), btns: document.querySelectorAll('[data-act]').length };
  });
  chk('Обновления: карточки приложения/Roadcast живы', r.roadcast && r.app, r);
  chk('Обновления: кнопки действий на месте', r.btns >= 3, r.btns);

  /* Инструменты→Экран: imm/wake не задеты (Stage SCR) */
  await clickSync(p, '.tab[data-id="tools"]');
  await sleep(400);
  await clickSync(p, '[data-ttool="1"]');
  await sleep(400);
  r = await p.evaluate(() => {
    const imm = document.querySelector('[data-k="imm"]');
    const wake = document.querySelector('[data-k="wake"]');
    return { imm: !!imm, wake: !!wake, immOn: imm ? imm.checked : null };
  });
  chk('Инструменты→Экран: тумблеры imm/wake живы', r.imm && r.wake, r);

  /* ══ Блок Д: стабильность повторных open/close ══ */
  await clickSync(p, '.tab[data-id="telemetry"]');
  await sleep(600);
  for (let i = 0; i < 5; i++) {
    await clickSync(p, '[data-tgear]');
    await sleep(180);
    await clickSync(p, '[data-telsettingsclose]');
    await sleep(180);
  }
  chk('5× open/close: меню закрыто, ошибок нет', await p.evaluate(() => !document.getElementById('telSetRoot')) && errs.length === 0, errs.slice(0, 2));
  /* одно действие — один эмит (обработчики не дублируются) */
  await clickSync(p, '[data-tgear]');
  await sleep(400);
  await clickSync(p, '[data-telcat="data"]');
  await sleep(600);
  await p.evaluate(() => { window.__t6log = []; });
  await clickSync(p, '[data-teltoggle="cont"]');
  await sleep(300);
  r = await p.evaluate(() => ({ n: window.__t6log.filter(c => c === 'settings.change').length }));
  chk('один клик — один settings.change (без дублей обработчиков)', r.n === 1, r);
  await clickSync(p, '[data-telsettingsclose]');
  await sleep(250);

  /* финал: повторный полный проход вкладок телеметрии без ошибок */
  await clickSync(p, '[data-ttab="0"]'); await sleep(500);
  await clickSync(p, '[data-ttab="1"]'); await sleep(500);
  await clickSync(p, '[data-ttab="2"]'); await sleep(500);
  chk('финал: переключение 0→1→2 без ошибок JS', errs.length === 0, errs.slice(0, 3));
  r = await p.evaluate(() => ({ polling: TMON.isPolling }));
  chk('финал: на «Истории» TMON остановлен', r.polling === false, r);

  chk('нет ошибок JS за весь сценарий', errs.length === 0, errs.slice(0, 3));
  await b.close();
  console.log('ИТОГ T6: pass=' + pass + ' fail=' + fail);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
