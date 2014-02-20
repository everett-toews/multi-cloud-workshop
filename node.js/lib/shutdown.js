var async = require('async'),
  config = require('../config.json'),
  logging = require('./logging'),
  keys = require('./keys'),
  security = require('./security-groups');

var log = logging.getLogger(config.logLevel);

exports.shutdown = function(client, callback) {
  log.info('Shutting Down: ' + client.provider);
  async.series([
    function(next) { keys.destroySshKey(client, next); },
    function(next) { security.destroySecurityGroup(client, next); }
  ], function(err) {
    if (err) {
      log.error('Unable to shut down environment', err);
      callback(err);
      return;
    }

    log.info('Success: ' + client.provider);
    callback();
  });
}