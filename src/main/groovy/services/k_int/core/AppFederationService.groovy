package services.k_int.core

import static org.springframework.transaction.annotation.Propagation.MANDATORY

import java.time.Instant

import org.springframework.scheduling.annotation.Scheduled

import com.github.zafarkhaja.semver.Version
import com.k_int.web.toolkit.utils.GormUtils

import grails.events.EventPublisher
import grails.events.annotation.Subscriber
import grails.events.emitter.EventEmitter
import grails.gorm.multitenancy.Tenants
import grails.gorm.transactions.Transactional
import grails.util.Metadata
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
class AppFederationService implements EventPublisher {

  @Autowired(required=true)
  AppInstanceDataService appInstanceService
  
  @Autowired(required=true)
  FolioLockService folioLockService

  private static String _familyName
  private static FederationRole _role
  private static String _instanceId
  

  /**
   * A string representing the application family name that this instance belongs to.
   * We simply return the full semver version.
   * 
   * @return The Family name
   */
  public synchronized String getFamilyName () {

    if (_familyName) return _familyName

    // Parse the data from the inbuilt metadata
    Version v  = Version.valueOf(
        Version.valueOf( Metadata.getCurrent().getApplicationVersion() ).getNormalVersion() )

    // Pull the major and minor only.
//    _familyName = "${v.majorVersion}.${v.minorVersion}"
    _familyName = "${v}"
    _familyName
  }

  /**
   * Register this application instance as a member of the family.
   */
  @Transactional
  public synchronized AppInstance registerInstance() {
    
    log.debug 'Registering application instance'
    
    final String familyName = getFamilyName()
    
    if (familyName == null) throw new IllegalArgumentException('familyName must not be null')
    
    if (_instanceId != null) throw new IllegalStateException('Registration should only be called once per instance"')
      
    // New UUID
    String theInstanceId = "${UUID.randomUUID()}"
    
    // Ensure we don't generating a clashing UUID. Chances are very slim but still theoretically possible.
    AppInstance appInst = appInstanceService.lockAppInstance(theInstanceId)
      
    while (appInst != null) {
      log.warn("Instance already exists with ${_instanceId}. The chances of this happening are slight, trying another UUID")
      theInstanceId = UUID.randomUUID()
      appInst = appInstanceService.lockAppInstance("${theInstanceId}")
    }
    
    appInst = appInstanceService.saveAppInstance( theInstanceId, familyName, Instant.now() )
    log.debug 'Registered with ID {}', appInst.id
    
    // Set the ID.
    _instanceId = appInst.id
    
    folioLockService.federatedLockAndDo('federation_promote', {
      final List<AppInstance> leaders = appInstanceService.getHealthyLeaders(getFamilyName())
      
      if (leaders.size() > 0) {
        // Already have a leader. Just return the instance record.
        log.debug 'Leader present this instance is a drone'
        
        // Update the local role.
        _role = appInst.role
        return
      }
      
      // Promote to leader, as no leader present.
      // Call this method instead of the internal version to prevent
      // the promotion even being raised.
      log.debug 'No healthy leader present. Promote this instance'
      _role = appInstanceService.promote( appInst ).role
    });
  
    // Reread in the object
    appInst = appInstanceService.lockAppInstance(theInstanceId)
     
    // Raise the registration event for the role
    notifyInternal("federation:registered:${_role}")
    
    // Return the instance.
    appInst
  }
  
  /**
   * Ensure we raise events tenant-less
   */
  EventEmitter notifyInternal(CharSequence eventId, Object... data = []) {
    log.debug "Firing event ${eventId}"
    return notify(eventId, data)
  }
  
  /**
   * Cleanup any Instances that have not emitted a pulse within the threshold
   */
  @Transactional
  public synchronized void cleanupUnhealthyInstances() {
    
    assertLeader()
    
    log.debug 'Cleanup any unhealthy instances'
    for (final AppInstance unhealthyInstance : appInstanceService.getUnhealthyInstances(getFamilyName())) {
      final long millisInactive = Instant.now().minusMillis(unhealthyInstance.lastPulse.toEpochMilli()).toEpochMilli()
      
      log.debug "Found instance: ${unhealthyInstance.id}, which has been inactive for ${(millisInactive / 1000)} seconds."
      deregisterInstance(unhealthyInstance.id)
    }
  }
  
  public Collection<String> allHealthyInstanceIds() {
    appInstanceService.getHealthyInstances(getFamilyName()).collect { it.id }
  }
  
  private static void assertLeader() {
    if (_role != FederationRole.LEADER)
      throw new IllegalStateException('Role of leader is required for operation')
  }
  
  @Transactional
  private synchronized void deregisterInstance( Serializable id ) {
    assertLeader()
    
    log.debug "Deregistering instance ${id}"
    appInstanceService.delete(id)
    notifyInternal('federation:cleanup:instance', id)
  }
  
  // Check self promotion
  @Transactional
  protected synchronized void checkPromotion() {
    if (_role == FederationRole.LEADER) return;
    
    folioLockService.federatedLockAndDo('federation_promote', {
      final List<AppInstance> leaders = appInstanceService.getHealthyLeaders(getFamilyName())
      
      if (leaders.size() > 0) {
        // Already have a leader. Just return the instance record.
        log.debug 'Leader present no need to promote'
        return
      }
  
      
      // Promote to leader, as no leader present.
      // Call this method instead of the internal version to prevent
      // the promotion even being raised.
      log.debug 'No healthy leader present. Promote this instance'
      appInstanceService.promote(appInstanceService.lockAppInstance(_instanceId))
      
      // Update the local references.
      _role = FederationRole.LEADER
      
      // Raise the registration event for leader
      notifyInternal("federation:promoted")
    })
  }
  
  /**
   * Emit a heartbeat pulse using the supplied service.
   */
  @Scheduled(fixedDelay=120000L, initialDelay=10000L)
  @Transactional
  public synchronized void pulse () {
    
    if (_instanceId == null) {
      log.warn 'Pulse called before the instance has been registered'
      return
    }
      
    asSystem {
      final AppInstance appInst = appInstanceService.lockAppInstance(_instanceId)
      if (appInst == null) throw new IllegalStateException("Could not obtain resource for this instance ID: ${_instanceId}")
        
      // Update the pulse
      appInstanceService.updateAppInstanceLastPulse( appInst.id )
    }
  }
  
  private static <T> T asSystem(Closure<T> work) {
    Tenants.withId(SystemDataService.DATASOURCE_SYSTEM, GormUtils.withTransaction { work } )
  }
  
  /**
   * Emit an internal tick to handle internal tasks.
   * Raises 1 of 2 possible events
   *  - federation:tick:leader
   *  - federation:tick:drone
   *  
   * Consuming Applications can listen to either, or both of theses events internally
   * and do tasks depending on the instances role in the federation 
   */
  @Scheduled(fixedDelay=30000L, initialDelay=30000L)
  public synchronized void tick() {
    
    // Check for self promotion 
    
    // Really this should all be done using a lock system, where a named
    // lock can be obtained for the federation. Need to expand later.
    
    if (_instanceId && _role) {      
      asSystem {
        checkPromotion()
      }
      
      notifyInternal("federation:tick:${_role}")
    }
    // else silence until ready
  }
  
  /**
   * Get the UUID currently associated with this instance within the federation
   * 
   * @return UUID in String form
   */
  public synchronized String getInstanceId() {
    if (_instanceId == null) {
      throw new IllegalStateException('Get instance id called before application has registered wit hthe federation.')
    }
    
    _instanceId
  }
  
  @Subscriber('federation:registered:leader')
  public void registeredAsLeader() {
    log.trace 'AppFederationService::registeredAsLeader'
    asSystem {
      cleanupUnhealthyInstances()
    }
  }
  
  @Subscriber('federation:tick:leader')
  public void tickLeader() {
    log.trace 'AppFederationService::tickLeader'
    asSystem {
      cleanupUnhealthyInstances()
    }
  }
}
