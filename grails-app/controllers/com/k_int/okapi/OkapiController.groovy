package com.k_int.okapi

import grails.converters.*
import grails.rest.*

class OkapiController {

  static responseFormats = ['json', 'xml']
  def okapiTenantAdminService


  def index() { 
  }

  // GET And DELETE verbs with header X-Okapi-Tenant indicate activation of this module for a given tenant.
  def tenant() {
    String tenant_id = request.getHeader(OkapiHeaders.TENANT)?.toLowerCase()

    log.info("OkapiController::tenant ${request.method} ${params} ${tenant_id}")
    def result = [:]
    if ( tenant_id && tenant_id.trim().length() > 0 ) {
      switch ( request.method ) {
        case 'GET':
        case 'POST':
          okapiTenantAdminService.createTenant(tenant_id)
          break
        case 'DELETE':
          try {
            log.debug("DELETE Tenant ${tenant_id}")
            // This is well risqe, but it actually suits our functional test framework ;)
            okapiTenantAdminService.dropTenant(tenant_id)
          }
          catch ( Exception e ) {
            log.warn("There was an exception thrown in okapiTenantAdminService.dropTenant. Not worrying unduly!")
          }
          break
        default:
          log.warn("Unhandled verb ${request.method} for module /_/tenant endpoint")
          break
      }
    }
    else {
      throw new RuntimeException("No X-Okapi-Tenant header")
    }

    render result as JSON
  }

}
