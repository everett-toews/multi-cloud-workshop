var async = require('async'),
  config = require('../config.json'),
  Connection = require('ssh2'),
  compute = require('./compute'),
  ejs = require('ejs'),
  fs = require('fs'),
  logging = require('./logging'),
  Stream = require('stream'),
  util = require('util');

var log = logging.getLogger(config.logLevel);

/**
 * Bootstrap the web server
 */
exports.bootstrapWeb = function(username, servers, callback) {
  var webCommands = [
    'sudo apt-get install software-properties-common',
    'sudo apt-add-repository ppa:chris-lea/node.js',
    'sudo apt-get update',
    'sudo apt-get upgrade -y',
    'sudo apt-get install -y nodejs git-core mysql-client',
    'cd /usr/local/src && npm install bootstrap-blog',
    setupApp(servers['db-01'], servers['lb-01']),
    'cd /usr/local/src/node_modules/bootstrap-blog && npm install jade@0.27.7',
    'cd /usr/local/src && export PORT=3000 && nohup node app.js > app.log 2> app.err < /dev/null &'
  ];

  // TODO create DB config file
  batchSSHCommands(username, servers['web-01'], webCommands, callback);
};

/**
 * Boostrap the database machine
 */
exports.bootstrapDb = function(username, servers, callback) {
  var dbCommands = [
    'sudo apt-get update',
    'sudo apt-get upgrade -y',
    'sudo apt-get update',
    'sudo debconf-set-selections <<< "mysql-server mysql-server/root_password password admin123"',
    'sudo debconf-set-selections <<< "mysql-server mysql-server/root_password_again password admin123"',
    'sudo apt-get -y install mysql-server',
    copyMysqlConfig(servers['db-01']),
    util.format('sudo cp %s %s',
      config.templates.mysqlConfig.temporaryPath,
      config.templates.mysqlConfig.path),
    'sudo /etc/init.d/mysql restart',
    'mysql -uroot -padmin123 -e "create database blog;"',
    'mysql -uroot -padmin123 -e "CREATE USER \'blog\'@\'10.0.0.0/255.0.0.0\' IDENTIFIED BY \'blog-pwd\';"',
    'mysql -uroot -padmin123 -e "GRANT ALL ON blog.* TO \'blog\'@\'10.0.0.0/255.0.0.0\';"'
  ];

  batchSSHCommands(username, servers['db-01'], dbCommands, callback);
};

/**
 * Bootstrap the load balancer
 */
exports.bootstrapLb = function(username, servers, callback) {
  var lbCommands = [
    'sudo apt-get update',
    'sudo apt-get upgrade -y',
    'sudo apt-get -y install haproxy',
    copyHaProxyInitDefault,
    util.format('sudo cp %s %s',
      config.templates.haproxyInitDefault.temporaryPath,
      config.templates.haproxyInitDefault.path),
    copyHaProxyConfig(servers['web-01']),
    util.format('sudo cp %s %s',
      config.templates.loadBalancer.temporaryPath,
      config.templates.loadBalancer.path),
    'sudo service haproxy restart'
  ];
  batchSSHCommands(username, servers['lb-01'], lbCommands, callback);
};

function copyHaProxyConfig(lbServer) {
  return function(server, username, callback) {
    exports.uploadTemplate(server, config.templates.loadBalancer, { serverIp: compute.getAddress(lbServer, true) }, username, callback);
  }
}

function copyHaProxyInitDefault(server, username, callback) {
  exports.uploadTemplate(server, config.templates.haproxyInitDefault, {}, username, callback);
}

function setupApp(dbServer, lbServer) {
  return function (server, username, callback) {
    exports.uploadTemplate(server, config.templates.blogApp, {
      lbIp: compute.getAddress(lbServer, false),
      dbIp: compute.getAddress(dbServer, true)
    }, username, callback);
  }
}

function copyMysqlConfig(dbServer) {
  return function (server, username, callback) {
    exports.uploadTemplate(server, config.templates.mysqlConfig, { serverIp: compute.getAddress(dbServer, true) }, username, callback);
  }
}


function batchSSHCommands(username, server, commands, callback) {
  log.verbose('Boostrapping Server (' + server.name + ',' + server.id + ')');
  async.forEachSeries(commands, function(command, next) {
    if (typeof command === 'function') {
       command(server, username, next);
    }
    else {
      log.verbose(util.format('Exec: %s@%s', command, compute.getAddress(server)));
      execSSHCommand(username, server, command, next);
    }
  }, callback);
}

exports.uploadTemplate = function(server, template, locals, username, callback) {
  var calledBack = false, count = 0, maxAttempts = 15;

  function attempt() {
    var c = new Connection();

    log.debug(template);
    log.debug(locals);
    log.debug(username);

    var sourceFile = ejs.render(fs.readFileSync(process.cwd() + template.body).toString(), locals);

    c.on('ready', function() {
      log.debug('Connection :: ready');
      c.sftp(function(err, sftp) {
        if (err) throw err;
        log.debug('SFTP :: Started Session');

        sftp.on('end', function() {
          log.debug('SFTP :: SFTP session closed');
        });
        // upload file
        var writeStream = sftp.createWriteStream(template.temporaryPath);
        var sourceStream = new Stream();

        sourceStream.pipe = function(dest) {
          dest.end(sourceFile);
          sourceStream.emit('end');
        };

        sourceStream
          .on('data', function(data) {
            console.log('Data!', data);
          })
          .on('error', function(err) {
            console.error('Error', err);
          })
          .on('end', function() {
          });

        // what to do when transfer finishes
        writeStream.on(
          'close',
          function() {
            log.debug("- file transferred");
            sftp.end();
            c.end();
          }
        );

        // initiate transfer of file
        sourceStream.pipe(writeStream);
      });
    });
    c.on('error', function(err) {
      log.debug('Connection :: error :: ' + err);
      doCallback()
    });
    c.on('end', function() {
      log.debug('Connection :: end');
      doCallback()
    });
    c.on('close', function(had_error) {
      log.debug('Connection :: close');
    });
    c.connect({
      host: compute.getAddress(server),
      port: 22,
      username: username,
      privateKey: fs.readFileSync(process.cwd() + config.keys.private)
    });
  }

  attempt();

  function doCallback(err) {
    if (calledBack) {
      return
    }

    if (err && (count < maxAttempts)) {
      count++;
      log.error('Unable to upload template to Server, retrying (' + server.name + ',' + server.id + ')', err.code);
      setTimeout(attempt, 10000);
      return;
    }

    if (err) {
      log.error('Unable to upload template to Server (' + server.name + ',' + server.id + ')', err);
    }

    calledBack = true;
    callback(err);
  }
}

/**
 * SSH example taken liberally from mscdex's repo for ssh2 package
 *
 * https://github.com/mscdex/ssh2
 *
 */
function execSSHCommand(username, server, command, callback) {
  var calledBack = false, count = 0, maxAttempts = 15;

  function attempt() {
    var c = new Connection();
    c.on('ready', function() {
      log.debug('Connection :: ready');
      c.exec(command, function(err, stream) {
        if (err) throw err;
        stream.on('data', function(data, extended) {
          log.debug((extended === 'stderr' ? 'STDERR: ' : 'STDOUT: ')
            + data);
        });
        stream.on('end', function() {
          log.debug('Stream :: EOF');
        });
        stream.on('close', function() {
          log.debug('Stream :: close');
        });
        stream.on('exit', function(code, signal) {
          log.debug('Stream :: exit :: code: ' + code + ', signal: ' + signal);
          c.end();
        });
      });
    });
    c.on('error', function(err) {
      log.debug('Connection :: error :: ' + err);
      doCallback(err);
    });
    c.on('end', function() {
      log.debug('Connection :: end');
      doCallback()
    });
    c.on('close', function(had_error) {
      log.debug('Connection :: close');
    });
    var options = {
      host: compute.getAddress(server),
      port: 22,
      username: username,
      privateKey: fs.readFileSync(process.cwd() + config.keys.private)
    };
    c.connect(options);
  }

  attempt();

  function doCallback(err) {
    if (calledBack) {
      return
    }

    if (err && (count < maxAttempts)) {
      count++;
      log.error('Unable to complete command on Server, retrying (' + server.name + ',' + server.id + ')', err.code);
      setTimeout(attempt, 10000);
      return;
    }

    if (err) {
      log.error('Unable to complete command on Server (' + server.name + ',' + server.id + ')', err);
    }

    calledBack = true;
    callback(err);
  }
}