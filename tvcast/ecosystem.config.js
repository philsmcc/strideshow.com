/**
 * pm2 config for the PanelCast signaling server.
 *
 * Separate pm2 app entry from the main StrideShow process so
 * restarting one never affects the other.
 *
 *   cd tvcast && pm2 start ecosystem.config.js && pm2 save
 */
module.exports = {
  apps: [
    {
      name: 'panelcast',
      script: 'signaling/server.js',
      cwd: __dirname,
      instances: 1,
      // Must stay 'fork': rooms live in memory, so cluster workers would each
      // hold a different set of rooms and pairing would fail intermittently.
      exec_mode: 'fork',
      watch: false,
      // Small footprint; restart if it ever leaks past this.
      max_memory_restart: '200M',
      env: {
        NODE_ENV: 'production',
        PANELCAST_PORT: 3100,
        PANELCAST_HOST: '127.0.0.1',
        PANELCAST_PUBLIC_BASE: 'https://www.strideshow.com/panelcast',
        // Uncomment once coturn is running (see docs/DEPLOY.md):
        // PANELCAST_TURN_URL: 'turn:turn.strideshow.com:3478',
        // PANELCAST_TURN_USER: 'panelcast',
        // PANELCAST_TURN_PASS: 'change-me',
      },
      out_file: 'logs/panelcast-out.log',
      error_file: 'logs/panelcast-err.log',
      merge_logs: true,
      time: true,
      autorestart: true,
      restart_delay: 2000,
    },
  ],
};
