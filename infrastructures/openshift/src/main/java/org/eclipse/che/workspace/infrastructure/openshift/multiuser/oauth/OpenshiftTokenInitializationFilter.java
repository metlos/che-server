/*
 * Copyright (c) 2012-2021 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.workspace.infrastructure.openshift.multiuser.oauth;

import static com.google.common.base.MoreObjects.firstNonNull;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.client.OpenShiftClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.model.user.User;
import org.eclipse.che.api.user.server.UserManager;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.commons.subject.Subject;
import org.eclipse.che.commons.subject.SubjectImpl;
import org.eclipse.che.multiuser.api.authentication.commons.SessionStore;
import org.eclipse.che.multiuser.api.authentication.commons.filter.MultiUserEnvironmentInitializationFilter;
import org.eclipse.che.multiuser.api.authentication.commons.token.RequestTokenExtractor;
import org.eclipse.che.multiuser.api.permission.server.AuthorizedSubject;
import org.eclipse.che.multiuser.api.permission.server.PermissionChecker;
import org.eclipse.che.workspace.infrastructure.openshift.OpenShiftClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This filter uses given token directly. It's used for native OpenShift user authentication.
 * Requests without token or with invalid token are rejected.
 *
 * <p>{@link OpenshiftTokenInitializationFilter#UNAUTHORIZED_ENDPOINT_PATHS} is list of
 * unauthenticated paths, that are allowed without token.
 */
@Singleton
public class OpenshiftTokenInitializationFilter extends MultiUserEnvironmentInitializationFilter {

  private static final Logger LOG =
      LoggerFactory.getLogger(OpenshiftTokenInitializationFilter.class);

  private static final List<String> UNAUTHORIZED_ENDPOINT_PATHS =
      Collections.singletonList("/system/state");

  private final PermissionChecker permissionChecker;
  private final OpenShiftClientFactory clientFactory;

  private final UserManager userManager;

  @Inject
  public OpenshiftTokenInitializationFilter(
      SessionStore sessionStore,
      RequestTokenExtractor tokenExtractor,
      OpenShiftClientFactory clientFactory,
      UserManager userManager,
      PermissionChecker permissionChecker) {
    super(sessionStore, tokenExtractor);
    this.clientFactory = clientFactory;
    this.userManager = userManager;
    this.permissionChecker = permissionChecker;
  }

  @Override
  protected String getUserId(String token) {
    try {
      io.fabric8.openshift.api.model.User user = getCurrentUser(token);
      return firstNonNull(user.getMetadata().getUid(), user.getMetadata().getName());
    } catch (KubernetesClientException e) {
      if (e.getCode() == 401) {
        LOG.error(
            "Unauthorized when getting current user. Invalid OpenShift token, probably expired. Re-login? Re-request the token?");
      }
      throw new RuntimeException(e);
    } catch (InfrastructureException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected Subject extractSubject(String token) {
    try {
      ObjectMeta userMeta = getCurrentUser(token).getMetadata();
      User user =
          userManager.getOrCreateUser(
              firstNonNull(userMeta.getUid(), userMeta.getName()),
              openshiftUserEmail(userMeta),
              userMeta.getName());
      return new AuthorizedSubject(
          new SubjectImpl(user.getName(), user.getId(), token, false), permissionChecker);
    } catch (InfrastructureException | ServerException | ConflictException e) {
      throw new RuntimeException(e);
    }
  }

  private io.fabric8.openshift.api.model.User getCurrentUser(String token)
      throws InfrastructureException {
    // We're effectively creating new client for each request. It might be a good idea to somehow
    // cache the client or user object. However, it may require non-trivial refactoring which may
    // be unnecessary. Keeping it as is for now to avoid premature optimization.
    OpenShiftClient client = clientFactory.createAuthenticatedClient(token);
    return client.currentUser();
  }

  protected String openshiftUserEmail(ObjectMeta userMeta) {
    // OpenShift User does not have data about user's email. However, we need some email. For now,
    // we can use fake email, but probably we will need to find better solution.
    return userMeta.getName() + "@che";
  }

  /**
   * If request path is in {@link OpenshiftTokenInitializationFilter#UNAUTHORIZED_ENDPOINT_PATHS},
   * the request is allowed. All other requests are rejected with error code 401.
   */
  @Override
  protected void handleMissingToken(
      ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    // if request path is in unauthorized endpoints, continue
    if (request instanceof HttpServletRequest) {
      HttpServletRequest httpRequest = (HttpServletRequest) request;
      String path = httpRequest.getServletPath();
      if (UNAUTHORIZED_ENDPOINT_PATHS.contains(path)) {
        LOG.debug("Allowing request to '{}' without authorization header.", path);
        chain.doFilter(request, response);
        return;
      }
    }

    LOG.error("Rejecting the request due to missing token in Authorization header.");
    sendError(response, 401, "Authorization token is missing");
  }

  @Override
  public void init(FilterConfig filterConfig) {
    LOG.trace("OpenshiftTokenInitializationFilter#init({})", filterConfig);
  }

  @Override
  public void destroy() {
    LOG.trace("OpenshiftTokenInitializationFilter#destroy()");
  }
}
