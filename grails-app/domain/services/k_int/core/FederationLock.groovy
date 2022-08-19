package services.k_int.core

import grails.compiler.GrailsCompileStatic

@GrailsCompileStatic
public class FederationLock {
  
  String id
  String name
  String family
  String owner
  
  static mapping = {
    
    // This is a System Level only Entity
    datasources([SystemDataService.DATASOURCE_SYSTEM])
    
    version false
    
    id column:'fl_id', generator: 'uuid2'
    name column: 'fl_name'
    family column:'fl_family'
    owner column: 'fl_owner'
  }
  
  static constraints = {
    name nullable: false, blank: false
    family nullable: false, blank: false
    owner nullable: false, blank: false
  }
}
