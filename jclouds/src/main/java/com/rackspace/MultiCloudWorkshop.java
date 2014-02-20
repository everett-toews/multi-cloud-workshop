package com.rackspace;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import com.google.inject.Module;
import org.jclouds.ContextBuilder;
import org.jclouds.aws.ec2.AWSEC2Api;
import org.jclouds.aws.ec2.features.AWSKeyPairApi;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.ec2.compute.options.EC2TemplateOptions;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.NovaAsyncApi;
import org.jclouds.openstack.nova.v2_0.compute.options.NovaTemplateOptions;
import org.jclouds.openstack.nova.v2_0.domain.KeyPair;
import org.jclouds.openstack.nova.v2_0.extensions.KeyPairApi;
import org.jclouds.rest.RestContext;
import org.jclouds.scriptbuilder.ScriptBuilder;
import org.jclouds.scriptbuilder.domain.OsFamily;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.io.Resources.getResource;
import static com.google.common.io.Resources.newInputStreamSupplier;
import static com.rackspace.MultiCloudWorkshop.ProviderProperty.*;
import static java.lang.String.format;
import static org.jclouds.compute.config.ComputeServiceProperties.POLL_INITIAL_PERIOD;
import static org.jclouds.compute.config.ComputeServiceProperties.POLL_MAX_PERIOD;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE;
import static org.jclouds.openstack.nova.v2_0.domain.zonescoped.ZoneAndId.fromZoneAndId;
import static org.jclouds.scriptbuilder.domain.Statements.exec;

/**
 * This class creates a load balancer, 2 web servers, and a database. It then
 * uses ssh commands to wire them together to create a working application
 * architecture.
 *
 * You'll find the following files in the resource directory:
 *   provider.properties      - configure your cloud resources
 *   logback.xml              - configure your logging
 *   multi-cloud-workshop.key - all servers are accessible via ssh using this key
 */
public class MultiCloudWorkshop {
    public static final String MCW = "multi-cloud-workshop";
    public static final String PUBLIC_KEY_FILENAME = MCW + ".pub";
    public static final String PRIVATE_KEY_FILENAME = MCW + ".key";
    public static final String LOAD_BALANCER = MCW + "-lb";
    public static final String DATABASE = MCW + "-db";
    public static final String WEB_SERVER_01 = MCW + "-webserver-01";
    public static final String WEB_SERVER_02 = MCW + "-webserver-02";

    private final ComputeService computeService;
    private final Map<ProviderProperty, String> props;

    private final Logger logger = LoggerFactory.getLogger(MultiCloudWorkshop.class);
    private final Stopwatch timer;

    /**
     * Do the following to build an application architecture:
     *   1. Setup a key pair for remote access to servers.
     *   2. Create 4 servers.
     *   3. Setup 1 server as a database.
     *   4. Setup 2 servers as web servers.
     *   5. Setup 1 server as a load balancer.
     *   6. Print out the results.
     */
    public static void main(String[] args) {
        MultiCloudWorkshop multiCloudWorkshop = null;

        try {
            multiCloudWorkshop = new MultiCloudWorkshop();

            multiCloudWorkshop.setupKeyPair();
            Map<String, NodeMetadata> nameToNode = multiCloudWorkshop.createServers();
            multiCloudWorkshop.setupDatabase(nameToNode.get(DATABASE));
            multiCloudWorkshop.setupWebServers(getWebServers(nameToNode));
            multiCloudWorkshop.setupLoadBalancer(nameToNode.get(LOAD_BALANCER), getWebServers(nameToNode));
            multiCloudWorkshop.printResults(nameToNode);
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            if (multiCloudWorkshop != null) {
                multiCloudWorkshop.close();
            }
        }
    }

    /**
     * Initialize the following resources:
     *   1. A timer for timing how long it took to execute the class.
     *   2. The cloud properties for configuration.
     *   3. Modules for logging and ssh.
     *   4. Overrides to increase how often jclouds polls for server status and
     *      how long it waits for ssh to timeout.
     *   5. The ComputeService abstract layer for working with clouds.
     */
    public MultiCloudWorkshop() throws IOException {
        timer = Stopwatch.createStarted();
        props = getProviderProperties();

        logger.info(format("Multi-Cloud Workshop on %s", props.get(PROVIDER).toUpperCase()));

        Iterable<Module> modules = ImmutableSet.<Module>of(
                new SLF4JLoggingModule(),
                new SshjSshClientModule());

        Properties overrides = new Properties();
        overrides.setProperty(POLL_INITIAL_PERIOD, "30000");
        overrides.setProperty(POLL_MAX_PERIOD, "30000");
        overrides.setProperty(TIMEOUT_SCRIPT_COMPLETE, "1200000");

        ComputeServiceContext context = ContextBuilder.newBuilder(props.get(NAME))
                .credentials(props.get(IDENTITY), props.get(CREDENTIAL))
                .modules(modules)
                .overrides(overrides)
                .buildView(ComputeServiceContext.class);
        computeService = context.getComputeService();
    }

    /**
     * Load the cloud configuration properties.
     */
    public static Map<ProviderProperty, String> getProviderProperties() throws IOException {
        Properties props = new Properties();
        props.load(newInputStreamSupplier(getResource("provider.properties")).getInput());

        String provider = props.getProperty(PROVIDER.toString());

        Map<ProviderProperty, String> providerProps = newHashMap();

        providerProps.put(PROVIDER, provider);
        providerProps.put(NAME, props.getProperty(String.format("%s.%s", provider, NAME)));
        providerProps.put(IDENTITY, props.getProperty(format("%s.%s", provider, IDENTITY)));
        providerProps.put(CREDENTIAL, props.getProperty(format("%s.%s", provider, CREDENTIAL)));
        providerProps.put(LOCATION, props.getProperty(format("%s.%s", provider, LOCATION)));
        providerProps.put(IMAGE, props.getProperty(format("%s.%s", provider, IMAGE)));
        providerProps.put(HARDWARE, props.getProperty(format("%s.%s", provider, HARDWARE)));

        return providerProps;
    }

    /**
     * Put the multi-cloud-workshop.pub public key in the cloud so we can login
     * to the servers using the multi-cloud-workshop.key private key.
     */
    private void setupKeyPair() throws IOException {
        logger.info("Key pair setup started");

        if (keyPairExists()) {
            logger.info(format("  Key pair already exists: %s", MCW));
            return;
        }

        String publicKey = Resources.toString(getResource(PUBLIC_KEY_FILENAME), Charsets.UTF_8);

        if (props.get(PROVIDER).equals("aws")) {
            AWSEC2Api awsEc2Api = computeService.getContext().unwrapApi(AWSEC2Api.class);
            AWSKeyPairApi awsKeyPairApi = awsEc2Api.getKeyPairApiForRegion(props.get(LOCATION)).get();

            awsKeyPairApi.importKeyPairInRegion(props.get(LOCATION), MCW, publicKey);
        } else {
            RestContext<NovaApi, NovaAsyncApi> novaContext = computeService.getContext().unwrap();
            NovaApi novaApi = novaContext.getApi();
            KeyPairApi keyPairApi = novaApi.getKeyPairExtensionForZone(props.get(LOCATION)).get();

            keyPairApi.createWithPublicKey(MCW, publicKey);
        }

        logger.info(format("  Key pair created: %s", MCW));
    }

    /**
     * Determine if the multi-cloud-workshop.pub public key is already in the cloud.
     */
    private boolean keyPairExists() {
        if (props.get(PROVIDER).equals("aws")) {
            AWSEC2Api awsEc2Api = computeService.getContext().unwrapApi(AWSEC2Api.class);
            AWSKeyPairApi awsKeyPairApi = awsEc2Api.getKeyPairApiForRegion(props.get(LOCATION)).get();

            Set<org.jclouds.ec2.domain.KeyPair> keyPairs = awsKeyPairApi.describeKeyPairsInRegion(props.get(LOCATION), MCW);

            for (org.jclouds.ec2.domain.KeyPair keyPair : keyPairs) {
                if (keyPair.getKeyName().equals(MCW)) {
                    return true;
                }
            }

            return false;
        } else {
            RestContext<NovaApi, NovaAsyncApi> novaContext = computeService.getContext().unwrap();
            NovaApi novaApi = novaContext.getApi();
            KeyPairApi keyPairApi = novaApi.getKeyPairExtensionForZone(props.get(LOCATION)).get();

            ImmutableSet<? extends KeyPair> keyPairs = keyPairApi.list().toSet();

            for (KeyPair keyPair : keyPairs) {
                if (keyPair.getName().equals(MCW)) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * Create 4 servers simultaneously with the options:
     *   1. Assign names to the nodes.
     *   2. Assign the public key multi-cloud-workshop.key.
     *   3. Use the private key multi-cloud-workshop.key to login.
     *   4. Open up ports 22, 80, 3000, 3306 for our application to use.
     *   5. Assign the hardware, image, and location based on the configuration.
     */
    private Map<String, NodeMetadata> createServers() throws RunNodesException, IOException {
        Set<String> nodeNames = ImmutableSet.of(DATABASE, WEB_SERVER_01, WEB_SERVER_02, LOAD_BALANCER);

        logger.info(format("Servers boot up started: %s", Joiner.on(", ").join(nodeNames)));
        logger.info("  Check log/jclouds-wire.log for progress");

        String privateKey = Resources.toString(getResource(PRIVATE_KEY_FILENAME), Charsets.UTF_8);

        TemplateOptions options = computeService.templateOptions()
                .nodeNames(nodeNames)
                .overrideLoginPrivateKey(privateKey)
                .inboundPorts(22, 80, 3000, 3306);

        String hardwareId = props.get(HARDWARE);

        if (props.get(PROVIDER).equals("aws")) {
            options = options.as(EC2TemplateOptions.class).keyPair(MCW);
        } else {
            hardwareId = fromZoneAndId(props.get(LOCATION), props.get(HARDWARE)).slashEncode();
            options = options.as(NovaTemplateOptions.class).keyPairName(MCW).generateKeyPair(false);
        }

        Template template = computeService.templateBuilder()
                .imageNameMatches(props.get(IMAGE))
                .locationId(props.get(LOCATION))
                .hardwareId(hardwareId)
                .options(options)
                .build();

        Set<? extends NodeMetadata> nodes = computeService.createNodesInGroup(MCW, 4, template);
        Map<String, NodeMetadata> nameToNode = newHashMap();

        // This kludge is temporarily necessary because of bug https://issues.apache.org/jira/browse/JCLOUDS-467 :(
        // All of your nodes will be named the same in AWS. This will be fixed in jclouds 1.7.2
        if (props.get(PROVIDER).equals("aws")) {
            Iterator<? extends NodeMetadata> nodeIterator = nodes.iterator();
            nameToNode.put(DATABASE, nodeIterator.next());
            nameToNode.put(WEB_SERVER_01, nodeIterator.next());
            nameToNode.put(WEB_SERVER_02, nodeIterator.next());
            nameToNode.put(LOAD_BALANCER, nodeIterator.next());
        } else {
            for (NodeMetadata node : nodes) {
                nameToNode.put(node.getName(), node);
            }
        }

        logger.info("  Servers boot up completed");

        return nameToNode;
    }

    /**
     * Run the ssh commands necessary to setup node as a database.
     */
    private void setupDatabase(NodeMetadata node) throws IOException {
        logger.info(format("Database setup started: %s", node.getName()));
        logger.info("  Check log/jclouds-ssh.log for progress");

        RunScriptOptions options = RunScriptOptions.Builder
                .blockOnComplete(true);

        String script = new ScriptBuilder()
                .addStatement(exec("sleep 1"))
                .addStatement(exec("sudo apt-get -q -y update"))
                .addStatement(exec("sudo apt-get -q -y upgrade"))
                .addStatement(exec("sudo debconf-set-selections <<< 'mysql-server mysql-server/root_password password admin123'"))
                .addStatement(exec("sudo debconf-set-selections <<< 'mysql-server mysql-server/root_password_again password admin123'"))
                .addStatement(exec("sudo apt-get -q -y install mysql-server"))
                .addStatement(exec(format("sudo sed -ie 's/bind-address.*/bind-address = %s/' /etc/mysql/my.cnf", getOnlyElement(node.getPrivateAddresses()))))
                .addStatement(exec("sudo service mysql restart"))
                .render(OsFamily.UNIX);

        computeService.runScriptOnNode(node.getId(), script, options);

        logger.info("  Database setup complete");
    }

    /**
     * Run the ssh commands necessary to setup the nodes as web servers.
     */
    private void setupWebServers(List<NodeMetadata> webServerNodes) {
        for (NodeMetadata node : webServerNodes) {
            logger.info(format("Web server setup started: %s", node.getName()));
            logger.info("  Check log/jclouds-ssh.log for progress");

            RunScriptOptions options = RunScriptOptions.Builder
                    .blockOnComplete(true);

            String script = new ScriptBuilder()
                    .addStatement(exec("sleep 1"))
                    .addStatement(exec("sudo apt-get -q -y update"))
                    .addStatement(exec("sudo apt-get -q -y upgrade"))
                    .addStatement(exec("sudo apt-get -q -y install apache2"))
                    .addStatement(exec("echo 'Hello from " + node.getPublicAddresses() + "' > /var/www/index.html"))
                    .render(OsFamily.UNIX);

            computeService.runScriptOnNode(node.getId(), script, options);

            logger.info("  Web server setup complete");
        }
    }

    /**
     * Get the web servers out of the servers that are running.
     */
    private static List<NodeMetadata> getWebServers(Map<String, NodeMetadata> nameToNode) {
        List<NodeMetadata> webServerNodes = newArrayList();

        for (String name : nameToNode.keySet()) {
            if (name.contains("webserver")) {
                webServerNodes.add(nameToNode.get(name));
            }
        }

        return webServerNodes;
    }

    /**
     * Run the ssh command necessary to setup the node as a load balancer.
     */
    private void setupLoadBalancer(NodeMetadata node, List<NodeMetadata> webServerNodes) throws IOException {
        logger.info(format("Load balancer setup started: %s", node.getName()));
        logger.info("  Check log/jclouds-ssh.log for progress");

        RunScriptOptions options = RunScriptOptions.Builder
                .blockOnComplete(true);

        String script = new ScriptBuilder()
                .addStatement(exec("sleep 1"))
                .addStatement(exec("sudo apt-get -q -y update"))
                .addStatement(exec("sudo apt-get -q -y upgrade"))
                .addStatement(exec("sudo apt-get -q -y install haproxy"))
                .addStatement(exec("sudo sed -ie 's/ENABLED=0/ENABLED=1/' /etc/default/haproxy"))
                .addStatement(exec(format("sudo sh -c \"echo '%s' > /etc/haproxy/haproxy.cfg\"", getLoadBalancerConfig(webServerNodes))))
                .addStatement(exec("sudo service haproxy restart"))
                .render(OsFamily.UNIX);

        computeService.runScriptOnNode(node.getId(), script, options);

        logger.info("  Load balancer setup complete");
    }

    /**
     * Get the load balancer configuration file.
     */
    private String getLoadBalancerConfig(List<NodeMetadata> webServerNodes) throws IOException {
        String haproxyCfg = Resources.toString(getResource("haproxy.cfg"), Charsets.UTF_8);
        StringBuilder configBuilder = new StringBuilder(haproxyCfg);

        for (NodeMetadata webServerNode : webServerNodes) {
            configBuilder.append(format("        server %s %s\n", webServerNode.getName(), getOnlyElement(webServerNode.getPrivateAddresses())));
        }

        return configBuilder.toString();
    }

    /**
     * Print the results.
     */
    private void printResults(Map<String, NodeMetadata> nameToNode) throws IOException {
        NodeMetadata loadBalancerNode = nameToNode.get(LOAD_BALANCER);

        logger.info(format("%nTo ssh into the individual servers: "));

        String privateKeyFilePath = getResource(PRIVATE_KEY_FILENAME).getPath();

        for (String name : nameToNode.keySet()) {
            NodeMetadata node = nameToNode.get(name);
            String publicIpAddress = getOnlyElement(node.getPublicAddresses());
            String user = node.getCredentials().getUser();

            logger.info(format("  %-36s ssh -i %s %s@%s", name, privateKeyFilePath, user, publicIpAddress));

            nameToNode.put(name, node);
        }

        logger.info(format("%nGo to http://%s to test the application%n", getOnlyElement(loadBalancerNode.getPublicAddresses())));
    }

    /**
     * Close the resources.
     */
    private void close() {
        computeService.getContext().close();

        logger.info(format("The code took %s to run%n", timer.stop()));
        logger.info("*** Please remember to DELETE YOUR SERVERS AND FLOATING IPs after the workshop to prevent unexpected charges! ***");
    }

    /**
     * An Enum for cloud configuration properties.
     */
    public enum ProviderProperty {
        PROVIDER("provider"),
        NAME("name"),
        IDENTITY("identity"),
        CREDENTIAL("credential"),
        LOCATION("location"),
        IMAGE("image"),
        HARDWARE("hardware");

        private final String text;

        private ProviderProperty(final String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
