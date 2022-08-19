package services.k_int.core

import static org.springframework.transaction.annotation.Propagation.MANDATORY

import grails.gorm.transactions.Transactional
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
class TenantManagerService {
  
  @Autowired(required=true)
  AppFederationService appFederationService
  
  @Autowired(required=true)
  KnownTenantDataService knownTenantDataService
  
  @Transactional
  public List<String> allActiveKnownTenantIds() {
    
    final String family = appFederationService.familyName
    
    return knownTenantDataService.listKnownTenantName( family, true )
  }
  
  @Transactional
  public boolean isTenantActive( Serializable id ) {
    final String family = appFederationService.familyName
    final String atId = knownTenantDataService.findKnownTenantId("${id}", family, true)
    
    atId != null
  }
  
  @Transactional
  public List<String> allKnownTenantIds( ) {
    final String family = appFederationService.familyName
    knownTenantDataService.listKnownTenantName(family)
  }
  
//  @Transactional
//  public boolean isTenantActiveForAnotherVersion( Serializable id ) {
//    final KnownTenant t = knownTenantDataService.findKnownTenantByName("${id}")
//    if (t == null) return false
//    
//    final String family = appFederationService.familyName
//    t.family != family
//  }
  
  @Transactional
  public void activate ( Serializable id ) {
    
    final KnownTenant at = knownTenantDataService.findKnownTenantByName("${id}", [lock:true])
    final String family = appFederationService.familyName
    
    if (at?.id) {
      // Module already active at some version...
      
      // Is it the current family or just inactive?
      if ( at.family != family || !at.active ) {
        
        // Update the family and the active marker.
        knownTenantDataService.updateKnownTenant(at.id, family, true)
      }      
      
      // Already activated for this family. NOOP.
    } else {
      // No existing record found, so add.
      knownTenantDataService.saveKnownTenant("${id}", family, true)
    }
  }
  
  @Transactional
  public void deactivate ( Serializable id ) {
    
    final String family = appFederationService.familyName
    final KnownTenant kt = knownTenantDataService.findKnownTenantByName("${id}", [lock:true])
    
    if (kt == null) {
      // Add deactivated record.
      knownTenantDataService.saveKnownTenant("${id}", family, false)
      return
    }
    
    // Otherwise update if necessary.
    if (kt.family != family) {
      // Already an entry for a different version. We shouldn't do anything.
      log.warn("Not deactivating tenant ${id} as there is an entry for it against another family.")
      return
    }
    
    // Just update the record.
    knownTenantDataService.updateKnownTenant(kt.id, family, false)
  }
  
  @Transactional
  public void purge ( Serializable id ) {
    
    final String family = appFederationService.familyName
    final KnownTenant at = knownTenantDataService.findKnownTenantByNameAndFamily("${id}", family, [lock:true])
    if (at?.id) {
      knownTenantDataService.deleteKnownTenant(at.id)
    }
  }
}
