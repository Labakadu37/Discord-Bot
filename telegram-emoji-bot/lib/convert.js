'use strict';

// Turns any image / sticker into a valid custom emoji file (100x100).

const { execFile } = require('child_process');
const fs = require('fs/promises');
const os = require('os');
const path = require('path');
const zlib = require('zlib');
const sharp = require('sharp');

const SIZE = 100;

/** PNG/WEBP/JPEG -> transparent 100x100 PNG. */
async function toStaticEmoji(buffer) {
    return sharp(buffer, { animated: false, density: 300 })
        .ensureAlpha()
        .resize(SIZE, SIZE, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
        .png()
        .toBuffer();
}

/** Animated sticker (.tgs, gzipped Lottie) -> 100x100 canvas, scaled down via a wrapping precomp. */
function rescaleTgs(buffer) {
    const anim = JSON.parse(zlib.gunzipSync(buffer).toString('utf8'));
    if (anim.w === SIZE && anim.h === SIZE) return buffer;

    const { w, h } = anim;
    const scale = (SIZE / Math.max(w, h)) * 100;
    const refId = '__emoji_scale';
    anim.assets = Array.isArray(anim.assets) ? anim.assets : [];
    anim.assets.push({ id: refId, layers: anim.layers });
    anim.layers = [{
        ddd: 0,
        ind: 1,
        ty: 0,
        nm: 'emoji',
        refId,
        sr: 1,
        ks: {
            o: { a: 0, k: 100 },
            r: { a: 0, k: 0 },
            p: { a: 0, k: [SIZE / 2, SIZE / 2, 0] },
            a: { a: 0, k: [w / 2, h / 2, 0] },
            s: { a: 0, k: [scale, scale, 100] },
        },
        ao: 0,
        w,
        h,
        ip: anim.ip,
        op: anim.op,
        st: 0,
        bm: 0,
    }];
    anim.w = SIZE;
    anim.h = SIZE;
    return zlib.gzipSync(Buffer.from(JSON.stringify(anim)));
}

function ffmpegPath() {
    if (process.env.FFMPEG_PATH) return process.env.FFMPEG_PATH;
    try {
        const bundled = require('ffmpeg-static');
        if (bundled) return bundled;
    } catch {
        // optional dependency not installed, fall back to the system one
    }
    return 'ffmpeg';
}

function run(cmd, args) {
    return new Promise((resolve, reject) => {
        execFile(cmd, args, { timeout: 60000 }, (err, _stdout, stderr) => {
            if (err) reject(new Error(`ffmpeg: ${String(stderr || err.message).trim().split('\n').pop()}`));
            else resolve();
        });
    });
}

/** Video sticker (.webm VP9) -> 100x100, max 3 s, no audio, alpha kept. */
async function rescaleWebm(buffer) {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'emoji-'));
    const input = path.join(dir, 'in.webm');
    const output = path.join(dir, 'out.webm');
    const filter = `scale=${SIZE}:${SIZE}:force_original_aspect_ratio=decrease,format=yuva420p,`
        + `pad=${SIZE}:${SIZE}:(ow-iw)/2:(oh-ih)/2:color=black@0`;
    const encode = ['-t', '3', '-an', '-vf', filter, '-c:v', 'libvpx-vp9', '-pix_fmt', 'yuva420p',
        '-b:v', '0', '-crf', '35', output];
    try {
        await fs.writeFile(input, buffer);
        try {
            // libvpx decoder is needed to keep the transparency of VP9 files
            await run(ffmpegPath(), ['-y', '-c:v', 'libvpx-vp9', '-i', input, ...encode]);
        } catch {
            await run(ffmpegPath(), ['-y', '-i', input, ...encode]);
        }
        return await fs.readFile(output);
    } finally {
        await fs.rm(dir, { recursive: true, force: true });
    }
}

module.exports = { SIZE, toStaticEmoji, rescaleTgs, rescaleWebm };
