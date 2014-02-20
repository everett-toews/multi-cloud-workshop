var winston = require('winston');

var logger;

exports.getLogger = function(level) {

  if (logger) {
    return logger;
  }

  logger = new winston.Logger({
    levels: {
      debug: 0,
      verbose: 1,
      info: 3,
      warn: 4,
      error: 5
    },
    colors: {
      debug: 'grey',
      verbose: 'cyan',
      info: 'green',
      warn: 'yellow',
      error: 'red'
    },
    transports: [
      new winston.transports.Console({
        level: level,
        prettyPrint: true,
        colorize: true
      })
    ]
  });

  return logger;
};

exports.logFunction = function(message, object) {
  if (object) {
    logger.log(this.event.split('::')[1], message, object);
  }
  else {
    logger.log(this.event.split('::')[1], message);
  }
};