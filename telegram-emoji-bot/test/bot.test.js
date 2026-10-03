'use strict';

// Runs the whole bot against a fake Telegram Bot API.

const test = require('node:test');
const assert = require('node:assert');
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const zlib = require('zlib');
const sharp = require('sharp');
const { startBot } = require('../lib/bot');

const USER = { id: 777, first_name: 'Jz' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function png(size) {
    return sharp({ create: { width: size, height: size, channels: 4, background: { r: 255, g: 0, b: 0, alpha: 0.5 } } }).png().toBuffer();
}

function tgs(size) {
    return zlib.gzipSync(JSON.stringify({ tgs: 1, fr: 60, ip: 0, op: 60, w: size, h: size, layers: [] }));
}

async function createFakeTelegram() {
    const files = new Map(); // file_id -> Buffer
    const sets = new Map();
    const customEmojis = new Map();
    const updates = [];
    const messages = [];
    let nextId = 1;
    const fake = { files, sets, customEmojis, messages, rejectReuse: false, calls: [] };

    function addSticker(set, data, { animated = false, sameFileAs } = {}) {
        const n = nextId++;
        const fileId = sameFileAs ? sameFileAs.file_id : `file${n}`;
        if (!sameFileAs) files.set(fileId, data);
        const sticker = {
            file_id: fileId,
            file_unique_id: sameFileAs ? sameFileAs.file_unique_id : `uniq${n}`,
            type: 'custom_emoji',
            width: 100,
            height: 100,
            is_animated: animated,
            is_video: false,
            emoji: '😀',
            set_name: set.name,
            custom_emoji_id: `ce${n}`,
        };
        set.stickers.push(sticker);
        customEmojis.set(sticker.custom_emoji_id, sticker);
        return sticker;
    }

    fake.makeSet = (name) => {
        const set = { name, title: name, sticker_type: 'custom_emoji', stickers: [] };
        sets.set(name, set);
        return set;
    };
    fake.addSticker = addSticker;
    fake.push = (message) => updates.push({ update_id: nextId++, message: { message_id: nextId++, from: USER, chat: { id: USER.id, type: 'private' }, ...message } });
    fake.pushCallback = (data) => updates.push({ update_id: nextId++, callback_query: { id: `cb${nextId++}`, from: USER, data, message: { chat: { id: USER.id } } } });

    async function storeSticker(set, input, form) {
        if (input.sticker.startsWith('attach://')) {
            const blob = form.get(input.sticker.slice('attach://'.length));
            const data = Buffer.from(await blob.arrayBuffer());
            if (input.format === 'static') {
                const meta = await sharp(data).metadata();
                if (meta.width !== 100 || meta.height !== 100) return 'STICKER_PNG_DIMENSIONS';
            } else if (input.format === 'animated') {
                const anim = JSON.parse(zlib.gunzipSync(data));
                if (anim.w !== 100 || anim.h !== 100) return 'STICKER_TGS_DIMENSIONS';
            }
            addSticker(set, data, { animated: input.format === 'animated' });
            return null;
        }
        if (fake.rejectReuse) return 'STICKER_FILE_INVALID';
        const source = [...customEmojis.values()].find((s) => s.file_id === input.sticker);
        if (!source) return 'wrong file identifier';
        addSticker(set, null, { animated: source.is_animated, sameFileAs: source });
        return null;
    }

    const methods = {
        getMe: () => ({ id: 1, is_bot: true, username: 'emoji_test_bot' }),
        setMyCommands: () => true,
        async getUpdates() {
            for (let i = 0; i < 5 && !updates.length; i++) await sleep(20);
            return updates.splice(0);
        },
        sendMessage(p) {
            const message = { message_id: nextId++, text: p.text };
            messages.push(message);
            return message;
        },
        editMessageText(p) {
            const message = messages.find((m) => m.message_id === p.message_id);
            message.text = p.text;
            return true;
        },
        answerCallbackQuery: () => true,
        getCustomEmojiStickers: (p) => p.custom_emoji_ids.map((id) => customEmojis.get(id)).filter(Boolean),
        getStickerSet(p) {
            const set = sets.get(p.name);
            if (!set) throw new Error('STICKERSET_INVALID');
            return set;
        },
        getFile: (p) => ({ file_id: p.file_id, file_path: `stickers/${p.file_id}` }),
        async createNewStickerSet(p, form) {
            if (sets.has(p.name)) throw new Error('sticker set name is already occupied');
            assert.strictEqual(p.sticker_type, 'custom_emoji');
            assert.match(p.name, /_by_emoji_test_bot$/);
            const set = { name: p.name, title: p.title, sticker_type: 'custom_emoji', stickers: [] };
            const error = await storeSticker(set, p.stickers[0], form);
            if (error) throw new Error(error);
            sets.set(p.name, set);
            return true;
        },
        async addStickerToSet(p, form) {
            const set = sets.get(p.name);
            if (!set) throw new Error('STICKERSET_INVALID');
            if (set.stickers.length >= 200) throw new Error('STICKERS_TOO_MUCH');
            const error = await storeSticker(set, p.sticker, form);
            if (error) throw new Error(error);
            return true;
        },
        deleteStickerFromSet(p) {
            for (const set of sets.values()) set.stickers = set.stickers.filter((s) => s.file_id !== p.sticker);
            return true;
        },
    };

    const server = http.createServer(async (req, res) => {
        const chunks = [];
        for await (const chunk of req) chunks.push(chunk);
        const body = Buffer.concat(chunks);
        const fileMatch = /^\/file\/bot[^/]+\/stickers\/(.+)$/.exec(req.url);
        if (fileMatch) {
            res.end(files.get(fileMatch[1]));
            return;
        }
        if (req.url.startsWith('/emoji/')) {
            if (req.url === '/emoji/1f525.png') res.end(await png(160));
            else { res.statusCode = 404; res.end(); }
            return;
        }
        const method = req.url.split('/').pop();
        let params = {};
        let form = null;
        if ((req.headers['content-type'] || '').startsWith('multipart/')) {
            form = await new Response(body, { headers: { 'content-type': req.headers['content-type'] } }).formData();
            for (const [key, value] of form.entries()) {
                if (typeof value === 'string') params[key] = /^[[{]/.test(value) ? JSON.parse(value) : value;
            }
        } else if (body.length) {
            params = JSON.parse(body);
        }
        fake.calls.push(method);
        res.setHeader('content-type', 'application/json');
        try {
            const result = await methods[method](params, form);
            res.end(JSON.stringify({ ok: true, result }));
        } catch (err) {
            res.end(JSON.stringify({ ok: false, error_code: 400, description: `Bad Request: ${err.message}` }));
        }
    });
    await new Promise((resolve) => server.listen(0, resolve));
    fake.url = `http://127.0.0.1:${server.address().port}`;
    fake.close = () => server.close();
    return fake;
}

async function waitForText(fake, re, timeout = 5000) {
    const start = Date.now();
    while (Date.now() - start < timeout) {
        const message = fake.messages.find((m) => re.test(m.text));
        if (message) return message;
        await sleep(20);
    }
    throw new Error(`no message matching ${re}\n${fake.messages.map((m) => m.text).join('\n---\n')}`);
}

test('copies emojis from other packs into my pack', async (t) => {
    const fake = await createFakeTelegram();
    const dataFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'emoji-bot-')), 'data.json');
    process.env.EMOJI_IMAGE_URL = `${fake.url}/emoji/{code}.png`;
    const bot = startBot({ token: 'TEST', apiRoot: fake.url, dataFile, pollTimeout: 0 });
    t.after(async () => {
        await bot.stop();
        fake.close();
    });

    const other = fake.makeSet('BrawlIcons_by_someone');
    const skull = fake.addSticker(other, await png(100));
    const star = fake.addSticker(other, tgs(100), { animated: true });

    // without a pack, the bot asks for a name first
    fake.push({ text: '🔥' });
    await waitForText(fake, /newpack Nom du pack/);

    fake.push({ text: '/newpack Jz Brawl', entities: [{ type: 'bot_command', offset: 0, length: 8 }] });
    await waitForText(fake, /Jz Brawl.*prêt/s);

    // two premium emojis (one sent twice) + one normal emoji
    const message = {
        text: '💀⭐💀🔥',
        entities: [
            { type: 'custom_emoji', offset: 0, length: 2, custom_emoji_id: skull.custom_emoji_id },
            { type: 'custom_emoji', offset: 2, length: 1, custom_emoji_id: star.custom_emoji_id },
            { type: 'custom_emoji', offset: 3, length: 2, custom_emoji_id: skull.custom_emoji_id },
        ],
    };
    fake.push(message);
    const summary = await waitForText(fake, /3<\/b> emoji\(s\) ajouté/);
    assert.match(summary.text, /1 déjà dans le pack/);
    const [mine] = [...fake.sets.values()].filter((s) => s.title === 'Jz Brawl');
    assert.strictEqual(mine.stickers.length, 3);
    assert.match(summary.text, new RegExp(`https://t.me/addemoji/${mine.name}`));

    // sending the same emojis again adds nothing
    fake.messages.length = 0;
    fake.push(message);
    const again = await waitForText(fake, /déjà dans le pack/);
    assert.match(again.text, /4 déjà dans le pack/);
    assert.strictEqual(mine.stickers.length, 3);

    // when the direct copy is refused, the file is downloaded and re-uploaded
    fake.rejectReuse = true;
    const ghost = fake.addSticker(other, await png(100));
    fake.push({ text: '👻', entities: [{ type: 'custom_emoji', offset: 0, length: 2, custom_emoji_id: ghost.custom_emoji_id }] });
    await waitForText(fake, /1<\/b> emoji\(s\) ajouté/);
    assert.strictEqual(mine.stickers.length, 4);
    fake.rejectReuse = false;

    // a whole pack from its link
    fake.messages.length = 0;
    const animals = fake.makeSet('Animals_by_someone');
    for (let i = 0; i < 3; i++) fake.addSticker(animals, await png(100));
    fake.push({ text: 'https://t.me/addemoji/Animals_by_someone', entities: [{ type: 'url', offset: 0, length: 40 }] });
    await waitForText(fake, /3<\/b> emoji\(s\) ajouté/);
    assert.strictEqual(mine.stickers.length, 7);

    // remove an emoji of my pack
    fake.messages.length = 0;
    const first = mine.stickers[0];
    fake.push({
        text: '/remove 💀',
        entities: [
            { type: 'bot_command', offset: 0, length: 7 },
            { type: 'custom_emoji', offset: 8, length: 2, custom_emoji_id: first.custom_emoji_id },
        ],
    });
    await waitForText(fake, /1 emoji\(s\) retiré/);
    assert.strictEqual(mine.stickers.length, 6);

    const saved = JSON.parse(fs.readFileSync(dataFile, 'utf8'));
    assert.strictEqual(saved.users[USER.id].current, mine.name);
});

test('a full pack continues in a new one', async (t) => {
    const fake = await createFakeTelegram();
    const dataFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'emoji-bot-')), 'data.json');
    const bot = startBot({ token: 'TEST', apiRoot: fake.url, dataFile, pollTimeout: 0 });
    t.after(async () => {
        await bot.stop();
        fake.close();
    });

    const big = fake.makeSet('Big_by_someone');
    for (let i = 0; i < 199; i++) fake.addSticker(big, await png(100));
    const small = fake.makeSet('Small_by_someone');
    for (let i = 0; i < 3; i++) fake.addSticker(small, await png(100));

    fake.push({ text: '/newpack Mega', entities: [{ type: 'bot_command', offset: 0, length: 8 }] });
    await waitForText(fake, /prêt/);
    fake.push({ text: 't.me/addemoji/Big_by_someone', entities: [{ type: 'url', offset: 0, length: 28 }] });
    await waitForText(fake, /199<\/b> emoji\(s\) ajouté/, 20000);

    fake.push({ text: 't.me/addemoji/Small_by_someone', entities: [{ type: 'url', offset: 0, length: 30 }] });
    const summary = await waitForText(fake, /Mega 2/, 20000);
    const byTitle = (title) => [...fake.sets.values()].find((s) => s.title === title);
    assert.strictEqual(byTitle('Mega').stickers.length, 200);
    assert.strictEqual(byTitle('Mega 2').stickers.length, 2);
    assert.match(summary.text, /3<\/b> emoji\(s\) ajouté/);
    assert.match(summary.text, new RegExp(byTitle('Mega').name));
    assert.match(summary.text, new RegExp(byTitle('Mega 2').name));
});
