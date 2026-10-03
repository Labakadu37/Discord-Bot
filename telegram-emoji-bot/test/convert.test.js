'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { execFileSync, spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const zlib = require('zlib');
const sharp = require('sharp');
const { toStaticEmoji, rescaleTgs, rescaleWebm } = require('../lib/convert');

test('images become transparent 100x100 PNGs', async () => {
    const jpeg = await sharp({ create: { width: 512, height: 300, channels: 3, background: 'red' } }).jpeg().toBuffer();
    const meta = await sharp(await toStaticEmoji(jpeg)).metadata();
    assert.strictEqual(meta.format, 'png');
    assert.strictEqual(meta.width, 100);
    assert.strictEqual(meta.height, 100);
    assert.strictEqual(meta.hasAlpha, true);
});

test('animated stickers are scaled to a 100x100 canvas', () => {
    const lottie = { tgs: 1, v: '5.5.2', fr: 60, ip: 0, op: 180, w: 512, h: 512, layers: [{ ty: 4, nm: 'shape' }] };
    const out = JSON.parse(zlib.gunzipSync(rescaleTgs(zlib.gzipSync(JSON.stringify(lottie)))));
    assert.strictEqual(out.w, 100);
    assert.strictEqual(out.h, 100);
    assert.strictEqual(out.tgs, 1);
    assert.strictEqual(out.layers.length, 1);
    assert.strictEqual(out.layers[0].ty, 0);
    assert.deepStrictEqual(out.assets[0].layers, lottie.layers);

    const small = zlib.gzipSync(JSON.stringify({ ...lottie, w: 100, h: 100 }));
    assert.strictEqual(rescaleTgs(small), small);
});

test('video stickers are scaled to 100x100', async (t) => {
    const ffmpeg = process.env.FFMPEG_PATH || (() => {
        try { return require('ffmpeg-static'); } catch { return 'ffmpeg'; }
    })();
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'emoji-test-'));
    const input = path.join(dir, 'in.webm');
    try {
        execFileSync(ffmpeg, ['-y', '-f', 'lavfi', '-i', 'testsrc=size=512x512:rate=30', '-t', '1',
            '-c:v', 'libvpx-vp9', '-pix_fmt', 'yuva420p', input], { stdio: 'ignore' });
    } catch {
        t.skip('ffmpeg not available');
        return;
    }
    const out = await rescaleWebm(fs.readFileSync(input));
    const output = path.join(dir, 'out.webm');
    fs.writeFileSync(output, out);
    const info = spawnSync(ffmpeg, ['-i', output], { encoding: 'utf8' }).stderr;
    fs.rmSync(dir, { recursive: true, force: true });
    assert.match(info, /vp9.*100x100/);
    assert.ok(out.length < 256 * 1024);
});
