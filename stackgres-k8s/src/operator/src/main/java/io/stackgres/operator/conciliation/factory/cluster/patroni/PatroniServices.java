/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.cluster.patroni;

import static io.stackgres.operator.conciliation.factory.cluster.patroni.PatroniConfigMap.PATRONI_RESTAPI_PORT_NAME;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.collect.ImmutableMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.stackgres.common.LabelFactory;
import io.stackgres.common.PatroniUtil;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgcluster.StackGresClusterPostgresService;
import io.stackgres.common.crd.sgcluster.StackGresClusterPostgresServiceType;
import io.stackgres.common.crd.sgcluster.StackGresClusterPostgresServices;
import io.stackgres.common.crd.sgcluster.StackGresClusterSpec;
import io.stackgres.operator.conciliation.OperatorVersionBinder;
import io.stackgres.operator.conciliation.ResourceGenerator;
import io.stackgres.operator.conciliation.cluster.StackGresClusterContext;
import io.stackgres.operator.conciliation.cluster.StackGresVersion;
import io.stackgres.operatorframework.resource.ResourceUtil;
import org.jooq.lambda.Seq;

@Singleton
@OperatorVersionBinder(startAt = StackGresVersion.V09, stopAt = StackGresVersion.V10)
public class PatroniServices implements
    ResourceGenerator<StackGresClusterContext> {

  public static final int POSTGRES_SERVICE_PORT = 5432;
  public static final int REPLICATION_SERVICE_PORT = 5433;
  public static final int PATRONI_SERVICE_PORT = 8008;

  private LabelFactory<StackGresCluster> labelFactory;

  public static String name(StackGresClusterContext clusterContext) {
    String name = clusterContext.getSource().getMetadata().getName();
    return PatroniUtil.name(name);
  }

  public static String restName(StackGresClusterContext clusterContext) {
    String name = clusterContext.getSource().getMetadata().getName();
    return PatroniUtil.name(name + "-rest");
  }

  public static String readWriteName(StackGresClusterContext clusterContext) {
    String name = clusterContext.getSource().getMetadata().getName();
    return PatroniUtil.readWriteName(name);
  }

  public static String readOnlyName(StackGresClusterContext clusterContext) {
    String name = clusterContext.getSource().getMetadata().getName();
    return PatroniUtil.readOnlyName(name);
  }

  public String configName(StackGresClusterContext clusterContext) {
    final StackGresCluster cluster = clusterContext.getSource();
    final String scope = labelFactory.clusterScope(cluster);
    return ResourceUtil.resourceName(
        scope + PatroniUtil.CONFIG_SERVICE);
  }

  /**
   * Create the Services associated with the cluster.
   */
  @Override
  public Stream<HasMetadata> generateResource(StackGresClusterContext context) {
    final StackGresCluster cluster = context.getSource();
    final String namespace = cluster.getMetadata().getNamespace();

    final Map<String, String> clusterLabels = labelFactory.clusterLabels(cluster);

    Service config = createConfigService(namespace, configName(context),
        clusterLabels);
    Service rest = createPatroniRestService(context);

    Seq<HasMetadata> services = Seq.of(config, rest);

    boolean isPrimaryServiceEnabled = Optional.of(cluster)
        .map(StackGresCluster::getSpec)
        .map(StackGresClusterSpec::getPostgresServices)
        .map(StackGresClusterPostgresServices::getPrimary)
        .map(StackGresClusterPostgresService::getEnabled)
        .orElse(true);

    if (isPrimaryServiceEnabled) {
      Service patroni = createPatroniService(context);
      services = services.append(patroni);
      Service primary = createPrimaryService(context);
      services = services.append(primary);
    }

    boolean isReplicaServiceEnabled = Optional.of(cluster)
        .map(StackGresCluster::getSpec)
        .map(StackGresClusterSpec::getPostgresServices)
        .map(StackGresClusterPostgresServices::getReplicas)
        .map(StackGresClusterPostgresService::getEnabled)
        .orElse(true);

    if (isReplicaServiceEnabled) {
      Service replicas = createReplicaService(context);
      services = services.append(replicas);
    }

    return services;
  }

  private Service createConfigService(String namespace, String serviceName,
                                      Map<String, String> labels) {
    return new ServiceBuilder()
        .withNewMetadata()
        .withNamespace(namespace)
        .withName(serviceName)
        .withLabels(labels)
        .endMetadata()
        .withNewSpec()
        .withClusterIP("None")
        .endSpec()
        .build();
  }

  private Service createPatroniRestService(StackGresClusterContext context) {
    final StackGresCluster cluster = context.getSource();

    final Map<String, String> clusterLabels = labelFactory.clusterLabels(cluster);

    return new ServiceBuilder()
        .withNewMetadata()
        .withNamespace(cluster.getMetadata().getNamespace())
        .withName(restName(context))
        .withLabels(clusterLabels)
        .endMetadata()
        .withNewSpec()
        .withPorts(
            new ServicePortBuilder()
                .withProtocol("TCP")
                .withName(PATRONI_RESTAPI_PORT_NAME)
                .withPort(PATRONI_SERVICE_PORT)
                .withTargetPort(new IntOrString(PATRONI_RESTAPI_PORT_NAME))
                .build())
        .withSelector(labelFactory.patroniClusterLabels(cluster))
        .withType(StackGresClusterPostgresServiceType.CLUSTER_IP.type())
        .endSpec()
        .build();
  }

  private Service createPatroniService(StackGresClusterContext context) {

    StackGresCluster cluster = context.getSource();

    final Map<String, String> primaryLabels = labelFactory.patroniPrimaryLabels(cluster);

    Map<String, String> annotations = Optional.ofNullable(cluster.getSpec())
        .map(StackGresClusterSpec::getPostgresServices)
        .map(StackGresClusterPostgresServices::getPrimary)
        .map(StackGresClusterPostgresService::getAnnotations)
        .orElse(ImmutableMap.of());

    String serviceType = Optional.ofNullable(cluster.getSpec())
        .map(StackGresClusterSpec::getPostgresServices)
        .map(StackGresClusterPostgresServices::getPrimary)
        .map(StackGresClusterPostgresService::getType)
        .orElse(StackGresClusterPostgresServiceType.CLUSTER_IP.type());

    return new ServiceBuilder()
        .withNewMetadata()
        .withNamespace(cluster.getMetadata().getNamespace())
        .withName(name(context))
        .withLabels(primaryLabels)
        .withAnnotations(annotations)
        .endMetadata()
        .withNewSpec()
        .withPorts(
            new ServicePortBuilder()
                .withProtocol("TCP")
                .withName(PatroniConfigMap.POSTGRES_PORT_NAME)
                .withPort(PatroniUtil.POSTGRES_SERVICE_PORT)
                .withTargetPort(new IntOrString(PatroniConfigMap.POSTGRES_PORT_NAME))
                .build(),
            new ServicePortBuilder()
                .withProtocol("TCP")
                .withName(PatroniConfigMap.POSTGRES_REPLICATION_PORT_NAME)
                .withPort(PatroniUtil.REPLICATION_SERVICE_PORT)
                .withTargetPort(new IntOrString(PatroniConfigMap.POSTGRES_REPLICATION_PORT_NAME))
                .build())
        .withType(serviceType)
        .endSpec()
        .build();
  }

  private Service createPrimaryService(StackGresClusterContext context) {
    StackGresCluster cluster = context.getSource();

    final Map<String, String> clusterLabels = labelFactory.clusterLabels(cluster);

    return new ServiceBuilder()
        .withNewMetadata()
        .withNamespace(cluster.getMetadata().getNamespace())
        .withName(readWriteName(context))
        .withLabels(clusterLabels)
        .endMetadata()
        .withNewSpec()
        .withType("ExternalName")
        .withExternalName(name(context) + "." + cluster.getMetadata().getNamespace()
            + ".svc.cluster.local")
        .endSpec()
        .build();
  }

  private Service createReplicaService(StackGresClusterContext context) {

    StackGresCluster cluster = context.getSource();

    final Map<String, String> replicaLabels = labelFactory.patroniReplicaLabels(cluster);

    Map<String, String> annotations = Optional.ofNullable(cluster.getSpec())
        .map(StackGresClusterSpec::getPostgresServices)
        .map(StackGresClusterPostgresServices::getReplicas)
        .map(StackGresClusterPostgresService::getAnnotations)
        .orElse(ImmutableMap.of());

    String serviceType = Optional.ofNullable(cluster.getSpec())
        .map(StackGresClusterSpec::getPostgresServices)
        .map(StackGresClusterPostgresServices::getReplicas)
        .map(StackGresClusterPostgresService::getType)
        .orElse(StackGresClusterPostgresServiceType.CLUSTER_IP.type());

    return new ServiceBuilder()
        .withNewMetadata()
        .withNamespace(cluster.getMetadata().getNamespace())
        .withName(readOnlyName(context))
        .withLabels(replicaLabels)
        .withAnnotations(annotations)
        .endMetadata()
        .withNewSpec()
        .withSelector(replicaLabels)
        .withPorts(new ServicePortBuilder()
                .withProtocol("TCP")
                .withName(PatroniConfigMap.POSTGRES_PORT_NAME)
                .withPort(PatroniUtil.POSTGRES_SERVICE_PORT)
                .withTargetPort(new IntOrString(PatroniConfigMap.POSTGRES_PORT_NAME))
                .build(),
            new ServicePortBuilder()
                .withProtocol("TCP")
                .withName(PatroniConfigMap.POSTGRES_REPLICATION_PORT_NAME)
                .withPort(PatroniUtil.REPLICATION_SERVICE_PORT)
                .withTargetPort(new IntOrString(PatroniConfigMap.POSTGRES_REPLICATION_PORT_NAME))
                .build())
        .withType(serviceType)
        .endSpec()
        .build();
  }

  @Inject
  public void setLabelFactory(LabelFactory<StackGresCluster> labelFactory) {
    this.labelFactory = labelFactory;
  }
}
