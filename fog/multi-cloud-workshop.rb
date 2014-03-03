require 'fog'

@config = {}

# Please set the PROVIDER environment variable to aws, hp, or rackspace.
#
# You will also need to set the approprate environment variables for your desired cloud
#
# Amazon:
#   AWS_ACCESS_KEY
#   AWS_SECRET_ACCESS_KEY
#
# HP:
#  HP_SECRET_KEY
#  HP_ACCESS_KEY
#  HP_TENANT_ID
#
# Rackspace:
#   RACKSPACE_USERNAME
#   RACKSPACE_API_KEY
#
@config[:rackspace] = {
  :service_opts => {
    :provider => 'rackspace',
    :version => :v2,
    :rackspace_username => ENV['RACKSPACE_USERNAME'] || Fog.credentials[:rackspace_username],
    :rackspace_api_key =>  ENV['RACKSPACE_API_KEY'] || Fog.credentials[:rackspace_api_key],
    :rackspace_region => :ord,
  },
  :flavor_name => '1 GB Performance',
  :image_name => 'Ubuntu 12.04 LTS'
}

@config[:aws] = {
  :service_opts => {
    :provider => 'aws',
    :aws_access_key_id => ENV['AWS_ACCESS_KEY'] || Fog.credentials[:aws_access_key_id],
    :aws_secret_access_key => ENV['AWS_SECRET_ACCESS_KEY'] || Fog.credentials[:aws_secret_access_key],
    :region => 'us-west-2',
  },
  :server_opts => {
    :username => 'ubuntu',
    :groups => ['multi-cloud-workshop']
  },
  :flavor_name => 'Medium Instance',
  :image_name => 'ubuntu\/images\/ubuntu-precise-12.04-amd64-server-20131003'
}
@config[:hp] = {
  :service_opts => {
    :provider => 'hp',
    :version => :v2,
    :hp_secret_key => ENV['HP_SECRET_KEY'] || Fog.credentials[:hp_secret_key],
    :hp_access_key => ENV['HP_ACCESS_KEY'] || Fog.credentials[:hp_access_key],
    :hp_tenant_id => ENV['HP_TENANT_ID'] || Fog.credentials[:hp_tenant_id],
    :hp_avl_zone => 'region-a.geo-1'
  },
  :server_opts => {
    :username => 'ubuntu',
    :security_groups => ['multi-cloud-workshop']
  },
  :flavor_name => 'standard.xsmall',
  :image_name => 'Ubuntu Precise 12.04 LTS Server 64-bit 20121026'
}

def config
  @config[provider]
end

def provider
  raise "Please set PROVIDER environment variable to aws, hp, or rackspace" unless ENV['PROVIDER']
  @provider ||= ENV['PROVIDER'].downcase.to_sym
end

def service
  @service ||= Fog::Compute.new config[:service_opts]
end

def create_server(name)
  puts "[#{name}] Server Creating"
  server_config = config[:server_opts] || {}
  server_config.merge!({
    :name => name,
    :flavor_id => flavor.id,
    :image_id => image.id,
    :private_key_path => 'multi-cloud-workshop.key',
    :key_name => 'multi-cloud-workshop'
  })

  server = service.servers.create server_config
  assign_public_ip_address_if_necessary(server)
  server
end

def flavor
  @flavor ||= service.flavors.find {|flavor| flavor.name =~ /#{config[:flavor_name]}/ }
end

def image
  @image ||= service.images.find {|image| image.name =~ /#{config[:image_name]}/}
end

def assign_public_ip_address_if_necessary(server)
  return unless provider == :hp
  server.wait_for { ready? }
  address = service.addresses.create :server => server
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
   "git clone https://github.com/rackerlabs/multi-cloud-demo-app demo-app",
   "cd demo-app; bundle install --deployment",
   "echo '#{database_yml(db_server)}' > demo-app/config/database.yml",
   "echo '#{carrierwave_config}' > demo-app/config/initializers/carrierwave.rb",
   "cd demo-app; bundle exec rake db:migrate",
   "cd demo-app; bundle exec rails s -d; sleep 1"
  ]

  ssh web_server, "sxsw-web", commands
  puts "[sxsw-web] web server configuration complete"
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
      host: #{db_server.private_ip_address}
      port: 3306
    ]
end

def carrierwave_config
  %Q[
    CarrierWave.configure do |config|
      config.fog_credentials = #{config[:service_opts].to_s}
      config.fog_directory  = "multi-cloud-workshop"
    end
    ]
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
            server sxsw-web #{server.private_ip_address}:3000
    ]
end

def setup_object_storage
  storage = Fog::Storage.new config[:service_opts]
  dir = storage.directories.get 'multi-cloud-workshop'
  unless dir
    storage.directories.create :key => 'multi-cloud-workshop', :public => true
  end
end

def security_group_exist?
  group = service.security_groups.find {|group| group.name == 'multi-cloud-workshop' } != nil
end

def setup_hp_security_group
  network_service = Fog::HP::Network.new config[:service_opts]
  return if network_service.security_groups.find {|group| group.name == 'multi-cloud-workshop' }

  puts "[security group] Creating security group multi-cloud-workshop"
  group = network_service.security_groups.create :name => 'multi-cloud-workshop',
    :description => 'This group was created for the SXSW Cloud Portability with Multi-Cloud Toolkits workshop'

  [80, 22, 3000, 3306].each do |port|
    network_service.security_group_rules.create :port_range_min => port,
      :port_range_max => port,
      :protocol => :tcp,
      :direction => :ingress,
      :tenant_id => group.tenant_id,
      :security_group_id => group.id
  end
end

def setup_aws_security_group
  return if service.security_groups.find {|group| group.name == 'multi-cloud-workshop' }
  puts "[security group] Creating security group multi-cloud-workshop"

  group = service.security_groups.create :name => 'multi-cloud-workshop', :description => 'This group was created for the SXSW Cloud Portability with Multi-Cloud Toolkits workshop'
  [80, 22, 3000, 3306].each do |port|
    group.authorize_port_range port..port, :ip_protocol => 'tcp'
  end
end

def setup_security_group
  return if provider == :rackspace
  setup_aws_security_group if provider == :aws
  setup_hp_security_group if provider == :hp
end

def setup_key_pair
  return if key_pair_exist?
  puts "[key pair] creating key pair multi-cloud-workshop"

  service.key_pairs.create :name => 'multi-cloud-workshop',
    :public_key => File.read('multi-cloud-workshop.pub'),
    :private_key => File.read('multi-cloud-workshop.key')
end

def key_pair_exist?
  service.key_pairs.get('multi-cloud-workshop') != nil
end

def build_environment
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
end

build_environment
