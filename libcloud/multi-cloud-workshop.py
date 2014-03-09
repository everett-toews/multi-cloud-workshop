from ConfigParser import ConfigParser
import logging
import os
import sys

from libcloud.compute.types import Provider as ComputeProvider
from libcloud.compute.providers import get_driver as get_compute_driver
from libcloud.compute.deployment import ScriptDeployment, ScriptFileDeployment
from libcloud.compute.base import NodeAuthSSHKey

from libcloud.loadbalancer.base import Member, Algorithm
from libcloud.loadbalancer.types import Provider as LBProvider
from libcloud.loadbalancer.providers import get_driver as get_lb_driver


# Setup a logger to write messages to the console.
log = logging.getLogger("libcloud")
log.setLevel(logging.INFO)
fmt = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
console = logging.StreamHandler()
console.setLevel(logging.INFO)
console.setFormatter(fmt)
log.addHandler(console)


def get_config(path="libcloud.conf"):
    """Load a configuration file to find provider-specific options."""
    cp = ConfigParser()
    cp.read(path)

    def to_dict(items):
        """Shortcut to create a dictionary from ConfigParser item lists."""
        return dict((item[0], item[1]) for item in items)

    if "active" in cp.sections():
        active = to_dict(cp.items("active"))
    else:
        raise Exception("No active section found in %s." % path)

    conf = to_dict(cp.items(active["provider"]))
    # Update the dictionary to include the `active` section.
    conf.update(active)
    
    return conf

def create_node(name, config, deploy_name=None, deployment=None):
    """Create a compute node with a given name and configuration information.
    Return the Node object."""
    # Some providers require extra settings when configuring their driver.
    # Look in the config for any starting with 'ex_' and include them.
    extra = dict((x[0], x[1]) for x in config.items() if x[0][:3] == "ex_")

    # Get the compute driver we want to connect to, then pass in credentials.
    ComputeDriver = get_compute_driver(config["compute_driver"])
    compute = ComputeDriver(config["identity"],
                            config["credential"],
                            region=config["region"], **extra)
    log.info("Created a %s compute driver in the %s region.",
             config["compute_driver"], config["region"])

    sec_group = None
    if config["provider"] == "amazon":
        # Create a security group for our nodes on Amazon.
        # Rackspace and HP do not use security groups.
        sec_group = "sxsw"
        sec_groups = compute.ex_list_security_groups()
        if sec_group not in sec_groups:
            compute.ex_create_security_group(sec_group,
                                             "Sec Group for SXSW Workshop")
            log.info("Created %s security group.", sec_group)

            # Let SSH (port 22) and web (port 80) traffic through.
            compute.ex_authorize_security_group(sec_group, 22, 22, "0.0.0.0/0")
            compute.ex_authorize_security_group(sec_group, 80, 80, "0.0.0.0/0")
            log.info("Authorized %s on ports 22 and 80.", sec_group)

    # Pair our SSH public key with the provider so we can communicate
    # with our deployed compute nodes.
    pub_key = open(config["public_key"], "r").read()

    key_name = os.path.split(config["private_key"])[-1]
    keys = [key.name for key in compute.list_key_pairs()]
    if key_name not in keys:
        # If this key isn't already paired, import the key by choosing a name
        # and passing in the contents of the public key.
        key = compute.import_key_pair_from_string(key_name, pub_key)
        log.info("Paired %s key with provider.", key)
    else:
        log.info("Already had %s key paired.", key_name)

    # Once the node is built, it'll be a bare image. Run the configured
    # bootstrap script using libcloud's ScriptDeployment to run the system
    # updates and install Flask.
    if deploy_name and deployment is None:
        deployment = ScriptFileDeployment(config[deploy_name])
        log.info("Created ScriptFileDeployment with %s file.", deploy_name)
    elif deployment and deploy_name is None:
        assert isinstance(deployment, ScriptDeployment)
        log.info("ScriptDeployment was passed in.")
    else:
        raise Exception("Unable to determine deployment.")

    # Find the size and image we want to use when creating our node.
    # The following iterates through lists of sizes and images and finds
    # the match for our configured strings.
    size_name = config["size"]
    size = filter(lambda size: size.id == size_name, compute.list_sizes())[0]

    img_name = config["image"]
    image = filter(lambda image: image.name == img_name, compute.list_images())[0]

    log.info("Deploying node with size=%s, image=%s", size, image)

    # Deploy our node. This calls create_node but waits for the creation to
    # complete, and then it uses paramiko to SSH into the node and run
    # the commands specified by the `deploy` argument. In order to do this,
    # the paramiko SSH client must know the private key, specified in
    # `ssh_key`. `ex_keyname` is the public key we paired up above.
    node = compute.deploy_node(name=name, image=image, size=size,
                deploy=deployment,
                ssh_key=config["private_key"],
                ssh_username=config["ssh_user"],
                ex_keyname=key_name,
                ex_securitygroup=sec_group, 
                timeout=50000)
    log.info("Node deployed: %s", node)

    return node


def prepare_haproxy_config(path, nodes):
    """Open the haproxy template and add the created nodes."""
    lines = open(path, "r").readlines()

    # Append the web server node private (internal) IPs to the config.
    for node in nodes:
        lines.append("        server %s %s\n" % (node.name,
                                                 node.private_ips[0]))
    return "".join(lines)


if __name__ == "__main__":
    config = get_config()

    log.info("Creating web nodes.")
    nodes = []
    for name in ("mcw-1", "mcw-2"):
        nodes.append(create_node(name, config, deploy_name="web_deployment"))

    log.info("Creating database node.")
    create_node("db", config, deploy_name="db_deployment")

    log.info("Creating haproxy load balancer node.")

    # The haproxy config file needs to have the two web nodes added to it.
    haproxy_config = prepare_haproxy_config(config["haproxy"], nodes)

    # Insert the haproxy.cfg file into the deployment script.
    haproxy_deploy = open(config["haproxy_deploy"], "r").read()
    script = haproxy_deploy % ({"config" : haproxy_config})
    deployment = ScriptDeployment(script.encode("utf8"))

    lb_node = create_node("haproxy", config, deployment=deployment)
    log.info("Access the load balancer at %s", lb_node.public_ips)
