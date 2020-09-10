/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.test.system.framework.services.kubernetes;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapBuilder;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerBuilder;
import io.kubernetes.client.openapi.models.V1ContainerPortBuilder;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentBuilder;
import io.kubernetes.client.openapi.models.V1DeploymentSpecBuilder;
import io.kubernetes.client.openapi.models.V1EnvVarBuilder;
import io.kubernetes.client.openapi.models.V1EnvVarSourceBuilder;
import io.kubernetes.client.openapi.models.V1LabelSelectorBuilder;
import io.kubernetes.client.openapi.models.V1ObjectFieldSelectorBuilder;
import io.kubernetes.client.openapi.models.V1ObjectMetaBuilder;
import io.kubernetes.client.openapi.models.V1PodSpecBuilder;
import io.kubernetes.client.openapi.models.V1PodTemplateSpecBuilder;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1beta1CustomResourceDefinition;
import io.kubernetes.client.openapi.models.V1beta1CustomResourceDefinitionBuilder;
import io.kubernetes.client.openapi.models.V1beta1CustomResourceDefinitionNamesBuilder;
import io.kubernetes.client.openapi.models.V1beta1CustomResourceDefinitionSpecBuilder;
import io.kubernetes.client.openapi.models.V1beta1CustomResourceDefinitionStatus;
import io.kubernetes.client.openapi.models.V1beta1PolicyRuleBuilder;
import io.kubernetes.client.openapi.models.V1beta1Role;
import io.kubernetes.client.openapi.models.V1beta1ClusterRole;
import io.kubernetes.client.openapi.models.V1beta1RoleBinding;
import io.kubernetes.client.openapi.models.V1beta1ClusterRoleBinding;
import io.kubernetes.client.openapi.models.V1beta1RoleBindingBuilder;
import io.kubernetes.client.openapi.models.V1beta1ClusterRoleBindingBuilder;
import io.kubernetes.client.openapi.models.V1beta1RoleBuilder;
import io.kubernetes.client.openapi.models.V1beta1ClusterRoleBuilder;
import io.kubernetes.client.openapi.models.V1beta1RoleRefBuilder;
import io.kubernetes.client.openapi.models.V1beta1SubjectBuilder;
import io.kubernetes.client.util.Yaml;
import io.pravega.common.concurrent.Futures;
import io.pravega.test.system.framework.Utils;
import io.pravega.test.system.framework.kubernetes.ClientFactory;
import io.pravega.test.system.framework.kubernetes.K8sClient;
import io.pravega.test.system.framework.services.Service;

import org.apache.commons.io.IOUtils;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

import static io.pravega.common.Exceptions.checkNotNullOrEmpty;
import static java.util.Collections.singletonList;

@Slf4j
public abstract class AbstractService implements Service {

    public static final int CONTROLLER_GRPC_PORT = 9090;
    public static final int CONTROLLER_REST_PORT = 10080;
    protected static final String DOCKER_REGISTRY =  System.getProperty("dockerRegistryUrl", "");
    protected static final String PREFIX = System.getProperty("imagePrefix", "pravega");
    protected static final String TCP = "tcp://";
    protected static final String TLS = "tls://";
    static final int DEFAULT_CONTROLLER_COUNT = 1;
    static final int DEFAULT_SEGMENTSTORE_COUNT = 1;
    static final int DEFAULT_BOOKIE_COUNT = 3;
    static final int MIN_READY_SECONDS = 10; // minimum duration the operator is up and running to be considered ready.
    static final int ZKPORT = 2181;

    static final int SEGMENTSTORE_PORT = 12345;
    static final int BOOKKEEPER_PORT = 3181;
    static final String NAMESPACE = "default";
    static final String CUSTOM_RESOURCE_GROUP_PRAVEGA = "pravega.pravega.io";
    static final String CUSTOM_RESOURCE_VERSION_PRAVEGA = "v1beta1";
    static final String CUSTOM_RESOURCE_API_VERSION = CUSTOM_RESOURCE_GROUP_PRAVEGA + "/" + CUSTOM_RESOURCE_VERSION_PRAVEGA;
    static final String CUSTOM_RESOURCE_PLURAL_PRAVEGA = "pravegaclusters";
    static final String CUSTOM_RESOURCE_KIND_PRAVEGA = "PravegaCluster";
    static final String PRAVEGA_CONTROLLER_LABEL = "pravega-controller";
    static final String PRAVEGA_SEGMENTSTORE_LABEL = "pravega-segmentstore";
    static final String PRAVEGA_CONTROLLER_CONFIG_MAP = "pravega-pravega-controller";
    static final String SECRET_NAME_USED_FOR_TLS = "selfsigned-cert-tls";
    static final String BOOKKEEPER_LABEL = "bookie";
    static final String PRAVEGA_ID = "pravega";
    static final String ZOOKEEPER_OPERATOR_IMAGE = System.getProperty("zookeeperOperatorImage", "pravega/zookeeper-operator:latest");
    static final String IMAGE_PULL_POLICY = System.getProperty("imagePullPolicy", "Always");
    static final String BOOKKEEPER_ID = "pravega-bk";
    static final String CUSTOM_RESOURCE_GROUP_BOOKKEEPER = "bookkeeper.pravega.io";
    static final String CUSTOM_RESOURCE_VERSION_BOOKKEEPER = "v1alpha1";
    static final String CUSTOM_RESOURCE_API_VERSION_BOOKKEEPER = CUSTOM_RESOURCE_GROUP_BOOKKEEPER + "/" + CUSTOM_RESOURCE_VERSION_BOOKKEEPER;
    static final String CUSTOM_RESOURCE_PLURAL_BOOKKEEPER = "bookkeeperclusters";
    static final String CUSTOM_RESOURCE_KIND_BOOKKEEPER = "BookkeeperCluster";
    static final String CONFIG_MAP_BOOKKEEPER = "bk-config-map";

    private static final String PRAVEGA_VERSION = System.getProperty("imageVersion", "latest");
    private static final String PRAVEGA_IMAGE_NAME = System.getProperty("pravegaImageName", "pravega");
    private static final String BOOKKEEPER_IMAGE_NAME = System.getProperty("bookkeeperImageName", "bookkeeper");
    private static final String TIER2_NFS = "nfs";
    private static final String TIER2_TYPE = System.getProperty("tier2Type", TIER2_NFS);
    private static final String WEBHOOK_CERT_GENERATE = "webhookCert.generate";
    private static final String BOOKKEEPER_VERSION = System.getProperty("bookkeeperImageVersion", "latest");
    private static final String ZK_SERVICE_NAME = "zookeeper-client:2181";
    private static final String JOURNALDIRECTORIES = "bk/journal/j0,/bk/journal/j1,/bk/journal/j2,/bk/journal/j3";
    private static final String LEDGERDIRECTORIES = "/bk/ledgers/l0,/bk/ledgers/l1,/bk/ledgers/l2,/bk/ledgers/l3";

    final K8sClient k8sClient;
    private final String id;

    AbstractService(final String id) {
        this.k8sClient = ClientFactory.INSTANCE.getK8sClient();
        this.id = id;
    }

    @Override
    public String getID() {
        return id;
    }

    CompletableFuture<Object> deployPravegaOnlyCluster(final URI zkUri, int controllerCount, int segmentStoreCount, ImmutableMap<String, String> props) {
    return registerTLSSecret()
            .thenCompose(v -> k8sClient.createAndUpdateCustomObject(CUSTOM_RESOURCE_GROUP_PRAVEGA, CUSTOM_RESOURCE_VERSION_PRAVEGA,
            NAMESPACE, CUSTOM_RESOURCE_PLURAL_PRAVEGA,
            getPravegaOnlyDeployment(zkUri.getAuthority(),
                    controllerCount,
                    segmentStoreCount,
                    props)));
    }

    private Map<String, Object> getPravegaOnlyDeployment(String zkLocation, int controllerCount, int segmentStoreCount, ImmutableMap<String, String> props) {
        // generate Pravega Spec.
        final Map<String, Object> pravegaPersistentVolumeSpec = getPersistentVolumeClaimSpec("20Gi", "standard");
        final String pravegaImg = DOCKER_REGISTRY + PREFIX + "/" + PRAVEGA_IMAGE_NAME;
        final Map<String, Object> pravegaImgSpec;

         pravegaImgSpec = ImmutableMap.of("repository", pravegaImg);

        final Map<String, Object> pravegaSpec = ImmutableMap.<String, Object>builder().put("controllerReplicas", controllerCount)
                .put("segmentStoreReplicas", segmentStoreCount)
                .put("debugLogging", true)
                .put("cacheVolumeClaimTemplate", pravegaPersistentVolumeSpec)
                .put("controllerResources", getResources("2000m", "3Gi", "1000m", "1Gi"))
                .put("segmentStoreResources", getResources("2000m", "5Gi", "1000m", "3Gi"))
                .put("options", props)
                .put("image", pravegaImgSpec)
                .put("longtermStorage", tier2Spec())
                .build();

        final Map<String, Object> staticTlsSpec = ImmutableMap.<String, Object>builder()
                .put("controllerSecret", SECRET_NAME_USED_FOR_TLS)
                .put("segmentStoreSecret", SECRET_NAME_USED_FOR_TLS)
                .build();

        final Map<String, Object> tlsSpec = ImmutableMap.<String, Object>builder()
                .put("static", staticTlsSpec)
                .build();

        return ImmutableMap.<String, Object>builder()
                .put("apiVersion", CUSTOM_RESOURCE_API_VERSION)
                .put("kind", CUSTOM_RESOURCE_KIND_PRAVEGA)
                .put("metadata", ImmutableMap.of("name", PRAVEGA_ID, "namespace", NAMESPACE))
                .put("spec", buildPravegaClusterSpecWithBookieUri(zkLocation, pravegaSpec, tlsSpec))
                .build();
    }

    protected Map<String, Object> buildPravegaClusterSpecWithBookieUri(String zkLocation, Map<String, Object> pravegaSpec,
                                                                       Map<String, Object> tlsSpec) {

        ImmutableMap.Builder<String, Object> builder = ImmutableMap.<String, Object>builder();
        builder.put("zookeeperUri", zkLocation)
                .put("bookkeeperUri", BOOKKEEPER_ID + "-" + BOOKKEEPER_LABEL + "-headless" + ":" + BOOKKEEPER_PORT)
                .put("pravega", pravegaSpec);
        builder.put("version", PRAVEGA_VERSION);

        if (Utils.TLS_AND_AUTH_ENABLED) {
            builder.put("tls", tlsSpec);
        }
        return builder.build();
    }

    protected static Map<String, Object> buildPatchedPravegaClusterSpec(String service, int replicaCount, String component) {

        final Map<String, Object> componentSpec = ImmutableMap.<String, Object>builder()
                .put(service, replicaCount)
                .build();

        return ImmutableMap.<String, Object>builder()
                .put("apiVersion", CUSTOM_RESOURCE_API_VERSION)
                .put("kind", CUSTOM_RESOURCE_KIND_PRAVEGA)
                .put("metadata", ImmutableMap.of("name", PRAVEGA_ID, "namespace", NAMESPACE))
                .put("spec", ImmutableMap.builder()
                        .put(component, componentSpec)
                        .build())
                .build();
    }

    private Map<String, Object> tier2Spec() {
        final Map<String, Object> spec;
        if (TIER2_TYPE.equalsIgnoreCase(TIER2_NFS)) {
            spec = ImmutableMap.of("filesystem", ImmutableMap.of("persistentVolumeClaim",
                                                                 ImmutableMap.of("claimName", "pravega-tier2")));
        } else {
            // handle other types of tier2 like HDFS and Extended S3 Object Store.
            spec = ImmutableMap.of(TIER2_TYPE, getTier2Config());
        }
        return spec;
    }

    private Map<String, Object> getTier2Config() {
        String tier2Config = System.getProperty("tier2Config");
        checkNotNullOrEmpty(tier2Config, "tier2Config");
        Map<String, String> split = Splitter.on(',').trimResults().withKeyValueSeparator("=").split(tier2Config);
        return split.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> {
            try {
                return Integer.parseInt(e.getValue());
            } catch (NumberFormatException ex) {
                // return all non integer configuration as String.
                return e.getValue();
            }
        }));
    }

    private Map<String, Object> getPersistentVolumeClaimSpec(String size, String storageClass) {
        return ImmutableMap.<String, Object>builder()
                .put("accessModes", singletonList("ReadWriteOnce"))
                .put("storageClassName", storageClass)
                .put("resources", ImmutableMap.of("requests", ImmutableMap.of("storage", size)))
                .build();
    }

    protected Map<String, Object> getImageSpec(String imageName, String tag) {
        return ImmutableMap.<String, Object>builder().put("repository", imageName)
                .put("tag", tag)
                .put("pullPolicy", IMAGE_PULL_POLICY)
                .build();
    }

    private Map<String, Object> getResources(String limitsCpu, String limitsMem, String requestsCpu, String requestsMem) {
        return ImmutableMap.<String, Object>builder()
                .put("limits", ImmutableMap.builder()
                        .put("cpu", limitsCpu)
                        .put("memory", limitsMem)
                        .build())
                .put("requests", ImmutableMap.builder()
                        .put("cpu", requestsCpu)
                        .put("memory", requestsMem)
                        .build())
                .build();
    }

    private static V1Secret getTLSSecret() throws IOException {
        String data = "";
        String yamlInputPath = "secret.yaml";
        try (InputStream inputStream = Utils.class.getClassLoader().getResourceAsStream(yamlInputPath)) {
            data = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        }
        Yaml.addModelMap("v1", "Secret", V1Secret.class);
        V1Secret yamlSecret = (V1Secret) Yaml.loadAs(data, V1Secret.class);
        return yamlSecret;
    }

    private CompletableFuture<V1Secret> registerTLSSecret() {
        if (!Utils.TLS_AND_AUTH_ENABLED) {
            return CompletableFuture.completedFuture(null);
        }
        try {
        V1Secret secret = getTLSSecret();
        V1Secret existingSecret  = Futures.getThrowingException(k8sClient.getSecret(SECRET_NAME_USED_FOR_TLS, NAMESPACE));
        if (existingSecret != null) {
            Futures.getThrowingException(k8sClient.deleteSecret(SECRET_NAME_USED_FOR_TLS, NAMESPACE));
        }
        return k8sClient.createSecret(NAMESPACE, secret);
        } catch (Exception e) {
            log.error("Could not register secret: ", e);
        }
        return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Object> deployBookkeeperCluster(final URI zkUri, int bookieCount, ImmutableMap<String, String> props) {
        return k8sClient.createConfigMap(NAMESPACE, getBookkeeperOperatorConfigMap())
                // request operator to deploy bookkeeper nodes.
                .thenCompose(v -> k8sClient.createAndUpdateCustomObject(CUSTOM_RESOURCE_GROUP_BOOKKEEPER, CUSTOM_RESOURCE_VERSION_BOOKKEEPER,
                        NAMESPACE, CUSTOM_RESOURCE_PLURAL_BOOKKEEPER,
                        getBookkeeperDeployment(zkUri.getAuthority(),
                                bookieCount,
                                props)));
    }

    private V1ConfigMap getBookkeeperOperatorConfigMap() {
            Map<String, String>  dataMap = new HashMap<>();
            dataMap.put("PRAVEGA_CLUSTER_NAME", PRAVEGA_ID);
            dataMap.put("WAIT_FOR", ZK_SERVICE_NAME);

        return new V1ConfigMapBuilder().withApiVersion("v1")
                .withKind("ConfigMap")
                .withMetadata(new V1ObjectMeta().name(CONFIG_MAP_BOOKKEEPER))
                .withData(dataMap)
                .build();
    }

    private Map<String, Object> getBookkeeperDeployment(String zkLocation, int bookieCount, ImmutableMap<String, String> props) {
        // generate BookkeeperSpec.
        final Map<String, Object> bkPersistentVolumeSpec = getPersistentVolumeClaimSpec("10Gi", "standard");
        final Map<String, Object> bookkeeperSpec = ImmutableMap.<String, Object>builder().put("image", getImageSpec(DOCKER_REGISTRY + PREFIX + "/" + BOOKKEEPER_IMAGE_NAME, BOOKKEEPER_VERSION))
                .put("replicas", bookieCount)
                .put("version", BOOKKEEPER_VERSION)
                .put("resources", getResources("2000m", "5Gi", "1000m", "3Gi"))
                .put("storage", ImmutableMap.builder()
                        .put("indexVolumeClaimTemplate", bkPersistentVolumeSpec)
                        .put("ledgerVolumeClaimTemplate", bkPersistentVolumeSpec)
                        .put("journalVolumeClaimTemplate", bkPersistentVolumeSpec)
                        .build())

                .put("envVars", CONFIG_MAP_BOOKKEEPER)
                .put("zookeeperUri", zkLocation)
                .put("autoRecovery", true)
                .put("options", ImmutableMap.builder()  .put("journalDirectories", JOURNALDIRECTORIES)
                        .put("ledgerDirectories", LEDGERDIRECTORIES)
                        .build())
                .build();

        return ImmutableMap.<String, Object>builder()
                .put("apiVersion", CUSTOM_RESOURCE_API_VERSION_BOOKKEEPER)
                .put("kind", CUSTOM_RESOURCE_KIND_BOOKKEEPER)
                .put("metadata", ImmutableMap.of("name", BOOKKEEPER_ID, "namespace", NAMESPACE))
                .put("spec", bookkeeperSpec)
                .build();
    }

    /**
     * Helper method to create the BK Cluster Spec which specifies just those values in the
     * spec which need to be patched. Other values remain same as were specified at the time of
     * deployment.
     * @param service Name of the service to be patched (bookkeeper/ segment store/ controller).
     * @param replicaCount Number of replicas.
     *
     * @return the new Pravega Cluster Spec containing the values that need to be patched.
     */

    protected static Map<String, Object> buildPatchedBookkeeperClusterSpec(String service, int replicaCount) {

        return ImmutableMap.<String, Object>builder()
                .put("apiVersion", CUSTOM_RESOURCE_API_VERSION_BOOKKEEPER)
                .put("kind", CUSTOM_RESOURCE_KIND_BOOKKEEPER)
                .put("metadata", ImmutableMap.of("name", BOOKKEEPER_ID, "namespace", NAMESPACE))
                .put("spec", ImmutableMap.builder()
                        .put(service, replicaCount)
                        .build())
                .build();
    }

    @Override
    public void clean() {
        // this is a NOP for KUBERNETES based implementation.
    }
}
