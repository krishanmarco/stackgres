/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.comparator;

import java.util.regex.Pattern;

public abstract class EndpointsComparator extends AbstractComparator {

  private final IgnorePatch[] ignorePatchPatterns = {
      new SimpleIgnorePatch("/subsets",
          "add"),
      new PatchPattern(Pattern.compile("^/subsets/.*$"),
          "replace"),
  };

  @Override
  protected IgnorePatch[] getPatchPattersToIgnore() {
    return ignorePatchPatterns;
  }

}
