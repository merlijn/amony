const createProxyMiddleware = require('http-proxy-middleware');

const serverProxy = createProxyMiddleware({
                      target: 'http://localhost:8080',
                      changeOrigin: true,
                     })

module.exports = function(app) {
  app.use('/api', serverProxy);
  app.use('/files', serverProxy);
};