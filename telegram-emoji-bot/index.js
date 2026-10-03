'use strict';

// Bot Telegram qui crée des packs d'emojis à partir d'emojis d'autres packs.
// Lancement : mets ton token dans .env (BOT_TOKEN=...), puis `npm install` et `npm start`.

const { execFile } = require('child_process');
const fs = require('fs');
const fsp = require('fs/promises');
const os = require('os');
const path = require('path');
const zlib = require('zlib');

// ===== Telegram API ================================================

// Minimal Telegram Bot API client (no dependency, uses Node's global fetch).

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

class TelegramError extends Error {
    constructor(method, data) {
        super(`${method}: ${data.description || 'unknown error'}`);
        this.method = method;
        this.code = data.error_code;
        this.description = data.description || '';
        this.parameters = data.parameters || {};
    }
}

function createClient(token, apiRoot = 'https://api.telegram.org') {
    const base = `${apiRoot}/bot${token}`;

    /**
     * Calls a Bot API method.
     * `files` = [{ field, data: Buffer, filename }] switches to multipart upload.
     * `options.onWait(seconds)` is called when Telegram asks us to slow down (429).
     */
    async function call(method, params = {}, files = null, options = {}) {
        const retries = options.retries ?? 5;
        for (let attempt = 0; ; attempt++) {
            let body;
            let headers;
            if (files && files.length) {
                body = new FormData();
                for (const [key, value] of Object.entries(params)) {
                    if (value === undefined || value === null) continue;
                    body.append(key, typeof value === 'object' ? JSON.stringify(value) : String(value));
                }
                for (const file of files) body.append(file.field, new Blob([file.data]), file.filename);
            } else {
                body = JSON.stringify(params);
                headers = { 'Content-Type': 'application/json' };
            }

            let data;
            try {
                const res = await fetch(`${base}/${method}`, { method: 'POST', body, headers });
                data = await res.json();
            } catch (err) {
                if (attempt >= retries) throw err;
                await sleep(1000 * (attempt + 1));
                continue;
            }

            if (data.ok) return data.result;
            if (data.error_code === 429 && attempt < retries) {
                const wait = data.parameters?.retry_after ?? 5;
                if (options.onWait) options.onWait(wait);
                await sleep(wait * 1000 + 250);
                continue;
            }
            throw new TelegramError(method, data);
        }
    }

    async function downloadFile(fileId) {
        const file = await call('getFile', { file_id: fileId });
        const res = await fetch(`${apiRoot}/file/bot${token}/${file.file_path}`);
        if (!res.ok) throw new Error(`download failed (${res.status})`);
        return Buffer.from(await res.arrayBuffer());
    }

    return { call, downloadFile };
}

// ===== Conversion en emoji 100x100 =================================

// Turns any image / sticker into a valid custom emoji file (100x100).


const SIZE = 100;

// sharp (image resizing) is optional: it doesn't install on every phone (Termux).
// Without it, emojis from other packs still work; only images / normal emojis need it.
let sharpModule;
function loadSharp() {
    if (sharpModule === undefined) {
        try {
            sharpModule = require('sharp');
        } catch {
            sharpModule = null;
        }
    }
    if (!sharpModule) throw new Error('module « sharp » absent (sur téléphone : npm install --cpu=wasm32 sharp)');
    return sharpModule;
}

/** PNG/WEBP/JPEG -> transparent 100x100 PNG. */
async function toStaticEmoji(buffer) {
    const sharp = loadSharp();
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
    const dir = await fsp.mkdtemp(path.join(os.tmpdir(), 'emoji-'));
    const input = path.join(dir, 'in.webm');
    const output = path.join(dir, 'out.webm');
    const filter = `scale=${SIZE}:${SIZE}:force_original_aspect_ratio=decrease,format=yuva420p,`
        + `pad=${SIZE}:${SIZE}:(ow-iw)/2:(oh-ih)/2:color=black@0`;
    const encode = ['-t', '3', '-an', '-vf', filter, '-c:v', 'libvpx-vp9', '-pix_fmt', 'yuva420p',
        '-b:v', '0', '-crf', '35', output];
    try {
        await fsp.writeFile(input, buffer);
        try {
            // libvpx decoder is needed to keep the transparency of VP9 files
            await run(ffmpegPath(), ['-y', '-c:v', 'libvpx-vp9', '-i', input, ...encode]);
        } catch {
            await run(ffmpegPath(), ['-y', '-i', input, ...encode]);
        }
        return await fsp.readFile(output);
    } finally {
        await fsp.rm(dir, { recursive: true, force: true });
    }
}

// ===== Lecture des messages ========================================

// Reads what the user sent (premium emojis, normal emojis, stickers, pack links, images).

const EMOJI_RE = /\p{Extended_Pictographic}|\p{Regional_Indicator}|⃣/u;
const PACK_LINK_RE = /t(?:elegram)?\.me\/(?:addemoji|addstickers)\/([A-Za-z][A-Za-z0-9_]{0,63})/gi;
const PACK_URL_RE = /(?:https?:\/\/)?t(?:elegram)?\.me\/(?:addemoji|addstickers)\/[A-Za-z0-9_]+/gi;
const segmenter = new Intl.Segmenter('fr', { granularity: 'grapheme' });

const DEFAULT_IMAGE_URLS = {
    apple: [
        'https://cdn.jsdelivr.net/gh/iamcal/emoji-data@master/img-apple-160/{code}.png',
        'https://raw.githubusercontent.com/iamcal/emoji-data/master/img-apple-160/{code}.png',
    ],
    google: ['https://raw.githubusercontent.com/iamcal/emoji-data/master/img-google-136/{code}.png'],
    twitter: ['https://cdn.jsdelivr.net/npm/@twemoji/svg@15.0.0/{code}.svg'],
};

function isEmoji(grapheme) {
    return EMOJI_RE.test(grapheme);
}

/** Normal (unicode) emojis and pack links found in a piece of plain text. */
function scanText(text, out) {
    for (const match of text.matchAll(PACK_LINK_RE)) out.push({ type: 'pack', name: match[1], at: match.index });
    for (const { segment, index } of segmenter.segment(text)) {
        if (isEmoji(segment)) out.push({ type: 'unicode', emoji: segment, at: index });
    }
}

/**
 * Everything usable in a message, in the order it appears.
 * Types: custom (premium emoji), unicode, pack (link), sticker, file (image/tgs/webm).
 * Sources found in the text keep their position (`at`, UTF-16 offset like Telegram's entities).
 */
function extractSources(message) {
    const sources = [];

    if (message.sticker) sources.push({ type: 'sticker', sticker: message.sticker });

    const file = fileSource(message);
    if (file) {
        // the caption only says which emoji the image stands for
        const caption = message.caption || '';
        const first = [...segmenter.segment(caption)].find(({ segment }) => isEmoji(segment));
        sources.push({ ...file, emoji: first ? first.segment : undefined });
        return sources;
    }

    const text = message.text ?? message.caption ?? '';
    const entities = [...(message.entities ?? message.caption_entities ?? [])]
        .sort((a, b) => a.offset - b.offset);

    const found = [];
    let cursor = 0;
    for (const entity of entities) {
        if (entity.offset < cursor) continue;
        const end = entity.offset + entity.length;
        if (entity.type === 'custom_emoji') {
            scanChunk(text, cursor, entity.offset, found);
            found.push({ type: 'custom', id: entity.custom_emoji_id, fallback: text.slice(entity.offset, end), at: entity.offset });
            cursor = end;
        } else if (entity.type === 'bot_command') {
            scanChunk(text, cursor, entity.offset, found);
            cursor = end;
        } else if (entity.type === 'text_link') {
            scanChunk(text, cursor, entity.offset, found);
            for (const match of String(entity.url).matchAll(PACK_LINK_RE)) {
                found.push({ type: 'pack', name: match[1], at: entity.offset });
            }
            cursor = end;
        }
    }
    scanChunk(text, cursor, text.length, found);

    found.sort((a, b) => a.at - b.at);
    sources.push(...found);
    return sources;
}

/** Text without the t.me/addemoji/… links (used for names typed next to a link). */
function stripPackLinks(text) {
    return text.replace(PACK_URL_RE, ' ').replace(/\s+/g, ' ').trim();
}

function scanChunk(text, from, to, out) {
    const chunk = [];
    scanText(text.slice(from, to), chunk);
    for (const source of chunk) out.push({ ...source, at: source.at + from });
}

function fileSource(message) {
    if (message.photo && message.photo.length) {
        const photo = message.photo[message.photo.length - 1];
        return { type: 'file', fileId: photo.file_id, uniqueId: photo.file_unique_id, format: 'static' };
    }
    const doc = message.document;
    if (!doc) return null;
    const name = (doc.file_name || '').toLowerCase();
    const mime = doc.mime_type || '';
    let format = null;
    if (mime === 'application/x-tgsticker' || name.endsWith('.tgs')) format = 'animated';
    else if (mime === 'video/webm' || name.endsWith('.webm')) format = 'video';
    else if (mime.startsWith('image/')) format = 'static';
    if (!format) return null;
    return { type: 'file', fileId: doc.file_id, uniqueId: doc.file_unique_id, format };
}

/** Possible file names of an emoji image ("2764-fe0f", "0031-fe0f-20e3", "1f525"...). */
function codeCandidates(emoji) {
    const cps = [...emoji].map((c) => c.codePointAt(0));
    const variants = [
        cps,
        cps.filter((cp) => cp !== 0xfe0f),
        cps[1] === 0xfe0f ? null : [cps[0], 0xfe0f, ...cps.slice(1)],
    ].filter(Boolean);
    const out = new Set();
    for (const pad of [4, 1]) {
        for (const variant of variants) out.add(variant.map((cp) => cp.toString(16).padStart(pad, '0')).join('-'));
    }
    return [...out];
}

const imageCache = new Map();

/** Downloads the picture of a normal emoji (Apple style by default, like in Telegram). */
async function fetchEmojiImage(emoji) {
    if (imageCache.has(emoji)) return imageCache.get(emoji);
    const style = (process.env.EMOJI_STYLE || 'apple').toLowerCase();
    const templates = process.env.EMOJI_IMAGE_URL
        ? [process.env.EMOJI_IMAGE_URL]
        : DEFAULT_IMAGE_URLS[style] || DEFAULT_IMAGE_URLS.apple;

    for (const code of codeCandidates(emoji)) {
        for (const template of templates) {
            const res = await fetch(template.replace('{code}', code)).catch(() => null);
            if (res && res.ok) {
                const data = Buffer.from(await res.arrayBuffer());
                imageCache.set(emoji, data);
                return data;
            }
        }
    }
    throw new Error(`pas d'image trouvée pour ${emoji}`);
}

// ===== Sauvegarde des packs ========================================

// Remembers each user's packs in a small JSON file (Telegram has no "list my packs" method).


class Store {
    constructor(file) {
        this.file = file;
        this.data = { users: {} };
        try {
            this.data = JSON.parse(fs.readFileSync(file, 'utf8'));
        } catch (err) {
            if (err.code !== 'ENOENT') console.error(`Impossible de lire ${file}:`, err.message);
        }
    }

    user(id) {
        if (!this.data.users[id]) this.data.users[id] = { packs: [], current: null, pendingTitle: null };
        return this.data.users[id];
    }

    save() {
        const tmp = `${this.file}.tmp`;
        fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
        fs.renameSync(tmp, this.file);
    }
}

// ===== Bot =========================================================

const MAX_EMOJIS = 200; // Telegram limit for a custom emoji pack
const FALLBACK_EMOJI = '⭐';

const COMMANDS = [
    { command: 'createpack', description: 'Créer un pack : /createpack Nom' },
    { command: 'addemoji', description: 'Ajouter des emojis à ton pack' },
    { command: 'removeemoji', description: 'Retirer des emojis de ton pack' },
    { command: 'renamepack', description: 'Renommer ton pack' },
    { command: 'copypack', description: 'Copier un pack entier dans un nouveau pack' },
    { command: 'seticon', description: 'Choisir l’icône de ton pack' },
    { command: 'mypacks', description: 'Tes packs / choisir celui à utiliser' },
    { command: 'deletepack', description: 'Supprimer ton pack' },
    { command: 'help', description: 'Toutes les commandes' },
];

const HELP = [
    '👋 <b>Je crée tes packs d’emojis !</b>',
    '',
    '/createpack <i>Nom</i> : crée un pack',
    '/addemoji <i>emojis</i> : ajoute des emojis à ton pack (emojis d’autres packs, emojis normaux, '
        + 'stickers, images, ou un lien t.me/addemoji/… pour ajouter tout un pack)',
    '/removeemoji <i>emojis</i> : retire des emojis de ton pack',
    '/renamepack <i>Nom</i> : renomme ton pack',
    '/copypack <i>lien</i> : copie un pack entier dans un nouveau pack',
    '/seticon <i>emoji</i> : choisit l’icône de ton pack',
    '/mypacks : tes packs et celui que tu utilises',
    '/deletepack : supprime ton pack',
    '',
    '💡 Tu peux aussi répondre à un message avec /addemoji ou /removeemoji.',
    '💡 Je marche en privé et dans les groupes. En privé, tu peux même m’envoyer les emojis sans commande.',
].join('\n');

const NO_PACK = 'Tu n’as pas de pack sélectionné.\nCrée-en un avec <code>/createpack Nom du pack</code> '
    + 'ou choisis-en un avec /mypacks.';

class FatalError extends Error {}
class PackFullError extends Error {}

const escapeHtml = (text) => String(text).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c]);
const packLink = (name) => `https://t.me/addemoji/${name}`;
const stickerFormat = (sticker) => (sticker.is_animated ? 'animated' : sticker.is_video ? 'video' : 'static');
const explain = (err) => (err.description || err.message || String(err)).replace(/^Bad Request: /, '');
const isGone = (err) => /STICKERSET_INVALID/i.test(err.description || '');
const cut = (text, max = 64) => Array.from(text).slice(0, max).join('').trim();

/** Pack names must end with _by_<bot> and only use letters, digits and single underscores. */
function makePackName(title, botUsername) {
    const suffix = `_by_${botUsername}`;
    const random = Math.random().toString(36).slice(2, 6);
    let base = title.normalize('NFKD').replace(/[^A-Za-z0-9]/g, '');
    if (!/^[A-Za-z]/.test(base)) base = `emoji${base}`;
    base = base.slice(0, Math.max(1, 64 - suffix.length - random.length - 1));
    return `${base}_${random}${suffix}`;
}

function nextTitle(title) {
    const match = /^(.*?) (\d+)$/.exec(title);
    return match ? `${match[1]} ${Number(match[2]) + 1}` : `${title} 2`;
}

function startBot({ token, apiRoot, dataFile, pollTimeout = 50 }) {
    const api = createClient(token, apiRoot);
    const store = new Store(dataFile);
    const queues = new Map();
    let me = null;
    let running = true;

    // ----- helpers -------------------------------------------------------------

    function reply(ctx, text, extra = {}) {
        return api.call('sendMessage', {
            chat_id: ctx.chatId,
            text,
            parse_mode: 'HTML',
            link_preview_options: { is_disabled: true },
            reply_parameters: { message_id: ctx.msg.message_id, allow_sending_without_reply: true },
            ...extra,
        });
    }

    function editButtonMessage(query, text) {
        if (!query.message) return null;
        return api.call('editMessageText', {
            chat_id: query.message.chat.id,
            message_id: query.message.message_id,
            text,
            parse_mode: 'HTML',
            link_preview_options: { is_disabled: true },
        }).catch(() => {});
    }

    const answer = (query, text) => api.call('answerCallbackQuery', { callback_query_id: query.id, text });

    const currentPack = (user) => user.packs.find((pack) => pack.name === user.current) || null;

    function forgetPack(user, name) {
        user.packs = user.packs.filter((pack) => pack.name !== name);
        if (user.current === name) user.current = null;
        if (user.pendingDelete === name) user.pendingDelete = null;
        store.save();
    }

    /** Sources of the message itself + of the message it replies to. */
    function allSources(msg) {
        const replied = msg.reply_to_message;
        const usable = replied && !replied.forum_topic_created && replied.from?.id !== me.id;
        return [...extractSources(msg), ...(usable ? extractSources(replied) : [])];
    }

    async function getCustomEmojis(ids) {
        const stickers = [];
        for (let i = 0; i < ids.length; i += 200) {
            stickers.push(...await api.call('getCustomEmojiStickers', { custom_emoji_ids: ids.slice(i, i + 200) }));
        }
        return stickers;
    }

    const customIds = (sources) => [...new Set(sources.filter((s) => s.type === 'custom').map((s) => s.id))];

    // ----- updates ---------------------------------------------------------------

    // one task at a time per user, so two batches never mix
    function enqueue(userId, chatId, task) {
        const next = (queues.get(userId) || Promise.resolve()).then(task).catch((err) => {
            console.error(err);
            return api.call('sendMessage', {
                chat_id: chatId,
                text: `❌ Oups, une erreur : ${escapeHtml(explain(err))}`,
                parse_mode: 'HTML',
            }).catch(() => {});
        });
        queues.set(userId, next);
        next.then(() => {
            if (queues.get(userId) === next) queues.delete(userId);
        });
    }

    function dispatch(update) {
        const query = update.callback_query;
        if (query) {
            enqueue(query.from.id, query.message?.chat.id ?? query.from.id, () => onButton(query));
            return;
        }
        const msg = update.message;
        if (!msg || !msg.from || msg.from.is_bot) return;
        enqueue(msg.from.id, msg.chat.id, () => onMessage(msg));
    }

    function parseCommand(msg) {
        const text = msg.text ?? msg.caption ?? '';
        const first = (msg.entities ?? msg.caption_entities ?? [])[0];
        if (!first || first.type !== 'bot_command' || first.offset !== 0) return null;
        const [name, mention] = text.slice(1, first.length).toLowerCase().split('@');
        if (mention && mention !== me.username.toLowerCase()) return null;
        return { name, end: first.length, args: text.slice(first.length).trim() };
    }

    const handlers = {
        start: showHelp,
        help: showHelp,
        aide: showHelp,
        createpack: createPack,
        newpack: createPack,
        addemoji: addCommand,
        add: addCommand,
        removeemoji: removeEmojis,
        remove: removeEmojis,
        renamepack: renamePack,
        rename: renamePack,
        copypack: copyPack,
        clonepack: copyPack,
        seticon: setIcon,
        mypacks: listPacks,
        packs: listPacks,
        deletepack: askDeletePack,
        cancel,
    };

    async function onMessage(msg) {
        const ctx = {
            msg,
            userId: msg.from.id,
            chatId: msg.chat.id,
            user: store.user(msg.from.id),
            isPrivate: msg.chat.type === 'private',
        };
        const command = parseCommand(msg);
        if (!command) {
            // in private chat, emojis sent without a command are added too
            if (ctx.isPrivate) return addEmojis(ctx, extractSources(msg));
            return null;
        }
        const handler = handlers[command.name];
        if (handler) return handler(ctx, command);
        return ctx.isPrivate ? showHelp(ctx) : null;
    }

    // ----- commands --------------------------------------------------------------

    function showHelp(ctx) {
        return reply(ctx, HELP);
    }

    async function createPack(ctx, command) {
        // "/createpack Jz Brawl 🔥💀": the name is the text before the first emoji or link
        const own = extractSources(ctx.msg);
        const text = ctx.msg.text ?? ctx.msg.caption ?? '';
        const offsets = own.map((s) => s.at).filter((at) => at !== undefined);
        const title = cut(text.slice(command.end, offsets.length ? Math.min(...offsets) : text.length));
        if (!title) {
            return reply(ctx, 'Donne un nom à ton pack :\n<code>/createpack Jz Brawl</code>\n\n'
                + '💡 Tu peux mettre des emojis juste après le nom pour les ajouter direct.');
        }
        ctx.user.pendingTitle = title;
        ctx.user.pendingDelete = null;
        store.save();

        const sources = allSources(ctx.msg);
        if (sources.length) return addEmojis(ctx, sources);
        return reply(ctx, `🆕 Pack « <b>${escapeHtml(title)}</b> » prêt !\n\n`
            + 'Ajoute tes emojis avec <code>/addemoji</code> suivi des emojis (ou réponds à un message avec /addemoji).\n'
            + 'Telegram crée le pack dès le premier emoji ajouté.');
    }

    function addCommand(ctx) {
        const sources = allSources(ctx.msg);
        if (!sources.length) {
            return reply(ctx, 'Envoie <code>/addemoji</code> suivi de tes emojis 🔥💀…\n\n'
                + '• des emojis d’autres packs ou des emojis normaux\n'
                + '• un lien <code>t.me/addemoji/…</code> pour ajouter tout un pack\n'
                + '• ou réponds à un message (emojis, sticker, image) avec /addemoji');
        }
        return addEmojis(ctx, sources);
    }

    async function renamePack(ctx, command) {
        const title = cut(command.args);
        if (!title) return reply(ctx, 'Écris le nouveau nom :\n<code>/renamepack Nouveau nom</code>');
        const { user } = ctx;
        if (user.pendingTitle) {
            user.pendingTitle = title;
            store.save();
            return reply(ctx, `✏️ Ton nouveau pack s’appellera « <b>${escapeHtml(title)}</b> ».`);
        }
        const pack = currentPack(user);
        if (!pack) return reply(ctx, NO_PACK);
        try {
            await api.call('setStickerSetTitle', { name: pack.name, title });
        } catch (err) {
            if (!isGone(err)) throw err;
            forgetPack(user, pack.name);
            return reply(ctx, `Le pack « ${escapeHtml(pack.title)} » n’existe plus.\n${NO_PACK}`);
        }
        pack.title = title;
        store.save();
        return reply(ctx, `✏️ Pack renommé en « <b>${escapeHtml(title)}</b> » !\n${packLink(pack.name)}`);
    }

    async function copyPack(ctx, command) {
        const link = allSources(ctx.msg).find((s) => s.type === 'pack');
        if (!link) {
            return reply(ctx, 'Envoie le lien du pack à copier :\n<code>/copypack https://t.me/addemoji/NomDuPack</code>\n\n'
                + '💡 Tu peux écrire un nom après le lien pour ta copie.');
        }
        let set;
        try {
            set = await api.call('getStickerSet', { name: link.name });
        } catch {
            return reply(ctx, `❌ Je ne trouve pas le pack « ${escapeHtml(link.name)} ».`);
        }
        const title = cut(stripPackLinks(command.args)) || cut(set.title);
        return addEmojis(ctx, [{ type: 'set', set }], { newPackTitle: title });
    }

    async function setIcon(ctx) {
        const { user } = ctx;
        if (!user.packs.length) return reply(ctx, NO_PACK);
        const ids = customIds(allSources(ctx.msg));
        if (!ids.length) return reply(ctx, 'Envoie <code>/seticon</code> suivi d’un emoji de ton pack.');
        const mine = new Map(user.packs.map((pack) => [pack.name, pack]));
        const stickers = await getCustomEmojis(ids);
        const icon = stickers.find((s) => s.set_name === user.current) || stickers.find((s) => mine.has(s.set_name));
        if (!icon) return reply(ctx, 'Cet emoji n’est dans aucun de tes packs. Envoie /seticon suivi d’un emoji de ton pack.');
        const pack = mine.get(icon.set_name);
        await api.call('setCustomEmojiStickerSetThumbnail', { name: pack.name, custom_emoji_id: icon.custom_emoji_id });
        return reply(ctx, `🖼️ Icône du pack « <b>${escapeHtml(pack.title)}</b> » changée !`);
    }

    async function removeEmojis(ctx) {
        const ids = customIds(allSources(ctx.msg));
        if (!ids.length) {
            return reply(ctx, 'Envoie <code>/removeemoji</code> suivi des emojis de ton pack à retirer, '
                + 'ou réponds avec /removeemoji à un message qui les contient.');
        }
        const mine = new Set(ctx.user.packs.map((pack) => pack.name));
        let removed = 0;
        let notMine = 0;
        const failed = [];
        for (const sticker of await getCustomEmojis(ids)) {
            if (!mine.has(sticker.set_name)) {
                notMine++;
                continue;
            }
            try {
                await api.call('deleteStickerFromSet', { sticker: sticker.file_id });
                removed++;
            } catch (err) {
                failed.push(explain(err));
            }
        }
        const lines = [`🗑️ ${removed} emoji(s) retiré(s).`];
        if (notMine) lines.push(`${notMine} emoji(s) ignoré(s) : ils ne sont pas dans tes packs.`);
        if (failed.length) lines.push(`❌ ${failed.length} échec(s) : ${escapeHtml(failed[0])}`);
        return reply(ctx, lines.join('\n'));
    }

    async function listPacks(ctx) {
        const { user } = ctx;
        const lines = [];
        for (const pack of [...user.packs]) {
            let count;
            try {
                count = (await api.call('getStickerSet', { name: pack.name })).stickers.length;
            } catch (err) {
                if (!isGone(err)) throw err;
                forgetPack(user, pack.name);
                continue;
            }
            const mark = pack.name === user.current && !user.pendingTitle ? '👉' : '▫️';
            lines.push(`${mark} <b>${escapeHtml(pack.title)}</b> (${count}/${MAX_EMOJIS})\n${packLink(pack.name)}`);
        }
        if (user.pendingTitle) lines.push(`👉 🆕 <b>${escapeHtml(user.pendingTitle)}</b> (créé au premier /addemoji)`);
        if (!lines.length) return reply(ctx, 'Tu n’as pas encore de pack. Crée-en un avec\n<code>/createpack Nom du pack</code>');

        const keyboard = user.packs.map((pack, i) => [{
            text: `${pack.name === user.current && !user.pendingTitle ? '✅ ' : ''}${pack.title}`,
            callback_data: `use:${ctx.userId}:${i}`,
        }]);
        return reply(ctx, `Tes packs (👉 = celui que j’utilise) :\n\n${lines.join('\n\n')}`
            + (keyboard.length ? '\n\nAppuie sur un pack pour l’utiliser.' : ''), {
            reply_markup: keyboard.length ? { inline_keyboard: keyboard } : undefined,
        });
    }

    async function askDeletePack(ctx) {
        const pack = currentPack(ctx.user);
        if (!pack) return reply(ctx, NO_PACK);
        ctx.user.pendingDelete = pack.name;
        store.save();
        return reply(ctx, `⚠️ Supprimer le pack « <b>${escapeHtml(pack.title)}</b> » ?\nC’est définitif, il disparaîtra pour tout le monde.`, {
            reply_markup: {
                inline_keyboard: [[
                    { text: '🗑️ Oui, supprimer', callback_data: `del:${ctx.userId}:yes` },
                    { text: 'Annuler', callback_data: `del:${ctx.userId}:no` },
                ]],
            },
        });
    }

    function cancel(ctx) {
        ctx.user.pendingTitle = null;
        ctx.user.pendingDelete = null;
        store.save();
        return reply(ctx, 'OK, annulé.');
    }

    async function onButton(query) {
        const match = /^(use|del):(\d+):(\w+)$/.exec(query.data || '');
        if (!match) return answer(query);
        const [, action, owner, value] = match;
        if (Number(owner) !== query.from.id) return answer(query, 'Ce bouton n’est pas pour toi 😉');
        const user = store.user(query.from.id);

        if (action === 'use') {
            const pack = user.packs[Number(value)];
            if (!pack) return answer(query, 'Pack introuvable');
            user.current = pack.name;
            user.pendingTitle = null;
            store.save();
            await answer(query, `J’utilise « ${pack.title} »`);
            return editButtonMessage(query, `✅ J’utilise maintenant le pack « <b>${escapeHtml(pack.title)}</b> ».\n${packLink(pack.name)}`);
        }

        const pack = user.packs.find((p) => p.name === user.pendingDelete);
        user.pendingDelete = null;
        store.save();
        if (value !== 'yes' || !pack) {
            await answer(query, 'Annulé');
            return editButtonMessage(query, 'OK, je ne supprime rien.');
        }
        try {
            await api.call('deleteStickerSet', { name: pack.name });
        } catch (err) {
            if (!isGone(err)) throw err;
        }
        forgetPack(user, pack.name);
        await answer(query, 'Pack supprimé');
        return editButtonMessage(query, `🗑️ Pack « <b>${escapeHtml(pack.title)}</b> » supprimé.`);
    }

    // ----- adding emojis -------------------------------------------------------

    function stickerItem(sticker, fallback) {
        const emoji = sticker.emoji || fallback || FALLBACK_EMOJI;
        return { kind: 'sticker', sticker, emoji, key: `s:${sticker.file_unique_id}`, uniqueId: sticker.file_unique_id, label: emoji };
    }

    async function resolveItems(sources) {
        const byId = new Map((await getCustomEmojis(customIds(sources))).map((sticker) => [sticker.custom_emoji_id, sticker]));
        const items = [];
        const problems = [];
        for (const source of sources) {
            if (source.type === 'custom') {
                const sticker = byId.get(source.id);
                if (sticker) items.push(stickerItem(sticker, source.fallback));
                else problems.push(`${source.fallback} : emoji introuvable`);
            } else if (source.type === 'sticker') {
                items.push(stickerItem(source.sticker));
            } else if (source.type === 'set') {
                for (const sticker of source.set.stickers) items.push(stickerItem(sticker));
            } else if (source.type === 'pack') {
                try {
                    const set = await api.call('getStickerSet', { name: source.name });
                    for (const sticker of set.stickers) items.push(stickerItem(sticker));
                } catch (err) {
                    problems.push(`pack ${source.name} : ${explain(err)}`);
                }
            } else if (source.type === 'unicode') {
                items.push({ kind: 'unicode', emoji: source.emoji, key: `e:${source.emoji}`, label: source.emoji });
            } else if (source.type === 'file') {
                const emoji = source.emoji || FALLBACK_EMOJI;
                items.push({ kind: 'file', fileId: source.fileId, format: source.format, emoji, key: `f:${source.uniqueId}`, uniqueId: source.uniqueId, label: 'image' });
            }
        }
        return { items, problems };
    }

    /** Builds a ready-to-upload 100x100 file for an item. */
    async function prepareFile(item) {
        if (item.kind === 'unicode') {
            return { format: 'static', ext: 'png', data: await toStaticEmoji(await fetchEmojiImage(item.emoji)) };
        }
        const isSticker = item.kind === 'sticker';
        const format = isSticker ? stickerFormat(item.sticker) : item.format;
        const data = await api.downloadFile(isSticker ? item.sticker.file_id : item.fileId);
        if (format === 'animated') return { format, ext: 'tgs', data: rescaleTgs(data) };
        if (format === 'video') {
            const small = isSticker && item.sticker.width === 100 && item.sticker.height === 100;
            return { format, ext: 'webm', data: small ? data : await rescaleWebm(data) };
        }
        // emojis from other packs are already 100x100: no resizing needed
        if (isSticker && item.sticker.width === 100 && item.sticker.height === 100) return { format: 'static', ext: 'webp', data };
        return { format: 'static', ext: 'png', data: await toStaticEmoji(data) };
    }

    function checkAccess(err) {
        if (/PEER_ID_INVALID|USER_ID_INVALID|user not found/i.test(err.description || '')) {
            throw new FatalError(`envoie-moi d’abord /start en privé (https://t.me/${me.username}) puis réessaie`);
        }
    }

    /** Puts one sticker in the target pack, creating the pack if it doesn't exist yet. */
    async function putSticker(ctx, target, input, files, progress) {
        const options = { onWait: (seconds) => progress.waiting(seconds) };

        if (target.pack) {
            try {
                await api.call('addStickerToSet', { user_id: ctx.userId, name: target.pack.name, sticker: input }, files, options);
            } catch (err) {
                checkAccess(err);
                if (/STICKERS_TOO_MUCH|too much/i.test(err.description || '')) throw new PackFullError();
                if (isGone(err)) {
                    forgetPack(ctx.user, target.pack.name);
                    throw new FatalError(`le pack « ${target.pack.title} » n’existe plus. Crée-en un nouveau avec /createpack`);
                }
                throw err;
            }
            return;
        }

        for (let attempt = 0; ; attempt++) {
            const name = makePackName(target.title, me.username);
            try {
                await api.call('createNewStickerSet', {
                    user_id: ctx.userId,
                    name,
                    title: target.title,
                    sticker_type: 'custom_emoji',
                    stickers: [input],
                }, files, options);
            } catch (err) {
                checkAccess(err);
                if (attempt < 3 && /occupied/i.test(err.description || '')) continue;
                throw err;
            }
            target.pack = { name, title: target.title, added: {} };
            ctx.user.packs.push(target.pack);
            ctx.user.current = name;
            if (target.fromPending) ctx.user.pendingTitle = null;
            store.save();
            return;
        }
    }

    /** Tries to copy the emoji directly first, then falls back to re-uploading the file. */
    async function addOne(ctx, item, target, progress) {
        const modes = item.kind === 'sticker' && item.sticker.type === 'custom_emoji' ? ['reuse', 'upload'] : ['upload'];
        let lastError;
        for (const mode of modes) {
            let input;
            let files = null;
            try {
                if (mode === 'reuse') {
                    input = { sticker: item.sticker.file_id, format: stickerFormat(item.sticker) };
                } else {
                    const file = await prepareFile(item);
                    input = { sticker: 'attach://emoji', format: file.format };
                    files = [{ field: 'emoji', data: file.data, filename: `emoji.${file.ext}` }];
                }
            } catch (err) {
                lastError = err;
                continue;
            }

            const emojiLists = item.emoji === FALLBACK_EMOJI ? [[FALLBACK_EMOJI]] : [[item.emoji], [FALLBACK_EMOJI]];
            for (const emojiList of emojiLists) {
                try {
                    await putSticker(ctx, target, { ...input, emoji_list: emojiList }, files, progress);
                    return;
                } catch (err) {
                    if (err instanceof FatalError || err instanceof PackFullError || err.code === 403) throw err;
                    lastError = err;
                    // the associated emoji was refused: retry the same file with ⭐
                    if (!/emoji/i.test(err.description || '')) break;
                }
            }
        }
        throw lastError;
    }

    function makeProgress(chatId, messageId) {
        let lastEdit = 0;
        let lastText = '';
        const edit = (text, retries) => api.call('editMessageText', {
            chat_id: chatId,
            message_id: messageId,
            text,
            parse_mode: 'HTML',
            link_preview_options: { is_disabled: true },
        }, null, { retries });
        return {
            update(text, force = false) {
                if (text === lastText || (!force && Date.now() - lastEdit < 3000)) return;
                lastEdit = Date.now();
                lastText = text;
                edit(text, 0).catch(() => {});
            },
            waiting(seconds) {
                this.update(`⏳ Telegram me demande de patienter ${seconds} s… (je continue tout seul)`, true);
            },
            async finish(text) {
                try {
                    await edit(text, 3);
                } catch {
                    await api.call('sendMessage', { chat_id: chatId, text, parse_mode: 'HTML', link_preview_options: { is_disabled: true } });
                }
            },
        };
    }

    /** The pack the emojis go to: a new one (copy or /createpack) or the one in use. */
    async function openTarget(ctx, newPackTitle) {
        const { user } = ctx;
        if (newPackTitle) return { pack: null, title: newPackTitle, count: 0, existing: new Set() };
        if (user.pendingTitle) return { pack: null, title: user.pendingTitle, count: 0, existing: new Set(), fromPending: true };
        const pack = currentPack(user);
        if (!pack) return null;
        try {
            const set = await api.call('getStickerSet', { name: pack.name });
            return { pack, title: pack.title, count: set.stickers.length, existing: new Set(set.stickers.map((s) => s.file_unique_id)) };
        } catch (err) {
            if (!isGone(err)) throw err;
            forgetPack(user, pack.name);
            return null;
        }
    }

    /** Remembers which source became which emoji, so sending the same emoji again doesn't duplicate it. */
    async function rememberSources(touched) {
        for (const entry of touched.values()) {
            try {
                const set = await api.call('getStickerSet', { name: entry.pack.name });
                const ids = new Set(set.stickers.map((s) => s.file_unique_id));
                const fresh = set.stickers.slice(entry.before);
                const added = {};
                for (const [key, id] of Object.entries(entry.pack.added || {})) if (ids.has(id)) added[key] = id;
                if (fresh.length === entry.items.length) {
                    entry.items.forEach((item, i) => { added[item.key] = fresh[i].file_unique_id; });
                }
                entry.pack.added = added;
            } catch (err) {
                console.error('rememberSources:', explain(err));
            }
        }
        store.save();
    }

    async function addEmojis(ctx, sources, { newPackTitle } = {}) {
        if (!sources.length) return showHelp(ctx);

        let target = await openTarget(ctx, newPackTitle);
        if (!target) return reply(ctx, NO_PACK);

        const status = await reply(ctx, '⏳ Je regarde tes emojis…');
        const progress = makeProgress(ctx.chatId, status.message_id);
        const { items, problems } = await resolveItems(sources);

        // skip duplicates and emojis already in the pack
        const seen = new Set();
        let skipped = 0;
        const todo = items.filter((item) => {
            const known = target.pack?.added?.[item.key];
            if (seen.has(item.key) || target.existing.has(item.uniqueId) || (known && target.existing.has(known))) {
                skipped++;
                return false;
            }
            seen.add(item.key);
            return true;
        });

        const touched = new Map(); // pack name -> { pack, before, items }
        const failed = [];
        let added = 0;
        let failedInARow = 0;
        let aborted = null;

        for (let i = 0; i < todo.length;) {
            const item = todo[i];
            if (target.count >= MAX_EMOJIS) {
                target = { pack: null, title: nextTitle(target.title), count: 0, existing: new Set() };
            }
            progress.update(`⏳ Ajout en cours… ${added + failed.length}/${todo.length}`);
            const before = target.count;
            try {
                await addOne(ctx, item, target, progress);
                target.count++;
                added++;
                failedInARow = 0;
                const entry = touched.get(target.pack.name) || { pack: target.pack, before, items: [] };
                entry.items.push(item);
                touched.set(target.pack.name, entry);
            } catch (err) {
                if (err instanceof PackFullError && target.count > 0) {
                    target.count = MAX_EMOJIS; // retry this emoji in a new pack
                    continue;
                }
                if (err instanceof FatalError || err.code === 403) {
                    aborted = explain(err);
                    break;
                }
                failed.push({ item, reason: explain(err) });
                if (++failedInARow >= 5 && added === 0) {
                    aborted = `Telegram refuse tout (${explain(err)})`;
                    break;
                }
            }
            i++;
        }

        await rememberSources(touched);

        const lines = [];
        if (!items.length && !problems.length) lines.push('Je n’ai trouvé aucun emoji dans ton message.');
        if (added) lines.push(`✅ <b>${added}</b> emoji(s) ajouté(s) !`);
        if (skipped) lines.push(`⏭️ ${skipped} déjà dans le pack (ou en double)`);
        const notAdded = [...problems, ...failed.map(({ item, reason }) => `${item.label} : ${reason}`)];
        if (notAdded.length) {
            lines.push(`❌ ${notAdded.length} pas ajouté(s) :`);
            for (const line of notAdded.slice(0, 10)) lines.push(`• ${escapeHtml(line)}`);
            if (notAdded.length > 10) lines.push(`• … et ${notAdded.length - 10} autre(s)`);
        }
        if (aborted) lines.push(`⚠️ Arrêté : ${escapeHtml(aborted)}`);
        const packs = touched.size ? [...touched.values()].map((entry) => entry.pack) : target.pack ? [target.pack] : [];
        for (const pack of packs) lines.push(`\n📦 <b>${escapeHtml(pack.title)}</b>\n${packLink(pack.name)}`);
        return progress.finish(lines.join('\n'));
    }

    // ----- polling -------------------------------------------------------------

    async function poll() {
        me = await api.call('getMe');
        await api.call('setMyCommands', { commands: COMMANDS }).catch(() => {});
        console.log(`✅ Bot démarré : https://t.me/${me.username}`);

        let offset = 0;
        while (running) {
            let updates;
            try {
                updates = await api.call('getUpdates', {
                    offset,
                    timeout: pollTimeout,
                    allowed_updates: ['message', 'callback_query'],
                }, null, { retries: 0 });
            } catch (err) {
                if (!running) break;
                console.error('getUpdates:', explain(err));
                await sleep(3000);
                continue;
            }
            for (const update of updates) {
                offset = update.update_id + 1;
                dispatch(update);
            }
        }
    }

    const done = poll();
    return {
        done,
        stop() {
            running = false;
            return Promise.all([done, ...queues.values()]);
        },
    };
}

// ===== Démarrage ==================================================

module.exports = { loadSharp, startBot, makePackName, nextTitle, extractSources, stripPackLinks, codeCandidates, toStaticEmoji, rescaleTgs, rescaleWebm };

if (require.main === module) {
    require('dotenv').config({ path: path.join(__dirname, '.env') });
    const token = process.env.BOT_TOKEN;
    if (!token) {
        console.error('❌ BOT_TOKEN manquant : crée un bot avec @BotFather et mets son token dans le fichier .env');
        process.exit(1);
    }
    const bot = startBot({
        token,
        apiRoot: process.env.TELEGRAM_API_URL || undefined,
        dataFile: process.env.DATA_FILE || path.join(__dirname, 'data.json'),
    });
    try {
        loadSharp();
    } catch {
        console.log('ℹ️ « sharp » n’est pas installé : les emojis d’autres packs marchent, mais pas les images ni les emojis normaux.');
        console.log('   Pour les activer sur téléphone : npm install --cpu=wasm32 sharp');
    }
    bot.done.catch((err) => {
        console.error('❌ Impossible de démarrer le bot :', err.message);
        process.exit(1);
    });
}
