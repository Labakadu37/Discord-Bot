const path = require('node:path');
const fs = require('node:fs');
const { DatabaseSync } = require('node:sqlite');

const dbPath = process.env.DB_PATH || path.join(__dirname, '..', 'data', 'messages.db');
fs.mkdirSync(path.dirname(dbPath), { recursive: true });

const db = new DatabaseSync(dbPath);

db.exec(`
  CREATE TABLE IF NOT EXISTS messages (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    sid        TEXT UNIQUE,
    contact    TEXT NOT NULL,
    direction  TEXT NOT NULL CHECK (direction IN ('in', 'out')),
    body       TEXT NOT NULL,
    status     TEXT NOT NULL,
    read       INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
  );
  CREATE INDEX IF NOT EXISTS idx_messages_contact ON messages (contact, id);
`);

const stmts = {
  insert: db.prepare(`
    INSERT INTO messages (sid, contact, direction, body, status, read)
    VALUES (?, ?, ?, ?, ?, ?)
    RETURNING *
  `),
  updateStatus: db.prepare('UPDATE messages SET status = ? WHERE sid = ? RETURNING *'),
  setSid: db.prepare('UPDATE messages SET sid = ?, status = ? WHERE id = ? RETURNING *'),
  bySid: db.prepare('SELECT * FROM messages WHERE sid = ?'),
  conversation: db.prepare(`
    SELECT * FROM messages WHERE contact = ? AND id < ? ORDER BY id DESC LIMIT ?
  `),
  markRead: db.prepare(`UPDATE messages SET read = 1 WHERE contact = ? AND direction = 'in' AND read = 0`),
  conversations: db.prepare(`
    SELECT m.contact, m.body AS last_body, m.direction AS last_direction,
           m.status AS last_status, m.created_at AS last_at,
           (SELECT COUNT(*) FROM messages u
             WHERE u.contact = m.contact AND u.direction = 'in' AND u.read = 0) AS unread
    FROM messages m
    WHERE m.id = (SELECT MAX(id) FROM messages x WHERE x.contact = m.contact)
    ORDER BY m.id DESC
  `),
  deleteConversation: db.prepare('DELETE FROM messages WHERE contact = ?'),
};

module.exports = {
  addMessage({ sid = null, contact, direction, body, status, read = false }) {
    return stmts.insert.get(sid, contact, direction, body, status, read ? 1 : 0);
  },
  setSid(id, sid, status) {
    return stmts.setSid.get(sid, status, id);
  },
  updateStatus(sid, status) {
    return stmts.updateStatus.get(status, sid);
  },
  findBySid(sid) {
    return stmts.bySid.get(sid);
  },
  getMessages(contact, { before = Number.MAX_SAFE_INTEGER, limit = 50 } = {}) {
    return stmts.conversation.all(contact, before, limit).reverse();
  },
  markRead(contact) {
    stmts.markRead.run(contact);
  },
  getConversations() {
    return stmts.conversations.all();
  },
  deleteConversation(contact) {
    stmts.deleteConversation.run(contact);
  },
};
