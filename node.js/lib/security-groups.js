var async = require('async'),
  config = require('../config.json'),
  logging = require('./logging');

var log = logging.getLogger(config.logLevel),
  securityGroupName = config.securityGroupName;

exports.createSecurityGroup = function(client, callback) {
  log.verbose('Creating Security Group: ' + client.provider);
  if (client.provider === 'rackspace') {
    log.info('Skipping SG: ' + client.provider);
    callback();
  }
  else if (client.provider === 'openstack') {
    callback();
  }
  else if (client.provider === 'amazon') {
    exports.setupAwsSecurityGroup(client, callback);
  }
  else {
    callback({ unknownProvider: true });
  }
};

exports.destroySecurityGroup = function(client, callback) {
  log.verbose('Destroying Security Group: ' + client.provider);
  client.destroyGroup(securityGroupName, callback)
};

exports.setupAwsSecurityGroup = function(client, callback) {
  client.addGroup({
    name: securityGroupName,
    description: 'multi cloud portability workshop'
  }, function(err) {
    if (err) {
      callback(err);
      return;
    }

    function getRule(port) {
      return { name: securityGroupName,
        rules: {
          'IpPermissions.1.IpProtocol': 'tcp',
          'IpPermissions.1.IpRanges.1.CidrIp': '0.0.0.0/0',
          'IpPermissions.1.FromPort': port,
          'IpPermissions.1.ToPort': port
        }
      }
    }

    async.forEach([
      getRule(22), getRule(80), getRule(3000), getRule(3306)
    ], function(item, next) {
      client.addRules(item, function(err) {
        if (err) {
          log.error(err);
        }
        next(err);
      });
    }, function(err) {
      callback(err);
    });
  });
};


