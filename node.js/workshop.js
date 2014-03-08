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

  log.info('Starting Provisioning: ' + provider);

  /**
   * Create a local cache of servers to be created
   */
  var servers = {
    'web-01': null,
    'db-01': null,
    'lb-01': null
  };

  /**
   * This is where we deploy & provision our application
   *
   * Each of the functions in the array is called in turn, exiting early
   * if any error happens. We're using async, a standard node.js
   * flow control package
   */
  async.series([
    function (next) { keys.uploadSshKey(client, next); },
    function (next) { security.createSecurityGroup(client, next); },
    function (next) {
      /**
       * Create each of the servers
       *
       * These calls will be executed in parallel as a function of
       * async.forEach
       */
      async.forEach(Object.keys(servers), function (name, cb) {
        compute.createServer(client, name, function (err, server) {
          if (err) {
            cb(err);
            return;
          }

          // cache the created server in our servers object
          servers[name] = server;
          cb();
        });
      }, next);
    },
    function (next) {
      /**
       * bootstrap our servers
       *
       * Generally, you'd be doing this with a more robust/professional
       * configuration platform like chef/puppet/salt/ansible.
       *
       * This example is intentionally simplified to be more accesible
       */
      var username = client.provider === 'rackspace' ? 'root' : 'ubuntu';
      async.forEach(Object.keys(servers), function (name, cb) {
        switch (name) {
          case 'web-01':
            bootstrap.bootstrapWeb(username, servers, cb);
            break;
          case 'lb-01':
            bootstrap.bootstrapLb(username, servers, cb);
            break;
          case 'db-01':
            bootstrap.bootstrapDb(username, servers, cb);
            break;
        }
      }, next);
    }
  ], function (err) {
    /**
     * End of provisioning
     *
     * We'll get here either if:
     *
     * a) provisioning was successful
     * b) any error happened along the way to provision
     * 
     */
    if (err) {
      log.error('Unable to provision environment', err);
      process.exit(1);
      return;
    }

    log.info('Success: ' + provider);
  });
}
