package com.k_int.okapi

import com.k_int.web.toolkit.rest.RestfulController
import grails.artefact.Artefact

@Artefact('Controller')
class OkapiRestfulController<T> extends RestfulController<T> {

  static responseFormats = ['json', 'xml']
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
    respond doTheLookup()
  }
}
