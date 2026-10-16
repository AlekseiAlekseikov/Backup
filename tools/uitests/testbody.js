/* Капот/багажник: открытие сразу по нажатию, без подтверждения */
const { chromium } = require('playwright-core');
const sleep = ms => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const chk = (n, c, e) => { if (c) { pass++; console.log('чек', n + ':', 'OK'); } else { fail++; console.log('чек', n + ':', 'FAIL', e !== undefined ? JSON.stringify(e) : ''); } };
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.addInitScript(() => {
    window.__emits = [];
    window.GeelyNative = { onEvent: (n, d) => { try { window.__emits.push([n, JSON.parse(d)]); } catch (e) {} } };
  });
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1500);

  // капот
  await p.click('#coHood');
  await sleep(400);
  let r = await p.evaluate(() => ({
    modal: !!document.querySelector('.mBack'),
    emit: window.__emits.filter(e => e[0] === 'car.hood').pop()
  }));
  chk('капот: команда сразу (car.hood open:true)', r.emit && r.emit[1].open === true, r);
  chk('капот: модалки нет', r.modal === false, r);

  // багажник
  await p.click('#coTrunk');
  await sleep(400);
  r = await p.evaluate(() => ({
    modal: !!document.querySelector('.mBack'),
    emit: window.__emits.filter(e => e[0] === 'car.trunk').pop()
  }));
  chk('багажник: команда сразу (car.trunk open:true)', r.emit && r.emit[1].open === true, r);
  chk('багажник: модалки нет', r.modal === false, r);

  console.log('ИТОГО:', pass, 'OK,', fail, 'FAIL | errors:', errs.length ? errs : 'none');
  await b.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
