/* Stage T4: экран «История» (HistoryV2Screen) — порт lib/screens_v2/history из capy.
   Проверяет: CategoryMenu-рельс (Поездки|Зарядки|Батарея), TripsBody (кнопка мест,
   поиск, группы дней DaySectionHeader, TripSessionCard с мини-картой и метриками),
   выбор поездки → фуллскрин (график + кольцо + карта маршрута + SessionMosaic 7 колонок),
   сводку 4 факта + expand/collapse, HistoryRow зарядок (статус/разъём/энергия/SOC),
   фуллскрин зарядки (график SOC+мощность, карта позиции, плитка цены → клавиатура →
   charge.cost), _CycleRow (CycleBar + заметки + мозаика 3×2, свап единиц эффективности),
   CycleTimelineCard (лента сеансов, deleted, chip доли), поиск-фильтр, эмиты
   history.series/cycle.sessions, repaintLive по sessionSeries, mount/unmount.
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
  await clickSync(p, '[data-ttab="2"]');
  await sleep(900);

  /* ── монтирование и рельс категорий ── */
  let r = await p.evaluate(() => ({
    mounted: window.telHistory ? telHistory.isMounted() : false,
    rail: !!document.querySelector('[data-telcat="trips"]'),
    cats: ['trips', 'charges', 'battery'].filter(c => !!document.querySelector('[data-telcat="' + c + '"]')).length,
    railW: (document.querySelector('[data-telcat="trips"]') || { parentElement: { offsetWidth: 0 } }).parentElement.offsetWidth
  }));
  chk('экран смонтирован', r.mounted, r);
  chk('три категории', r.cats === 3, r.cats);
  chk('рельс 320px (categoryMenuRailWidth)', r.railW === 320, r.railW);

  /* ── TripHistoryPane ── */
  r = await p.evaluate(() => {
    const txt = document.body.innerText;
    return {
      places: !!document.querySelector('[data-telsoft="places"]'),
      search: !!document.getElementById('telHistSearch'),
      dayHeaders: document.querySelectorAll('#telHistRoot .telCard').length,
      trips: document.querySelectorAll('[data-teltrip]').length,
      header1: (document.body.innerText.match(/поездок|поездка/) || [''])[0],
      stats: txt.includes('18.4') && txt.includes('2.60') && txt.includes('км/ч'),
      mapSvg: !!document.querySelector('[data-teltrip] svg path'),
      metricLabels: /РАССТ\./.test(txt) && /ЭНЕРГИЯ/.test(txt) && /СРЕДН\./.test(txt) && /ВРЕМЯ/.test(txt),
      duration: /26 мин/.test(txt)
    };
  });
  chk('кнопка «Посмотреть места» (history-places-button)', r.places, r);
  chk('строка поиска TripsFilterBar', r.search, r);
  chk('карточки поездок (3 демо)', r.trips === 3, r.trips);
  chk('заголовок дня «N поездок • км • кВт·ч»', !!r.header1 && /24\.7 км • 3\.50 кВт·ч/.test(await p.evaluate(() => document.body.innerText)), r.header1);
  chk('метрики карточки: 18,4 км · 2,60 кВт·ч', r.stats, r);
  chk('мини-карта SVG (RoutePreviewMap)', r.mapSvg, r);
  chk('подписи метрик РАССТ/ЭНЕРГИЯ/СРЕДН/ВРЕМЯ', r.metricLabels, r);
  chk('длительность «26 мин» (formatDurationMinutes)', r.duration, r);

  /* ── выбор поездки: фуллскрин ── */
  const emitted = [];
  await p.evaluate(() => {
    window.__t4emit = window.__geelyEmit;
    window.__geelyEmit = function (ch, payload) { window.__t4log = window.__t4log || []; window.__t4log.push({ ch, payload }); if (window.__t4emit) window.__t4emit(ch, payload); };
  });
  await clickSync(p, '[data-teltrip="d1"]');
  await sleep(900);
  r = await p.evaluate(() => ({
    fs: !!document.getElementById('telHistChart'),
    ring: !!document.getElementById('telHistRing'),
    mosaic: document.querySelectorAll('#telHistMosaic [data-telmosaic]').length,
    routePath: !!document.querySelector('#telHistRoot svg path[d^="M"]'),
    mapToggle: !!document.querySelector('[data-telmaptoggle]'),
    heroEff: /км\/кВт·ч|кВт·ч\/50км|кВт·ч\/100км/.test(document.getElementById('telHistMosaic').innerText),
    socTile: /SOC/.test(document.getElementById('telHistMosaic').innerText),
    tempTile: /Температура/.test(document.getElementById('telHistMosaic').innerText),
    collapse: !!document.querySelector('[data-telcollapse]'),
    chartDrawn: !!(document.querySelector('#telHistChart svg') || document.querySelector('#telHistChart .bar') || (document.getElementById('telHistChart') && document.getElementById('telHistChart').children.length > 0)),
    ringDrawn: !!(document.querySelector('#telHistRing svg') || (document.getElementById('telHistRing') && document.getElementById('telHistRing').innerText.trim() !== '')),
    emits: (window.__t4log || []).map(function (e) { return e.ch; })
  }));
  chk('фуллскрин: карточка графика', r.fs, r);
  chk('фуллскрин: кольцо (EnergySessionPanel)', r.ring, r);
  chk('график поездки отрисован', r.chartDrawn, r);
  chk('кольцо отрисовано', r.ringDrawn, r);
  chk('карта маршрута (полилиния)', r.routePath, r);
  chk('кнопка expand карты', r.mapToggle, r);
  chk('SessionMosaic: 9 плиток', r.mosaic === 9, r.mosaic);
  chk('hero-плитка эффективности', r.heroEff, r);
  chk('плитки SOC/Температура (wide)', r.socTile && r.tempTile, r);
  chk('кнопка сворачивания детайла', r.collapse, r);
  chk('эмиты history.series', r.emits.indexOf('history.series') >= 0, r.emits);

  /* ── _tripHeaderInsight: InsightTripPlaces + OwnAverageInsight ── */
  r = await p.evaluate(() => {
    const head = document.getElementById('telHistMosaic').closest('.telCard').querySelector('.telCardHead');
    const txt = head.innerText;
    return {
      places: /Дом/.test(txt) && /Работа/.test(txt),
      arrowSvg: !!head.querySelector('svg'),
      phrase: /Эта поездка потратила на 5\.4 Вт·ч\/км меньше, чем за последние 30 дней \(8 поездок\)\./.test(txt)
    };
  });
  chk('инсайт: места «Дом → Работа» (InsightTripPlaces)', r.places, r);
  chk('инсайт: стрелка arrow_forward', r.arrowSvg, r);
  chk('инсайт: фраза usedLess 5.4 Вт·ч/км (8 поездок)', r.phrase, r);

  /* d2: различимость не достигнута → notDistinguishable */
  await clickSync(p, '[data-telcollapse]');
  await sleep(400);
  await clickSync(p, '[data-teltrip="d2"]');
  await sleep(900);
  r = await p.evaluate(() => {
    const head = document.getElementById('telHistMosaic').closest('.telCard').querySelector('.telCardHead');
    return {
      phrase: /Эту поездку пока нельзя отличить от ваших последних 30 дней \(8 поездок\)\./.test(head.innerText)
    };
  });
  chk('инсайт d2: notDistinguishable (IQR шире разрыва)', r.phrase, r);

  /* d3: usedLess 10.4 + место только конечное («Дача» не в справочнике мест) */
  await clickSync(p, '[data-telcollapse]');
  await sleep(400);
  await clickSync(p, '[data-teltrip="d3"]');
  await sleep(900);
  r = await p.evaluate(() => {
    const head = document.getElementById('telHistMosaic').closest('.telCard').querySelector('.telCardHead');
    const txt = head.innerText;
    return {
      places: /Дом/.test(txt) && !/Дача/.test(txt),
      phrase: /на 10\.4 Вт·ч\/км меньше, чем за последние 30 дней \(8 поездок\)\./.test(txt)
    };
  });
  chk('инсайт d3: только конечное имя (placeContaining)', r.places, r);
  chk('инсайт d3: usedLess 10.4 Вт·ч/км', r.phrase, r);

  /* возврат к d1: «Работа → ТРЦ» для d2 не проверяем отдельной стрелкой — d1 для следующих блоков */
  await clickSync(p, '[data-telcollapse]');
  await sleep(400);
  await clickSync(p, '[data-teltrip="d1"]');
  await sleep(900);



  /* expand/collapse карты */
  await clickSync(p, '[data-telmaptoggle]');
  await sleep(150);
  r = await p.evaluate(() => ({
    expanded: (document.querySelector('[data-telmaptoggle]') || { title: '' }).title
  }));
  chk('карта: подпись кнопки сменилась (Уменьшить)', /Уменьшить/.test(r.expanded), r.expanded);
  await clickSync(p, '[data-telmaptoggle]');
  await sleep(150);

  /* сворачивание детайла → сводка */
  await clickSync(p, '[data-telcollapse]');
  await sleep(500);
  r = await p.evaluate(() => {
    const root = document.getElementById('telHistRoot');
    const txt = root.innerText;
    return {
      summary: /Выберите сессию, чтобы открыть её\./.test(txt),
      facts4: /Начало/.test(txt) && /Конец/.test(txt) && /Длительность/.test(txt) && /Расстояние/.test(txt),
      list: document.querySelectorAll('[data-teltrip]').length === 3,
      expandBtn: !!document.querySelector('[data-telexpand]'),
      rail: !!document.querySelector('[data-telcat="charges"]')
    };
  });
  chk('сводка: первые 4 факта', r.facts4, r);
  chk('сводка: подсказка развернуть', r.summary, r);
  chk('сворачивание возвращает список + рельс', r.list && r.rail, r);
  chk('кнопка «Развернуть» на сводке', r.expandBtn, r);
  await clickSync(p, '[data-telexpand]');
  await sleep(500);
  chk('разворачивание обратно (график)', await p.evaluate(() => !!document.getElementById('telHistChart')));

  /* ── категория «Зарядки» ── */
  await clickSync(p, '[data-telcollapse]');
  await sleep(400);
  await clickSync(p, '[data-telcat="charges"]');
  await sleep(600);
  r = await p.evaluate(() => {
    const row = document.querySelector('[data-telhist]');
    const txt = row ? row.innerText : '';
    return {
      rows: document.querySelectorAll('[data-telhist]').length,
      title: /\d{2}\/\d{2}\/\d{4} в \d{2}:\d{2}/.test(txt),
      status: /ЗАВЕРШЕНО/.test(txt),
      plug: /AC|перем/.test(txt) || /Type 2|AC/.test(txt),
      energy: /10\.20 kWh/.test(txt),
      soc: /46\.0% -> 78\.0%/.test(txt)
    };
  });
  chk('HistoryRow зарядки: 1 строка', r.rows === 1, r.rows);
  chk('заголовок dd/MM/yyyy в HH:mm', r.title, r);
  chk('статус сессии', r.status, r);
  chk('разъём AC (chargePlugLabel)', r.plug, r);
  chk('энергия 10.20 kWh (energyLabel)', r.energy, r);
  chk('SOC 46.0% -> 78.0% (socRangeLabel)', r.soc, r);

  await clickSync(p, '[data-telhist="dc1"]');
  await sleep(900);
  r = await p.evaluate(() => {
    const mosaic = document.getElementById('telHistMosaic');
    return {
      chart: !!(document.getElementById('telHistChart') && document.getElementById('telHistChart').children.length),
      mosaicTiles: mosaic ? mosaic.querySelectorAll('[data-telmosaic]').length : 0,
      avgPower: mosaic ? /Средняя мощность/.test(mosaic.innerText) : false,
      peakPower: mosaic ? /Пиковая мощность/.test(mosaic.innerText) : false,
      costTile: mosaic ? !!mosaic.querySelector('[data-telmosaic="cost"]') : false,
      temp: mosaic ? /18\.5°C -> 16\.0°C/.test(mosaic.innerText) : false,
      posMap: !!document.querySelector('#telHistRoot [data-telmaptoggle], #telHistRoot svg circle'),
      ringAbsent: !document.getElementById('telHistRing')
    };
  });
  chk('фуллскрин зарядки: график SOC', r.chart, r);
  chk('мозаика зарядки: 9 плиток', r.mosaicTiles === 9, r.mosaicTiles);
  chk('плитки мощности (средняя/пиковая)', r.avgPower && r.peakPower, r);
  chk('плитка цены (onPressed)', r.costTile, r);
  chk('плитка температуры сессии (18.5→16.0°C)', r.temp, r);
  chk('карта позиции (одна точка-маркер)', r.posMap, r);
  chk('у зарядки нет кольца', r.ringAbsent, r);

  /* ── клавиатура цены ── */
  await clickSync(p, '[data-telmosaic="cost"]');
  await sleep(400);
  r = await p.evaluate(() => ({
    pad: !!document.querySelector('[data-telkey="save"]'),
    segs: document.querySelectorAll('[data-telseg]').length,
    display: (document.querySelector('.telAnchorPanel') || { innerText: '' }).innerText
  }));
  chk('клавиатура цены открылась', r.pad, r);
  chk('переключатель ставка/сумма', r.segs >= 2, r.segs);
  await clickSync(p, '[data-teldigit="1"]');
  await clickSync(p, '[data-teldigit="2"]');
  await sleep(200);
  r = await p.evaluate(() => (document.querySelector('.telAnchorPanel') || { innerText: '' }).innerText);
  chk('ввод суммы (0,90 → 90,12… как T3)', /90,12/.test(r), r);
  await clickSync(p, '[data-telkey="save"]');
  await sleep(400);
  r = await p.evaluate(() => {
    const log = window.__t4log || [];
    const cost = log.filter(function (e) { return e.ch === 'charge.cost'; }).pop();
    return { closed: !document.querySelector('[data-telkey="save"]'), cost: cost ? cost.payload : null };
  });
  chk('клавиатура закрылась', r.closed, r);
  chk('charge.cost отправлен (поле rate)', !!r.cost && r.cost.costPerKwh != null, r.cost);

  /* ── категория «Батарея» ── */
  await clickSync(p, '[data-telcollapse]');
  await sleep(400);
  await clickSync(p, '[data-telcat="battery"]');
  await sleep(600);
  r = await p.evaluate(() => {
    const txt = document.body.innerText;
    const rows = document.querySelectorAll('[data-telcyc]');
    const first = rows[0];
    return {
      rows: rows.length,
      bar: first ? !!first.querySelector('svg, [style*="border-radius:999"]') || first.innerText.indexOf('#') >= 0 : false,
      openNote: /В процессе/.test(txt),
      partialNote: /Неполный/.test(txt),
      mixedNote: /Две валюты/.test(txt),
      partlyNote: /67% с ценой/.test(txt),
      frozenNote: /Итоговый/.test(txt),
      mosaic: first ? first.querySelectorAll('[data-telmosaic]').length : 0,
      effTile: first ? !!first.querySelector('[data-telmosaic="efficiency"]') : false,
      costPerKwh: /За кВт·ч/.test(txt),
      capacity: /Доступно/.test(txt)
    };
  });
  chk('циклы: 5 строк', r.rows === 5, r.rows);
  chk('CycleBar с ординалом #N', r.bar, r);
  chk('заметка «В процессе» (открытый цикл)', r.openNote, r);
  chk('заметка «Неполный»', r.partialNote, r);
  chk('заметка «Две валюты» (mixedCurrency)', r.mixedNote, r);
  chk('заметка «67% с ценой» (partlyPriced)', r.partlyNote, r);
  chk('заметка «Итоговый» (frozen)', r.frozenNote, r);
  chk('мозаика цикла: 6 плиток 3 колонки', r.mosaic === 6, r.mosaic);
  chk('плитка эффективности со свапом', r.effTile, r);
  chk('плитки За кВт·ч / Доступно', r.costPerKwh && r.capacity, r);
  r = await p.evaluate(() => {
    const row = document.querySelector('[data-telcyc="1"]');
    const txt = row ? row.innerText : '';
    return {
      capacity: /37\.8/.test(txt) && /Доступно/.test(txt),
      avgCost: /BYN 1\.02/.test(txt),
      cost: /BYN 38\.40/.test(txt)
    };
  });
  chk('цикл #1: ёмкость 37.8 кВт·ч (measuredCapacity)', r.capacity, r);
  chk('цикл #1: за кВт·ч BYN 1.02 (cost/pricedEnergy)', r.avgCost, r);
  chk('цикл #1: стоимость BYN 38.40', r.cost, r);
  r = await p.evaluate(() => {
    const row = document.querySelector('[data-telcyc="2"]');
    const txt = row ? row.innerText : '';
    return { mixedCost: /--/.test(txt) && /Две валюты/.test(txt) };
  });
  chk('цикл #2 (две валюты): стоимость --', r.mixedCost, r);

  /* свап единиц эффективности */
  const effBefore = await p.evaluate(() => (document.querySelector('[data-telmosaic="efficiency"]') || { innerText: '' }).innerText);
  await clickSync(p, '[data-telmosaic="efficiency"]');
  await sleep(300);
  const effAfter = await p.evaluate(() => (document.querySelector('[data-telmosaic="efficiency"]') || { innerText: '' }).innerText);
  chk('свап единиц (км/кВт·ч → кВт·ч/50км)', effBefore !== effAfter && /50км|100км/.test(effAfter), effBefore + ' → ' + effAfter);
  await clickSync(p, '[data-telmosaic="efficiency"]');
  await clickSync(p, '[data-telmosaic="efficiency"]');
  await sleep(200);

  /* ── детайл цикла: CycleTimelineCard ── */
  await p.evaluate(() => { window.__t4log = []; });
  await clickSync(p, '[data-telcyc="1"]');
  await sleep(700);
  r = await p.evaluate(() => {
    const root = document.getElementById('telHistRoot');
    const txt = root.innerText;
    return {
      title: /Что проехала эта батарея/.test(txt),
      count: /5/.test(txt),
      rows: root.querySelectorAll('[style*="border-radius"]').length,
      deleted: /Сеанс удалён/.test(txt),
      chip: /55% этой батареи/.test(txt),
      kinds: /Стоянка/.test(txt) && /Зарядка/.test(txt) && /Поездка/.test(txt),
      tripFact: /14\.4 км/.test(txt),
      chargeFact: /31\.50 kWh/.test(txt),
      emits: (window.__t4log || []).map(function (e) { return e.ch; })
    };
  });
  chk('CycleTimelineCard: заголовок', r.title, r);
  chk('лента: 5 сеансов', r.count, r);
  chk('метки видов (Стоянка/Зарядка/Поездка)', r.kinds, r);
  chk('удалённый сеанс «Сеанс удалён»', r.deleted, r);
  chk('chip доли «55% этой батареи»', r.chip, r);
  chk('факт поездки (14,4 км)', r.tripFact, r);
  chk('факт зарядки (31,50 кВт·ч)', r.chargeFact, r);
  chk('эмиты cycle.sessions', r.emits.indexOf('cycle.sessions') >= 0, r.emits);

  /* ── поиск по местоположению ── */
  await clickSync(p, '[data-telcollapse]');
  await sleep(400);
  await clickSync(p, '[data-telcat="trips"]');
  await sleep(500);
  await p.evaluate(() => {
    const si = document.getElementById('telHistSearch');
    si.value = 'Работа';
    si.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await sleep(400);
  r = await p.evaluate(() => ({
    trips: document.querySelectorAll('[data-teltrip]').length,
    timeline: /Работа/.test(document.body.innerText)
  }));
  chk('фильтр «Работа»: 2 поездки (место в начале/конце)', r.trips === 2, r.trips);
  await p.evaluate(() => {
    const si = document.getElementById('telHistSearch');
    si.value = 'НетТакогоМеста';
    si.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await sleep(300);
  chk('пустой фильтр: подсказка', await p.evaluate(() => /По этому фильтру поездок нет/.test(document.body.innerText)));
  await p.evaluate(() => {
    document.querySelector('[data-telclearsearch]').click();
  });
  await sleep(300);
  chk('очистка фильтра: снова 3', await p.evaluate(() => document.querySelectorAll('[data-teltrip]').length === 3));

  /* ── пресеты времени (Все / 7 дней / 30 дней) ── */
  r = await p.evaluate(() => ({
    chips: document.querySelectorAll('[data-telfpreset]').length,
    labels: Array.from(document.querySelectorAll('[data-telfpreset]')).map(b => b.innerText.trim()).join('|')
  }));
  chk('чипы пресетов (Все|7 дней|30 дней)', r.chips === 3 && r.labels === 'Все|7 дней|30 дней', r);
  await p.evaluate(() => {
    /* старая поездка (40 дней) — только «Все» и «30 дней» её показывают */
    TEL.trips.push({ id: 'd-old', start: Date.now() - 40 * 86400000, end: Date.now() - 40 * 86400000 + 36e5, km: 5, kwh: 0.8, min: 9, soc0: 60, soc1: 58, cost: null, currency: '$', sp0: 'Гараж', sp1: 'Гараж', regen: 10, dur: 540000, tmp0: 5, tmp1: 5 });
    telHistory.repaintLive();
  });
  await sleep(400);
  chk('«Все»: 4 поездки (в т.ч. 40 дней назад)', await p.evaluate(() => document.querySelectorAll('[data-teltrip]').length === 4));
  await clickSync(p, '[data-telfpreset="days7"]');
  await sleep(300);
  chk('«7 дней»: старая отсечена (3)', await p.evaluate(() => document.querySelectorAll('[data-teltrip]').length === 3));
  r = await p.evaluate(() => {
    const sel = document.querySelector('[data-telfpreset="days7"]');
    return { selBg: (sel || { style: {} }).style.background, others: Array.from(document.querySelectorAll('[data-telfpreset]')).filter(b => b !== sel).every(b => b.style.background !== sel.style.background) };
  });
  chk('выбранный чип подсвечен (selectionFill)', r.selBg !== '' && r.others, r);
  await clickSync(p, '[data-telfpreset="days30"]');
  await sleep(300);
  chk('«30 дней»: 40-дневная тоже отсечена (3)', await p.evaluate(() => document.querySelectorAll('[data-teltrip]').length === 3));
  await clickSync(p, '[data-telfpreset="all"]');
  await sleep(300);
  await p.evaluate(() => { TEL.trips.pop(); telHistory.repaintLive(); });
  await sleep(300);

  /* ── repaintLive по sessionSeries ── */
  await p.evaluate(() => {
    const d = JSON.parse(JSON.stringify(HISTD.series.d1));
    d.intervals = d.intervals.map(function (e) { e.traction = 999; return e; });
    window.dispatchEvent(new CustomEvent('native:telemetry.sessionSeries', { detail: d }));
  });
  await sleep(500);
  chk('sessionSeries → repaintLive (график жив)', await p.evaluate(() => telHistory.isMounted() && !!document.querySelector('#telHistRoot')));

  /* ── смена вкладки: unmount ── */
  await clickSync(p, '[data-ttab="0"]');
  await sleep(600);
  chk('уход со вкладки: unmount', await p.evaluate(() => !telHistory.isMounted() && !document.getElementById('telHistHost')));
  await clickSync(p, '[data-ttab="2"]');
  await sleep(600);
  chk('возврат: mount снова', await p.evaluate(() => telHistory.isMounted()));

  chk('нет ошибок JS за сценарий', errs.length === 0, errs.slice(0, 4));
  console.log('ИТОГ T4: pass=' + pass + ' fail=' + fail);
  await b.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
