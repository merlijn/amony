// const createProxyMiddleware = require('http-proxy-middleware');
//
// const serverProxy = createProxyMiddleware({
//                       target: 'http://127.0.0.1:8080',
//                       changeOrigin: true,
//                      })
//
// module.exports = function(app) {
//   app.use('/api', serverProxy);
//   app.use('/resources', serverProxy);
// };