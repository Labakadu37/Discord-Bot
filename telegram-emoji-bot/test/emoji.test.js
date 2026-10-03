'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { extractSources, codeCandidates } = require('../lib/emoji');
const { makePackName, nextTitle } = require('../lib/bot');

test('keeps the order of premium emojis, normal emojis and pack links', () => {
    const text = '🔥ab❤️ t.me/addemoji/CoolPack 👍🏽';
    const sources = extractSources({
        text,
        entities: [{ type: 'custom_emoji', offset: 4, length: 2, custom_emoji_id: '42' }],
    });
    assert.deepStrictEqual(sources, [
        { type: 'unicode', emoji: '🔥' },
        { type: 'custom', id: '42', fallback: '❤️' },
        { type: 'pack', name: 'CoolPack' },
        { type: 'unicode', emoji: '👍🏽' },
    ]);
});

test('ignores the command and reads text links', () => {
    const sources = extractSources({
        text: '/remove ici 😀',
        entities: [
            { type: 'bot_command', offset: 0, length: 7 },
            { type: 'text_link', offset: 8, length: 3, url: 'https://t.me/addstickers/Animals' },
        ],
    });
    assert.deepStrictEqual(sources, [{ type: 'pack', name: 'Animals' }, { type: 'unicode', emoji: '😀' }]);
});

test('an image uses the caption emoji instead of adding it', () => {
    const sources = extractSources({
        photo: [{ file_id: 'small', file_unique_id: 's' }, { file_id: 'big', file_unique_id: 'b' }],
        caption: 'mon logo 😎',
    });
    assert.deepStrictEqual(sources, [{ type: 'file', fileId: 'big', uniqueId: 'b', format: 'static', emoji: '😎' }]);
});

test('emoji image names', () => {
    assert.deepStrictEqual(codeCandidates('🔥').slice(0, 2), ['1f525', '1f525-fe0f']);
    assert.ok(codeCandidates('❤').includes('2764-fe0f'));
    assert.ok(codeCandidates('1️⃣').includes('0031-fe0f-20e3'));
    assert.ok(codeCandidates('1️⃣').includes('31-20e3'));
});

test('pack names follow Telegram rules', () => {
    for (const title of ['Jz Brawl', '123 éàç', '🔥🔥', 'a'.repeat(80)]) {
        const name = makePackName(title, 'my_emoji_bot');
        assert.match(name, /^[A-Za-z][A-Za-z0-9_]*_by_my_emoji_bot$/);
        assert.ok(name.length <= 64);
        assert.ok(!name.includes('__'));
    }
    assert.strictEqual(nextTitle('Jz Brawl'), 'Jz Brawl 2');
    assert.strictEqual(nextTitle('Jz Brawl 2'), 'Jz Brawl 3');
});
