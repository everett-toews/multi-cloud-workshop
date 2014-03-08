var program = require('commander'),
    logging = require('./lib/logging'),
    config = require('./config.json');

/**
 * Configure our command line arguments
 */
program
  .version('0.0.1')
  .option('-p, --provider [provider]', 'Specify your provider: rackspace, aws, hpcloud')
  .option('-l, --log [level]', 'Specify custom log level, [trace, debug, verbose, info, error]')
  .parse(process.argv);

if (!program.provider) {
  program.outputHelp();
  return;
}

/**
 * Configure logging for the multi-cloud-workshop
 * The default log level can be found in config.json
 */
var log = logging.getLogger(program.log || config.logLevel);

demo(program.provider);

function demo(provider) {

  var async = require('async'),
    compute = require('./lib/compute'),
    https = require('https'),
    pkgcloud = require('pkgcloud'),
    keys = require('./lib/keys'),
    security = require('./lib/security-groups'),
    bootstrap = require('./lib/bootstrap');

  /**
   * Make sure we set a reasonable default for max-sockets.
   * This is to allow high numbers of async calls over the network
   */
  https.globalAgent.maxSockets = 1000;

  /**
   * Create your pkgcloud compute client for your requested provider
   */
  var client = pkgcloud.compute.createClient(config.settings[provider].client);

  /**
   * Enable logging on the compute client
   */
  client.on('log::*', logging.logFunction);

  // This is where the magic happens
  log.info('Provisioning: ' + provider);

  var servers = {
    'web-01': null,
    'db-01': null,
    'lb-01': null
  };

  async.series([
    function (next) {
      keys.uploadSshKey(client, next);
    },
    function (next) {
      security.createSecurityGroup(client, next);
    },
    function (next) {
      async.forEach(Object.keys(servers), function (name, cb) {
        compute.createServer(client, name, function (err, server) {
          if (err) {
            cb(err);
            return;
          }

          servers[name] = server;
          cb();
        });
      }, next);
    },
    function (next) {
      var username = client.provider === 'rackspace' ? 'root' : 'ubuntu';

      async.forEach(Object.keys(servers), function (name, cb) {
        if (name === 'web-01') {
          bootstrap.bootstrapWeb(username, servers, cb);
        }
        else if (name === 'lb-01') {
          bootstrap.bootstrapLb(username, servers, cb);
        }
        else if (name === 'db-01') {
          bootstrap.bootstrapDb(username, servers, cb);
        }
        else {
          cb({ unknownHost: true });
        }
      }, next);
    }
  ], function (err) {
    if (err) {
      log.error('Unable to provision environment', err);
      process.exit(1);
      return;
    }

    log.info('Success: ' + provider);
  });
}
