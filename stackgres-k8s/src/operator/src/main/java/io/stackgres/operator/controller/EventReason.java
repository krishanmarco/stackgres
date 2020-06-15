/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.controller;

import static io.stackgres.operator.controller.EventReason.Type.NORMAL;
import static io.stackgres.operator.controller.EventReason.Type.WARNING;

public enum EventReason {

  CLUSTER_CREATED(NORMAL, "ClusterCreated"),
  CLUSTER_UPDATED(NORMAL, "ClusterUpdated"),
  CLUSTER_DELETED(NORMAL, "ClusterDeleted"),
  CLUSTER_CONFIG_ERROR(WARNING, "ClusterConfigFailed"),
  BACKUP_CREATED(NORMAL, "BackupCreated"),
  BACKUP_UPDATED(NORMAL, "BackupUpdated"),
  BACKUP_DELETED(NORMAL, "BackupDeleted"),
  BACKUP_CONFIG_ERROR(WARNING, "BackupConfigFailed"),
  DISTRIBUTED_LOGS_CREATED(NORMAL, "DistributedLogsCreated"),
  DISTRIBUTED_LOGS_UPDATED(NORMAL, "DistributedLogsUpdated"),
  DISTRIBUTED_LOGS_DELETED(NORMAL, "DistributedLogsDeleted"),
  DISTRIBUTED_LOGS_CONFIG_ERROR(WARNING, "DistributedLogsConfigFailed"),
  OPERATOR_ERROR(WARNING, "OperatorError");

  private final String type;
  private final String reason;

  EventReason(Type type, String reason) {
    this.type = type.type();
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }

  public String type() {
    return type;
  }

  public enum Type {
    NORMAL("Normal"),
    WARNING("Warning");

    private final String type;

    Type(String type) {
      this.type = type;
    }

    public String type() {
      return type;
    }
  }
}
