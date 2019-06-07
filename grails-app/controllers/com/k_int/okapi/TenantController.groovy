package com.k_int.okapi

import static org.apache.http.HttpStatus.*

import grails.converters.*
import grails.rest.*
import grails.web.Controller
import groovy.transform.CompileStatic

@Controller
@CompileStatic
class TenantController {
  static allowedMethods = [index:['POST', 'DELETE'], disable:['POST']]
  
  OkapiTenantAdminService okapiTenantAdminService
  
  private final String getTenantId() {
    final String tenant_id = request.getHeader(OkapiHeaders.TENANT)?.toLowerCase()?.trim()
    
    if ((tenant_id?.length() ?: 0) < 1) { 
      throw new RuntimeException("No ${OkapiHeaders.TENANT} header")
    }
    
    tenant_id
  }
    
  private sendResponse (int code = SC_OK, def data = null) {
    render ([status: code], (data ?: [:]) as JSON)
  }

  // POST And DELETE verbs for enable and purge respectively.
  def index() {
    final String tenant_id = getTenantId()

    log.info("TenantController::tenant ${request.method} ${params} ${tenant_id}")
    switch ( request.method ) {
      case 'POST':
        final def post_body = request.JSON
        log.info("Recveived data ${post_body}")
        okapiTenantAdminService.enableTenant(tenant_id, (post_body as Map))
        sendResponse(SC_CREATED)
        break
      case 'DELETE':
        try {
          log.debug("PURGE Tenant ${tenant_id}")
          // This is well risque, but it actually suits our functional test framework ;)
          okapiTenantAdminService.purgeTenant(tenant_id)
          sendResponse(SC_NO_CONTENT)
        }
        catch ( Exception e ) {
          log.warn("There was an exception thrown in okapiTenantAdminService.dropTenant. Not worrying unduly!")
        }
        break
      default:
        log.warn("Unhandled verb ${request.method} for module tenant api")
        break
    }
  }
  
  def disable() {
    final String tenant_id = getTenantId()
    log.debug("DISABLE Tenant ${tenant_id}")
    okapiTenantAdminService.disableTenant(tenant_id)
    sendResponse(SC_NO_CONTENT)
  }
}
