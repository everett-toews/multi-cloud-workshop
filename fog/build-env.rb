require 'fog'

@config = {}
@config[:rackspace] = {
  :service_opts => {
    :provider => 'rackspace',
    :version => :v2,
    :rackspace_username => RACKSPACE_USER_NAME,
    :rackspace_api_key =>  RACKSPACE_API_KEY,
    :rackspace_region => :ord,
  },
  :flavor_name => '1 GB Performance',
  :image_name => 'Ubuntu 12.04 LTS'
}

@config[:aws] = {
  :service_opts => {
    :provider => 'aws',
    :aws_access_key_id => AWS_ACCESS_KEY_ID,
    :aws_secret_access_key => AWS_SECRET_ACCESS_KEY,
    :region => 'us-west-2',    
  },
  :server_opts => {
    :username => 'ubuntu',
    :groups => ['sxsw-demo']
  },
  :flavor_name => 'Medium Instance', #'Micro Instance',
  :image_name => 'ubuntu-precise-12.04-amd64-server-20131003'
}

def config
  @config
end

def provider
  :rackspace
end

def flavor
  @flavor ||= service.flavors.find {|flavor| flavor.name =~ /#{config[provider][:flavor_name]}/ }
end

def image
  @image ||= service.images.find {|image| image.name =~ /#{config[provider][:image_name]}/}
end

def service
  @service ||= Fog::Compute.new config[provider][:service_opts]
end

def create_server(name)
  puts "[#{name}] Server Creating"
  
  server_config = config[provider][:server_opts] || {}
  server_config.merge!({
    :name => name,
    :flavor_id => flavor.id,
    :image_id => image.id,
    :private_key_path => 'sxsw_rsa',
    :key_name => 'sxsw-demo'
  })
  
  server = service.servers.create server_config
  server
end

def ssh(server, name, commands, debug = true)
  server.ssh commands do |stdout, stderr|
    puts "[#{name}][stdout] #{stdout}" if debug && !stdout.empty?
    puts "[#{name}][stderr] #{stderr}" if debug && !stderr.empty?
  end
end

def setup_webhead(web_server, db_server)
  puts "[sxsw-web] Started web server configuration"
  commands = [
   "sudo apt-get update",
   "sudo apt-get upgrade -y",
   "sudo apt-get -y install build-essential ruby1.9.1 ruby1.9.1-dev libopenssl-ruby1.9.1 git libpq-dev",
   "sudo apt-get -y install libmysqlclient18 libmysqlclient-dev libmagickwand-dev libmagickcore-dev libmagickcore4-extra libgraphviz-dev libgvc5",
   "sudo gem install bundler",
   "git clone https://github.com/krames/sxsw-demo-app",
   "cd sxsw-demo-app; bundle install --deployment",
   "echo '#{database_yml(db_server)}' > sxsw-demo-app/config/database.yml",
   "echo '#{carrierwave_config}' > sxsw-demo-app/config/initializers/carrierwave.rb",
   "cd sxsw-demo-app; bundle exec rake db:migrate",
   "cd sxsw-demo-app; bundle exec rails s -d; sleep 1"
  ]

  ssh web_server, "sxsw-web", commands
  puts "[sxsw-web] web server configuration complete"
end

def setup_db_server(server)
  puts "[sxsw-db] Started database server configuration"
  commands = [
    "sudo apt-get update",
    "sudo apt-get upgrade -y",
    "sudo apt-get update",
    "sudo debconf-set-selections <<< 'mysql-server mysql-server/root_password password admin123'",
    "sudo debconf-set-selections <<< 'mysql-server mysql-server/root_password_again password admin123'",
    "sudo apt-get -y install mysql-server",
    
    # change the bind address /etc/mysql/my.cnf
    %Q[sudo sed -ie 's/bind-address.*/bind-address = #{server.private_ip_address}/' /etc/mysql/my.cnf],
    # restart server
    "sudo service mysql restart",
    # create user
    %q[sudo mysql -padmin123 -e "CREATE USER 'sxsw'@'10.%' IDENTIFIED BY 'austin123'"],
    # create database
    "sudo mysql -padmin123 -e 'CREATE DATABASE sxsw_demo'",
    # grant rights
    %q[sudo mysql -padmin123 -e "grant all privileges on sxsw_demo.* to 'sxsw'@'%' identified by 'austin123'"],
  ]
  
  ssh server, 'sxsw-db', commands
  puts "[sxsw-db] database server configuration complete"
end

def setup_haproxy(server, web_server)
  puts "[sxsw-haproxy] Started haproxy server configuration"
  commands = [
    "sudo apt-get update",
    "sudo apt-get upgrade -y",
    "sudo apt-get -y install haproxy",
    %q[sudo sed -ie 's/ENABLED=0/ENABLED=1/' /etc/default/haproxy],
    %Q[sudo sh -c "echo '#{haproxy_config(web_server)}' > /etc/haproxy/haproxy.cfg"],
    "sudo service haproxy restart"
  ]
  
  ssh server, 'sxsw-haproxy', commands
  puts "[sxsw-haproxy] haproxy server configuration complete"
end

def server_address(server)
  #amazon prefers the public address
  provider == :aws ? server.public_ip_address : server.private_ip_address
end

def database_yml(db_server)
  %Q[
    development:
      adapter: mysql2
      encoding: utf8
      database: sxsw_demo
      pool: 5
      username: sxsw
      password: austin123
      host: #{server_address(db_server)}
      port: 3306
    ]
end

def haproxy_config(server)
  %Q[
    global
            log 127.0.0.1   local0
            log 127.0.0.1   local1 notice
            #log loghost    local0 info
            maxconn 4096
            #chroot /usr/share/haproxy
            user haproxy
            group haproxy
            daemon
            #debug
            #quiet
            stats socket /tmp/haproxy

    defaults
            log global
            mode http
            option httplog
            option dontlognull
            retries 3
            option redispatch
            maxconn 2000
            contimeout 5000
            clitimeout 50000
            srvtimeout 50000

    listen  web-proxy 0.0.0.0:80
            mode http
            balance roundrobin
            server sxsw-web #{server_address(server)}:3000
    ]
end

def carrierwave_config
  %Q[
    CarrierWave.configure do |config|
      config.fog_credentials = #{config[provider][:service_opts].to_s}
      config.fog_directory  = "sxsw-demo"
    end
    ]
end

def setup_object_storage
  storage = Fog::Storage.new config[provider][:service_opts]
  dir = storage.directories.get 'sxsw-demo'
  unless dir
    storage.directories.create :key => 'sxsw-demo', :public => true
  end
end

def security_group_exist?
  service.security_groups.get('sxsw-demo') != nil
end

def setup_security_group
  return unless provider == :aws
  return if security_group_exist?
  puts "[security group] Creating security group sxsw-demo"
  
  group = service.security_groups.create :name => 'sxsw-demo', :description => 'This group was created for the SXSW Cloud Portability with Multi-Cloud Toolkits workshop'
  [80, 22, 3000, 3306].each do |port|
    group.authorize_port_range port..port, :ip_protocol => 'tcp'
  end
end

def key_pair_exist?
  service.key_pairs.get('sxsw-demo') != nil
end

def setup_key_pair
  return if key_pair_exist?
  puts "[key pair] creating key pair sxsw-demo"
  
  service.key_pairs.create :name => 'sxsw-demo', 
    :public_key => File.read('sxsw_rsa.pub'),
    :private_key => File.read('sxsw_rsa')
end

puts "\n*** BUILDING SYSTEM USING #{provider.to_s.upcase} ***\n\n"

setup_security_group
setup_key_pair
setup_object_storage

db_server = create_server('sxsw-db')
web_server = create_server('sxsw-web')
haproxy_server = create_server('sxsw-haproxy')

db_server.wait_for { ready? && sshable?}
setup_db_server(db_server)

web_server.wait_for { ready?  && sshable? }
setup_webhead(web_server, db_server)

haproxy_server.wait_for { ready?  && sshable? }
setup_haproxy(haproxy_server, web_server)

puts "\nThe system should be successfully provisioned. You can access it at http://#{haproxy_server.public_ip_address}\n\n"
puts "ssh access individual servers:\n"
puts "\t[sxsw-db]      ssh -i #{db_server.private_key_path} #{db_server.username}@#{db_server.public_ip_address}\n"
puts "\t[sxsw-web]     ssh -i #{web_server.private_key_path} #{web_server.username}@#{web_server.public_ip_address}\n"
puts "\t[sxsw-haproxy] ssh -i #{haproxy_server.private_key_path} #{haproxy_server.username}@#{haproxy_server.public_ip_address}\n"

puts "\n*** PLEASE REMEMBER TO DELETE YOUR SERVERS AND FILES AFTER THE WORKSHOP TO PREVENT UNEXPECTED CHARGES! ***\n\n"



