var async = require('async'),
  compute = require('./lib/compute'),
  https = require('https'),
  config = require('./config.json'),
  logging = require('./lib/logging'),
  pkgcloud = require('pkgcloud'),
  shutdown = require('./lib/shutdown'),
  bootstrap = require('./lib/bootstrap'),
  keys = require('./lib/keys'),
  security = require('./lib/security-groups');

// Setup some logging helpers
var log = logging.getLogger(config.logLevel);

// configure reasonable max socket limits
https.globalAgent.maxSockets = 1000;

// First lets create our three compute clients
var clients = {
  aws: pkgcloud.compute.createClient(config.settings.aws.client),
  hp: pkgcloud.compute.createClient(config.settings.hpcloud.client),
  rackspace: pkgcloud.compute.createClient(config.settings.rackspace.client)
};

// Wire up our logging
Object.keys(clients).forEach(function(key) {
  clients[key].on('log::*', logging.logFunction);
});

// get the provider name and action from the command line arguments
var provider = process.argv[2],
  action = process.argv[3];

// If we get the destroy action, go clean up
if (action === 'destroy') {
  shutdown.shutdown(clients[provider], function(err) {
    process.exit(err ? 1 : 0);
  });
  return;
}

// This is where the magic happens

log.info('Provisioning: ' + provider);

var servers = {
  'web-01': null,
  'db-01': null,
  'lb-01': null
};

async.series([
  function(next) { keys.uploadSshKey(clients[provider], next); },
  function(next) { security.createSecurityGroup(clients[provider], next); },
  function(next) {
    async.forEach(Object.keys(servers), function(name, cb) {
      compute.createServer(clients[provider], name, function(err, server) {
        if (err) {
          cb(err);
          return;
        }

        servers[name] = server;
        cb();
      });
    }, next);
  },
  function(next) {
    async.forEach(Object.keys(servers), function(name, cb) {
      if (name === 'web-01') {
        bootstrap.bootstrapWeb('ubuntu', servers, cb);
      }
      else if (name === 'lb-01') {
        bootstrap.bootstrapLb('ubuntu', servers, cb);
      }
      else if (name === 'db-01') {
        bootstrap.bootstrapDb('ubuntu', servers, cb);
      }
      else {
        cb({ unknownHost: true });
      }
    }, next);
  }
], function(err) {
  if (err) {
    log.error('Unable to provision environment', err);
    process.exit(1);
    return;
  }

  log.info('Success: ' + provider);
});
