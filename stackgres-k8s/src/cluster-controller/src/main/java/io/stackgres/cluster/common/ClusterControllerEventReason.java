/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.cluster.common;

import static io.stackgres.operatorframework.resource.EventReason.Type.WARNING;

import io.stackgres.common.StackGresContainer;
import io.stackgres.operatorframework.resource.EventReason;

public enum ClusterControllerEventReason implements EventReason {

  CLUSTER_CONTROLLER_ERROR(WARNING, "ClusterControllerFailed");

  private final Type type;
  private final String reason;

  ClusterControllerEventReason(Type type, String reason) {
    this.type = type;
    this.reason = reason;
  }

  @Override
  public String component() {
    return StackGresContainer.CLUSTER_CONTROLLER.getName();
  }

  @Override
  public String reason() {
    return reason;
  }

  @Override
  public Type type() {
    return type;
  }

}
