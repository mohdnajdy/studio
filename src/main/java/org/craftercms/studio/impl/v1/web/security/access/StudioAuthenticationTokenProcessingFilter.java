/*
 * Copyright (C) 2007-2019 Crafter Software Corporation. All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.craftercms.studio.impl.v1.web.security.access;

import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.craftercms.commons.http.HttpUtils;
import org.craftercms.studio.api.v1.exception.ServiceLayerException;
import org.craftercms.studio.api.v1.log.Logger;
import org.craftercms.studio.api.v1.log.LoggerFactory;
import org.craftercms.studio.api.v1.service.security.SecurityService;
import org.craftercms.studio.api.v1.service.security.UserDetailsManager;
import org.craftercms.studio.api.v1.util.StudioConfiguration;
import org.craftercms.studio.impl.v1.util.SessionTokenUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.GenericFilterBean;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.craftercms.studio.api.v1.service.security.SecurityService.STUDIO_SESSION_TOKEN_ATRIBUTE;
import static org.craftercms.studio.api.v1.util.StudioConfiguration.AUTHENTICATION_CHAIN_PROVIDER_ENABLED;
import static org.craftercms.studio.api.v1.util.StudioConfiguration.AUTHENTICATION_CHAIN_PROVIDER_TYPE;
import static org.craftercms.studio.api.v1.util.StudioConfiguration.AUTHENTICATION_CHAIN_PROVIDER_TYPE_HEADERS;
import static org.craftercms.studio.api.v1.util.StudioConfiguration.AUTHENTICATION_CHAIN_PROVIDER_USERNAME_HEADER;
import static org.craftercms.studio.api.v1.util.StudioConfiguration.CONFIGURATION_AUTHENTICATION_CHAIN_CONFIG;
import static org.craftercms.studio.api.v1.util.StudioConfiguration.SECURITY_IGNORE_RENEW_TOKEN_URLS;
import static org.craftercms.studio.api.v1.util.StudioConfiguration.SECURITY_SESSION_TIMEOUT;

public class StudioAuthenticationTokenProcessingFilter extends GenericFilterBean {

    private final static Logger crafterLogger = LoggerFactory.getLogger(StudioAuthenticationTokenProcessingFilter.class);

    private static ReentrantLock semaphore = new ReentrantLock();

    private UserDetailsManager userDetailsManager;
    private SecurityService securityService;
    private StudioConfiguration studioConfiguration;

    private boolean authenticationHeadersEnabled = false;
    private List<String> usernameHeaders = null;

    public void init() {
        List<HierarchicalConfiguration<ImmutableNode>> chainConfig =
            studioConfiguration.getSubConfigs(CONFIGURATION_AUTHENTICATION_CHAIN_CONFIG);
        if (chainConfig != null) {
            authenticationHeadersEnabled = chainConfig.stream().anyMatch(providerConfig ->
                    providerConfig.getString(AUTHENTICATION_CHAIN_PROVIDER_TYPE).toUpperCase()
                            .equals(AUTHENTICATION_CHAIN_PROVIDER_TYPE_HEADERS) &&
                            providerConfig.getBoolean(AUTHENTICATION_CHAIN_PROVIDER_ENABLED));
            usernameHeaders = chainConfig.stream().filter(providerConfig ->
                    providerConfig.getString(AUTHENTICATION_CHAIN_PROVIDER_TYPE).toUpperCase()
                            .equals(AUTHENTICATION_CHAIN_PROVIDER_TYPE_HEADERS) &&
                            providerConfig.getBoolean(AUTHENTICATION_CHAIN_PROVIDER_ENABLED))
                    .map(providerConfig -> {
                        return providerConfig.getString(AUTHENTICATION_CHAIN_PROVIDER_USERNAME_HEADER);
                    }).collect(Collectors.toList());
        }
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = this.getAsHttpRequest(servletRequest);
        HttpSession httpSession = httpRequest.getSession();
        semaphore.lock();
        try {
            String userName = securityService.getCurrentUser();
            String authToken = securityService.getCurrentToken();


            if (userName != null) {
                UserDetails userDetails = this.userDetailsManager.loadUserByUsername(userName);

                if (SessionTokenUtils.validateToken(authToken, userDetails.getUsername())) {

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userDetails, null,
                                    userDetails.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    if (httpRequest.getRequestURI().startsWith(httpRequest.getContextPath() + "/api/1") &&
                            !getIgnoreRenewTokenUrls()
                                    .contains(HttpUtils.getRequestUriWithoutContextPath(httpRequest))) {
                        int timeout = Integer.parseInt(studioConfiguration.getProperty(SECURITY_SESSION_TIMEOUT));
                        String newToken = SessionTokenUtils.createToken(userDetails.getUsername(), timeout);
                        httpSession.setAttribute(STUDIO_SESSION_TOKEN_ATRIBUTE, newToken);
                    }
                } else {
                    crafterLogger.debug("Session is not valid. Clearing HttpSession");
                    httpSession.removeAttribute(STUDIO_SESSION_TOKEN_ATRIBUTE);
                    httpSession.invalidate();
                }
            } else { if (isAuthenticationHeadersEnabled()) {
                    // If user not authenticated check for authentication headers
                    String usernameHeader = null;
                    for (String header : usernameHeaders) {
                        usernameHeader = httpRequest.getHeader(header);
                        if (StringUtils.isNotEmpty(usernameHeader)) {
                            try {
                                securityService.authenticate(usernameHeader, RandomStringUtils.randomAlphanumeric(16));
                                UserDetails userDetails = this.userDetailsManager.loadUserByUsername(usernameHeader);
                                UsernamePasswordAuthenticationToken authentication =
                                        new UsernamePasswordAuthenticationToken(userDetails, null,
                                                userDetails.getAuthorities());
                                SecurityContextHolder.getContext().setAuthentication(authentication);
                                break;
                            } catch (Exception e) {
                                crafterLogger.error("Unable to authenticate user using authentication headers.", e);
                            }
                        }
                    }
                }
            }
        } catch (ServiceLayerException e) {
            logger.error("Unknown service error trying to authenticate user", e);
        } finally {
            semaphore.unlock();
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }

    private HttpServletRequest getAsHttpRequest(ServletRequest request)
    {
        if (!(request instanceof HttpServletRequest)) {
            throw new RuntimeException("Expecting an HTTP request");
        }

        return (HttpServletRequest) request;
    }

    public List<String> getIgnoreRenewTokenUrls() {
        return Arrays.asList(studioConfiguration.getProperty(SECURITY_IGNORE_RENEW_TOKEN_URLS).split(","));
    }

    public boolean isAuthenticationHeadersEnabled() {
        return authenticationHeadersEnabled;
    }

    public UserDetailsManager getUserDetailsManager() {
        return userDetailsManager;
    }

    public void setUserDetailsManager(UserDetailsManager userDetailsManager) {
        this.userDetailsManager = userDetailsManager;
    }

    public SecurityService getSecurityService() {
        return securityService;
    }

    public void setSecurityService(SecurityService securityService) {
        this.securityService = securityService;
    }

    public StudioConfiguration getStudioConfiguration() {
        return studioConfiguration;
    }

    public void setStudioConfiguration(StudioConfiguration studioConfiguration) {
        this.studioConfiguration = studioConfiguration;
    }
}
