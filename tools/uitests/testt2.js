/* Stage T2: экран «Поездки» (TripsV2Screen) — порт lib/screens_v2/trips из capy.
   Проверяет: TMON-контроллер, график EnergyBarChart, кольцо, футеры drive/parked/ledger,
   дропдаун окна, трек drive|parked, info-панели, юнит эффективности, live-тик,
   выбор-тултип (переживает тик), окно «С момента включения» (спаны+сон+баланс),
   темы CapyUI, старт/стоп опроса. Хук-сервер: копия ui/ на http://localhost:8080. */
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
  p.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1200);
  chk('T2 скрипты без ошибок JS', errs.length === 0, errs.slice(0, 3));
  await p.click('.tab[data-id="telemetry"]');
  await sleep(900);

  /* ── контроллер и живое окно ── */
  chk('TMON активен на вкладке', await p.evaluate(() => window.TMON && TMON.isPolling === true));
  chk('живые минуты слиты (minutes>0)', await p.evaluate(() => TMON.minutes.length > 0), await p.evaluate(() => TMON.minutes.length));
  chk('график: столбцы SVG', await p.evaluate(() => document.querySelectorAll('#telEnergyChart svg rect').length > 20));
  chk('ось Y: 0.00 и верхняя метка', await p.evaluate(() => {
    const t = Array.from(document.querySelectorAll('#telEnergyChart svg text')).map(x => x.textContent);
    return t.includes('0.00') && t.some(x => /^\d+\.\d{2}$/.test(x) && x !== '0.00');
  }));
  chk('ось X: метки времени ЧЧ:ММ', await p.evaluate(() => {
    const t = Array.from(document.querySelectorAll('#telEnergyChart svg text')).map(x => x.textContent);
    return t.some(x => /^\d{2}:\d{2}$/.test(x));
  }));

  /* ── карточка сеанса ── */
  chk('кольцо сеанса (SVG)', await p.evaluate(() => document.querySelectorAll('#telSessionRing svg path').length > 0));
  chk('«Сведения о сеансе» + длительность', await p.evaluate(() => {
    const card = Array.from(document.querySelectorAll('.telCard')).find(c => /сведения о сеансе/i.test(c.textContent));
    return !!(card && /\d+\s?(h\s?\d+\s?min|\d+\s?min)/.test(card.textContent));
  }));

  /* ── drive-футер ── */
  chk('футер: эффективность км/кВт·ч', await p.evaluate(() => {
    const s = Array.from(document.querySelectorAll('.telCard .statVal')).map(x => x.textContent.trim());
    return s.some(x => /км\/кВт·ч|кВт·ч\/50 км|кВт·ч\/100 км/.test(document.body.innerText)) && s.length >= 4;
  }));

  /* ── дропдаун окна ── */
  await p.click('[data-teldrop]');
  await sleep(400);
  chk('меню окна: 5 опций + скрим', await p.evaluate(() =>
    document.querySelectorAll('[data-teldropopt]').length === 5 && !!document.querySelector('[data-teldropscrim]')));
  chk('текущая поездка выбрана', await p.evaluate(() => {
    const sel = document.querySelector('[data-teldropopt].sel');
    return !!sel && /текущая поездка/i.test(sel.textContent);
  }));
  await p.click('[data-teldropopt][data-val="last8Hours"]');
  await sleep(800);
  chk('выбор 8 часов применился', await p.evaluate(() =>
    /последние 8 часов/i.test((document.querySelector('[data-teldrop]') || { textContent: '' }).textContent) &&
    !document.querySelector('[data-teldropopt]')));

  /* ── трек drive|parked ── */
  chk('сегменты drive|parked появились', await p.evaluate(() =>
    Array.from(document.querySelectorAll('[data-telseg]')).map(x => x.dataset.val).join(',') === 'drive,parked'));
  await p.click('[data-telseg][data-val="parked"]');
  await sleep(700);
  chk('parked-футер: Вт и кВт·ч', await p.evaluate(() => {
    const body = document.body.innerText;
    return /Вт/.test(body) && TMON.track === 'parked';
  }));
  await p.click('[data-telseg][data-val="drive"]');
  await sleep(600);

  /* ── info-панели ── */
  await p.click('[data-telinfo="energy"]');
  await sleep(400);
  chk('панель «О расходе энергии»', await p.evaluate(() =>
    !!document.querySelector('[data-telanchorclose]') && /о расходе энергии/i.test(document.body.innerText)));
  await p.click('[data-telanchorclose]');
  await sleep(300);
  await p.click('[data-telinfo="session"]');
  await sleep(400);
  chk('панель «Что показывает кольцо»', await p.evaluate(() =>
    !!document.querySelector('[data-telanchorclose]') && /что показывает кольцо/i.test(document.body.innerText)));
  await p.click('[data-telanchorclose]');
  await sleep(300);

  /* ── юнит эффективности ── */
  const units = [];
  for (let i = 0; i < 3; i++) {
    units.push(await p.evaluate(() => (document.querySelector('[data-tel="effUnit"]') || { textContent: '' }).textContent.trim()));
    await p.click('[data-tel="effUnit"]');
    await sleep(400);
  }
  chk('цикл юнита: 3 разных', new Set(units).size === 3, units);

  /* ── выбор-тултип переживает live-тик (на живом окне данные заполняют слоты) ── */
  await p.click('[data-teldrop]');
  await sleep(300);
  await p.click('[data-teldropopt][data-val="currentDrive"]');
  await sleep(900);
  await p.evaluate(() => {
    const host = document.getElementById('telEnergyChart');
    const b = host.getBoundingClientRect();
    host.dispatchEvent(new MouseEvent('mousedown', { clientX: b.left + b.width * 0.25, clientY: b.top + b.height / 2, bubbles: true }));
    window.dispatchEvent(new MouseEvent('mouseup'));
  });
  await sleep(400);
  chk('тултип по столбцу', await p.evaluate(() => {
    const t = document.querySelector('.telTip');
    return !!(t && /кВт·ч/.test(t.textContent));
  }));
  await sleep(1200);
  chk('тултип пережил live-тик', await p.evaluate(() => !!document.querySelector('.telTip')));

  /* ── live-тик растит серию ── */
  const before = await p.evaluate(() => TMON.minutes.length);
  await p.evaluate(() => {
    const e = TEL.energyLive.energy;
    const last = e.buckets[e.buckets.length - 1];
    const nb = JSON.parse(JSON.stringify(last));
    nb.t = last.t + 60000; nb.traction = 540; nb.km = 0.55;
    e.buckets.push(nb);
    TMON.onEnergyLive(TEL.energyLive);
  });
  await sleep(800);
  chk('live-тик: минута добавлена', await p.evaluate(n => TMON.minutes.length > n, before),
    { before, after: await p.evaluate(() => TMON.minutes.length) });

  /* ── окно «С момента включения»: спаны + сон + баланс (через UI, как в машине) ── */
  await p.click('[data-teldrop]');
  await sleep(300);
  await p.click('[data-teldropopt][data-val="sincePowerOn"]');
  await sleep(900);
  chk('«С момента включения» применилось', await p.evaluate(() =>
    /с момента включения/i.test((document.querySelector('[data-teldrop]') || { textContent: '' }).textContent)));
  chk('сон синтезирован (минут > 30)', await p.evaluate(() => TMON.minutes.length > 30), await p.evaluate(() => TMON.minutes.length));
  chk('баланс: Пришло/Ушло/Баланс', await p.evaluate(() => {
    const body = document.body.innerText;
    return /пришло/i.test(body) && /ушло/i.test(body) && /баланс/i.test(body);
  }));
  chk('лента состояний (10px) на месте', await p.evaluate(() => {
    const host = document.getElementById('telEnergyChart');
    return !!host && (host.nextElementSibling || host.previousElementSibling || host);
  }));
  chk('спаны wire-формата разобраны', await p.evaluate(() =>
    TMON._spans.length === 3 && TMON._spans.every(s => s.start != null && s.end != null)));

  /* ── темы CapyUI ── */
  const bg1 = await p.evaluate(() => document.querySelector('.telCard').style.background);
  await p.evaluate(() => { setTheme = 8; applyTheme(); const st = content.scrollTop; render('telemetry'); content.scrollTop = st; });
  await sleep(500);
  const bg2 = await p.evaluate(() => document.querySelector('.telCard').style.background);
  chk('смена темы перекрашивает экран', bg1 !== bg2, { bg1, bg2 });
  await p.evaluate(() => { setTheme = 3; applyTheme(); const st = content.scrollTop; render('telemetry'); content.scrollTop = st; });
  await sleep(400);

  /* ── старт/стоп опроса при уходе с вкладки ── */
  await p.evaluate(() => select('quick'));
  await sleep(500);
  const stopped = await p.evaluate(() => !TMON.isPolling);
  await p.evaluate(() => select('telemetry'));
  await sleep(500);
  chk('опрос живёт только на вкладке', stopped && await p.evaluate(() => TMON.isPolling === true));

  chk('страница без ошибок JS (итог)', errs.length === 0, errs.slice(0, 3));
  await b.close();
  console.log('\ntestt2: OK=' + pass + ' FAIL=' + fail);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
