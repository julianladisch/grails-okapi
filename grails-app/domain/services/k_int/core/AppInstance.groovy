package services.k_int.core

import static services.k_int.core.FederationRole.DRONE

import java.time.Instant

import grails.compiler.GrailsCompileStatic

@GrailsCompileStatic
class AppInstance {
  
  String id
  String family
  Instant lastPulse
  FederationRole role = DRONE
  
  static mapping = {
    
    // This is a System Level only Entity
    datasources([SystemDataService.DATASOURCE_SYSTEM])
    
    version false
    
    id column:'ai_id', generator: 'assigned'
    family column:'ai_family'
    lastPulse column: 'ai_last_pulse'
    role column:'ai_role'
  }
  
  static constraints = {
    family nullable: false, blank: false
    lastPulse nullable: false
    role nullable: false
  }
}
