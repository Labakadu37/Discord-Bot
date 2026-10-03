'use strict';

// Remembers each user's packs in a small JSON file (Telegram has no "list my packs" method).

const fs = require('fs');

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

module.exports = { Store };
