require('dotenv').config({ quiet: true });

const http = require('node:http');
const crypto = require('node:crypto');
const express = require('express');
const { Server } = require('socket.io');
const twilio = require('twilio');
const db = require('./db');

const {
  PORT = 3000,
  APP_TOKEN,
  TWILIO_ACCOUNT_SID,
  TWILIO_AUTH_TOKEN,
  TWILIO_PHONE_NUMBER,
  PUBLIC_URL,
  DEFAULT_COUNTRY_CODE = '33',
} = process.env;

for (const [name, value] of Object.entries({ APP_TOKEN, TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_PHONE_NUMBER })) {
  if (!value) {
    console.error(`Variable manquante dans .env : ${name} (voir .env.example)`);
    process.exit(1);
  }
}
if (APP_TOKEN.length < 16) {
  console.error('APP_TOKEN doit faire au moins 16 caractères.');
  process.exit(1);
}

const twilioClient = twilio(TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN);
const publicUrl = PUBLIC_URL?.replace(/\/+$/, '');

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const MAX_SMS_LENGTH = 1600; // limite Twilio

/** Convertit "06 12 34 56 78", "0033612345678"... en E.164 ("+33612345678"). */
function normalizePhone(raw) {
  if (typeof raw !== 'string') return null;
  let phone = raw.replace(/[\s.\-()]/g, '');
  if (phone.startsWith('00')) phone = `+${phone.slice(2)}`;
  else if (phone.startsWith('0')) phone = `+${DEFAULT_COUNTRY_CODE}${phone.slice(1)}`;
  return /^\+[1-9]\d{6,14}$/.test(phone) ? phone : null;
}

function tokenMatches(token) {
  if (typeof token !== 'string') return false;
  const a = crypto.createHash('sha256').update(token).digest();
  const b = crypto.createHash('sha256').update(APP_TOKEN).digest();
  return crypto.timingSafeEqual(a, b);
}

function requireAuth(req, res, next) {
  const token = req.get('authorization')?.replace(/^Bearer\s+/i, '');
  if (!tokenMatches(token)) return res.status(401).json({ error: 'Token invalide' });
  next();
}

function webhookValidator(path) {
  // Twilio signe ses requêtes avec l'URL publique exacte : on la fournit si on est derrière un proxy (ngrok, etc.)
  return twilio.webhook(TWILIO_AUTH_TOKEN, publicUrl ? { url: `${publicUrl}${path}` } : {});
}

// ---------------------------------------------------------------------------
// App
// ---------------------------------------------------------------------------

const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' } });

app.set('trust proxy', true);
app.use(express.json());
app.use(express.urlencoded({ extended: false }));

app.get('/health', (req, res) => res.json({ ok: true }));

// --- Webhooks Twilio --------------------------------------------------------

// SMS reçu sur ton numéro Twilio
app.post('/webhooks/sms', webhookValidator('/webhooks/sms'), (req, res) => {
  const { From, Body = '', MessageSid, NumMedia } = req.body;
  const contact = normalizePhone(From) || From;

  if (!db.findBySid(MessageSid)) {
    let body = Body;
    if (Number(NumMedia) > 0) body = `${body}\n[${NumMedia} pièce(s) jointe(s) MMS]`.trim();
    const message = db.addMessage({ sid: MessageSid, contact, direction: 'in', body, status: 'received' });
    io.emit('message', message);
  }

  res.type('text/xml').send('<Response></Response>');
});

// Mises à jour de statut des SMS envoyés (envoyé, délivré, échec...)
app.post('/webhooks/status', webhookValidator('/webhooks/status'), (req, res) => {
  const { MessageSid, MessageStatus } = req.body;
  const message = db.updateStatus(MessageSid, MessageStatus);
  if (message) io.emit('status', message);
  res.sendStatus(204);
});

// --- API pour l'app mobile -------------------------------------------------

const api = express.Router();
api.use(requireAuth);

api.get('/me', (req, res) => res.json({ phoneNumber: TWILIO_PHONE_NUMBER }));

api.get('/conversations', (req, res) => res.json(db.getConversations()));

api.get('/conversations/:phone/messages', (req, res) => {
  const contact = normalizePhone(req.params.phone) || req.params.phone;
  const before = Number(req.query.before) || undefined;
  const limit = Math.min(Number(req.query.limit) || 50, 200);
  res.json(db.getMessages(contact, { before, limit }));
});

api.post('/conversations/:phone/read', (req, res) => {
  db.markRead(normalizePhone(req.params.phone) || req.params.phone);
  res.sendStatus(204);
});

api.delete('/conversations/:phone', (req, res) => {
  db.deleteConversation(normalizePhone(req.params.phone) || req.params.phone);
  res.sendStatus(204);
});

api.post('/messages', async (req, res) => {
  const to = normalizePhone(req.body?.to);
  const body = typeof req.body?.body === 'string' ? req.body.body.trim() : '';

  if (!to) return res.status(400).json({ error: 'Numéro invalide. Exemple : +33612345678' });
  if (!body) return res.status(400).json({ error: 'Le message est vide.' });
  if (body.length > MAX_SMS_LENGTH) return res.status(400).json({ error: `Message trop long (max ${MAX_SMS_LENGTH} caractères).` });

  let message = db.addMessage({ contact: to, direction: 'out', body, status: 'sending', read: true });
  io.emit('message', message);

  try {
    const sent = await twilioClient.messages.create({
      from: TWILIO_PHONE_NUMBER,
      to,
      body,
      ...(publicUrl && { statusCallback: `${publicUrl}/webhooks/status` }),
    });
    message = db.setSid(message.id, sent.sid, sent.status);
    io.emit('status', message);
    res.status(201).json(message);
  } catch (err) {
    console.error('Erreur Twilio :', err.message);
    message = db.setSid(message.id, `failed-${message.id}`, 'failed');
    io.emit('status', message);
    res.status(502).json({ error: err.message, message });
  }
});

app.use('/api', api);

// --- Temps réel -----------------------------------------------------------

io.use((socket, next) => {
  if (tokenMatches(socket.handshake.auth?.token)) return next();
  next(new Error('Token invalide'));
});

server.listen(PORT, () => {
  console.log(`Serveur SMS démarré sur le port ${PORT}`);
  console.log(`Numéro Twilio : ${TWILIO_PHONE_NUMBER}`);
  if (publicUrl) {
    console.log(`Webhook à configurer dans Twilio : ${publicUrl}/webhooks/sms`);
  } else {
    console.warn('PUBLIC_URL non défini : les SMS reçus et les accusés de réception ne fonctionneront pas.');
  }
});
