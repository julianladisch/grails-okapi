package services.k_int.core

import static org.springframework.transaction.annotation.Propagation.MANDATORY
import static org.springframework.transaction.annotation.Propagation.REQUIRED

import java.time.Instant

import grails.gorm.multitenancy.Tenant
import grails.gorm.services.Service
import grails.gorm.transactions.Transactional
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@Slf4j
@CompileStatic
@Service(AppInstance.class)
@Tenant({ SystemDataService.DATASOURCE_SYSTEM })
public abstract class AppInstanceDataService {
  
  protected String instanceId = null
  
  @Transactional(propagation=MANDATORY)
  protected abstract AppInstance getAppInstance( Serializable id, Map args =[:] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract List<AppInstance> findAllByRoleAndFamilyAndIdNotEqualAndLastPulseGreaterThan(
    FederationRole role, String family, String id = instanceId, Instant pulse = Instant.now().minusMillis(180000L), Map args = [lock: true] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract List<AppInstance> findAllByFamilyAndLastPulseGreaterThan(
   String family,  Instant pulse = Instant.now().minusMillis(180000L), Map args = [lock: true] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract List<AppInstance> findAllByFamilyAndIdNotEqualAndLastPulseLessThan( String family, String id, Instant pulse, Map args )
  
  @Transactional(propagation=MANDATORY)
  AppInstance lockAppInstance( Serializable id ) {
    getAppInstance( id, [lock: true])
  }
  
  @Transactional(propagation=MANDATORY)
  List<AppInstance> getHealthyLeaders (final String family) {
    findAllByRoleAndFamilyAndIdNotEqualAndLastPulseGreaterThan( FederationRole.LEADER, family, instanceId, Instant.now().minusMillis(180000L), [lock: true] )
  }
  
  @Transactional(propagation=MANDATORY)
  List<AppInstance> getUnhealthyInstances (final String family) {
    findAllByFamilyAndIdNotEqualAndLastPulseLessThan( family, instanceId, Instant.now().minusMillis(180000L), [lock: true] )
  }
  
  @Transactional(propagation=MANDATORY)
  List<AppInstance> getHealthyInstances (final String family) {
    findAllByFamilyAndLastPulseGreaterThan(family)
  }
  
  @Transactional(propagation=REQUIRED)
  protected abstract AppInstance saveAppInstance ( Serializable id, String family, Instant lastPulse, Map args = [flush: true, failOnError: true])
  
  @Transactional(propagation=REQUIRED)
  protected abstract AppInstance updateAppInstanceRole ( Serializable id, FederationRole role, Map args = [flush: true, failOnError: true] )
  
  @Transactional(propagation=REQUIRED)
  protected abstract AppInstance updateAppInstanceLastPulse ( Serializable id, Instant lastPulse = Instant.now(), Map args = [flush: true, failOnError: true] )
  
  @Transactional(propagation=REQUIRED)
  protected abstract AppInstance delete ( Serializable id )
  
//  synchronized AppInstance register( final String familyName ) throws IllegalStateException {
//    
//    log.debug 'Registering application instance'
//    
//    if (familyName == null) throw new IllegalArgumentException('familyName must not be null')
//    
//    if (instanceId != null) throw new IllegalStateException('Registration should only be called once per instance"')
//      
//    // New UUID
//    UUID theInstanceId = UUID.randomUUID()
//      
//    // This instance hasn't been registered check for UUID already existing.
//    AppInstance appInst = lockAppInstance(theInstanceId)
//    
//    if (appInst != null) {
//      // Small chance of collision. We should log as a warning and assume 2 instances generated the same UUID
//      log.warn("Instance already exists with ${instanceId}. The chances of this happening are slight, trying another UUID")
//      theInstanceId = UUID.randomUUID()
//      appInst = lockAppInstance(theInstanceId)
//      
//      if (appInst != null)
//        throw new IllegalStateException('Duplicate uuid generated twice. Suggests something attempting to register more than once per instance.')
//    }
//    
//    // Insert the record.
//    appInst = new AppInstance()
//    appInst.id = theInstanceId
//    appInst.family = familyName
//    appInst.lastPulse = Instant.now()
//    
//    appInst = saveAppInstance( appInst )
//    log.debug 'Registered with ID {}', appInst.id
//    
//    // Update the local reference.
//    instanceId = appInst.id
//    
//    appInst
//  }
  
  @Transactional(propagation=MANDATORY)
  synchronized AppInstance promote( final AppInstance inst ) throws IllegalStateException {
    log.debug 'Promote this instance to leader'
    if (inst.role == FederationRole.LEADER) {
      log.debug('Instance is already leader. Just returning.')
      return inst
    }
    
    final AppInstance leader = updateAppInstanceRole(inst.id, FederationRole.LEADER)
    log.debug 'Instance promoted'
    
    leader
  }
  
}
