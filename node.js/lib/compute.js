var async = require('async'),
  config = require('../config.json'),
  logging = require('./logging');

var log = logging.getLogger(config.logLevel);

exports.createServer = function(client, name, callback) {

  function createOptions() {
    var options;
    switch (client.provider) {
      case 'amazon':
        options = {
          name: name,
          flavor: config.settings.aws.flavorId,
          image: config.settings.aws.imageId,
          SecurityGroup: config.securityGroupName,
          keyname: config.securityGroupName
        };
        break;
      case 'openstack':
        options = {
          name: name,
          flavor: config.settings.hpcloud.flavorId,
          image: config.settings.hpcloud.imageId,
          keyname: config.securityGroupName,
          securityGroups: [
            { name: config.securityGroupName }
          ]
        };
        break;
      case 'rackspace':
        options =  {
          name: name,
          flavor: config.settings.rackspace.flavorId,
          image: config.settings.rackspace.imageId,
          keyname: config.securityGroupName
        }
    }
    return options;
  }

  log.verbose('Creating Server (' + name +'): ' + client.provider);
  client.createServer(createOptions(), function(err, server) {
    if (err) {
      callback(err);
      return;
    }

    var interval = Math.floor(Math.random() * (30000 - 25000 + 1) + 25000)
    log.verbose('Server Created (' + name + ',' + server.id +'), Waiting for RUNNING status: ' + client.provider);
    server.setWait({ status: server.STATUS.running }, interval, 60 * 10 * 1000, function(err) {
      if (err) {
        callback(err);
        return;
      }

      log.verbose('Server Online (' + name + ',' + server.id + '): ' + client.provider, {
        id: server.id,
        name: server.name,
        addresses: server.addresses
      });

      // HP doesn't have public IP address bound by default, so we need to call os-floating-ip
      if (client.provider === 'openstack') {

        client.allocateNewFloatingIp(function(err, floatingIp) {
          if (err) {
            callback(err);
            return;
          }

          client.addFloatingIp(server, floatingIp, function(err) {
            if (err) {
              callback(err);
              return;
            }

            client.getServer(server, function(err, server) {
              if (err) {
                callback(err);
                return;
              }

              log.verbose('Server Online Plus IPs (' + name + ',' + server.id + '): ' + client.provider, {
                id: server.id,
                name: server.name,
                addresses: server.addresses
              });

              callback(null, server);
            });
          });
        });

        return
      }

      callback(null, server);
    });
  });
};