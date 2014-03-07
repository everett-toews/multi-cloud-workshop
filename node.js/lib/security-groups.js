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
    exports.setupOpenstackSecurityGroup(client, callback);
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

  var name = securityGroupName;

  // early return; rackspace doesn't support groups
  if (client.provider === 'rackspace') {
    callback();
    return
  }
  // can't delete by name with openstack
  else if (client.provider === 'openstack') {
    client.listGroups(function(err, groups) {
      if (err) {
        callback(err);
        return;
      }

      groups.forEach(function(group) {
        if (group.name === securityGroupName) {
          name = group.id;
        }
      });

      log.verbose('group id', name);
      client.destroyGroup(name, callback);
    });

    return;
  }

  client.destroyGroup(name, callback)
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

exports.setupOpenstackSecurityGroup = function(client, callback) {
  client.addGroup({
    name: securityGroupName,
    description: 'multi cloud portability workshop'
  }, function(err, group) {
    if (err) {
      callback(err);
      return;
    }

    function getRule(port) {
      return { groupId: group.id,
        ipProtocol: 'tcp',
        cidr: '0.0.0.0/0',
        fromPort: port,
        toPort: port
      }
    }

    client.addRules([
      getRule(22), getRule(80), getRule(3000), getRule(3306)
    ], function(err, rules) {
      if (err) {
        log.error(err);
      }

      callback(err, rules);
    });
  });
};


