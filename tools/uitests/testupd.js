/* Stage UPD: вкладка «Обновления» — GitHub Releases (AlekseiAlekseikov/GeelyTools).
   Живой путь: мок GeelyNative + пуши update.state (те же фазы, что AppUpdater).
   Браузерный путь: демо-карточка v1.1.0 из макета работает как раньше. */
const { chromium } = require('playwright-core');
const sleep = ms => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const chk = (n, c, e) => { if (c) { pass++; console.log('чек', n + ':', 'OK'); } else { fail++; console.log('чек', n + ':', 'FAIL', e !== undefined ? JSON.stringify(e) : ''); } };

(async () => {
  const b = await chromium.launch();
  const errs = [];

  /* ══ Страница A: приложение (мок GeelyNative) ══ */
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  p.on('pageerror', e => errs.push(e.message));
  await p.addInitScript(() => {
    window.__emits = [];
    window.GeelyNative = {
      appVersion: () => '1.0.16',
      onEvent: (n, d) => { try { window.__emits.push([n, JSON.parse(d)]); } catch (e) {} }
    };
  });
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1500);

  // Открытие вкладки → авто-проверка
  await p.evaluate(() => select('updates'));
  await sleep(300);
  let r = await p.evaluate(() => ({
    emitted: window.__emits.filter(e => e[0] === 'update.check').length,
    checking: !!Array.from(document.querySelectorAll('button')).find(b => /Проверяю/.test(b.textContent)),
    src: document.body.textContent.indexOf('AlekseiAlekseikov/GeelyTools') >= 0,
    cur: document.body.textContent.indexOf('v1.0.16') >= 0
  }));
  chk('1. открытие вкладки: авто-проверка GitHub (emit update.check)', r.emitted >= 1, r);
  chk('2. состояние checking: кнопка «Проверяю…»', r.checking === true, r);
  chk('3. источник подписан: GitHub · AlekseiAlekseikov/GeelyTools', r.src === true, r);
  chk('4. текущая версия v1.0.16 (из appVersion)', r.cur === true, r);

  // Available: версия + changelog
  await p.evaluate(() => window.__nativeEvent('update.state', {
    phase: 'available', current: '1.0.16', version: '1.0.17',
    changelog: '### Что нового\n- Живая навигация OSM\n- Исправлен CarPlay\n- Ускорен запуск'
  }));
  await sleep(200);
  r = await p.evaluate(() => ({
    head: (document.getElementById('updHead') || {}).textContent,
    notes: Array.from(document.querySelectorAll('.notes div')).map(x => x.textContent),
    btn: !!document.getElementById('updInstall'),
    toast: document.querySelector('#toast').textContent
  }));
  chk('5. карточка «EX2 · v1.0.17 доступно»', /v1\.0\.17 доступно/.test(r.head || ''), r);
  chk('6. changelog из релиза: 3 чистые строки (без ### и дефисов)', r.notes.length === 3 && r.notes[0] === 'Живая навигация OSM', r);
  chk('7. тост «Доступна версия v1.0.17»', /Доступна версия v1\.0\.17/.test(r.toast), r);
  chk('8. кнопка «Установить сейчас»', r.btn === true, r);

  // Установка → downloading
  await p.evaluate(() => document.getElementById('updInstall').click());
  await sleep(200);
  r = await p.evaluate(() => ({
    emitted: window.__emits.filter(e => e[0] === 'update.install').length,
    pct: (document.getElementById('updPct') || {}).textContent,
    fill: (document.getElementById('updFill') || {}).style.width
  }));
  chk('9. «Установить» → emit update.install, зона скачивания', r.emitted === 1 && r.pct === '0%', r);

  // Прогресс 45% → 60% (докачка)
  await p.evaluate(() => window.__nativeEvent('update.state', { phase: 'downloading', percent: 45, message: 'Скачивание 45%' }));
  await sleep(150);
  await p.evaluate(() => window.__nativeEvent('update.state', { phase: 'downloading', percent: 60, message: 'Продолжаю скачивание (попытка 2)…' }));
  await sleep(200);
  r = await p.evaluate(() => ({
    pct: (document.getElementById('updPct') || {}).textContent,
    fill: (document.getElementById('updFill') || {}).style.width,
    msg: document.querySelector('.updTop span').textContent
  }));
  chk('10. прогресс: 60%, полоса 60%, сообщение докачки', r.pct === '60%' && r.fill === '60%' && /попытка 2/.test(r.msg), r);

  // Installing
  await p.evaluate(() => window.__nativeEvent('update.state', { phase: 'installing', message: 'Устанавливаю… приложение перезапустится' }));
  await sleep(200);
  r = await p.evaluate(() => ({ hint: (document.querySelector('.updHint') || {}).textContent, top: (document.querySelector('.updTop b') || {}).textContent }));
  chk('11. установка: «Не выключайте автомобиль»', r.top === 'Установка' && /Не выключайте/.test(r.hint || ''), r);

  // Error → Повторить
  await p.evaluate(() => window.__nativeEvent('update.state', { phase: 'error', message: 'Связь оборвалась. Нажмите обновление ещё раз.' }));
  await sleep(200);
  r = await p.evaluate(() => ({
    err: (document.getElementById('updHead2') || {}).textContent,
    retry: !!document.getElementById('updRetry'),
    toast: document.querySelector('#toast').textContent
  }));
  chk('12. ошибка: карточка «Не получилось» + текст', /Не получилось/.test(r.err || '') && /Связь оборвалась/.test(r.err || ''), r);
  chk('13. кнопка «Повторить» ведёт на новую проверку', r.retry === true, r);
  const checksBefore = await p.evaluate(() => window.__emits.filter(e => e[0] === 'update.check').length);
  await p.evaluate(() => document.getElementById('updRetry').click());
  await sleep(200);
  r = await p.evaluate(() => ({ n: window.__emits.filter(e => e[0] === 'update.check').length }));
  chk('14. «Повторить» → emit update.check', r.n === checksBefore + 1, { checksBefore, now: r.n });

  // UpToDate
  await p.evaluate(() => window.__nativeEvent('update.state', { phase: 'uptodate', current: '1.0.16' }));
  await sleep(200);
  r = await p.evaluate(() => ({ head: (document.getElementById('updHead') || {}).textContent }));
  chk('15. «У вас последняя версия»', /последняя версия/.test(r.head || ''), r);
  await p.close();

  /* ══ Страница B: браузер (демо макета) ══ */
  const p2 = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  p2.on('pageerror', e => errs.push(e.message));
  await p2.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1200);
  await p2.evaluate(() => select('updates'));
  await sleep(300);
  r = await p2.evaluate(() => ({
    demo: document.body.textContent.indexOf('v1.1.0 доступно') >= 0,
    btn: !!document.getElementById('updInstall')
  }));
  chk('16. браузер: демо-карточка v1.1.0 из макета на месте', r.demo === true && r.btn === true, r);
  await p2.click('#updInstall');
  await sleep(300);
  r = await p2.evaluate(() => ({ prog: !!document.querySelector('.updProg') }));
  chk('17. браузер: демо-анимация установки работает', r.prog === true, r);

  console.log('ИТОГО:', pass, 'OK,', fail, 'FAIL | errors:', errs.length ? errs : 'none');
  await b.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
