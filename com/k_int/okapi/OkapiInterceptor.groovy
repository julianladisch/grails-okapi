package com.k_int.okapi;

import javax.servlet.http.HttpServletRequest

/**
 *
 * @See: https://objectcomputing.com/resources/publications/sett/september-2015-grails-3-interceptors
 */
public class OkapiInterceptor {

  // Inject the okapi tenant admin service
  OkapiTenantAdminService okapiTenantAdminService

  int order = HIGHEST_PRECEDENCE + 100

  public OkapiInterceptor() {

    // Match all EXCEPT the tenant controller
    matchAll()
      .excludes(controller:'tenant')
  }

  boolean before() {
    // See if this request has an X-OKAPI-TENANT header
    // If so, see if we have a hibernateDatastore for that tenant yet

    // HttpServletRequest httpServletRequest = getRequest()
    String tenantId = request.getHeader(OkapiHeaders.TENANT)?.toLowerCase()?.trim()
    if ( tenantId ) {
      log.debug("OkapiInterceptor::performSchemaCheck(${tenantId})");
      okapiTenantAdminService.performSchemaCheck(tenantId);
    }
 
    true
  }

}
