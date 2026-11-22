// e2e-harbour.mp4, recorded by Chromium itself from a canvas: 64x48, VP9 in
// mp4, a second and a half of changing colour. VP9 and not H.264 because
// Playwright's Chromium cannot play H.264 (no proprietary codecs:
// canPlayType('video/mp4; codecs="avc1.42E01E"') is ""), and the box has no
// ffmpeg. An mp4 all the same, which is what the viewer goes by.
//
//   node test/e2e/fixtures/record-mp4.js test/e2e/fixtures/e2e-harbour.mp4
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  const b64 = await p.evaluate(async () => {
    const c = document.createElement('canvas'); c.width = 64; c.height = 48;
    const g = c.getContext('2d'); document.body.appendChild(c);
    const s = c.captureStream(15);
    const r = new MediaRecorder(s, { mimeType: 'video/mp4;codecs=vp9', videoBitsPerSecond: 50000 });
    const chunks = []; r.ondataavailable = e => chunks.push(e.data);
    let t = 0; const iv = setInterval(() => { g.fillStyle = `hsl(${t*20},80%,50%)`; g.fillRect(0,0,64,48); t++; }, 66);
    r.start(); await new Promise(res => setTimeout(res, 1500)); r.stop();
    await new Promise(res => r.onstop = res); clearInterval(iv);
    const buf = await new Blob(chunks).arrayBuffer();
    let bin = ''; new Uint8Array(buf).forEach(x => bin += String.fromCharCode(x)); return btoa(bin);
  });
  fs.writeFileSync(process.argv[2], Buffer.from(b64, 'base64'));
  await b.close();
})();
