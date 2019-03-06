package com.k_int.okapi;

import grails.events.Event
import grails.events.annotation.Subscriber
import groovy.util.logging.Slf4j
import com.k_int.web.toolkit.refdata.GrailsDomainRefdataHelpers

@Slf4j
public class RefdataService {
    
  @Subscriber('okapi:schema_update')
  public void onTenantSchemaCreated(String tenantId, schemaName) {
    
    log.debug("RefdataService::onTenantSchemaCreated(${schemaName})")
    // Skip this until we can work out whats going wrong...
    GrailsDomainRefdataHelpers.setDefaultsForTenant(schemaName)
  }
}
