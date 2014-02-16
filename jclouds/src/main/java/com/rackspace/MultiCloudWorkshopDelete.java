package com.rackspace;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Module;
import com.rackspace.MultiCloudWorkshop.ProviderProperty;
import org.jclouds.ContextBuilder;
import org.jclouds.aws.ec2.AWSEC2Api;
import org.jclouds.aws.ec2.features.AWSKeyPairApi;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.SecurityGroup;
import org.jclouds.compute.extensions.SecurityGroupExtension;
import org.jclouds.domain.Location;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.NovaAsyncApi;
import org.jclouds.openstack.nova.v2_0.domain.FloatingIP;
import org.jclouds.openstack.nova.v2_0.domain.KeyPair;
import org.jclouds.openstack.nova.v2_0.extensions.FloatingIPApi;
import org.jclouds.openstack.nova.v2_0.extensions.KeyPairApi;
import org.jclouds.rest.RestContext;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static com.rackspace.MultiCloudWorkshop.MCW;
import static com.rackspace.MultiCloudWorkshop.ProviderProperty.*;
import static java.lang.String.format;
import static org.jclouds.compute.config.ComputeServiceProperties.POLL_INITIAL_PERIOD;
import static org.jclouds.compute.config.ComputeServiceProperties.POLL_MAX_PERIOD;

public class MultiCloudWorkshopDelete {
    private final ComputeService computeService;
    private final Map<ProviderProperty, String> providerProps;

    private final Logger logger = LoggerFactory.getLogger(MultiCloudWorkshopDelete.class);
    private final Stopwatch timer;

    public static void main(String[] args) {
        MultiCloudWorkshopDelete multiCloudWorkshopDelete = null;

        try {
            multiCloudWorkshopDelete = new MultiCloudWorkshopDelete();
            multiCloudWorkshopDelete.deleteKeyPairs();
            multiCloudWorkshopDelete.deleteSecurityGroups();
            multiCloudWorkshopDelete.deleteFloatingIPsAndNodes();
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            if (multiCloudWorkshopDelete != null) {
                multiCloudWorkshopDelete.close();
            }
        }
    }

    public MultiCloudWorkshopDelete() throws IOException {
        timer = Stopwatch.createStarted();
        providerProps = MultiCloudWorkshop.getProviderProperties();

        logger.info(format("Multi-Cloud Workshop Delete on %s", providerProps.get(PROVIDER).toUpperCase()));
        logger.info("  Check log/jclouds-wire.log for progress");

        Iterable<Module> modules = ImmutableSet.<Module>of(
                new SLF4JLoggingModule(),
                new SshjSshClientModule());

        Properties overrides = new Properties();
        overrides.setProperty(POLL_INITIAL_PERIOD, "30000");
        overrides.setProperty(POLL_MAX_PERIOD, "30000");

        ComputeServiceContext context = ContextBuilder.newBuilder(providerProps.get(NAME))
                .credentials(providerProps.get(IDENTITY), providerProps.get(CREDENTIAL))
                .modules(modules)
                .overrides(overrides)
                .buildView(ComputeServiceContext.class);
        computeService = context.getComputeService();
    }

    private void deleteKeyPairs() {
        logger.info("  Deleted Key Pairs:");

        if (providerProps.get(PROVIDER).equals("aws")) {
            AWSEC2Api awsEc2Api = computeService.getContext().unwrapApi(AWSEC2Api.class);
            AWSKeyPairApi awsKeyPairApi = awsEc2Api.getKeyPairApiForRegion(providerProps.get(LOCATION)).get();

            Set<org.jclouds.ec2.domain.KeyPair> keyPairs = awsKeyPairApi.describeKeyPairsInRegion(providerProps.get(LOCATION), MCW);

            for (org.jclouds.ec2.domain.KeyPair keyPair : keyPairs) {
                if (keyPair.getKeyName().contains(MCW)) {
                    awsKeyPairApi.deleteKeyPairInRegion(providerProps.get(LOCATION), keyPair.getKeyName());
                    logger.info(format("    %s", keyPair.getKeyName()));
                }
            }
        } else {
            RestContext<NovaApi, NovaAsyncApi> novaContext = computeService.getContext().unwrap();
            NovaApi novaApi = novaContext.getApi();
            KeyPairApi keyPairApi = novaApi.getKeyPairExtensionForZone(providerProps.get(LOCATION)).get();

            ImmutableSet<? extends KeyPair> keyPairs = keyPairApi.list().toSet();

            for (KeyPair keyPair : keyPairs) {
                if (keyPair.getName().contains(MCW)) {
                    keyPairApi.delete(keyPair.getName());
                    logger.info(format("    %s", keyPair.getName()));
                }
            }
        }
    }

    private void deleteSecurityGroups() {
        if (!providerProps.get(PROVIDER).equals("rackspace")) {
            logger.info("  Deleted Security Groups:");

            SecurityGroupExtension securityGroupExtension = computeService.getSecurityGroupExtension().get();

            for (SecurityGroup securityGroup : securityGroupExtension.listSecurityGroups()) {
                if (securityGroup.getName().contains(MCW)) {
                    securityGroupExtension.removeSecurityGroup(securityGroup.getId());
                    logger.info(format("    %s", securityGroup.getName()));
                }
            }
        }
    }

    private boolean securityGroupExists(Location location) {

        return false;
    }

    private void deleteFloatingIPsAndNodes() {
        try {
            Set<? extends NodeMetadata> nodes = computeService.listNodesDetailsMatching(nameContains(MCW));

            if (providerProps.get(PROVIDER).equals("hp")) {
                RestContext<NovaApi, NovaAsyncApi> novaContext = computeService.getContext().unwrap();
                NovaApi novaApi = novaContext.getApi();

                Optional<? extends FloatingIPApi> floatingIPApiOptional = novaApi.getFloatingIPExtensionForZone(providerProps.get(LOCATION));

                if (floatingIPApiOptional.isPresent()) {

                    logger.info("  Deleted IPs:");

                    for (FloatingIP floatingIp : floatingIPApiOptional.get().list().toSet()) {
                        for (NodeMetadata node : nodes) {
                            if (floatingIp.getInstanceId().equals(node.getProviderId())) {
                                floatingIPApiOptional.get().delete(floatingIp.getId());
                                logger.info(format("    %s", floatingIp.getIp()));
                            }
                        }
                    }
                }
            }

            logger.info("  Deleted nodes:");

            for (NodeMetadata node : nodes) {
                computeService.destroyNode(node.getId());

                logger.info(format("    %s", node.getName()));
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void close() {
        computeService.getContext().close();

        logger.info(format("%nThe code took %s to run%n", timer.stop()));
    }

    public static Predicate<ComputeMetadata> nameContains(final String name) {
        Preconditions.checkNotNull(name, "name must be defined");

        return new Predicate<ComputeMetadata>() {
            @Override
            public boolean apply(ComputeMetadata computeMetadata) {
                return computeMetadata.getName().contains(name);
            }

            @Override
            public String toString() {
                return "nameContains(" + name + ")";
            }
        };
    }
}
