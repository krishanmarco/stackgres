/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.distributedlogs.patroni;

import java.util.Map;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.stackgres.common.LabelFactory;
import io.stackgres.common.crd.CommonDefinition;
import io.stackgres.common.crd.sgbackup.StackGresBackup;
import io.stackgres.common.crd.sgbackupconfig.StackGresBackupConfig;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgdbops.StackGresDbOps;
import io.stackgres.common.crd.sgdistributedlogs.StackGresDistributedLogs;
import io.stackgres.common.crd.sgpgconfig.StackGresPostgresConfig;
import io.stackgres.common.crd.sgpooling.StackGresPoolingConfig;
import io.stackgres.common.crd.sgprofile.StackGresProfile;
import io.stackgres.common.resource.ResourceFinder;
import io.stackgres.operator.conciliation.OperatorVersionBinder;
import io.stackgres.operator.conciliation.ResourceGenerator;
import io.stackgres.operator.conciliation.cluster.StackGresVersion;
import io.stackgres.operator.conciliation.distributedlogs.DistributedLogsContext;
import io.stackgres.operatorframework.resource.ResourceUtil;

@Singleton
@OperatorVersionBinder(startAt = StackGresVersion.V09, stopAt = StackGresVersion.V10)
public class PatroniRole implements
    ResourceGenerator<DistributedLogsContext> {
  public static final String SUFFIX = "-patroni";

  private LabelFactory<StackGresDistributedLogs> labelFactory;

  private ResourceFinder<ServiceAccount> serviceAccountFinder;

  public static String roleName(DistributedLogsContext clusterContext) {
    return roleName(clusterContext.getSource().getMetadata().getName());
  }

  public static String roleName(String clusterName) {
    return ResourceUtil.resourceName(clusterName + SUFFIX);
  }

  @Override
  public Stream<HasMetadata> generateResource(DistributedLogsContext context) {
    return Stream.of(
        createServiceAccount(context),
        createRole(context),
        createRoleBinding(context));
  }

  /**
   * Create the ServiceAccount for patroni associated to the cluster.
   */
  private ServiceAccount createServiceAccount(DistributedLogsContext context) {
    final StackGresDistributedLogs cluster = context.getSource();
    final Map<String, String> labels = labelFactory
        .clusterLabels(cluster);
    final String serviceAccountName = roleName(context);
    final String serviceAccountNamespace = cluster.getMetadata().getNamespace();

    return serviceAccountFinder.findByNameAndNamespace(serviceAccountName, serviceAccountNamespace)
        .map(sa -> {
          sa.getMetadata().setLabels(labels);
          return sa;
        })
        .orElse(new ServiceAccountBuilder()
            .withNewMetadata()
            .withName(serviceAccountName)
            .withNamespace(serviceAccountNamespace)
            .withLabels(labels)
            .withOwnerReferences(context.getOwnerReferences())
            .endMetadata()
            .build());
  }

  /**
   * Create the Role for patroni associated to the cluster.
   */
  private Role createRole(DistributedLogsContext context) {
    final StackGresDistributedLogs cluster = context.getSource();
    final Map<String, String> labels = labelFactory
        .clusterLabels(cluster);
    return new RoleBuilder()
        .withNewMetadata()
        .withName(roleName(context))
        .withNamespace(cluster.getMetadata().getNamespace())
        .withLabels(labels)
        .withOwnerReferences(context.getOwnerReferences())
        .endMetadata()
        .addToRules(new PolicyRuleBuilder()
            .withApiGroups("")
            .withResources("endpoints", "configmaps")
            .withVerbs("create", "get", "list", "patch", "update", "watch")
            .build())
        .addToRules(new PolicyRuleBuilder()
            .withApiGroups("")
            .withResources("secrets")
            .withVerbs("get")
            .build())
        .addToRules(new PolicyRuleBuilder()
            .withApiGroups("")
            .withResources("pods")
            .withVerbs("get", "list", "patch", "update", "watch")
            .build())
        .addToRules(new PolicyRuleBuilder()
            .withApiGroups("")
            .withResources("pods/exec")
            .withVerbs("create")
            .build())
        .addToRules(new PolicyRuleBuilder()
            .withApiGroups("batch")
            .withResources("cronjobs")
            .withVerbs("get", "patch")
            .build())
        .addToRules(new PolicyRuleBuilder()
            .withApiGroups("")
            .withResources("services")
            .withVerbs("create")
            .build())
        .addToRules(new PolicyRuleBuilder()
            .withApiGroups("")
            .withResources("events")
            .withVerbs("get", "list", "create", "patch", "update")
            .build())
        .addToRules(new PolicyRuleBuilder()
            .withApiGroups(CommonDefinition.GROUP)
            .withResources(HasMetadata.getPlural(StackGresBackup.class))
            .withVerbs("list", "get", "create", "patch", "delete")
            .build())
        .addToRules(new PolicyRuleBuilder()
            .withApiGroups(CommonDefinition.GROUP)
            .withResources(
                HasMetadata.getPlural(StackGresBackup.class),
                HasMetadata.getPlural(StackGresBackupConfig.class),
                HasMetadata.getPlural(StackGresCluster.class),
                HasMetadata.getPlural(StackGresPostgresConfig.class),
                HasMetadata.getPlural(StackGresPoolingConfig.class),
                HasMetadata.getPlural(StackGresProfile.class),
                HasMetadata.getPlural(StackGresDistributedLogs.class),
                HasMetadata.getPlural(StackGresDbOps.class))
            .withVerbs("get", "list", "watch", "patch")
            .build())
        .addToRules(new PolicyRuleBuilder()
            .withApiGroups(CommonDefinition.GROUP)
            .withResources(
                HasMetadata.getPlural(StackGresDistributedLogs.class) + "/status")
            .withVerbs("update")
            .build())
        .build();
  }

  /**
   * Create the RoleBinding for patroni associated to the cluster.
   */
  private RoleBinding createRoleBinding(DistributedLogsContext context) {
    final StackGresDistributedLogs cluster = context.getSource();
    final Map<String, String> labels = labelFactory
        .clusterLabels(cluster);
    return new RoleBindingBuilder()
        .withNewMetadata()
        .withName(roleName(context))
        .withNamespace(cluster.getMetadata().getNamespace())
        .withLabels(labels)
        .withOwnerReferences(context.getOwnerReferences())
        .endMetadata()
        .withSubjects(new SubjectBuilder()
            .withKind("ServiceAccount")
            .withName(roleName(context))
            .withNamespace(cluster.getMetadata().getNamespace())
            .build())
        .withRoleRef(new RoleRefBuilder()
            .withKind("Role")
            .withName(roleName(context))
            .withApiGroup("rbac.authorization.k8s.io")
            .build())
        .build();
  }

  @Inject
  public void setServiceAccountFinder(ResourceFinder<ServiceAccount> serviceAccountFinder) {
    this.serviceAccountFinder = serviceAccountFinder;
  }

  @Inject
  public void setLabelFactory(LabelFactory<StackGresDistributedLogs> labelFactory) {
    this.labelFactory = labelFactory;
  }
}
