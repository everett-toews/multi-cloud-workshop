import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.google.common.net.HostAndPort;
import com.google.inject.Module;
import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.config.ComputeServiceProperties;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.NovaAsyncApi;
import org.jclouds.openstack.nova.v2_0.domain.SecurityGroup;
import org.jclouds.openstack.nova.v2_0.extensions.SecurityGroupApi;
import org.jclouds.predicates.SocketOpen;
import org.jclouds.rest.RestContext;
import org.jclouds.scriptbuilder.ScriptBuilder;
import org.jclouds.scriptbuilder.domain.OsFamily;
import org.jclouds.sshj.config.SshjSshClientModule;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.jclouds.compute.predicates.NodePredicates.inGroup;
import static org.jclouds.scriptbuilder.domain.Statements.exec;
import static org.jclouds.util.Predicates2.retry;

public class JCloudsWorkshop {
    private final List<ComputeService> computeServices = newArrayList();
    private final Map<ComputeService, NodeMetadata> nodes = newHashMap();

    public static void main(String[] args) {
        JCloudsWorkshop jcloudsWorkshop = new JCloudsWorkshop();

        try {
            jcloudsWorkshop.createNodes();
            jcloudsWorkshop.configureAndStartWebservers();
            jcloudsWorkshop.detectExtensions();
            jcloudsWorkshop.printResults();
//       jcloudsWorkshop.deleteNodes();
        }
        catch (Throwable e) {
            e.printStackTrace();
        }
        finally {
            jcloudsWorkshop.close();
        }
    }

    public JCloudsWorkshop() {
        Iterable<Module> modules = ImmutableSet.<Module> of(
                new SLF4JLoggingModule(),
                new SshjSshClientModule());

        Properties overrides = new Properties();
        overrides.setProperty(ComputeServiceProperties.POLL_INITIAL_PERIOD, "30000");
        overrides.setProperty(ComputeServiceProperties.POLL_MAX_PERIOD, "30000");
        overrides.setProperty(Constants.PROPERTY_CONNECTION_TIMEOUT, "0");

        addAWSComputeService(modules, overrides);
        addHPComputeService(modules, overrides);
        addRackspaceComputeService(modules, overrides);
    }

    private void addAWSComputeService(Iterable<Module> modules, Properties overrides) {
        String awsAccessKey = System.getenv("AWS_ACCESS_KEY");
        String awsSecretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY");

        if (awsAccessKey != null && awsSecretAccessKey != null) {
            ComputeServiceContext context = ContextBuilder.newBuilder("aws-ec2")
                    .credentials(awsAccessKey, awsSecretAccessKey)
                    .modules(modules)
                    .overrides(overrides)
                    .buildView(ComputeServiceContext.class);
            computeServices.add(context.getComputeService());
        }
    }

    private void addHPComputeService(Iterable<Module> modules, Properties overrides) {
        String hpUsername = System.getenv("HP_USERNAME");
        String hpTenant = System.getenv("HP_TENANT");
        String hpPassword = System.getenv("HP_PASSWORD");

        if (hpUsername != null && hpPassword != null) {
            ComputeServiceContext context = ContextBuilder.newBuilder("hpcloud-compute")
                    .credentials(hpTenant + ":" + hpUsername, hpPassword)
                    .modules(modules)
                    .overrides(overrides)
                    .buildView(ComputeServiceContext.class);
            computeServices.add(context.getComputeService());
        }
    }

    private void addRackspaceComputeService(Iterable<Module> modules, Properties overrides) {
        String rackspaceUsername = System.getenv("RACKSPACE_USERNAME");
        String rackspaceApiKey = System.getenv("RACKSPACE_API_KEY");

        if (rackspaceUsername != null && rackspaceApiKey != null) {
            ComputeServiceContext context = ContextBuilder.newBuilder("rackspace-cloudservers-us")
                    .credentials(rackspaceUsername, rackspaceApiKey)
                    .modules(modules)
                    .overrides(overrides)
                    .buildView(ComputeServiceContext.class);
            computeServices.add(context.getComputeService());
        }
    }

    private void createNodes() {
        for (ComputeService computeService: computeServices) {
            try {
                TemplateOptions options = computeService.templateOptions()
                        .inboundPorts(22, 80);

                Template template = computeService.templateBuilder()
                        .osDescriptionMatches(".*Ubuntu.*12.04.*")
                        .minRam(1024)
                        .options(options)
                        .build();

                Set<? extends NodeMetadata> nodesInGroup = computeService.createNodesInGroup("jclouds-workshop", 1, template);
                NodeMetadata node = nodesInGroup.iterator().next();

                nodes.put(computeService, node);
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private void configureAndStartWebservers() {
        for (ComputeService computeService: nodes.keySet()) {
            try {
                NodeMetadata node = nodes.get(computeService);
                String publicAddress = node.getPublicAddresses().iterator().next();

                awaitSsh(publicAddress);

                String message = new StringBuilder()
                        .append("Hello from ")
                        .append(node.getHostname())
                        .append(" @ ")
                        .append(publicAddress)
                        .append(" in ")
                        .append(node.getLocation().getParent().getId())
                        .toString();

                RunScriptOptions options = RunScriptOptions.Builder

                        .blockOnComplete(true);

                String script = new ScriptBuilder()
                        .addStatement(exec("sudo apt-get -qq -y update"))
                        .render(OsFamily.UNIX);

                computeService.runScriptOnNode(node.getId(), script, options);

                script = new ScriptBuilder()
                        .addStatement(exec("sudo apt-get -qq -y install apache2"))
                        .addStatement(exec("ufw allow 22"))
                        .addStatement(exec("ufw allow 80"))
                        .addStatement(exec("ufw --force enable"))
                        .addStatement(exec("echo '" + message + "' > /var/www/index.html"))
                        .render(OsFamily.UNIX);

                computeService.runScriptOnNode(node.getId(), script, options);
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private void awaitSsh(String ip) throws TimeoutException {
        SocketOpen socketOpen = computeServices.get(0).getContext().utils().injector().getInstance(SocketOpen.class);
        Predicate<HostAndPort> socketTester = retry(socketOpen, 300, 5, 5, SECONDS);
        socketTester.apply(HostAndPort.fromParts(ip, 22));
    }

    private void detectExtensions() {
        for (ComputeService computeService: computeServices) {
            try {
                RestContext<NovaApi, NovaAsyncApi> nova = computeService.getContext().unwrap();
                Set<String> zones = nova.getApi().getConfiguredZones();
                String zone = Iterables.getFirst(zones, null);
                String provider = computeService.getContext().toString().contains("rackspace") ? "Rackspace" : "HP";

                Optional<? extends SecurityGroupApi> securityGroupExt =
                        nova.getApi().getSecurityGroupExtensionForZone(zone);

                System.out.println("Provider: " + provider);
                System.out.println("  Security Group Support: " + securityGroupExt.isPresent());

                if (securityGroupExt.isPresent()) {
                    SecurityGroupApi securityGroupApi = securityGroupExt.get();

                    for (SecurityGroup securityGroup: securityGroupApi.list()) {
                        System.out.println("    Security Group: " + securityGroup.getName());
                    }
                }
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private void printResults() throws IOException {
        for (ComputeService computeService: nodes.keySet()) {
            try {
                NodeMetadata node = nodes.get(computeService);
                String publicAddress = node.getPublicAddresses().iterator().next();
                String provider = computeService.getContext().toString().contains("rackspace") ? "Rackspace" : "HP";

                System.out.println("Provider: " + provider);

                if (node.getCredentials().getOptionalPrivateKey().isPresent()) {
                    Files.write(node.getCredentials().getPrivateKey(), new File("jclouds.pem"), UTF_8);
                    System.out.println("  Login: ssh -i jclouds.pem " + node.getCredentials().getUser() + "@" + publicAddress);
                }
                else {
                    System.out.println("  Login: ssh " + node.getCredentials().getUser() + "@" + publicAddress);
                    System.out.println("  Password: " + node.getCredentials().getPassword());
                }

                System.out.println("  Go to http://" + publicAddress);
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * This will delete all servers in group "jclouds-workshop"
     */
    private void deleteNodes() {
        System.out.println("Delete Nodes");

        for (ComputeService computeService: computeServices) {
            try {
                Set<? extends NodeMetadata> servers = computeService.destroyNodesMatching(inGroup("jclouds-workshop"));

                for (NodeMetadata nodeMetadata: servers) {
                    System.out.println("  " + nodeMetadata);
                }
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private void close() {
        for (ComputeService computeService: computeServices) {
            computeService.getContext().close();
        }
    }
}
