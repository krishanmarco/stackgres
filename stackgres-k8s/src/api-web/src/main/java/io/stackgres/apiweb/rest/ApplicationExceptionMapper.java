/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.rest;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import com.google.common.base.Throwables;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.jboss.resteasy.spi.ApplicationException;

@Provider
public class ApplicationExceptionMapper extends AbstractGenericExceptionMapper<ApplicationException>
    implements ExceptionMapper<ApplicationException> {
  private StatusParserProvider statusParserProvider;

  @Override
  public Response toResponse(ApplicationException e) {

    Throwable cause = Throwables.getRootCause(e);

    if (cause instanceof KubernetesClientException) {
      KubernetesClientException kce = (KubernetesClientException) cause;
      final StatusParser statusParser = statusParserProvider.getStatusParser();
      KubernetesExceptionMapper mapper = new KubernetesExceptionMapper(statusParser);
      return mapper.toResponse(kce);
    }

    return super.toResponse(e);
  }

  @Inject
  public void setStatusParserProvider(StatusParserProvider statusParserProvider) {
    this.statusParserProvider = statusParserProvider;
  }
}
