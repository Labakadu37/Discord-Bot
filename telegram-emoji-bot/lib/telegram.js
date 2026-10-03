'use strict';

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

module.exports = { createClient, TelegramError, sleep };
