package com.k_int.okapi

import com.k_int.web.tools.SimpleLookupService

import grails.artefact.Artefact
import grails.rest.RestfulController

@Artefact('Controller')
class OkapiRestfulController<T> extends RestfulController<T> {

  static responseFormats = ['json', 'xml']
  SimpleLookupService simpleLookupService

  OkapiRestfulController(Class<T> resource) {
    super(resource)
  }
  
  OkapiRestfulController(Class<T> resource, boolean readOnly) {
    super(resource, readOnly)
  }

  def getObjectToBind() {
    return request.JSON
  }
  
  def index() {
    
    def p = params
    int perPage = Math.min(params.int('perPage') ?: 100, 100)
    int page = params.int("page") ?: 1
    List<String> filters = params.list("filters") ?: params.list("filters[]")
    List<String> match_in = params.list("match") ?: params.list("match[]")
    
    def results = simpleLookupService.lookup(this.resource, params.term, perPage, page, filters, match_in)
    respond results
  }
}
