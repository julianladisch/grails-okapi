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
