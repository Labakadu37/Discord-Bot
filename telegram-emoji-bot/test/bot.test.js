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
const FRIEND = { id: 888, first_name: 'Pote' };
const GROUP = { id: -100123, type: 'supergroup', title: 'Brawl' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function png(size) {
    return sharp({ create: { width: size, height: size, channels: 4, background: { r: 255, g: 0, b: 0, alpha: 0.5 } } }).png().toBuffer();
}

function tgs(size) {
    return zlib.gzipSync(JSON.stringify({ tgs: 1, fr: 60, ip: 0, op: 60, w: size, h: size, layers: [] }));
}

/** Builds a message like Telegram: '/command …' text, premium emojis and links become entities. */
function message(...parts) {
    let text = '';
    const entities = [];
    for (const part of parts) {
        if (typeof part === 'string') {
            if (!text && part.startsWith('/')) entities.push({ type: 'bot_command', offset: 0, length: part.split(' ')[0].length });
            text += part;
        } else if (part.custom) {
            const fallback = part.fallback || '😀';
            entities.push({ type: 'custom_emoji', offset: text.length, length: fallback.length, custom_emoji_id: part.custom.custom_emoji_id });
            text += fallback;
        } else if (part.url) {
            entities.push({ type: 'url', offset: text.length, length: part.url.length });
            text += part.url;
        }
    }
    return { text, entities };
}

async function createFakeTelegram() {
    const files = new Map(); // file_id -> Buffer
    const sets = new Map();
    const customEmojis = new Map();
    const updates = [];
    const messages = [];
    const answers = [];
    let nextId = 1;
    const fake = { sets, messages, answers, rejectReuse: false };

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

    fake.makeSet = (name, title = name) => {
        const set = { name, title, sticker_type: 'custom_emoji', stickers: [] };
        sets.set(name, set);
        return set;
    };
    fake.addSticker = addSticker;
    fake.byTitle = (title) => [...sets.values()].find((set) => set.title === title);
    fake.push = (msg, { from = USER, chat = { id: from.id, type: 'private' } } = {}) => {
        const full = { message_id: nextId++, from, chat, ...msg };
        updates.push({ update_id: nextId++, message: full });
        return full;
    };
    fake.press = (data, msg, from = USER) => updates.push({
        update_id: nextId++,
        callback_query: { id: `cb${nextId++}`, from, data, message: { message_id: msg.message_id, chat: { id: msg.chat_id } } },
    });

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

    function getSet(name) {
        const set = sets.get(name);
        if (!set) throw new Error('STICKERSET_INVALID');
        return set;
    }

    const methods = {
        getMe: () => ({ id: 1, is_bot: true, username: 'emoji_test_bot' }),
        setMyCommands: () => true,
        async getUpdates() {
            for (let i = 0; i < 5 && !updates.length; i++) await sleep(20);
            return updates.splice(0);
        },
        sendMessage(p) {
            const msg = { message_id: nextId++, chat_id: p.chat_id, text: p.text, reply_markup: p.reply_markup };
            messages.push(msg);
            return msg;
        },
        editMessageText(p) {
            const msg = messages.find((m) => m.message_id === p.message_id);
            msg.text = p.text;
            msg.reply_markup = undefined;
            return true;
        },
        answerCallbackQuery(p) {
            answers.push(p.text);
            return true;
        },
        getCustomEmojiStickers: (p) => p.custom_emoji_ids.map((id) => customEmojis.get(id)).filter(Boolean),
        getStickerSet: (p) => getSet(p.name),
        getFile: (p) => ({ file_id: p.file_id, file_path: `stickers/${p.file_id}` }),
        async createNewStickerSet(p, form) {
            if (sets.has(p.name)) throw new Error('sticker set name is already occupied');
            assert.strictEqual(p.sticker_type, 'custom_emoji');
            assert.match(p.name, /_by_emoji_test_bot$/);
            const set = { name: p.name, title: p.title, sticker_type: 'custom_emoji', stickers: [], owner: Number(p.user_id) };
            const error = await storeSticker(set, p.stickers[0], form);
            if (error) throw new Error(error);
            sets.set(p.name, set);
            return true;
        },
        async addStickerToSet(p, form) {
            const set = getSet(p.name);
            if (set.stickers.length >= 200) throw new Error('STICKERS_TOO_MUCH');
            const error = await storeSticker(set, p.sticker, form);
            if (error) throw new Error(error);
            return true;
        },
        deleteStickerFromSet(p) {
            for (const set of sets.values()) set.stickers = set.stickers.filter((s) => s.file_id !== p.sticker);
            return true;
        },
        setStickerSetTitle(p) {
            getSet(p.name).title = p.title;
            return true;
        },
        setCustomEmojiStickerSetThumbnail(p) {
            const set = getSet(p.name);
            if (!set.stickers.some((s) => s.custom_emoji_id === p.custom_emoji_id)) throw new Error('STICKER_INVALID');
            set.icon = p.custom_emoji_id;
            return true;
        },
        deleteStickerSet(p) {
            getSet(p.name);
            sets.delete(p.name);
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
        const msg = fake.messages.find((m) => re.test(m.text));
        if (msg) return msg;
        await sleep(20);
    }
    throw new Error(`no message matching ${re}\n${fake.messages.map((m) => m.text).join('\n---\n')}`);
}

/** Sends a message and waits for the bot's answer matching `re`. */
async function talk(fake, msg, re, options) {
    fake.messages.length = 0;
    fake.push(msg, options);
    return waitForText(fake, re, 20000);
}

async function setup(t) {
    const fake = await createFakeTelegram();
    const dataFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'emoji-bot-')), 'data.json');
    process.env.EMOJI_IMAGE_URL = `${fake.url}/emoji/{code}.png`;
    const bot = startBot({ token: 'TEST', apiRoot: fake.url, dataFile, pollTimeout: 0 });
    t.after(async () => {
        await bot.stop();
        fake.close();
    });
    fake.saved = () => JSON.parse(fs.readFileSync(dataFile, 'utf8'));
    return fake;
}

test('createpack, addemoji, renamepack, seticon, removeemoji', async (t) => {
    const fake = await setup(t);
    const other = fake.makeSet('BrawlIcons_by_someone');
    const skull = fake.addSticker(other, await png(100));
    const star = fake.addSticker(other, tgs(100), { animated: true });

    await talk(fake, message('/addemoji 🔥'), /createpack Nom du pack/);
    await talk(fake, message('/createpack'), /Donne un nom/);
    await talk(fake, message('/createpack Jz Brawl'), /Jz Brawl.*prêt/s);
    assert.strictEqual(fake.sets.size, 1, 'Telegram needs a first emoji to create the pack');

    // two premium emojis (one sent twice) + one normal emoji
    const emojis = message('/addemoji ', { custom: skull }, { custom: star, fallback: '⭐' }, { custom: skull }, '🔥');
    let summary = await talk(fake, emojis, /emoji\(s\) ajouté/);
    assert.match(summary.text, /3<\/b> emoji\(s\) ajouté/);
    assert.match(summary.text, /1 déjà dans le pack/);
    const mine = fake.byTitle('Jz Brawl');
    assert.strictEqual(mine.stickers.length, 3);
    assert.strictEqual(mine.owner, USER.id);
    assert.match(summary.text, new RegExp(`https://t.me/addemoji/${mine.name}`));

    // the same emojis again: nothing new
    summary = await talk(fake, emojis, /déjà dans le pack/);
    assert.match(summary.text, /4 déjà dans le pack/);
    assert.strictEqual(mine.stickers.length, 3);

    // in private chat, emojis without a command work too; a refused direct copy is re-uploaded
    fake.rejectReuse = true;
    const ghost = fake.addSticker(other, await png(100));
    await talk(fake, message({ custom: ghost }), /1<\/b> emoji\(s\) ajouté/);
    assert.strictEqual(mine.stickers.length, 4);
    fake.rejectReuse = false;

    // a whole pack from its link
    const animals = fake.makeSet('Animals_by_someone');
    for (let i = 0; i < 3; i++) fake.addSticker(animals, await png(100));
    await talk(fake, message('/addemoji ', { url: 'https://t.me/addemoji/Animals_by_someone' }), /3<\/b> emoji\(s\) ajouté/);
    assert.strictEqual(mine.stickers.length, 7);

    // rename
    await talk(fake, message('/renamepack Jz Brawl Ultra'), /renommé/);
    assert.strictEqual(mine.title, 'Jz Brawl Ultra');

    // icon: must be an emoji of my pack
    await talk(fake, message('/seticon ', { custom: skull }), /aucun de tes packs/);
    await talk(fake, message('/seticon ', { custom: mine.stickers[2] }), /Icône du pack/);
    assert.strictEqual(mine.icon, mine.stickers[2].custom_emoji_id);

    // remove, by replying to a message that contains the emoji
    const first = mine.stickers[0];
    const withEmoji = { message_id: 5, from: USER, chat: { id: USER.id, type: 'private' }, ...message({ custom: first }) };
    await talk(fake, { ...message('/removeemoji'), reply_to_message: withEmoji }, /1 emoji\(s\) retiré/);
    assert.strictEqual(mine.stickers.length, 6);

    assert.strictEqual(fake.saved().users[USER.id].current, mine.name);
});

test('copypack, mypacks buttons, deletepack', async (t) => {
    const fake = await setup(t);
    const source = fake.makeSet('Animals_by_someone', 'Animaux');
    for (let i = 0; i < 3; i++) fake.addSticker(source, await png(100));
    const link = { url: 'https://t.me/addemoji/Animals_by_someone' };

    await talk(fake, message('/copypack'), /lien du pack/);
    await talk(fake, message('/copypack ', link, ' Ma copie'), /3<\/b> emoji\(s\) ajouté/);
    await talk(fake, message('/copypack ', link), /3<\/b> emoji\(s\) ajouté/);
    const copy = fake.byTitle('Ma copie');
    const copy2 = [...fake.sets.values()].find((set) => set.title === 'Animaux' && set !== source);
    assert.strictEqual(copy.stickers.length, 3);
    assert.strictEqual(copy2.stickers.length, 3);

    // the last copy is the pack in use; pick the first one with its button
    const list = await talk(fake, message('/mypacks'), /Tes packs/);
    assert.match(list.text, /Ma copie<\/b> \(3\/200\)/);
    assert.strictEqual(list.reply_markup.inline_keyboard.length, 2);
    const firstButton = list.reply_markup.inline_keyboard[0][0].callback_data;

    fake.press(firstButton, list, FRIEND);
    await sleep(200);
    assert.deepStrictEqual(fake.answers, ['Ce bouton n’est pas pour toi 😉']);

    fake.press(firstButton, list);
    await waitForText(fake, /J’utilise maintenant le pack « <b>Ma copie/);
    assert.strictEqual(fake.saved().users[USER.id].current, copy.name);

    // delete it (with confirmation)
    const question = await talk(fake, message('/deletepack'), /Supprimer le pack « <b>Ma copie/);
    fake.press(question.reply_markup.inline_keyboard[0][1].callback_data, question);
    await waitForText(fake, /je ne supprime rien/);
    assert.ok(fake.sets.has(copy.name));

    const again = await talk(fake, message('/deletepack'), /Supprimer le pack/);
    fake.press(again.reply_markup.inline_keyboard[0][0].callback_data, again);
    await waitForText(fake, /supprimé/);
    assert.ok(!fake.sets.has(copy.name));
    const saved = fake.saved().users[USER.id];
    assert.deepStrictEqual(saved.packs.map((p) => p.name), [copy2.name]);
    assert.strictEqual(saved.current, null);
});

test('works in groups, only with commands', async (t) => {
    const fake = await setup(t);
    const other = fake.makeSet('BrawlIcons_by_someone');
    const skull = fake.addSticker(other, await png(100));
    const inGroup = { from: FRIEND, chat: GROUP };

    await talk(fake, message('/createpack@emoji_test_bot Team Brawl 🔥'), /1<\/b> emoji\(s\) ajouté/, inGroup);
    const pack = fake.byTitle('Team Brawl');
    assert.strictEqual(pack.owner, FRIEND.id);

    // normal group messages and commands for other bots are ignored
    fake.messages.length = 0;
    fake.push(message({ custom: skull }), inGroup);
    fake.push(message('/addemoji@other_bot ', { custom: skull }), inGroup);
    await sleep(300);
    assert.strictEqual(fake.messages.length, 0);
    assert.strictEqual(pack.stickers.length, 1);

    // reply to someone else's message with /addemoji
    const fromSomeone = { message_id: 9, from: USER, chat: GROUP, ...message('regarde ', { custom: skull }) };
    await talk(fake, { ...message('/addemoji'), reply_to_message: fromSomeone }, /1<\/b> emoji\(s\) ajouté/, inGroup);
    assert.strictEqual(pack.stickers.length, 2);
});

test('a full pack continues in a new one', async (t) => {
    const fake = await setup(t);
    const big = fake.makeSet('Big_by_someone');
    for (let i = 0; i < 199; i++) fake.addSticker(big, await png(100));
    const small = fake.makeSet('Small_by_someone');
    for (let i = 0; i < 3; i++) fake.addSticker(small, await png(100));

    await talk(fake, message('/createpack Mega'), /prêt/);
    await talk(fake, message('/addemoji ', { url: 't.me/addemoji/Big_by_someone' }), /199<\/b> emoji\(s\) ajouté/);
    const summary = await talk(fake, message('/addemoji ', { url: 't.me/addemoji/Small_by_someone' }), /Mega 2/);
    assert.strictEqual(fake.byTitle('Mega').stickers.length, 200);
    assert.strictEqual(fake.byTitle('Mega 2').stickers.length, 2);
    assert.match(summary.text, /3<\/b> emoji\(s\) ajouté/);
    assert.match(summary.text, new RegExp(fake.byTitle('Mega').name));
    assert.match(summary.text, new RegExp(fake.byTitle('Mega 2').name));
});
