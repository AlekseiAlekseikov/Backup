/* Stage VO3: гибрид. На вкладке «Голос» voice.ui анимирует микрофон (плашка не нужна),
   вне вкладки сервис показывает нативную плашку (браузером не проверяется — только JS-часть). */
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
  await p.addInitScript(() => { window.GeelyNative = { onEvent: (n, d) => {} }; });
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1500);
  const ev = (name, data) => p.evaluate(([n, d]) => window.__nativeEvent(n, d), [name, data]);

  // 1) НЕ на вкладке Голос: события voice.ui безвредно игнорируются (плашка — нативная)
  await p.click('.tab[data-id="telemetry"]');
  await sleep(600);
  let r = await p.evaluate(() => typeof curId !== 'undefined' ? curId : null);
  chk('старт: открыта телеметрия', r === 'telemetry', r);
  await ev('voice.ui', { phase: 'listen' });
  await sleep(300);
  r = await p.evaluate(() => ({ tab: curId, mic: !!document.getElementById('micBtn') }));
  chk('вне вкладки: вкладка не переключилась', r.tab === 'telemetry' && r.mic === false, r);

  // 2) На вкладке Голос: listen → анимация как в макете
  await p.click('.tab[data-id="voice"]');
  await sleep(600);
  await ev('voice.ui', { phase: 'listen' });
  await sleep(600);
  r = await p.evaluate(() => ({
    live: document.getElementById('micBtn').classList.contains('live'),
    state: document.getElementById('voiceState').textContent,
    eq: document.getElementById('voiceEq').classList.contains('on'),
    rings: getComputedStyle(document.getElementById('micBtn'), '::before').animationName,
    dur: getComputedStyle(document.getElementById('micBtn'), '::before').animationDuration
  }));
  chk('listen: микрофон live', r.live === true, r);
  chk('listen: «Слушаю…»', r.state === 'Слушаю…', r.state);
  chk('listen: эквалайзер вкл', r.eq === true, r);
  chk('listen: кольца 1.6s', r.rings === 'ring' && r.dur === '1.6s', r);
  await p.screenshot({ path: '/tmp/vo3-listen.png' });

  // 3) status: «Привет» → «Слушаю…», partial → текст
  await ev('voice.ui', { phase: 'status', text: 'Привет' });
  await sleep(200);
  chk('status Привет → «Слушаю…»', await p.evaluate(() => document.getElementById('voiceState').textContent === 'Слушаю…'));
  await ev('voice.ui', { phase: 'status', text: 'открой бага' });
  await sleep(200);
  chk('status partial → текст', await p.evaluate(() => document.getElementById('voiceState').textContent === 'открой бага'));

  // 4) result → «Жду обращения» + карточка; idle → карточка остаётся
  await ev('voice.ui', { phase: 'result', phrase: 'открой багажник', reply: 'Готово' });
  await sleep(300);
  r = await p.evaluate(() => ({
    live: document.getElementById('micBtn').classList.contains('live'),
    eq: document.getElementById('voiceEq').classList.contains('on'),
    state: document.getElementById('voiceState').textContent,
    resp: document.getElementById('voiceResp').innerText.replace(/\n/g, ' | '),
    on: document.getElementById('voiceResp').classList.contains('on')
  }));
  chk('result: микрофон погас', r.live === false && r.eq === false, r);
  chk('result: «Жду обращения»', r.state === 'Жду обращения', r.state);
  chk('result: карточка «открой багажник» | Готово', r.on && r.resp.includes('открой багажник') && r.resp.includes('Готово'), r.resp);
  await p.screenshot({ path: '/tmp/vo3-result.png' });
  await ev('voice.ui', { phase: 'idle' });
  await sleep(200);
  chk('idle: карточка остаётся', await p.evaluate(() => document.getElementById('voiceResp').classList.contains('on')));

  // 5) повторный listen скрывает карточку
  await ev('voice.ui', { phase: 'listen' });
  await sleep(300);
  chk('повтор: карточка скрыта', await p.evaluate(() => !document.getElementById('voiceResp').classList.contains('on')));
  await ev('voice.ui', { phase: 'idle' });
  await sleep(150);

  // 6) клик по микрофону при нативном мосте: демо не играет (анимацию ведёт сервис)
  await p.click('#micBtn');
  await sleep(400);
  r = await p.evaluate(() => ({
    live: document.getElementById('micBtn').classList.contains('live'),
    state: document.getElementById('voiceState').textContent
  }));
  chk('клик при нативе: демо не играет', r.live === false && r.state === 'Жду обращения', r);

  // 7) браузер без моста: демо живо
  const p2 = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  await p2.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1200);
  await p2.click('.tab[data-id="voice"]');
  await sleep(500);
  await p2.click('#micBtn');
  await sleep(500);
  r = await p2.evaluate(() => ({ live: document.getElementById('micBtn').classList.contains('live'), state: document.getElementById('voiceState').textContent }));
  chk('браузер: демо-анимация жива', r.live === true && r.state === 'Слушаю…', r);

  console.log('ИТОГО:', pass, 'OK,', fail, 'FAIL | page errors:', errs.length ? errs : 'none');
  await b.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
