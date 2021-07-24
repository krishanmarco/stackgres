/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.jobs.dbops;

import static io.stackgres.jobs.dbops.clusterrestart.PodTestUtil.assertPodEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.google.common.collect.ImmutableMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import io.smallrye.mutiny.Multi;
import io.stackgres.common.ClusterPendingRestartUtil.RestartReasons;
import io.stackgres.common.StackGresContext;
import io.stackgres.common.crd.sgcluster.ClusterDbOpsRestartStatus;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgdbops.DbOpsRestartStatus;
import io.stackgres.common.crd.sgdbops.StackGresDbOps;
import io.stackgres.common.crd.sgdbops.StackGresDbOpsStatus;
import io.stackgres.jobs.dbops.clusterrestart.ClusterRestartImpl;
import io.stackgres.jobs.dbops.clusterrestart.ClusterRestartState;
import io.stackgres.jobs.dbops.clusterrestart.ImmutableClusterRestartState;
import io.stackgres.jobs.dbops.clusterrestart.ImmutableRestartEvent;
import io.stackgres.jobs.dbops.clusterrestart.InvalidCluster;
import io.stackgres.jobs.dbops.clusterrestart.PodTestUtil;
import io.stackgres.jobs.dbops.clusterrestart.RestartEventType;
import io.stackgres.jobs.dbops.lock.MockKubeDb;
import io.stackgres.testutil.JsonUtil;
import io.stackgres.testutil.StringUtils;
import org.apache.commons.compress.utils.Lists;
import org.jooq.lambda.Seq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithKubernetesTestServer
public abstract class ClusterStateHandlerTest {

  @InjectMock
  public ClusterRestartImpl clusterRestart;

  @Inject
  public PodTestUtil podTestUtil;

  @Inject
  public MockKubeDb kubeDb;

  public String namespace = StringUtils.getRandomNamespace();

  public String dbOpsName = StringUtils.getRandomClusterName();

  public String clusterName = StringUtils.getRandomClusterName();

  public StackGresDbOps dbOps;

  public StackGresCluster cluster;

  protected static void assertEqualsRestartState(ClusterRestartState expected,
      ClusterRestartState actual) {
    assertEquals(expected.getClusterName(), actual.getClusterName());
    assertEquals(expected.getNamespace(), actual.getNamespace());

    assertPodEquals(expected.getPrimaryInstance(), actual.getPrimaryInstance());

    var expectedInitialInstances = expected.getInitialInstances()
        .stream().sorted(Comparator.comparing(pod -> pod.getMetadata().getName()))
        .collect(Collectors.toUnmodifiableList());
    var actualInitialInstances = actual.getInitialInstances().stream()
        .sorted(Comparator.comparing(pod -> pod.getMetadata().getName()))
        .collect(Collectors.toUnmodifiableList());

    Seq.zip(expectedInitialInstances, actualInitialInstances)
        .forEach(tuple -> assertPodEquals(tuple.v1, tuple.v2));

    final List<Pod> restartedInstances = expected.getRestartedInstances()
        .stream().sorted(Comparator.comparing(pod -> pod.getMetadata().getName()))
        .collect(Collectors.toUnmodifiableList());

    final List<Pod> actualRestartedInstances = actual.getRestartedInstances()
        .stream().sorted(Comparator.comparing(pod -> pod.getMetadata().getName()))
        .collect(Collectors.toUnmodifiableList());

    Seq.zip(restartedInstances, actualRestartedInstances)
        .forEach(tuple -> assertPodEquals(tuple.v1, tuple.v2));

    final List<Pod> expectedTotalInstances = expected.getTotalInstances()
        .stream().sorted(Comparator.comparing(pod -> pod.getMetadata().getName()))
        .collect(Collectors.toUnmodifiableList());
    final List<Pod> actualTotalInstances = actual.getTotalInstances()
        .stream().sorted(Comparator.comparing(pod -> pod.getMetadata().getName()))
        .collect(Collectors.toUnmodifiableList());

    Seq.zip(expectedTotalInstances, actualTotalInstances)
        .forEach(tuple -> assertPodEquals(tuple.v1, tuple.v2));
  }

  @BeforeEach
  public void setUp() {
    namespace = StringUtils.getRandomNamespace();
    clusterName = StringUtils.getRandomClusterName();

    dbOps = JsonUtil.readFromJson("stackgres_dbops/dbops_securityupgrade.json",
        StackGresDbOps.class);

    cluster = JsonUtil.readFromJson("stackgres_cluster/default.json",
        StackGresCluster.class);

    dbOps.getMetadata().setName(dbOpsName);
    dbOps.getMetadata().setNamespace(namespace);
    dbOps.getSpec().setSgCluster(clusterName);
    dbOps.setStatus(new StackGresDbOpsStatus());
    dbOps.getStatus().setOpRetries(0);
    dbOps.getStatus().setOpStarted(Instant.now().toString());
    dbOps.getSpec().setOp("securityUpgrade");

    cluster.getMetadata().setName(clusterName);
    cluster.getMetadata().setNamespace(namespace);

    cluster = kubeDb.addOrReplaceCluster(cluster);
    dbOps = kubeDb.addOrReplaceDbOps(dbOps);
  }

  public abstract AbstractRestartStateHandler getRestartStateHandler();

  public abstract DbOpsRestartStatus getRestartStatus(StackGresDbOps dbOps);

  public abstract Optional<ClusterDbOpsRestartStatus> getRestartStatus(StackGresCluster cluster);

  protected abstract void initializeDbOpsStatus(StackGresDbOps securityUpgradeOp, List<Pod> pods);

  protected Pod getPrimaryInstance(List<Pod> pods) {
    return pods.stream()
        .filter(pod -> StackGresContext.PRIMARY_ROLE.equals(
            pod.getMetadata().getLabels().get(StackGresContext.ROLE_KEY)))
        .findFirst().orElseThrow(() -> new InvalidCluster("Cluster has no primary pod"));
  }

  @Test
  void givenAnUninitializedJobState_itShouldInitializeIt() {
    podTestUtil.preparePods(cluster, 0, 1, 2);

    var pods = podTestUtil.getClusterPods(cluster);

    final String dbOpsName = dbOps.getMetadata().getName();
    getRestartStateHandler()
        .restartCluster(dbOps)
        .await()
        .atMost(Duration.ofMillis(50));
    var storedDbOps = kubeDb.getDbOps(dbOpsName, namespace);

    List<String> expectedInitialInstances = pods.stream().map(Pod::getMetadata)
        .map(ObjectMeta::getName)
        .sorted(String::compareTo)
        .collect(Collectors.toUnmodifiableList());

    final DbOpsRestartStatus initializedSecurityUpgradeStatus = getRestartStatus(storedDbOps);

    Pod primaryPod = pods.stream()
        .filter(pod -> pod.getMetadata().getName().endsWith("-0"))
        .findAny().get();

    assertEquals(primaryPod.getMetadata().getName(), initializedSecurityUpgradeStatus
        .getPrimaryInstance());

    List<String> actualInitialInstances = initializedSecurityUpgradeStatus
        .getInitialInstances();

    assertEquals(expectedInitialInstances, actualInitialInstances);

    List<String> actualPendingRestartedInstances = initializedSecurityUpgradeStatus
        .getPendingToRestartInstances();

    assertEquals(expectedInitialInstances, actualPendingRestartedInstances);

    assertTrue(() -> initializedSecurityUpgradeStatus.getRestartedInstances() == null
        || initializedSecurityUpgradeStatus.getRestartedInstances().isEmpty());

    assertNull(initializedSecurityUpgradeStatus.getFailure());

    assertFalse(Boolean.parseBoolean(initializedSecurityUpgradeStatus.getSwitchoverInitiated()));

    assertEquals(dbOps, storedDbOps, "It should store the DBOps status changes");
  }

  @Test
  void givenAnUninitializedClusterStatus_itShouldInitializeIt() {
    podTestUtil.preparePods(cluster, 0, 1, 2);

    var pods = podTestUtil.getClusterPods(cluster);

    List<StackGresCluster> storedCluster = Lists.newArrayList();
    kubeDb.watchCluster(clusterName, namespace, storedCluster::add);

    getRestartStateHandler()
        .restartCluster(dbOps)
        .await()
        .atMost(Duration.ofMillis(50));

    verifyClusterInitializedStatus(pods, Seq.seq(storedCluster).findFirst().get());
  }

  protected void verifyClusterInitializedStatus(List<Pod> pods, StackGresCluster cluster) {
    List<String> expectedInitialInstances = pods.stream().map(Pod::getMetadata)
        .map(ObjectMeta::getName)
        .sorted(String::compareTo)
        .collect(Collectors.toUnmodifiableList());

    final ClusterDbOpsRestartStatus initializedSecurityUpgradeStatus =
        getRestartStatus(cluster).orElseThrow();

    Pod primaryPod = pods.stream()
        .filter(pod -> pod.getMetadata().getName().endsWith("-0"))
        .findAny().get();

    assertEquals(primaryPod.getMetadata().getName(), initializedSecurityUpgradeStatus
        .getPrimaryInstance());

    List<String> actualInitialInstances = initializedSecurityUpgradeStatus
        .getInitialInstances();

    assertEquals(expectedInitialInstances, actualInitialInstances);
  }

  @Test
  void givenAnInitializedJobState_itShouldNotModifiedIt() {
    podTestUtil.preparePods(cluster, 0, 1, 2);

    var pods = podTestUtil.getClusterPods(cluster);

    initializeDbOpsStatus(dbOps, pods);

    getRestartStateHandler()
        .restartCluster(dbOps)
        .await()
        .atMost(Duration.ofMillis(50));

    var storedDbOps = kubeDb.getDbOps(dbOpsName,
        namespace);

    assertEquals(dbOps, storedDbOps);
  }

  @Test
  void givenAnInitializedClusterStatus_itShouldNotModifiedIt() {
    podTestUtil.preparePods(cluster, 0, 1, 2);

    var pods = podTestUtil.getClusterPods(cluster);

    initializeClusterStatus(cluster, pods);

    List<StackGresCluster> storedCluster = Lists.newArrayList();
    kubeDb.watchCluster(clusterName, namespace, storedCluster::add);

    getRestartStateHandler()
        .restartCluster(dbOps)
        .await()
        .atMost(Duration.ofMillis(50));

    assertEquals(1, storedCluster.size());
  }

  protected abstract void initializeClusterStatus(StackGresCluster cluster,
      List<Pod> pods);

  @Test
  void buildRestartState_shouldNotFail() {
    podTestUtil.preparePods(cluster, 0, 1, 2);

    var pods = podTestUtil.getClusterPods(cluster);

    final Pod primaryPod = pods.stream()
        .filter(pod -> pod.getMetadata().getName().endsWith("-0"))
        .findAny().get();

    final Pod replica1Pod = pods.stream()
        .filter(pod -> pod.getMetadata().getName().endsWith("-1"))
        .findAny().get();

    initializeDbOpsStatus(dbOps, pods);

    dbOps = kubeDb.addOrReplaceDbOps(dbOps);

    var expectedClusterState = ImmutableClusterRestartState.builder()
        .namespace(dbOps.getMetadata().getNamespace())
        .dbOpsName(dbOps.getMetadata().getName())
        .dbOpsOperation(dbOps.getSpec().getOp())
        .clusterName(dbOps.getSpec().getSgCluster())
        .isOnlyPendingRestart(false)
        .restartMethod(dbOps.getSpec().getSecurityUpgrade().getMethod())
        .isSwitchoverInitiated(Boolean.FALSE)
        .primaryInstance(primaryPod)
        .addInitialInstances(primaryPod, replica1Pod)
        .addRestartedInstances(replica1Pod)
        .addAllTotalInstances(pods)
        .putAllPodRestartReasonsMap(pods.stream()
            .collect(ImmutableMap.toImmutableMap(
                Function.identity(), pod -> RestartReasons.of())))
        .build();

    var clusterRestartState = getRestartStateHandler().restartCluster(dbOps)
        .await()
        .atMost(Duration.ofMillis(50));

    assertEqualsRestartState(expectedClusterState, clusterRestartState);
  }

  @Test
  void givenACleanCluster_shouldUpdateTheOpStatus() {
    podTestUtil.preparePods(cluster, 0, 1, 2);

    var pods = podTestUtil.getClusterPods(cluster)
        .stream().sorted(Comparator.comparing(p -> p.getMetadata().getName()))
        .collect(Collectors.toUnmodifiableList());

    when(clusterRestart.restartCluster(any()))
        .thenReturn(Multi.createFrom()
            .items(
                ImmutableRestartEvent.builder()
                    .eventType(RestartEventType.POSTGRES_RESTART)
                    .pod(pods.get(0))
                    .build(),
                ImmutableRestartEvent.builder()
                    .eventType(RestartEventType.POD_CREATED)
                    .pod(podTestUtil.buildReplicaPod(cluster, 3))
                    .build(),
                ImmutableRestartEvent.builder()
                    .eventType(RestartEventType.POD_RESTART)
                    .pod(pods.get(1))
                    .build(),
                ImmutableRestartEvent.builder()
                    .eventType(RestartEventType.POD_RESTART)
                    .pod(pods.get(2))
                    .build(),
                ImmutableRestartEvent.builder()
                    .eventType(RestartEventType.SWITCHOVER)
                    .pod(pods.get(0))
                    .build(),
                ImmutableRestartEvent.builder()
                    .eventType(RestartEventType.POD_RESTART)
                    .pod(pods.get(0))
                    .build()));

    List<StackGresDbOps> storedDbOps = Lists.newArrayList();
    kubeDb.watchDbOps(dbOpsName, namespace, storedDbOps::add);

    getRestartStateHandler().restartCluster(dbOps)
        .await().atMost(Duration.ofMillis(50));

    verifyDbOpsRestartStatus(pods, Seq.seq(storedDbOps).findLast().get());

    var lastClusterStatus = kubeDb.getCluster(clusterName, namespace);
    assertTrue(getRestartStatus(lastClusterStatus).isEmpty(),
        "It should erase the dbOps status after job is complete");
  }

  protected void verifyDbOpsRestartStatus(List<Pod> pods, StackGresDbOps dbOps) {
    final var restartStatus = getRestartStatus(dbOps);

    assertTrue(restartStatus.getPendingToRestartInstances().isEmpty());
    assertEquals(Boolean.TRUE.toString(), restartStatus.getSwitchoverInitiated());
    assertEquals(pods.size() + 1, restartStatus.getRestartedInstances().size());
    assertEquals(pods.size(), restartStatus.getInitialInstances().size());
    assertTrue(() -> restartStatus.getFailure() == null
        || restartStatus.getFailure().isEmpty());
  }

}
