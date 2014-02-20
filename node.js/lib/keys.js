var async = require('async'),
  config = require('../config.json'),
  fs = require('fs'),
  logging = require('./logging');

var log = logging.getLogger(config.logLevel),
  securityGroupName = config.securityGroupName;

exports.uploadSshKey = function(client, callback) {
  log.verbose('Uploading SSH Key: ' + client.provider);
  client.addKey({
    name: securityGroupName,
    key: fs.readFileSync(process.cwd() + config.keys.public).toString()
  }, callback);
};

exports.destroySshKey = function(client, callback) {
  log.verbose('Destroying SSH Key: ' + client.provider);
  client.destroyKey(securityGroupName, callback)
};


