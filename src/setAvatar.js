// Updates the bot's avatar (logo) with src/assets/logo.png
// Usage: npm run set-avatar
require('dotenv').config('./.env');
const fs = require('fs');
const path = require('path');
const { REST, Routes } = require('discord.js');

const logoPath = path.join(__dirname, 'assets', 'logo.png');

if (!process.env.DISCORD_TOKEN) {
    console.error('DISCORD_TOKEN is missing in .env');
    process.exit(1);
}

const avatar = `data:image/png;base64,${fs.readFileSync(logoPath).toString('base64')}`;
const rest = new REST({ version: '10' }).setToken(process.env.DISCORD_TOKEN);

rest.patch(Routes.user(), { body: { avatar } })
    .then((user) => console.log(`Avatar updated for ${user.username}`))
    .catch((error) => {
        console.error('Failed to update avatar:', error.message);
        process.exit(1);
    });
