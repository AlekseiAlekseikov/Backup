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
  await p.addInitScript(() => {
    window.__emits = [];
    window.GeelyNative = { onEvent: (n, d) => { try { window.__emits.push([n, JSON.parse(d)]); } catch (e) {} } };
  });
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1200);
  const emits = () => p.evaluate(() => window.__emits.filter(e => e[0] !== 'toast'));
  const last = async () => (await emits()).slice(-1)[0];

  const quick = await p.$('.tab[data-id="quick"]');
  chk('панель Быстрый доступ в доке', !!quick);
  await p.click('.tab[data-id="quick"]');
  await sleep(600);

  await p.click('.mode[data-v="Эко"]');
  chk('drive.mode Эко', JSON.stringify(await last()) === JSON.stringify(['drive.mode', { mode: 'Эко' }]), await last());

  const recupBtns = await p.$$('.seg[data-g="r"] button');
  chk('рекуперация: 3 кнопки', recupBtns.length === 3, recupBtns.length);
  await recupBtns[2].click();
  chk('drive.recup Высокая', JSON.stringify(await last()) === JSON.stringify(['drive.recup', { value: 'Высокая' }]), await last());

  await p.click('.air[data-v="Ноги"]');
  chk('climate.air Ноги', JSON.stringify(await last()) === JSON.stringify(['climate.air', { value: 'Ноги', on: true }]), await last());
  await p.click('.air[data-v="Ноги+Стекло"]');
  chk('climate.air Ноги+Стекло', JSON.stringify(await last()) === JSON.stringify(['climate.air', { value: 'Ноги+Стекло', on: true }]), await last());

  await p.click('.air.tog[data-v="Рециркуляция"]');
  chk('climate.recirc вкл', JSON.stringify(await last()) === JSON.stringify(['climate.recirc', { value: 'Рециркуляция', on: true }]), await last());
  await p.click('.air.tog[data-v="Рециркуляция"]');
  chk('climate.recirc выкл', (await last())[1].on === false, await last());

  await p.click('.heat[data-v="Руль"]');
  chk('climate.heat Руль on', JSON.stringify(await last()) === JSON.stringify(['climate.heat', { name: 'Руль', on: true }]), await last());
  await p.click('.heat[data-v="Руль"]');
  chk('climate.heat Руль off', (await last())[1].on === false, await last());
  await p.click('.heat[data-v="Лобовое стекло"]');
  chk('climate.heat Лобовое', (await last())[1].name === 'Лобовое стекло' && (await last())[1].on === true, await last());
  await p.click('.heat[data-v="Заднее стекло"]');
  chk('climate.heat Заднее', (await last())[1].name === 'Заднее стекло', await last());
  await p.click('.heat[data-v="Стекло Макс"]');
  chk('climate.heat Стекло Макс', (await last())[1].name === 'Стекло Макс', await last());

  await p.click('.seat[data-v="Сиденье водителя"]');
  chk('climate.seat ур.1', JSON.stringify(await last()) === JSON.stringify(['climate.seat', { name: 'Сиденье водителя', level: 1 }]), await last());
  await p.click('.seat[data-v="Сиденье водителя"]');
  chk('climate.seat ур.2', (await last())[1].level === 2, await last());
  await p.click('.seat[data-v="Сиденье водителя"]');
  await p.click('.seat[data-v="Сиденье водителя"]');
  chk('climate.seat выкл (0)', (await last())[1].level === 0, await last());
  await p.click('.seat[data-v="Сиденье пассажира"]');
  chk('climate.seat пассажир', (await last())[1].name === 'Сиденье пассажира', await last());

  const dot = await p.$('.dot[data-c="beige"]');
  if (dot) { await dot.click(); chk('car.color', (await last())[0] === 'car.color' && (await last())[1].color === 'beige', await last()); }
  else chk('car.color (точка найдена)', false, 'нет .dot');

  // панель «Голос»: микрофон и перезапуск
  await p.click('.tab[data-id="voice"]');
  await sleep(600);
  const mic = await p.$('#micBtn');
  if (mic) { await mic.click(); chk('voice.request', (await last())[0] === 'voice.request', await last()); }
  else chk('voice.request (#micBtn)', false, 'нет #micBtn');
  const rs = await p.$('#micRestart');
  if (rs) { await rs.click(); chk('voice.micRestart', (await last())[0] === 'voice.micRestart', await last()); }
  else chk('voice.micRestart (#micRestart)', false, 'нет #micRestart');

  const all = await emits();
  chk('все эмиты — ожидаемые типы', all.every(e => ['ui.ready','telemetry.request','tab.change','drive.mode','drive.recup','climate.air','climate.recirc','climate.heat','climate.seat','car.color','voice.request','voice.micRestart'].includes(e[0])), all.map(e => e[0]));
  console.log('ИТОГО:', pass, 'OK,', fail, 'FAIL | page errors:', errs.length ? errs : 'none');
  await b.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
