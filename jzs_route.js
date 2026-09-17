// ── JZS Brawl file server ─────────────────────────────────────────────────
const path = require('path');
const fs   = require('fs');

// Add this to your Express app in index.js:
// require('./jzs_route')(app);

module.exports = function(app) {
    const jzsDir = path.join(__dirname, 'jzs');

    // Serve libJZSUI.so
    app.get('/jzs/libJZSUI.so', (req, res) => {
        const filePath = path.join(jzsDir, 'libJZSUI.so');
        if (!fs.existsSync(filePath)) {
            return res.status(404).send('Not found');
        }
        res.setHeader('Content-Type', 'application/octet-stream');
        res.setHeader('Content-Disposition', 'attachment; filename="libJZSUI.so"');
        res.sendFile(filePath);
        console.log('[JZS] Module served to', req.ip);
    });

    // Version check endpoint
    app.get('/jzs/version', (req, res) => {
        res.json({ version: '1.0', name: 'JZS Brawl' });
    });

    console.log('[JZS] Routes ready: /jzs/libJZSUI.so');
};
