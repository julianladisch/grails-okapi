package com.k_int.okapi

import grails.artefact.Artefact
import grails.converters.*
import grails.rest.*

@Artefact('Controller')
class OkapiController {

  static responseFormats = ['json', 'xml']
  def hibernateDatastore
  def dataSource
  def tenantAdminService


  def index() { 
  }

  // GET And DELETE verbs with header X-Okapi-Tenant indicate activation of this module for a given tenant.
  def tenant() {
    String tenant_id = request.getHeader(OkapiHeaders.TENANT)?.toLowerCase()

    log.info("OkapiController::tenant ${request.method} ${params} ${tenant_id}");
    def result = [:]
    if ( tenant_id && tenant_id.trim().length() > 0 ) {
      switch ( request.method ) {
        case 'GET':
        case 'POST':
          tenantAdminService.createTenant(tenant_id)
          break;
        case 'DELETE':
          try {
            log.debug("DELETE Tenant ${tenant_id}");
            // This is well risqe, but it actually suits our functional test framework ;)
            tenantAdminService.dropTenant(tenant_id)
          }
          catch ( Exception e ) {
            log.warn("There was an exception thrown in tenantAdminService.dropTenant. Not worrying unduly!");
          }
          break;
        default:
          log.warn("Unhandled verb ${request.method} for module /_/tenant endpoint");
          break;
      }
    }
    else {
      throw new RuntimeException("No X-Okapi-Tenant header");
    }

    render result as JSON
  }

}
