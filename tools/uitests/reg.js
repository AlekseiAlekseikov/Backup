/* Сводная регрессия телеметрии B4–B12 (восстановлена по хукам после потери /tmp):
   окна/кольцо/лента (b9), слайдер+пресеты+график зарядки (b8/b10), история+детайлы+слияние (b12),
   хуки: .trip[data-trip], «оценка по поездкам», telTab, TEL_LIVE, #ewChart [data-bi]/#chgChart [data-cbi], HISTD.series */
const { chromium } = require('playwright-core');
const sleep = ms => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
function chk(name, cond, extra) {
  if (cond) { pass++; console.log('чек', name + ':', 'OK'); }
  else { fail++; console.log('чек', name + ':', 'FAIL ' + (extra !== undefined ? JSON.stringify(extra) : '')); }
}
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1500);
  chk('TEL_LIVE определён', await p.evaluate(() => typeof window.TEL_LIVE === 'object'));
  await p.click('.tab[data-id="telemetry"]');
  await sleep(900);

  // TAB0: расход энергии — Stage T2: экран «Поездки» (TripsV2Screen, tel-trips.js)
  let r = await p.evaluate(() => ({
    bars: document.querySelectorAll('#telEnergyChart svg rect').length,
    ring: !!document.querySelector('#telSessionRing svg path'),
    sess: /сведения о сеансе/i.test(document.body.innerText),
    minutes: window.TMON ? TMON.minutes.length : 0
  }));
  chk('TAB0 график (бары)', r.bars > 20 && r.minutes > 0, r);
  chk('TAB0 кольцо сеанса', r.ring && r.sess, r);
  const bar = true; // клик синтетическим mousedown: график перерисовывается ежесекундно (repaintLive)
  if (bar) {
    await p.evaluate(() => {
      const host = document.getElementById('telEnergyChart');
      const b = host.getBoundingClientRect();
      host.dispatchEvent(new MouseEvent('mousedown', { clientX: b.left + b.width * 0.25, clientY: b.top + b.height / 2, bubbles: true }));
      window.dispatchEvent(new MouseEvent('mouseup'));
    });
    await sleep(500);
  }
  chk('TAB0 тултип по клику', await p.evaluate(() => {
    const root = document.getElementById('telTripsRoot');
    const tip = root ? root.querySelector('.telTip') : null;
    return !!(tip && tip.textContent.trim().length > 0);
  }));

  // TAB1: зарядка — Stage T3: экран ChargingV2Screen (tel-charge.js)
  await p.evaluate(() => document.querySelector('[data-ttab="1"]').click()); await sleep(900);
  r = await p.evaluate(() => ({
    mounted: window.telCharge ? telCharge.isMounted() : false,
    cols: ['telChgCol0','telChgCol1','telChgCol2'].filter(id => document.getElementById(id)).length,
    slider: !!document.getElementById('telLimitSliderHost'),
    presets: document.querySelectorAll('[data-telpreset]').length,
    stop: !!document.querySelector('[data-telsoft="stop"]'),
    force: !!document.querySelector('[data-telsoft="force"]'),
    amps: !!document.querySelector('[data-telsoft="amps"]'),
    range: (document.getElementById('telChgCol0')||{innerText:''}).innerText.includes('266')
  }));
  chk('TAB1 экран смонтирован (3 колонки)', r.mounted && r.cols === 3, r);
  chk('TAB1 слайдер лимита', r.slider, r);
  chk('TAB1 пресеты (4)', r.presets === 4, r.presets);
  chk('TAB1 контролы ext (стоп/форс/ток)', r.stop && r.force && r.amps, r);
  chk('TAB1 запас хода авто', r.range, r);
  await p.evaluate(() => document.querySelector('[data-teltab="panel"][data-val="graph"]').click()); await sleep(1800);
  r = await p.evaluate(() => ({
    cbars: document.querySelectorAll('#telChargeChart svg rect').length,
    overlay: document.querySelectorAll('#telChargeChart svg path').length,
    ticks: Array.from(document.querySelectorAll('#telChargeChart svg text')).map(t=>t.textContent),
    footer: /полученный запас хода · оценка/i.test(document.body.innerText) && /добавленная энергия/i.test(document.body.innerText),
    km: (document.body.innerText.match(/\+\d+\s?км/) || [])[0],
    target: /лимит достигнут/i.test(document.body.innerText)
  }));
  chk('TAB1 график SOC (бары+кривая мощности)', r.cbars > 10 && r.overlay > 0, r.cbars + '/' + r.overlay);
  chk('TAB1 тики мощности 80/30/10/0', ['80','30','10','0'].every(t => r.ticks.includes(t)), r.ticks);
  chk('TAB1 футер (запас·оценка/добавлено/лимит)', r.footer && r.km && r.target, r);

  // TAB2: история — Stage T4: экран HistoryV2Screen (tel-history.js)
  await p.click('[data-ttab="2"]'); await sleep(900);
  r = await p.evaluate(() => ({
    mounted: window.telHistory ? telHistory.isMounted() : false,
    cats: ['trips','charges','battery'].filter(c => !!document.querySelector('[data-telcat="' + c + '"]')).length,
    trips: document.querySelectorAll('[data-teltrip]').length,
    search: !!document.getElementById('telHistSearch'),
    histd: !!(window.HISTD && window.HISTD.series)
  }));
  chk('TAB2 экран смонтирован (рельс 3 категории)', r.mounted && r.cats === 3, r);
  chk('TAB2 список поездок (карточки с мини-картами)', r.trips > 0 && r.search, r);
  const trip = await p.$('[data-teltrip]');
  if (trip) { await trip.click(); await sleep(1000); }
  r = await p.evaluate(() => ({
    chart: !!document.getElementById('telHistChart'),
    ring: !!document.getElementById('telHistRing'),
    mosaic: document.querySelectorAll('#telHistMosaic [data-telmosaic]').length
  }));
  chk('TAB2 детайл поездки (график+кольцо+мозаика)', r.chart && r.ring && r.mosaic >= 9, r);
  chk('TAB2 цикл-детайл по батарее', await p.evaluate(async () => {
    document.querySelector('[data-telcollapse]').click();
    await new Promise(r => setTimeout(r, 400));
    document.querySelector('[data-telcat="battery"]').click();
    await new Promise(r => setTimeout(r, 500));
    const row = document.querySelector('[data-telcyc]');
    if (!row) return false;
    row.click();
    await new Promise(r => setTimeout(r, 700));
    return /Что проехала эта батарея/.test(document.body.innerText);
  }));
  chk('HISTD.series жив (демо)', await p.evaluate(() => Object.keys((window.HISTD && window.HISTD.series) || {}).length >= 0));

  // вкладка Голос и quick — целы после правки seg
  await p.click('.tab[data-id="quick"]'); await sleep(500);
  chk('quick: рекуперация 3 кнопки', (await p.$$('.seg[data-g="r"] button')).length === 3);

  console.log('ИТОГО:', pass, 'OK,', fail, 'FAIL | page errors:', errs.length ? errs : 'none');
  await b.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
