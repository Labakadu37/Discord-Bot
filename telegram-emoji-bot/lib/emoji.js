'use strict';

// Reads what the user sent (premium emojis, normal emojis, stickers, pack links, images).

const EMOJI_RE = /\p{Extended_Pictographic}|\p{Regional_Indicator}|⃣/u;
const PACK_LINK_RE = /t(?:elegram)?\.me\/(?:addemoji|addstickers)\/([A-Za-z][A-Za-z0-9_]{0,63})/gi;
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
    for (const { at, ...source } of found) sources.push(source);
    return sources;
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

module.exports = { extractSources, fetchEmojiImage, codeCandidates, isEmoji };
