package services.k_int.core

import static org.springframework.transaction.annotation.Propagation.MANDATORY
import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW

import javax.sql.DataSource

import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.connections.HibernateConnectionSource

import grails.gorm.multitenancy.Tenant
import grails.gorm.services.Service
import grails.gorm.transactions.Transactional
import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@Slf4j
@CompileStatic
@Service(FederationLock)
@Tenant({ SystemDataService.DATASOURCE_SYSTEM })
public abstract class FederationLockDataService {
  
  @Autowired(required=true)
  AppFederationService appFederationService
  
  @Transactional(propagation=MANDATORY)
  protected abstract FederationLock getByNameAndFamily( String name, String family, Map args = [lock: true] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract FederationLock getByNameAndFamilyAndOwner( String name, String family, String owner, Map args = [lock: true] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract FederationLock saveFederationLock ( String name, String family, String owner, Map args = [flush: true, failOnError: true] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract void deleteFederationLock ( Serializable id, Map args = [flush: true] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract List<FederationLock> findAllByOwner( String owner, Map args = [lock: true] )
  
  /**
   * Get a named lock across the federation of this app family.
   * 
   * @param name
   * @return The lock or null if it was not possible to get the lock.
   */
  @Transactional(propagation=REQUIRES_NEW)
  public FederationLock aquireNamedLock ( String name ) {
    
    try {
      final String familyName = appFederationService.getFamilyName()
      
      FederationLock lock = getByNameAndFamily( name, familyName )
      final String _me = appFederationService.instanceId;
      
      if ( lock ) {
        
        if (lock.owner == _me) {
          return lock
        }
        
        // Else lock owned by another instance.
        return null
      }
      
      // No lock add one and return it.
      return saveFederationLock(name, familyName, _me)
      
    } catch (Throwable t) {
      log.error("Error aquiring distributed lock '${name}'", t)
      return null
    }
  }
  
  @Transactional(propagation=MANDATORY)
  public void relinquishNamedLock ( String name ) {
    try {
      
      final String familyName = appFederationService.getFamilyName()
      final String _me = appFederationService.instanceId
      
      FederationLock lock = getByNameAndFamilyAndOwner(name, familyName, _me)
      
      if ( lock ) {
        // Relinquish this lock.
        deleteFederationLock( lock.id )
      } else {
        log.warn ("Request to relinquish lock ${name}, but lock not owned by this instance.")
      }
      
    } catch (Throwable t) {
      log.error("Error relinquishing distributed lock '${name}'", t)
    }
  }
  
  @Transactional(propagation=MANDATORY)
  public boolean namedLockExists ( String name ) {
    try {
      
      final String familyName = appFederationService.getFamilyName()
      final String _me = appFederationService.instanceId
      
      FederationLock lock = getByNameAndFamily(name, familyName, [lock: false])
      
      return lock != null
      
    } catch (Throwable t) {
      log.error("Error checking for lock '${name}'", t)
    }
  }
  
  protected void relinquishAllLocks () {
    
    // We use a direct SQL connection here. We expect this method to be called
    // As the app is shutting down gracefully and therefore the Entity Manager
    // will likely have already been destroyed. Using the same datasource, should
    // still allow us to use the connection pool/settings though. 
    
    final String schema = SystemDataService.getSystemDatasourceSchemaName()
    final String _me = appFederationService.instanceId
    
    if (schema && _me) {
      final HibernateDatastore dtst = (HibernateDatastore) this.getTargetDatastore()
      final DataSource dsrc = ((HibernateConnectionSource) dtst.connectionSources.defaultConnectionSource).dataSource
      final Sql sql = new Sql(dsrc)
      
      try {
        sql.execute("DELETE FROM ${schema}.federation_lock WHERE fl_id = '${_me}'".toString())
      } finally {
        sql.close()
      }
    }
  }
  
  @Transactional(propagation=MANDATORY)
  public void removeNamedLocksForOwner ( String owner ) {
    try {
      
      final List<FederationLock> locks = findAllByOwner( owner )
      
      for ( FederationLock lock : locks) {
        deleteFederationLock( lock.id )
      }
      
    } catch (Throwable t) {
      log.error("Error removing locks owned by '${owner}'", t)
    }
  }
}
