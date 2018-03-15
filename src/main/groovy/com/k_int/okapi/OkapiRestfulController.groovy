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
    def results = simpleLookupService.lookup(this.resource, params.term, Math.min(params.perPage ?: 100, 100), params.int("page"), params.list("filters"), params.list("match"))
    respond results
  }
}
