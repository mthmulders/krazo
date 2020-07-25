/*
 * Copyright (c) 2014-2015 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2018, 2019 Eclipse Krazo committers and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.eclipse.krazo.cdi;

import org.eclipse.krazo.KrazoConfig;
import org.eclipse.krazo.Properties;
import org.eclipse.krazo.event.ControllerRedirectEventImpl;
import org.eclipse.krazo.jaxrs.JaxRsContext;
import org.eclipse.krazo.util.CdiUtils;
import org.eclipse.krazo.util.PropertyUtils;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.PassivationCapable;
import javax.inject.Inject;
import javax.mvc.MvcContext;
import javax.mvc.event.AfterProcessViewEvent;
import javax.mvc.event.BeforeControllerEvent;
import javax.mvc.event.ControllerRedirectEvent;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.UriBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The ApplicationScoped redirect scope manager.
 *
 * @author Manfred Riem (manfred.riem at oracle.com)
 * @author Santiago Pericas-Geertsen
 */
@ApplicationScoped
@SuppressWarnings("unchecked")
public class RedirectScopeManager {

    public static final String DEFAULT_ATTR_NAME = "org.eclipse.krazo.redirect.ScopeId";
    public static final String DEFAULT_COOKIE_NAME = "org.eclipse.krazo.redirect.Cookie";
    private static final String INSTANCE = "Instance-";
    private static final String CREATIONAL = "Creational-";

    /**
     * Stores the HTTP servlet request we are working for.
     */
    @Inject
    private HttpServletRequest request;

    /**
     * Stores the HTTP servlet response we work for.
     */
    @Inject
    @JaxRsContext
    private HttpServletResponse response;

    /**
     * Application's configuration.
     */
    @Inject
    @JaxRsContext
    private Configuration config;

    /**
     * Stores the MVC context.
     */
    @Inject
    private MvcContext mvc;

    @Inject
    private KrazoConfig krazoConfig;

    /**
     * The name of Request Attribute/Parameter to use when redirecting
     */
    private String scopeAttrName;

    /**
     * The name of Cookie to use when redirecting
     */
    private String cookieName;

    /**
     * Check that {@literal @}Context injection worked correctly
     */
    @PostConstruct
    public void init() {
        if (config == null || response == null || krazoConfig == null) {
            throw new IllegalStateException("It looks like @Context injection doesn't work for CDI beans. Please " +
                "make sure you are using a recent version of Jersey.");
        }

        scopeAttrName = krazoConfig.getRedirectScopeAttributeName();
        cookieName = krazoConfig.getRedirectScopeCookieName();
    }

    /**
     * Destroy the instance.
     *
     * @param contextual the contextual.
     */
    public void destroy(Contextual contextual) {
        String scopeId = (String) request.getAttribute(this.scopeAttrName);
        if (null != scopeId) {
            HttpSession session = request.getSession();
            if (contextual instanceof PassivationCapable == false) {
                throw new RuntimeException("Unexpected type for contextual");
            }
            PassivationCapable pc = (PassivationCapable) contextual;
            final String sessionKey = this.scopeAttrName + "-" + scopeId;
            Map<String, Object> scopeMap = (Map<String, Object>) session.getAttribute(sessionKey);
            if (null != scopeMap) {
                Object instance = scopeMap.get(INSTANCE + pc.getId());
                CreationalContext<?> creational = (CreationalContext<?>) scopeMap.get(CREATIONAL + pc.getId());
                if (null != instance && null != creational) {
                    contextual.destroy(instance, creational);
                    creational.release();
                }
            }
        }
    }

    /**
     * Get the instance.
     *
     * @param <T> the type.
     * @param contextual the contextual.
     * @return the instance, or null.
     */
    public <T> T get(Contextual<T> contextual) {
        T result = null;

        String scopeId = (String) request.getAttribute(this.scopeAttrName);
        if (null != scopeId) {
            HttpSession session = request.getSession();
            if (contextual instanceof PassivationCapable == false) {
                throw new RuntimeException("Unexpected type for contextual");
            }
            PassivationCapable pc = (PassivationCapable) contextual;
            final String sessionKey = this.scopeAttrName + "-" + scopeId;
            Map<String, Object> scopeMap = (Map<String, Object>) session.getAttribute(sessionKey);
            if (null != scopeMap) {
                result = (T) scopeMap.get(INSTANCE + pc.getId());
            } else {
                request.setAttribute(this.scopeAttrName, null);       // old cookie, force new scope generation
            }
        }

        return result;
    }

    /**
     * Get the instance (create it if it does not exist).
     *
     * @param <T> the type.
     * @param contextual the contextual.
     * @param creational the creational.
     * @return the instance.
     */
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creational) {
        T result = get(contextual);

        if (result == null) {
            String scopeId = (String) request.getAttribute(this.scopeAttrName);
            if (null == scopeId) {
                scopeId = generateScopeId();
            }
            HttpSession session = request.getSession();
            result = contextual.create(creational);
            if (contextual instanceof PassivationCapable == false) {
                throw new RuntimeException("Unexpected type for contextual");
            }
            PassivationCapable pc = (PassivationCapable) contextual;
            final String sessionKey = this.scopeAttrName + "-" + scopeId;
            Map<String, Object> scopeMap = (Map<String, Object>) session.getAttribute(sessionKey);
            if (null != scopeMap) {
                session.setAttribute(sessionKey, scopeMap);
                scopeMap.put(INSTANCE + pc.getId(), result);
                scopeMap.put(CREATIONAL + pc.getId(), creational);
            }
        }

        return result;
    }

    /**
     * Update scopeId request attribute based on either cookie or URL query param
     * information received in the request.
     *
     * @param event the event.
     */
    public void beforeProcessControllerEvent(@Observes BeforeControllerEvent event) {
        if (usingCookies()) {
            final Cookie[] cookies = request.getCookies();
            if (null != cookies) {
                for (Cookie cookie : cookies) {
                    if (cookie.getName().equals(this.cookieName)) {
                        request.setAttribute(this.scopeAttrName, cookie.getValue());
                        return;     // we're done
                    }
                }
            }
        } else {
            final String scopeId = event.getUriInfo().getQueryParameters().getFirst(this.scopeAttrName);
            if (scopeId != null) {
                request.setAttribute(this.scopeAttrName, scopeId);
            }
        }
    }

    /**
     * Perform the work we need to do at AfterProcessViewEvent time.
     *
     * @param event the event.
     */
    public void afterProcessViewEvent(@Observes AfterProcessViewEvent event) {
        if (request.getAttribute(this.scopeAttrName) != null) {
            String scopeId = (String) request.getAttribute(this.scopeAttrName);
            HttpSession session = request.getSession();
            final String sessionKey = this.scopeAttrName + "-" + scopeId;
            Map<String, Object> scopeMap = (Map<String, Object>) session.getAttribute(sessionKey);
            if (null != scopeMap) {
                scopeMap.entrySet().stream().forEach((entrySet) -> {
                    String key = entrySet.getKey();
                    Object value = entrySet.getValue();
                    if (key.startsWith(INSTANCE)) {
                        BeanManager beanManager = CdiUtils.getApplicationBeanManager();
                        Bean<?> bean = beanManager.resolve(beanManager.getBeans(value.getClass()));
                        destroy(bean);
                    }
                });
                scopeMap.clear();
                session.removeAttribute(sessionKey);
            }
        }
    }

    /**
     * Upon detecting a redirect, either add cookie to response or re-write URL of new
     * location to co-relate next request.
     *
     * @param event the event.
     */
    public void controllerRedirectEvent(@Observes ControllerRedirectEvent event) {
        if (request.getAttribute(this.scopeAttrName) != null) {
            if (usingCookies()) {
                Cookie cookie = new Cookie(this.cookieName, request.getAttribute(this.scopeAttrName).toString());
                cookie.setPath(request.getContextPath());
                cookie.setMaxAge(600);
                cookie.setHttpOnly(true);
                response.addCookie(cookie);
            } else {
                final ContainerResponseContext crc = ((ControllerRedirectEventImpl) event).getContainerResponseContext();
                final UriBuilder builder = UriBuilder.fromUri(crc.getStringHeaders().getFirst(HttpHeaders.LOCATION));
                builder.queryParam(this.scopeAttrName, request.getAttribute(this.scopeAttrName).toString());
                crc.getHeaders().putSingle(HttpHeaders.LOCATION, builder.build());
            }
        }
    }

    /**
     * Generate the scope id.
     *
     * @return the scope id.
     */
    private String generateScopeId() {
        HttpSession session = request.getSession();
        String scopeId = UUID.randomUUID().toString();
        String sessionKey = this.scopeAttrName + "-" + scopeId;
        synchronized (this) {
            while (session.getAttribute(sessionKey) != null) {
                scopeId = UUID.randomUUID().toString();
                sessionKey = this.scopeAttrName + "-" + scopeId;
            }
            session.setAttribute(sessionKey, new HashMap<>());
            request.setAttribute(this.scopeAttrName, scopeId);
        }
        return scopeId;
    }

    /**
     * Checks application configuration to see if cookies should be used.
     *
     * @return value of property.
     */
    private boolean usingCookies() {
        return PropertyUtils.getProperty(config, Properties.REDIRECT_SCOPE_COOKIES, false);
    }
}
