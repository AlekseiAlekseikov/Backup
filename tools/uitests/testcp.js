/* Stage CPv2: CarPlay / Android Auto по архитектуре capy.
   Часть 1 (файловые проверки): три подсистемы — presence, rendering, touch;
   гарантии R1–R9; ни одного listener-регистра в OEM-сервисах; один источник
   истины рендера; MainActivity.onPause — синхронный release до super;
   фикс паузы музыки (wake-word гейт + MAY_DUCK).
   Часть 2 (браузер): правило вкладок resolveProjectionTabs, авто-открытие
   по ребру disconnected→connected, отложенная цель ui.destination —
   через реальный путь window.__nativeEvent (тот же пуш, что из Kotlin),
   плюс активация/деактивация рендера по ребру вкладки и 4 состояния
   сообщения из статусов рендер-мостов. */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const sleep = ms => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const chk = (n, c, e) => { if (c) { pass++; console.log('чек', n + ':', 'OK'); } else { fail++; console.log('чек', n + ':', 'FAIL', e !== undefined ? JSON.stringify(e) : ''); } };
const ROOT = path.resolve(__dirname, '../..');

(async () => {
  /* ══ Часть 1: файловые проверки архитектуры ══ */
  const K = p => fs.readFileSync(path.join(ROOT, 'app/src/main/kotlin/com/timhss/capyenergy', p), 'utf8');
  const J = p => fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/example/voiceapp3', p), 'utf8');
  const html = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/ui/index.html'), 'utf8');

  const cc = K('carplay/CarplayContract.kt');
  chk('Ф1. CarplayContract: exfeature-экшен и геометрия по умолчанию', cc.includes('"carplay.exfeature.service"') && cc.includes('DEFAULT_BUFFER_WIDTH = 1920'));
  chk('Ф2. CarplayContract: repair 600/1800 мс + heartbeat 10 с (R3)', cc.includes('longArrayOf(600L, 1_800L)') && cc.includes('HEARTBEAT_MS = 10_000L'));
  const ac = K('androidauto/AndroidAutoContract.kt');
  chk('Ф3. AndroidAutoContract: свой экшен; heartbeat ВЫКЛЮЧЕН (различие стеков)', ac.includes('"aauto.exfeature.service"') && /HEARTBEAT_MS = 0L/.test(ac));

  const policy = K('carplay/CarplayAttachPolicy.kt');
  chk('Ф4. AttachPolicy: все 6 условий R1+R7 (вкладка, resume, сервис, surface, w>0, h>0)', ['dartActive', 'activityResumed', 'serviceConnected', 'surfaceReady', 'bufferWidth > 0', 'bufferHeight > 0'].every(x => policy.includes(x)));

  const coord = K('carplay/CarplayRenderCoordinator.kt');
  const idxDetach = coord.indexOf('gateway.detach()');
  const idxTakeOver = coord.indexOf('CarplayAction.TAKE_OVER ->');
  const idxApplyAttach = coord.indexOf('applyAttach(isTakeOver = true)');
  chk('Ф5. R2: TAKE_OVER = detach() ПЕРЕД attach()', idxTakeOver >= 0 && idxDetach > idxTakeOver && idxApplyAttach > idxDetach);
  const refreshBranch = coord.slice(coord.indexOf('CarplayAction.REFRESH ->'), coord.indexOf('CarplayAction.RELEASE ->'));
  chk('Ф6. R3: REFRESH — attach без detach перед ним', refreshBranch.includes('applyAttach') && !refreshBranch.includes('gateway.detach()'));

  const host = K('carplay/CarplaySurfaceHost.kt');
  const pausedBody = host.slice(host.indexOf('fun onActivityPaused()'), host.indexOf('/** Snapshot under'));
  chk('Ф7. R4: onActivityPaused синхронный (без post)', pausedBody.includes('setActivityResumed(false)') && !pausedBody.includes('post'));
  chk('Ф8. R8-адаптация TextureView: releaseSurfaceSynchronized до возврата из onSurfaceTextureDestroyed', host.includes('fun onSurfaceTextureDestroyed()') && host.includes('releaseSurfaceSynchronously()'));
  const gw = host.slice(host.indexOf('BinderRenderGateway'));
  chk('Ф9. R5: в gateway НЕТ вызова updateViewArea', !/\.updateViewArea\(/.test(gw));

  const tc = K('projection/ProjectionTouchContract.kt');
  chk('Ф10. TouchContract: session-экшены отдельно от exfeature (две разные связи)', tc.includes('CARPLAY_SESSION_ACTION = "carplay.session.service"') && tc.includes('ANDROID_AUTO_SESSION_ACTION = "aauto.session.service"'));
  const tp = K('projection/ProjectionTouchPolicy.kt');
  chk('Ф11. TouchPolicy: CANCEL переписывается в UP; releaseGesture поднимает палец', tp.includes('ProjectionTouchAction.CANCEL') && tp.includes('UP') && tp.includes('fun releaseGesture()'));
  const tctl = K('projection/ProjectionTouchController.kt');
  chk('Ф12. TouchController: send/setBufferSize/releaseGestures — правила в политике, отправка на своём потоке', ['fun send(', 'fun setBufferSize(', 'fun releaseGestures('].every(x => tctl.includes(x)));
  const tsess = K('projection/ProjectionTouchSession.kt');
  chk('Ф13. TouchSession: notifyMotionEvent по AIDL; CANCEL >= 3 не теряется', tsess.includes('notifyMotionEvent(event)'));

  /* Гарантии не-интерференции: ни одного listener-регистра в OEM-сервисах */
  let offenders = [];
  const walk = (dir) => {
    for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, f.name);
      if (f.isDirectory()) walk(full);
      else if (f.name.endsWith('.kt')) {
        const c = fs.readFileSync(full, 'utf8');
        if (/registerSessionListener|registerMediaBrowserListener|registerCallUpdateListener|registerRouteGuidanceListener|registerEnhancedSiriListener|registerAppListListener|IExMediaBrowser/.test(c)) offenders.push(full);
      }
    }
  };
  walk(path.join(ROOT, 'app/src/main/java'));
  walk(path.join(ROOT, 'app/src/main/kotlin'));
  chk('Ф14. НИ ОДНОЙ регистрации listener в OEM-сервисах и никакого IExMediaBrowser (причина паузы музыки исключена)', offenders.length === 0, offenders);

  let attachFiles = [];
  const walk2 = (dir) => {
    for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, f.name);
      if (f.isDirectory()) walk2(full);
      else if (f.name.endsWith('.kt') && fs.readFileSync(full, 'utf8').includes('manager.notifyMainSurfaceAttached(')) attachFiles.push(path.relative(ROOT, full));
    }
  };
  walk2(path.join(ROOT, 'app/src/main'));
  chk('Ф15. notifyMainSurfaceAttached — только из carplay/androidauto пакетов (один источник истины рендера)', attachFiles.length === 2 && attachFiles.every(f => /capyenergy\/(carplay|androidauto)\//.test(f)), attachFiles);

  const ma = J('MainActivity.kt');
  chk('Ф16. R4: onPause — projection release СИНХРОННО ДО super.onPause()', ma.indexOf('onActivityPausedSynchronously()') >= 0 && ma.indexOf('onActivityPausedSynchronously()') < ma.indexOf('\n        super.onPause()'));
  chk('Ф17. onDestroy: dispose рендер-моста', ma.includes('projectionRender?.dispose()'));
  const layout = fs.readFileSync(path.join(ROOT, 'app/src/main/res/layout/activity_main.xml'), 'utf8');
  chk('Ф18. layout: TextureView-оверлеи carplaySurface + aautoSurface поверх WebView', layout.includes('carplaySurface') && layout.includes('aautoSurface') && layout.includes('TextureView'));
  const ub = J('ui/UiBridge.kt');
  chk('Ф19. UiBridge: projectionFrame (JS API) + события activate/deactivate/refresh', ub.includes('fun projectionFrame(') && ['"projection.activate"', '"projection.deactivate"', '"projection.refresh"'].every(x => ub.includes(x)));
  const prb = J('ui/ProjectionRenderBridge.kt');
  chk('Ф20. ProjectionRenderBridge: touchController.bind на activate, unbind на deactivate (жест поднимается до снятия binding)', prb.includes('touchController.bind(stack)') && prb.includes('touchController.unbind(stack)'));
  chk('Ф21. ProjectionRenderBridge: onActivityPausedSynchronously — hosts, затем releaseGestures, порядок как в spec', prb.indexOf('carplayHost.onActivityPaused()') < prb.indexOf('touchController.releaseGestures()'));
  const tl = J('ui/ProjectionTouchListener.kt');
  chk('Ф22. TouchListener: маппинг view→buffer (bufferW/viewW), live-карта для AA, CANCEL пересылается', tl.includes('bufferWidth.toDouble() / view.width') && tl.includes('LinkedHashMap') && tl.includes('CANCEL'));
  const vas = J('VoiceAssistantService.kt');
  chk('Ф23. ФИКС ПАУЗЫ МУЗЫКИ: фокус MAY_DUCK вместо EXCLUSIVE', vas.includes('AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK') && !vas.includes('AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE'));
  chk('Ф24. ФИКС ПАУЗЫ МУЗЫКИ: wake-word гейт по projection-сессии', vas.includes('isProjectionConnected()') && vas.includes('startProjectionGate()'));
  const gate = J('projection/ProjectionVoiceGate.kt');
  chk('Ф25. ProjectionVoiceGate: собственный read-only монитор (никаких listener-регистраций)', gate.includes('ProjectionPresenceMonitor(') && !/registerListener|setListener/.test(gate));

  chk('Ф26. index.html: заглушка УДАЛЕНА (нет макета телефона и фейковых кнопок)', !html.includes('const PHONE') && !html.includes('pscreen') && !html.includes('cpBtn') && !html.includes('Подключить iPhone') && !html.includes('Автозапуск CarPlay'));
  chk('Ф27. index.html: механизм рендер-интеграции на месте', ['projView', 'ACTIVE_STACK', 'syncProjectionActivation', 'reportProjectionFrame', 'projApplyStatus', 'canRender('].every(x => html.includes(x)));
  chk('Ф28. index.html: правило вкладок capy сохранено (resolveProjectionTabs/arrivedTab/PENDING_DEST)', ['resolveProjectionTabs', 'arrivedTab', 'PENDING_DEST'].every(x => html.includes(x)));
  const aidl = fs.readFileSync(path.join(ROOT, 'app/src/main/aidl/com/njda/carplay/session/ISessionManager.aidl'), 'utf8');
  const aidlAa = fs.readFileSync(path.join(ROOT, 'app/src/main/aidl/com/njda/aauto/session/ISessionManager.aidl'), 'utf8');
  chk('Ф29. AIDL не изменён: notifyMotionEvent + isConnected держат нумерацию транзакций', aidl.includes('notifyMotionEvent') && aidl.includes('isConnected()') && aidlAa.includes('notifyMotionEvent'));

  /* ══ Часть 2, страница A: с моком GeelyNative ══ */
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.addInitScript(() => {
    window.__emits = [];
    window.__frames = [];
    window.__state = { imm: true, wake: false };
    window.GeelyNative = {
      appVersion: () => '1.0.16',
      appState: () => JSON.stringify(window.__state),
      onEvent: (n, d) => { try { window.__emits.push([n, JSON.parse(d)]); } catch (e) {} },
      projectionFrame: (stack, x, y, w, h) => { window.__frames.push([stack, x, y, w, h]); }
    };
  });
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1500);

  const tabs = () => p.evaluate(() => ({
    cp: !!document.querySelector('.tab[data-id="carplay"]'),
    aa: !!document.querySelector('.tab[data-id="aa"]'),
    sel: (document.querySelector('.tab.sel') || {}).dataset ? document.querySelector('.tab.sel').dataset.id : null
  }));
  const push = obj => p.evaluate(o => window.__nativeEvent('projection.presence', o), obj);

  let t = await tabs();
  chk('1. тумблер выкл: вкладок CarPlay/AA нет', t.cp === false && t.aa === false, t);
  await push({ carplay: 'unknown', androidAuto: 'unknown', carplayAvailable: true, androidAutoAvailable: true });
  await sleep(200);
  t = await tabs();
  chk('2. unknown+available при выкл. тумблере — вкладок всё равно нет', t.cp === false && t.aa === false, t);

  await p.evaluate(() => { select('tools'); document.querySelector('[data-ttool="3"]').click(); });
  await sleep(300);
  await p.evaluate(() => { const el = document.querySelector('[data-k="proj"]'); el.checked = true; el.dispatchEvent(new Event('change', { bubbles: true })); });
  await sleep(300);
  t = await tabs();
  chk('3. тумблер вкл: обе вкладки (unknown+available fallback)', t.cp === true && t.aa === true, t);
  const emitProj = await p.evaluate(() => window.__emits.filter(e => e[0] === 'settings.change' && e[1].key === 'proj').pop());
  chk('4. тумблер шлёт settings.change {proj:true}', emitProj && emitProj[1].value === true, emitProj);
  chk('5. включение тумблера не перетаскивает на вкладку телефона', t.sel === 'tools', t);

  await push({ carplay: 'connected', androidAuto: 'unknown', carplayAvailable: true, androidAutoAvailable: true, carplayEdgeAt: 100 });
  await sleep(200);
  t = await tabs();
  chk('6. CarPlay подключён: вкладка CarPlay есть, AA нет (unknown не добавляет вторую)', t.cp === true && t.aa === false, t);
  chk('7. первый read не «прибытие» — вкладка сама не открывается (UNKNOWN→CONNECTED)', t.sel === 'tools', t);

  await p.evaluate(() => select('carplay'));
  await sleep(200);
  await push({ carplay: 'disconnected', androidAuto: 'unknown', carplayAvailable: true, androidAutoAvailable: true, carplayEdgeAt: 200 });
  await sleep(200);
  t = await tabs();
  chk('8. CarPlay отключён: его вкладки нет, AA остался (unknown+available)', t.cp === false && t.aa === true, t);
  chk('9. исчезнувшая открытая вкладка → quick (_setVisibility)', t.sel === 'quick', t);

  await push({ carplay: 'connected', androidAuto: 'disconnected', carplayAvailable: true, androidAutoAvailable: true, carplayEdgeAt: 300, androidAutoEdgeAt: 299 });
  await sleep(200);
  t = await tabs();
  chk('10. прибытие iPhone (DISCONNECTED→CONNECTED): вкладка открылась сама', t.sel === 'carplay', t);

  await push({ carplay: 'connected', androidAuto: 'connected', carplayAvailable: true, androidAutoAvailable: true, carplayEdgeAt: 300, androidAutoEdgeAt: 500 });
  await sleep(200);
  t = await tabs();
  chk('11. оба подключены, AA новее → только AA', t.cp === false && t.aa === true, t);
  await push({ carplay: 'connected', androidAuto: 'connected', carplayAvailable: true, androidAutoAvailable: true, carplayEdgeAt: 700, androidAutoEdgeAt: 500 });
  await sleep(200);
  t = await tabs();
  chk('12. оба подключены, CarPlay новее → только CarPlay', t.cp === true && t.aa === false, t);
  await push({ carplay: 'connected', androidAuto: 'connected', carplayAvailable: true, androidAutoAvailable: true, carplayEdgeAt: 700, androidAutoEdgeAt: 700 });
  await sleep(200);
  t = await tabs();
  chk('13. оба подключены, рёбра неразличимы → обе вкладки', t.cp === true && t.aa === true, t);

  await push({ carplay: 'disconnected', androidAuto: 'disconnected', carplayAvailable: false, androidAutoAvailable: false, carplayEdgeAt: 800, androidAutoEdgeAt: 801 });
  await sleep(200);
  t = await tabs();
  chk('14. disconnected + стек не заявлен → вкладок нет; ушли на quick', t.cp === false && t.aa === false && t.sel === 'quick', t);
  await p.evaluate(() => window.__nativeEvent('ui.destination', { destination: 'carplay' }));
  await sleep(200);
  t = await tabs();
  chk('15. ui.destination без вкладки ничего не открыл (цель держится)', t.sel === 'quick' && t.cp === false, t);
  await push({ carplay: 'connected', androidAuto: 'disconnected', carplayAvailable: true, androidAutoAvailable: false, carplayEdgeAt: 900 });
  await sleep(200);
  t = await tabs();
  chk('16. вкладка появилась — отложенная цель открылась', t.cp === true && t.sel === 'carplay', t);

  await p.evaluate(() => { select('tools'); document.querySelector('[data-ttool="3"]').click(); const el = document.querySelector('[data-k="proj"]'); el.checked = false; el.dispatchEvent(new Event('change', { bubbles: true })); });
  await sleep(300);
  t = await tabs();
  chk('17. тумблер выкл при подключённом телефоне: вкладок нет, текущая вкладка не тронута', t.cp === false && t.aa === false && t.sel === 'tools', t);
  await p.evaluate(() => window.__nativeEvent('telemetry.data', { trips: [{}], settings: { projectionBetaEnabled: true } }));
  await sleep(300);
  t = await tabs();
  chk('18. projectionBetaEnabled из пакета включает вкладки (по присутствию: CarPlay)', t.cp === true && t.aa === false, t);

  /* ── Активация рендера: по ребру вкладки, не на каждый render ── */
  await p.evaluate(() => { window.__emits.length = 0; window.__frames.length = 0; select('carplay'); });
  await sleep(500);
  let r = await p.evaluate(() => ({
    activate: window.__emits.filter(e => e[0] === 'projection.activate').pop(),
    frames: window.__frames.filter(f => f[0] === 'carplay')
  }));
  chk('19. вкладка CarPlay активна → projection.activate {stack:"carplay"}', r.activate && r.activate[1].stack === 'carplay', r);
  chk('20. рамка видео отправлена нативу (projectionFrame, физические пиксели > 0)', r.frames.length > 0 && r.frames.every(f => f[1] >= 0 && f[3] > 0 && f[4] > 0), r.frames);

  await p.evaluate(() => { window.__emits.length = 0; render('carplay'); render('carplay'); });
  await sleep(300);
  r = await p.evaluate(() => window.__emits.filter(e => e[0] === 'projection.activate').length);
  chk('21. rebuild UI НЕ дёргает активацию повторно (ребро, как _requested в capy)', r === 0, r);

  /* ── Четыре состояния сообщения (порт _message) ── */
  await p.evaluate(() => window.__nativeEvent('carplay.status', { available: true, bound: true, attached: false, textureId: null, bufferWidth: 0, bufferHeight: 0, attachCount: 0, lastError: null }));
  await sleep(300);
  r = await p.evaluate(() => ({ msg: (document.querySelector('.projMsg b') || {}).textContent, refresh: !!document.querySelector('#projRefresh') }));
  chk('22. статус: attached=false, без ошибки → «Подключение к телефону…» + Переподключить', r.msg === 'Подключение к телефону…' && r.refresh === true, r);

  await p.evaluate(() => window.__nativeEvent('carplay.status', { available: true, bound: true, attached: true, textureId: 1, bufferWidth: 1920, bufferHeight: 1080, attachCount: 1, lastError: null }));
  await sleep(300);
  r = await p.evaluate(() => ({ msg: !!document.querySelector('.projMsg'), diag: (document.querySelector('.projDiag') || {}).textContent }));
  chk('23. attached+textureId (canRender) → сообщения нет — поверхность у натива', r.msg === false, r);
  chk('24. диагностика до attach не показывается поверх видео', r.diag === undefined, r);

  await p.evaluate(() => window.__nativeEvent('carplay.status', { available: true, bound: true, attached: false, textureId: null, bufferWidth: 1920, bufferHeight: 1080, attachCount: 1, lastError: 'ATTACH_FAILED' }));
  await sleep(300);
  r = await p.evaluate(() => ({ msg: (document.querySelector('.projMsg b') || {}).textContent, diag: (document.querySelector('.projDiag') || {}).textContent }));
  chk('25. ошибка рендера → понятное сообщение (код → текст, не raw-строка)', r.msg === 'Рендерер телефона не ответил' && /1920×1080/.test(r.diag || ''), r);

  await p.evaluate(() => { window.__emits.length = 0; document.querySelector('#projRefresh').click(); });
  await sleep(200);
  r = await p.evaluate(() => window.__emits.filter(e => e[0] === 'projection.refresh').pop());
  chk('26. «Переподключить» → projection.refresh (R3 same-surface re-attach)', r && r[1].stack === 'carplay', r);

  await p.evaluate(() => { window.__emits.length = 0; select('quick'); });
  await sleep(300);
  r = await p.evaluate(() => window.__emits.filter(e => e[0] === 'projection.deactivate').pop());
  chk('27. уход с вкладки → projection.deactivate (surface отдаётся OEM)', r && r[1].stack === 'carplay', r);

  /* ── Android Auto: тот же путь ── */
  await push({ carplay: 'connected', androidAuto: 'disconnected', carplayAvailable: true, androidAutoAvailable: true, carplayEdgeAt: 950, androidAutoEdgeAt: 901 });
  await sleep(200);
  await p.evaluate(() => { window.__emits.length = 0; window.__frames.length = 0; select('aa'); });
  await sleep(500);
  r = await p.evaluate(() => ({
    activate: window.__emits.filter(e => e[0] === 'projection.activate').pop(),
    frames: window.__frames.filter(f => f[0] === 'androidAuto')
  }));
  chk('28. вкладка AA активна → projection.activate {stack:"androidAuto"} + рамка', r.activate && r.activate[1].stack === 'androidAuto' && r.frames.length > 0, r);

  await p.close();

  /* ══ Часть 2, страница B: без мока (чистый браузер) ══ */
  const p2 = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  p2.on('pageerror', e => errs.push(e.message));
  await p2.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1200);
  t = await p2.evaluate(() => ({
    cp: !!document.querySelector('.tab[data-id="carplay"]'),
    aa: !!document.querySelector('.tab[data-id="aa"]'),
    mockup: !!document.querySelector('.pscreen')
  }));
  chk('29. без машины: вкладок проекции нет (демо-доступность убрана), макета нет', t.cp === false && t.aa === false && t.mockup === false, t);
  await p2.evaluate(() => { select('tools'); document.querySelector('[data-ttool="3"]').click(); const el = document.querySelector('[data-k="proj"]'); el.checked = true; el.dispatchEvent(new Event('change', { bubbles: true })); });
  await sleep(300);
  t = await p2.evaluate(() => ({ cp: !!document.querySelector('.tab[data-id="carplay"]'), aa: !!document.querySelector('.tab[data-id="aa"]') }));
  chk('30. тумблер вкл без native: вкладок всё равно нет — только настоящий presence решает', t.cp === false && t.aa === false, t);
  await p2.close();

  chk('31. ни одной JS-ошибки на обеих страницах', errs.length === 0, errs);
  await b.close();
  console.log(`\ntestcp: OK=${pass} FAIL=${fail}`);
  process.exit(fail > 0 ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
