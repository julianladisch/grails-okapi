package services.k_int.core

import grails.compiler.GrailsCompileStatic

@GrailsCompileStatic
public class KnownTenant {
  
  String id
  String name
  String family
  boolean active
  
  static mapping = {
    
    // This is a System Level only Entity
    datasources([SystemDataService.DATASOURCE_SYSTEM])
    
    version false
    
    id column:'at_id', generator: 'uuid2'
    name column: 'at_name'
    family column:'at_family'
    active column: 'at_active'
    
  }
  
  static constraints = {
    name nullable: false, blank: false, unique: true
    family nullable: false, blank: false
  }
}
