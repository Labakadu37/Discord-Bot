'use strict';

const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '.env') });
const { startBot } = require('./lib/bot');

const token = process.env.BOT_TOKEN;
if (!token) {
    console.error('❌ BOT_TOKEN manquant : crée un bot avec @BotFather et mets son token dans le fichier .env');
    process.exit(1);
}

startBot({
    token,
    apiRoot: process.env.TELEGRAM_API_URL || undefined,
    dataFile: process.env.DATA_FILE || path.join(__dirname, 'data.json'),
}).done.catch((err) => {
    console.error('❌ Impossible de démarrer le bot :', err.message);
    process.exit(1);
});
