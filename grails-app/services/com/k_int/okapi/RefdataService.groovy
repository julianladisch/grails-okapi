package com.k_int.okapi;

import grails.events.Event
import grails.events.annotation.Subscriber
import groovy.util.logging.Slf4j
import com.k_int.web.toolkit.refdata.GrailsDomainRefdataHelpers

@Slf4j
public class RefdataService {
    
  @Subscriber('okapi:schema_update')
  public void onTenantSchemaCreated(String new_schema_name) {
    
    log.debug("RefdataService::onTenantSchemaCreated(${new_schema_name})")
    // Skip this until we can work out whats going wrong...
    GrailsDomainRefdataHelpers.setDefaultsForTenant(new_schema_name)
  }
}
