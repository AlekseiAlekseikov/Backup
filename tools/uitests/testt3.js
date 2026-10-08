/* Stage T3: экран «Зарядка» (ChargingV2Screen) — порт lib/screens_v2/charging из capy.
   Проверяет: ThreeColumnLayout (энергия|лимит|сеанс), панели, слайдер+пресеты,
   вкладки Уровень|График, график SOC+мощность (overlay, тики 80/30/10/0,
   x-тики HH:MM, тултип с окном), футер (запас·оценка/добавлено/цена/длительность/
   лимит достигнут), клавиатуру цены (rate|total, оба поля в записи),
   диалог тока (шаг, кламп), стоп/форс, климат-баннер (гистерезис),
   сводку-донат (батарея/климат), live-цикл 5с (ask history.series),
   бродкаст chargeState, mount/unmount. Хук-сервер: копия ui/ на :8080. */
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
  await sleep(800);
  await clickSync(p, '[data-ttab="1"]');
  await sleep(1200);

  /* ── монтирование ThreeColumnLayout ── */
  let r = await p.evaluate(() => ({
    mounted: window.telCharge ? telCharge.isMounted() : false,
    cols: ['telChgCol0', 'telChgCol1', 'telChgCol2'].filter(id => document.getElementById(id)).length,
    cards: document.querySelectorAll('#telChargeRoot .telCard').length
  }));
  chk('экран смонтирован', r.mounted, r);
  chk('три колонки (leading|primary|trailing)', r.cols === 3, r.cols);
  chk('карточки заполнения (3 AppCard)', r.cards >= 3, r.cards);

  /* ── EnergyPanel: запас хода авто/прил, контролы ext ── */
  r = await p.evaluate(() => {
    const t = (document.getElementById('telChgCol0') || { innerText: '' }).innerText;
    return {
      car: /266/.test(t) && /запас хода автомобиля/i.test(t),
      own: /202/.test(t) && /оценка по завершённым поездкам/i.test(t),
      stop: !!document.querySelector('[data-telsoft="stop"]'),
      force: !!document.querySelector('[data-telsoft="force"]'),
      amps: !!document.querySelector('[data-telsoft="amps"]'),
      stopLabel: (document.querySelector('[data-telsoft="stop"]') || { innerText: '' }).innerText
    };
  });
  chk('запас хода автомобиля (266 км)', r.car, r);
  chk('оценка приложения (202 км + подпись)', r.own, r);
  chk('стоп виден при активной зарядке', r.stop && /остановить зарядку/i.test(r.stopLabel), r.stopLabel);
  chk('форс-зарядка и ток (SoftActionTile)', r.force && r.amps, r);

  /* ── климат-баннер (демо: 5.5 кВт из 9.8 кВт → high) ── */
  r = await p.evaluate(() => ({
    banner: !!document.querySelector('.telBreathe'),
    text: (document.querySelector('.telBreathe') ? document.querySelector('.telBreathe').closest('div[style*="flex"]') : { innerText: '' }).innerText
  }));
  chk('климат-баннер (high) с числами', r.banner && /климат расходует вашу зарядку/i.test(r.text) && /5,5|5\.5/.test(r.text), r.text);

  /* ── LimitPanel: вкладки, слайдер, пресеты ── */
  r = await p.evaluate(() => ({
    tabs: Array.from(document.querySelectorAll('[data-teltab="panel"]')).map(b => b.textContent),
    slider: !!document.getElementById('telLimitSliderHost'),
    sliderText: (document.getElementById('telLimitSliderHost') || { innerText: '' }).innerText.replace(/\n/g, '|'),
    presets: Array.from(document.querySelectorAll('[data-telpreset]')).map(b => b.getAttribute('data-telpreset')),
    info: !!document.querySelector('[data-telinfo]')
  }));
  chk('вкладки Уровень|График', r.tabs.join('|') === 'Уровень|График', r.tabs);
  chk('слайдер: цель 80%, текущий SOC, прогноз', r.slider && /80/.test(r.sliderText) && /52,4|52\.4/.test(r.sliderText) && /ОЦЕНКА · 308 км прогнозируется/.test(r.sliderText), r.sliderText);
  chk('пресеты custom/70/85/100', r.presets.join(',') === '80,70,85,100', r.presets);
  chk('инфо-кнопка пределов', r.info, r);

  /* пресет 85 → emit charge.target */
  await p.evaluate(() => { window.__emitted = []; window.__geelyEmit = (k, d) => window.__emitted.push([k, d]); });
  chk('клик пресет 85', await clickSync(p, '[data-telpreset="85"]'));
  await sleep(400);
  r = await p.evaluate(() => ({
    emitted: (window.__emitted || []).filter(e => e[0] === 'charge.target'),
    sliderVal: (document.getElementById('telLimitSliderHost') || { innerText: '' }).innerText.match(/85/)
  }));
  chk('пресет 85 → charge.target {percent:85}', r.emitted.length === 1 && r.emitted[0][1].percent === 85, r.emitted);
  chk('слайдер показывает 85', !!r.sliderVal, r.sliderVal);

  /* инфо-панель: 3 InformationCard по пресетам */
  chk('клик инфо', await clickSync(p, '[data-telinfo]'));
  await sleep(400);
  r = await p.evaluate(() => ({
    panel: !!document.querySelector('.telAnchorPanel'),
    text: (document.querySelector('.telAnchorPanel') || { innerText: '' }).innerText,
    scrim: !!document.querySelector('[data-telanchorclose]')
  }));
  chk('инфо-панель: заголовок + 3 пресета', r.panel && /какой предел зарядки выбрать\?/i.test(r.text) && /70%/.test(r.text) && /85%/.test(r.text) && /100%/.test(r.text) && /для ежедневных поездок/i.test(r.text) && /для более дальних поездок/i.test(r.text) && /для максимального запаса хода/i.test(r.text), r.text.slice(0, 80));
  chk('инфо-панель: скрим-закрытие', r.scrim);
  await clickSync(p, '[data-telanchorclose]');
  await sleep(300);
  chk('инфо-панель закрылась', await p.evaluate(() => !document.querySelector('.telAnchorPanel')));

  /* диалог тока: значение min при null, шаг, кламп */
  await clickSync(p, '[data-telsoft="amps"]');
  await sleep(400);
  r = await p.evaluate(() => {
    const t = (document.querySelector('.telAnchorPanel') || { innerText: '' }).innerText;
    return { open: !!document.querySelector('[data-telamps]'), text: t, range: /Диапазон автомобиля 5–32 А/.test(t) };
  });
  chk('диалог тока: заголовок/описание/диапазон', r.open && /сила тока зарядки/i.test(r.text) && r.range, r.text.slice(0, 90));
  chk('значение по умолчанию = min (5 А, отчёта нет)', /(^|\n)5\nА/.test(r.text) || /5\nА/.test(r.text), r.text);
  await clickSync(p, '[data-telamps="1"]');
  await sleep(300);
  r = await p.evaluate(() => (window.__emitted || []).filter(e => e[0] === 'charge.amps'));
  chk('шаг тока → charge.amps {amps:6}', r.length === 1 && r[0][1].amps === 6, r);
  await clickSync(p, '[data-telanchorclose]');
  await sleep(300);

  /* ── слайдер: драг с пальца ── */
  r = await p.evaluate(async () => {
    const host = document.getElementById('telLimitSliderHost');
    const rc = host.getBoundingClientRect();
    function fire(t, x) { host.dispatchEvent(new PointerEvent(t, { clientX: x, clientY: rc.top + 100, bubbles: true, pointerId: 7 })); }
    fire('pointerdown', rc.left + rc.width * 0.7);
    await new Promise(r2 => setTimeout(r2, 30));
    fire('pointermove', rc.left + rc.width * 0.9);
    await new Promise(r2 => setTimeout(r2, 30));
    const during = host.innerText;
    fire('pointerup', rc.left + rc.width * 0.9);
    await new Promise(r2 => setTimeout(r2, 150));
    return { during: during.replace(/\n/g, '|'), emitted: (window.__emitted || []).filter(e => e[0] === 'charge.target').map(e => e[1]).slice(-1) };
  });
  chk('драг обновляет цель на месте (95)', /95/.test(r.during), r.during);
  chk('драг → commit charge.target (round)', r.emitted.length === 1 && r.emitted[0].percent >= 90 && r.emitted[0].percent <= 100, r.emitted);

  /* ── вкладка График ── */
  chk('клик График', await clickSync(p, '[data-teltab="panel"][data-val="graph"]'));
  await sleep(2200);
  r = await p.evaluate(() => {
    const svg = document.querySelector('#telChargeChart svg');
    return {
      bars: svg ? svg.querySelectorAll('rect').length : 0,
      overlay: svg ? svg.querySelectorAll('path').length : 0,
      ticks: Array.from(document.querySelectorAll('#telChargeChart svg text')).map(t => t.textContent),
      tip: document.querySelector('.telTip') ? document.querySelector('.telTip').innerText.replace(/\n/g, '|') : null
    };
  });
  chk('график: SOC-столбцы', r.bars > 10, r.bars);
  chk('график: кривая мощности (overlay)', r.overlay >= 1, r.overlay);
  chk('график: тики мощности 80/30/10/0', ['80', '30', '10', '0'].every(t => r.ticks.includes(t)), r.ticks);
  chk('график: x-тики ЧЧ:ММ (3 шт)', r.ticks.filter(t => /^\d\d:\d\d$/.test(t)).length === 3, r.ticks);
  chk('тултип: SOC%, кВт, окно ЧЧ:ММ–ЧЧ:ММ', r.tip && /%/.test(r.tip) && /кВт/.test(r.tip) && /\d\d:\d\d–\d\d:\d\d/.test(r.tip), r.tip);

  /* клик по столбцу графика — выбор/сброс */
  await p.evaluate(() => {
    const host = document.getElementById('telChargeChart');
    const rc = host.getBoundingClientRect();
    host.dispatchEvent(new MouseEvent('mousedown', { clientX: rc.left + rc.width * 0.25, clientY: rc.top + rc.height / 2, bubbles: true }));
    window.dispatchEvent(new MouseEvent('mouseup'));
  });
  await sleep(600);
  chk('клик по графику — выбор живо (тултип обновлён)', await p.evaluate(() => {
    const tip = document.querySelector('.telTip');
    return !!(tip && /\d\d:\d\d–\d\d:\d\d/.test(tip.innerText));
  }));

  /* футер: 5 колонок статистики */
  r = await p.evaluate(() => ({
    km: (document.body.innerText.match(/\+\d+\s?км/) || [])[0],
    kwh: (document.body.innerText.match(/\+\d+[\.,]\d\s?кВт·ч/) || [])[0],
    cost: (document.body.innerText.match(/[₽$Br]\s?\d+[\.,]\d\d/) || [])[0],
    dur: /47 мин|52 мин/.test(document.body.innerText),
    target: /Лимит достигнут/.test(document.body.innerText) && /\d\d:\d\d/.test((document.querySelector('#telChgCol1') || { innerText: '' }).innerText),
    captions: ['Полученный запас хода · ОЦЕНКА', 'Добавленная энергия', 'Стоимость · ОЦЕНКА', 'Длительность'].every(c => document.body.innerText.includes(c))
  }));
  chk('футер: запас·оценка (+N км)', !!r.km, r.km);
  chk('футер: добавлено (+N кВт·ч)', !!r.kwh, r.kwh);
  chk('футер: стоимость (₽ N,NN)', !!r.cost, r.cost);
  chk('футер: длительность (N мин)', r.dur, r.dur);
  chk('футер: лимит достигнут ЧЧ:ММ', r.target, r.target);
  chk('футер: подписи capy', r.captions, r.captions);

  /* ── клавиатура цены ── */
  chk('клик по цене', await clickSync(p, '[data-telcost]'));
  await sleep(400);
  r = await p.evaluate(() => {
    const t = (document.querySelector('.telAnchorPanel') || { innerText: '' }).innerText;
    return {
      open: !!document.querySelector('[data-teldigit]'),
      fields: Array.from(document.querySelectorAll('[data-telseg]')).map(b => b.textContent),
      title: /Цена зарядки/.test(t),
      desc: /Действует только для этой зарядки/.test(t),
      readout: (t.match(/[₽$]\s?[\d,\.]+/) || [])[0],
      keys: document.querySelectorAll('[data-teldigit]').length
    };
  });
  chk('клавиатура: заголовок+описание', r.open && r.title && r.desc, r.title);
  chk('клавиатура: поля Цена/кВт·ч | Всего оплачено', r.fields.join('|') === 'Цена/кВт·ч|Всего оплачено', r.fields);
  chk('клавиатура: 10 цифр + clear/delete/save', r.keys === 10, r.keys);
  chk('клавиатура: исходное = оплаченное (19,26)', r.readout && /19,26/.test(r.readout), r.readout);
  /* вводим 12,34 в поле total → save → оба поля в записи */
  await p.evaluate(() => { window.__emitted = []; });
  await clickSync(p, '[data-telkey="clear"]');
  await sleep(200);
  for (const d of ['1', '2', '3', '4']) await clickSync(p, '[data-teldigit="' + d + '"]');
  await sleep(200);
  await clickSync(p, '[data-telkey="save"]');
  await sleep(300);
  r = await p.evaluate(() => ({
    emitted: (window.__emitted || []).filter(e => e[0] === 'charge.cost')[0],
    closed: !document.querySelector('[data-teldigit]')
  }));
  chk('save → charge.cost total=12,34', r.emitted && r.emitted[1].paidAmount === 12.34, r.emitted);
  chk('save пишет ОБА поля (rate как есть)', r.emitted && r.emitted[1].costPerKwh != null && r.emitted[1].costPerKwh === 0.9, r.emitted);
  chk('клавиатура закрылась', r.closed);

  /* ── SessionPanel: сводка-донат ── */
  r = await p.evaluate(() => {
    const t = (document.getElementById('telChgCol2') || { innerText: '' }).innerText;
    return {
      title: /Последний сеанс зарядки/i.test(t),
      dur: /47 мин/.test(t),
      ring: document.querySelectorAll('#telChargeRing svg path').length,
      markers: document.querySelectorAll('#telChargeRing .telDonutMarker').length,
      battery: /21,4 кВт·ч|21\.4 кВт·ч/.test(t),
      climate: /2,3 кВт·ч|2\.3 кВт·ч/.test(t) || /≥\s*2,[\d] кВт·ч/.test(t)
    };
  });
  chk('сводка: заголовок + длительность', r.title && r.dur, r.dur);
  chk('сводка: донат (дуги)', r.ring >= 2, r.ring);
  chk('сводка: маркеры батарея/климат', r.markers >= 1, r.markers);
  chk('сводка: батарея 21,4 кВт·ч', r.battery, r);
  chk('сводка: климат (N или ≥N кВт·ч)', r.climate, r);

  /* ── эмиты стоп/форс ── */
  await p.evaluate(() => { window.__emitted = []; });
  await clickSync(p, '[data-teltab="panel"][data-val="level"]');
  await sleep(600);
  await clickSync(p, '[data-telsoft="stop"]');
  await sleep(200);
  await clickSync(p, '[data-telsoft="force"]');
  await sleep(200);
  r = await p.evaluate(() => (window.__emitted || []).map(e => e[0] + ':' + JSON.stringify(e[1])));
  chk('стоп → charge.stop {}', r.some(x => x === 'charge.stop:{}'), r);
  chk('форс → charge.force {on:true}', r.some(x => x === 'charge.force:{"on":true}'), r);

  /* ── live-цикл: ask истории каждые 5с при активной зарядке ── */
  await p.evaluate(() => { window.__emitted = []; });
  await sleep(5600);
  r = await p.evaluate(() => (window.__emitted || []).filter(e => e[0] === 'history.series').length);
  chk('live-цикл: history.series каждые 5с', r >= 1, r);

  /* ── бродкаст chargeState обновляет цель без перерисовки драга ── */
  r = await p.evaluate(() => {
    telCharge.onChargeState({ targetSoc: 90, amps: 16 });
    return {
      target: (document.getElementById('telLimitSliderHost') || { innerText: '' }).innerText.match(/90/)
    };
  });
  chk('chargeState → цель 90 на слайдере', !!r.target, r.target);

  /* ── unmount при уходе с вкладки ── */
  await clickSync(p, '[data-ttab="0"]');
  await sleep(600);
  chk('unmount при уходе с вкладки', await p.evaluate(() => !telCharge.isMounted() && !document.getElementById('telChargeRoot')));
  await clickSync(p, '[data-ttab="1"]');
  await sleep(800);
  chk('remount при возврате', await p.evaluate(() => telCharge.isMounted() && !!document.getElementById('telChargeRoot')));

  /* ── гистерезис климат-баннера: чистый климат → баннер уходит ── */
  r = await p.evaluate(() => {
    TEL.energyLive = Object.assign({}, TEL.energyLive, { chargeClimate: { buckets: [{ climate: 9, climateSec: 60 }] } });
    telCharge.repaintLive();
    return { banner: !!document.querySelector('.telBreathe') };
  });
  chk('климат-гистерезис: 0.5 кВт → баннер скрыт', !r.banner, r);

  chk('нет ошибок JS за сессию (итог)', errs.length === 0, errs.slice(0, 4));
  await b.close();
  console.log('\ntestt3: OK=' + pass + ' FAIL=' + fail);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
