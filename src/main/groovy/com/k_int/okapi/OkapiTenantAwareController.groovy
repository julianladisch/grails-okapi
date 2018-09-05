package com.k_int.okapi

import java.util.List

import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.web.access.AccessDeniedHandlerImpl

import com.k_int.web.toolkit.rest.TenantAwareRestfulController
import com.k_int.web.tools.SimpleLookupService

import grails.artefact.Artefact
import grails.gorm.multitenancy.CurrentTenant
import grails.plugin.springsecurity.SpringSecurityService

@CurrentTenant
@Artefact('Controller')
class OkapiTenantAwareController<T> extends TenantAwareRestfulController<T> {

  static responseFormats = ['json', 'xml']
  
  SimpleLookupService simpleLookupService
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
  
  def index() {
    
    final int offset = params.int("offset") ?: 0
    final int perPage = Math.min(params.int('perPage') ?: params.int('max') ?: 100, 100)
    final int page = params.int("page") ?: (offset ? (offset / perPage) + 1 : 1)
    final List<String> filters = params.list("filters[]") ?: params.list("filters")
    final List<String> match_in = params.list("match[]") ?: params.list("match")
    final List<String> sorts = params.list("sort[]") ?: params.list("sort")
    
    if (params.boolean('stats')) {
      respond simpleLookupService.lookupWithStats(this.resource, params.term, perPage, page , filters, match_in, sorts)
    } else {
      respond simpleLookupService.lookup(this.resource, params.term, perPage, page , filters, match_in, sorts)
    }
  }
}
