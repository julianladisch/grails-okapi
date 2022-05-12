package com.k_int.okapi

import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.web.access.AccessDeniedHandlerImpl

import com.k_int.web.toolkit.rest.TenantAwareRestfulController

import grails.artefact.Artefact
import grails.gorm.multitenancy.CurrentTenant
import grails.plugin.springsecurity.SpringSecurityService

@CurrentTenant
@Artefact('Controller')
class OkapiTenantAwareController<T> extends TenantAwareRestfulController<T> {

  static responseFormats = ['json', 'xml']
  
  SpringSecurityService springSecurityService

  OkapiTenantAwareController(Class<T> resource) {
    super(resource)
  }
  
  OkapiTenantAwareController(Class<T> resource, boolean readOnly) {
    super(resource, readOnly)
  }

  def getObjectToBind() {
    request.JSON
  }
  
  protected UserDetails getPatron() {
    springSecurityService.principal
  }
  
  protected boolean hasAnyAuthority(Set auths) {
    AccessDeniedHandlerImpl f
    def pAuths = patron?.authorities?.collect { it.authority }
    def intersect = pAuths?.intersect(auths)
    intersect
  }
  
  protected boolean hasAuthority(String auth) {
    def intersect = hasAnyAuthority((auth ? [auth] : []) as Set)
    intersect
  }
}
