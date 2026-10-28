/* Stage SCR: иконка/название GeelyTools + тумблеры «Полный экран» (порт capy
   applyImmersiveMode) и «Запуск при пробуждении». Файловые проверки — по
   файлам проекта (fs), поведение тумблеров — в браузере. */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const sleep = ms => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const chk = (n, c, e) => { if (c) { pass++; console.log('чек', n + ':', 'OK'); } else { fail++; console.log('чек', n + ':', 'FAIL', e !== undefined ? JSON.stringify(e) : ''); } };
const ROOT = path.resolve(__dirname, '../..');

(async () => {
  /* ══ Файловые проверки ══ */
  const strings = fs.readFileSync(path.join(ROOT, 'app/src/main/res/values/strings.xml'), 'utf8');
  chk('1. app_name = GeelyTools', strings.includes('>GeelyTools</string>'), strings.match(/app_name[^<]*/));
  chk('2. VoiceApp3 больше нет в strings', !strings.includes('VoiceApp3'));

  const manifest = fs.readFileSync(path.join(ROOT, 'app/src/main/AndroidManifest.xml'), 'utf8');
  chk('3. иконка приложения = @mipmap/ic_launcher', manifest.includes('android:icon="@mipmap/ic_launcher"'));
  chk('4. label приложения = @string/app_name (GeelyTools)', /<application[\s\S]*?android:label="@string\/app_name"/.test(manifest));
  chk('5. label MainActivity = @string/app_name', /android:name="\.MainActivity"[\s\S]{0,80}android:label="@string\/app_name"/.test(manifest.replace(/\n\s+/g, ' ')));
  chk('6. «Voice App» в манифесте больше нет', !manifest.includes('"Voice App"'));

  const iconSizes = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
  let iconsOk = true, iconErr = '';
  for (const [dpi, size] of Object.entries(iconSizes)) {
    const f = path.join(ROOT, `app/src/main/res/mipmap-${dpi}/ic_launcher.png`);
    if (!fs.existsSync(f)) { iconsOk = false; iconErr += ` ${dpi}:нет`; continue; }
    const buf = fs.readFileSync(f);
    const w = buf.readUInt32BE(16), h = buf.readUInt32BE(20);
    if (w !== size || h !== size) { iconsOk = false; iconErr += ` ${dpi}:${w}x${h}`; }
  }
  chk('7. mipmap ic_launcher во всех 5 плотностях (48/72/96/144/192)', iconsOk, iconErr);
  chk('8. иконка непустая (xxxhdpi > 30 КБ)', fs.statSync(path.join(ROOT, 'app/src/main/res/mipmap-xxxhdpi/ic_launcher.png')).size > 30000);

  const ub = fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/example/voiceapp3/ui/UiBridge.kt'), 'utf8');
  chk('9. ключ immersive как в capy (app_immersive_mode_enabled)', ub.includes('"app_immersive_mode_enabled"'));
  chk('10. ключ запуска при пробуждении (app_launch_on_wake_enabled)', ub.includes('"app_launch_on_wake_enabled"'));
  chk('11. appState() отдаёт imm/wake в JS', /fun appState/.test(ub) && /addProperty\("imm"/.test(ub) && /addProperty\("wake"/.test(ub));
  chk('12. settings.change: imm применяет режим живьём (setImmersive)', /"imm" ->[\s\S]{0,400}setImmersive\(value\)/.test(ub));

  const ma = fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/example/voiceapp3/MainActivity.kt'), 'utf8');
  chk('13. onCreate применяет immersive из persistence (как main() в capy)', ma.includes('app_immersive_mode_enabled'));
  chk('14. onResume переприменяет (как didChangeAppLifecycleState в capy)', /onResume\(\)[\s\S]{0,400}applyImmersive\(\)/.test(ma));
  chk('15. флаг isResumed для wake-приёмника', ma.includes('var isResumed'));

  const svc = fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt'), 'utf8');
  chk('16. приёмник SCREEN_ON + USER_PRESENT в сервисе', svc.includes('Intent.ACTION_SCREEN_ON') && svc.includes('Intent.ACTION_USER_PRESENT'));
  chk('17. приёмник не поднимает уже открытое приложение (isResumed)', /launchIfWakeEnabled[\s\S]{0,300}MainActivity\.isResumed/.test(svc));
  chk('18. очистка приёмника в cleanup()', /screenWakeReceiver\?\.let \{ unregisterReceiver\(it\) \}/.test(svc));

  const html = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/ui/index.html'), 'utf8');
  chk('18b. топбар приложения не скрывается полным экраном (notop/applyTopbar удалены)', !html.includes('notop') && !html.includes('applyTopbar'));

  /* ══ UI: тумблеры ══ */
  const b = await chromium.launch();
  const errs = [];
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  p.on('pageerror', e => errs.push(e.message));
  await p.addInitScript(() => {
    window.__emits = [];
    window.__state = { imm: true, wake: true };
    window.GeelyNative = {
      appVersion: () => '1.0.16',
      appState: () => JSON.stringify(window.__state),
      onEvent: (n, d) => { try { window.__emits.push([n, JSON.parse(d)]); } catch (e) {} }
    };
  });
  await p.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1500);

  // Инициализация из persistence
  let r = await p.evaluate(() => ({ imm: SET.imm, wake: SET.wake, topbarH: (document.querySelector('.topbar') || { offsetHeight: 0 }).offsetHeight, notop: document.body.classList.contains('notop') }));
  chk('19. инициализация из appState: SET.imm=true, SET.wake=true', r.imm === true && r.wake === true, r);
  chk('20. при полном экране топбар приложения остаётся (capy: прячутся только системные панели)', r.topbarH > 0 && !r.notop, r);

  // Инструменты → Экран: оба тумблера, оба включены
  await p.evaluate(() => { select('tools'); document.querySelector('[data-ttool="1"]').click(); });
  await sleep(300);
  r = await p.evaluate(() => {
    const g = k => { const el = document.querySelector(`[data-k="${k}"]`); return el ? { checked: el.checked, text: el.closest('.trow').textContent } : null; };
    return { imm: g('imm'), wake: g('wake') };
  });
  chk('21. тумблер «Полный экран» на Инструменты→Экран, включён', r.imm && r.imm.checked === true && /Полный экран/.test(r.imm.text), r);
  chk('22. тумблер «Запуск при пробуждении» на Инструменты→Экран, включён', r.wake && r.wake.checked === true && /Запуск при пробуждении/.test(r.wake.text), r);

  // Переключения шлют settings.change
  await p.evaluate(() => { const el = document.querySelector('[data-k="imm"]'); el.checked = false; el.dispatchEvent(new Event('change', { bubbles: true })); });
  await sleep(200);
  await p.evaluate(() => { const el = document.querySelector('[data-k="wake"]'); el.checked = false; el.dispatchEvent(new Event('change', { bubbles: true })); });
  await sleep(200);
  r = await p.evaluate(() => ({
    imm: window.__emits.filter(e => e[0] === 'settings.change' && e[1].key === 'imm').pop(),
    wake: window.__emits.filter(e => e[0] === 'settings.change' && e[1].key === 'wake').pop(),
    topbarH: (document.querySelector('.topbar') || { offsetHeight: 0 }).offsetHeight
  }));
  chk('23. «Полный экран» выкл → settings.change {imm:false}', r.imm && r.imm[1].value === false, r);
  chk('24. топбар остаётся видимым и после переключения (notop-логика удалена)', r.topbarH > 0, r);
  chk('25. «Запуск при пробуждении» выкл → settings.change {wake:false}', r.wake && r.wake[1].value === false, r);
  await p.close();

  /* Браузер без native: тумблеры рисуются выключенными, работают локально */
  const p2 = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  p2.on('pageerror', e => errs.push(e.message));
  await p2.goto('http://localhost:8080/', { waitUntil: 'load' });
  await sleep(1200);
  await p2.evaluate(() => { select('tools'); document.querySelector('[data-ttool="1"]').click(); });
  await sleep(300);
  r = await p2.evaluate(() => {
    const imm = document.querySelector('[data-k="imm"]');
    const wake = document.querySelector('[data-k="wake"]');
    return { immOff: imm && !imm.checked, wakeOff: wake && !wake.checked, wakeThere: !!wake };
  });
  chk('26. без native: оба тумблера есть, по умолчанию выключены', r.immOff === true && r.wakeOff === true && r.wakeThere === true, r);

  console.log('ИТОГО:', pass, 'OK,', fail, 'FAIL | errors:', errs.length ? errs : 'none');
  await b.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
