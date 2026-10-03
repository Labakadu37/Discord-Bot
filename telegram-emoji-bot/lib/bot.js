'use strict';

const { createClient, sleep } = require('./telegram');
const { extractSources, fetchEmojiImage } = require('./emoji');
const { toStaticEmoji, rescaleTgs, rescaleWebm } = require('./convert');
const { Store } = require('./store');

const MAX_EMOJIS = 200; // Telegram limit for a custom emoji pack
const FALLBACK_EMOJI = '⭐';

const COMMANDS = [
    { command: 'newpack', description: 'Créer un nouveau pack d’emojis' },
    { command: 'packs', description: 'Mes packs / choisir celui à remplir' },
    { command: 'remove', description: 'Retirer des emojis de mon pack' },
    { command: 'help', description: 'Comment ça marche' },
];

const HELP = [
    '👋 <b>Je crée tes packs d’emojis !</b>',
    '',
    '1️⃣ <code>/newpack Nom du pack</code> pour créer un pack',
    '2️⃣ Envoie-moi des emojis : des emojis d’autres packs, des emojis normaux, des stickers, des images, '
        + 'ou un lien <code>t.me/addemoji/…</code> pour copier un pack entier',
    '3️⃣ Je les mets direct dans ton pack et je te donne le lien ✨',
    '',
    '/packs : voir tes packs et choisir celui à remplir',
    '/remove : réponds avec /remove à un message qui contient des emojis de ton pack pour les retirer',
].join('\n');

class FatalError extends Error {}
class PackFullError extends Error {}

const escapeHtml = (text) => String(text).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c]);
const packLink = (name) => `https://t.me/addemoji/${name}`;
const stickerFormat = (sticker) => (sticker.is_animated ? 'animated' : sticker.is_video ? 'video' : 'static');
const explain = (err) => (err.description || err.message || String(err)).replace(/^Bad Request: /, '');

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
    let botUsername = null;
    let running = true;

    const send = (chatId, text, extra = {}) => api.call('sendMessage', {
        chat_id: chatId,
        text,
        parse_mode: 'HTML',
        link_preview_options: { is_disabled: true },
        ...extra,
    });

    // one task at a time per user, so two batches never mix
    function enqueue(userId, chatId, task) {
        const next = (queues.get(userId) || Promise.resolve()).then(task).catch((err) => {
            console.error(err);
            return send(chatId, `❌ Oups, une erreur : ${escapeHtml(explain(err))}`).catch(() => {});
        });
        queues.set(userId, next);
        next.then(() => {
            if (queues.get(userId) === next) queues.delete(userId);
        });
    }

    function dispatch(update) {
        const query = update.callback_query;
        if (query) {
            enqueue(query.from.id, query.message?.chat.id ?? query.from.id, () => onCallback(query));
            return;
        }
        const msg = update.message;
        if (!msg || !msg.from || msg.chat.type !== 'private') return;
        enqueue(msg.from.id, msg.chat.id, () => onMessage(msg));
    }

    function parseCommand(msg) {
        const text = msg.text || '';
        const first = (msg.entities || [])[0];
        if (!first || first.type !== 'bot_command' || first.offset !== 0) return null;
        const [name, mention] = text.slice(1, first.length).toLowerCase().split('@');
        if (mention && mention !== botUsername.toLowerCase()) return null;
        return { name, args: text.slice(first.length).trim() };
    }

    async function onMessage(msg) {
        const ctx = { userId: msg.from.id, chatId: msg.chat.id, user: store.user(msg.from.id) };
        const command = parseCommand(msg);
        if (!command) return addEmojis(msg, ctx);

        switch (command.name) {
            case 'newpack':
            case 'nouveau':
                return newPack(ctx, command.args);
            case 'packs':
                return listPacks(ctx);
            case 'remove':
            case 'supprimer':
                return removeEmojis(msg, ctx);
            case 'cancel':
                ctx.user.pendingTitle = null;
                store.save();
                return send(ctx.chatId, 'OK, annulé.');
            default:
                return send(ctx.chatId, HELP);
        }
    }

    async function newPack(ctx, args) {
        const title = Array.from(args).slice(0, 64).join('').trim();
        if (!title) return send(ctx.chatId, 'Donne un nom à ton pack, par exemple :\n<code>/newpack Jz Brawl</code>');
        ctx.user.pendingTitle = title;
        store.save();
        return send(ctx.chatId, `🆕 Pack « <b>${escapeHtml(title)}</b> » prêt !\n\n`
            + 'Envoie-moi maintenant tes emojis (emojis d’autres packs, emojis normaux, stickers, images, '
            + 'ou un lien t.me/addemoji/…). Je crée le pack dès le premier emoji.');
    }

    async function listPacks(ctx) {
        const { user } = ctx;
        if (!user.packs.length) return send(ctx.chatId, 'Tu n’as pas encore de pack. Crée-en un avec\n<code>/newpack Nom du pack</code>');
        const lines = user.packs.map((pack) => `${pack.name === user.current ? '👉' : '▫️'} <b>${escapeHtml(pack.title)}</b>\n${packLink(pack.name)}`);
        if (user.pendingTitle) lines.push(`🆕 Prochain pack : <b>${escapeHtml(user.pendingTitle)}</b> (créé au prochain emoji)`);
        const keyboard = user.packs.map((pack, i) => [{
            text: `${pack.name === user.current ? '✅ ' : ''}${pack.title}`,
            callback_data: `use:${i}`,
        }]);
        return send(ctx.chatId, `Tes packs (👉 = celui que je remplis) :\n\n${lines.join('\n\n')}\n\nAppuie sur un pack pour le remplir.`, {
            reply_markup: { inline_keyboard: keyboard },
        });
    }

    async function onCallback(query) {
        const user = store.user(query.from.id);
        const match = /^use:(\d+)$/.exec(query.data || '');
        const pack = match && user.packs[Number(match[1])];
        if (!pack) return api.call('answerCallbackQuery', { callback_query_id: query.id, text: 'Pack introuvable' });
        user.current = pack.name;
        user.pendingTitle = null;
        store.save();
        await api.call('answerCallbackQuery', { callback_query_id: query.id, text: `Je remplis « ${pack.title} »` });
        return send(query.message?.chat.id ?? query.from.id, `✅ J’ajoute maintenant tes emojis dans « <b>${escapeHtml(pack.title)}</b> ».`);
    }

    async function getCustomEmojis(ids) {
        const stickers = [];
        for (let i = 0; i < ids.length; i += 200) {
            stickers.push(...await api.call('getCustomEmojiStickers', { custom_emoji_ids: ids.slice(i, i + 200) }));
        }
        return stickers;
    }

    async function removeEmojis(msg, ctx) {
        const sources = [...extractSources(msg), ...(msg.reply_to_message ? extractSources(msg.reply_to_message) : [])];
        const ids = [...new Set(sources.filter((s) => s.type === 'custom').map((s) => s.id))];
        if (!ids.length) {
            return send(ctx.chatId, 'Pour retirer des emojis : réponds avec /remove à un message qui contient '
                + 'des emojis de ton pack, ou écris /remove suivi des emojis.');
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
        return send(ctx.chatId, lines.join('\n'));
    }

    // ----- adding emojis -----------------------------------------------------

    function stickerItem(sticker, fallback) {
        const emoji = sticker.emoji || fallback || FALLBACK_EMOJI;
        return { kind: 'sticker', sticker, emoji, key: `s:${sticker.file_unique_id}`, uniqueId: sticker.file_unique_id, label: emoji };
    }

    async function resolveItems(sources) {
        const ids = [...new Set(sources.filter((s) => s.type === 'custom').map((s) => s.id))];
        const byId = new Map((await getCustomEmojis(ids)).map((sticker) => [sticker.custom_emoji_id, sticker]));
        const items = [];
        const problems = [];
        for (const source of sources) {
            if (source.type === 'custom') {
                const sticker = byId.get(source.id);
                if (sticker) items.push(stickerItem(sticker, source.fallback));
                else problems.push(`${source.fallback} : emoji introuvable`);
            } else if (source.type === 'sticker') {
                items.push(stickerItem(source.sticker));
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
        return { format: 'static', ext: 'png', data: await toStaticEmoji(data) };
    }

    /** Puts one sticker in the target pack, creating the pack if it doesn't exist yet. */
    async function putSticker(ctx, target, input, files, progress) {
        const options = { onWait: (seconds) => progress.waiting(seconds) };

        if (target.pack) {
            try {
                await api.call('addStickerToSet', { user_id: ctx.userId, name: target.pack.name, sticker: input }, files, options);
            } catch (err) {
                if (/STICKERS_TOO_MUCH|too much/i.test(err.description || '')) throw new PackFullError();
                if (/STICKERSET_INVALID/i.test(err.description || '')) {
                    ctx.user.packs = ctx.user.packs.filter((pack) => pack.name !== target.pack.name);
                    if (ctx.user.current === target.pack.name) ctx.user.current = null;
                    store.save();
                    throw new FatalError(`le pack « ${target.pack.title} » n’existe plus. Crée-en un nouveau avec /newpack`);
                }
                throw err;
            }
            return;
        }

        for (let attempt = 0; ; attempt++) {
            const name = makePackName(target.title, botUsername);
            try {
                await api.call('createNewStickerSet', {
                    user_id: ctx.userId,
                    name,
                    title: target.title,
                    sticker_type: 'custom_emoji',
                    stickers: [input],
                }, files, options);
            } catch (err) {
                if (attempt < 3 && /occupied/i.test(err.description || '')) continue;
                throw err;
            }
            target.pack = { name, title: target.title, added: {} };
            ctx.user.packs.push(target.pack);
            ctx.user.current = name;
            ctx.user.pendingTitle = null;
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
                    await send(chatId, text);
                }
            },
        };
    }

    async function openTarget(ctx) {
        const { user } = ctx;
        if (user.pendingTitle) return { pack: null, title: user.pendingTitle, count: 0, existing: new Set() };
        const pack = user.packs.find((p) => p.name === user.current);
        if (!pack) return null;
        try {
            const set = await api.call('getStickerSet', { name: pack.name });
            return { pack, title: pack.title, count: set.stickers.length, existing: new Set(set.stickers.map((s) => s.file_unique_id)) };
        } catch (err) {
            if (!/STICKERSET_INVALID/i.test(err.description || '')) throw err;
            user.packs = user.packs.filter((p) => p.name !== pack.name);
            user.current = null;
            store.save();
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

    async function addEmojis(msg, ctx) {
        const sources = extractSources(msg);
        if (!sources.length) return send(ctx.chatId, HELP);

        let target = await openTarget(ctx);
        if (!target) {
            return send(ctx.chatId, 'D’abord, crée ton pack avec un nom :\n<code>/newpack Nom du pack</code>\npuis renvoie-moi les emojis.');
        }

        const status = await send(ctx.chatId, '⏳ Je regarde tes emojis…', {
            reply_parameters: { message_id: msg.message_id, allow_sending_without_reply: true },
        });
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
        const me = await api.call('getMe');
        botUsername = me.username;
        await api.call('setMyCommands', { commands: COMMANDS }).catch(() => {});
        console.log(`✅ Bot démarré : https://t.me/${botUsername}`);

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

module.exports = { startBot, makePackName, nextTitle };
