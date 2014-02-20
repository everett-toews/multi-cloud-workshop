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

        };
        break;
      case 'rackspace':
        options =  {

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

    var interval = Math.floor(Math.random() * (6500 - 4500 + 1) + 4500)
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

      callback(null, server);
    });
  });
};