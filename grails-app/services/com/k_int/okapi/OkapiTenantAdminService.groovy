package com.k_int.okapi

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW

import java.sql.ResultSet

import javax.sql.DataSource

import com.k_int.okapi.remote_resources.RemoteOkapiLinkListener
import com.k_int.okapi.system.FolioHibernateDatastore
import com.k_int.web.toolkit.refdata.GrailsDomainRefdataHelpers
import com.k_int.web.toolkit.utils.GormUtils

import grails.core.GrailsApplication
import grails.events.EventPublisher
import grails.events.annotation.Subscriber
import grails.gorm.transactions.Transactional
import grails.util.GrailsNameUtils
import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import services.k_int.core.AppFederationService
import services.k_int.core.FolioLockService
import services.k_int.core.TenantManagerService
import services.k_int.core.liquibase.ExtendedGrailsLiquibase

@Slf4j
@CompileStatic
class OkapiTenantAdminService implements EventPublisher {

  private static final String TENANT_MODULE_FROM = 'module_from'
  private static final String TENANT_MODULE_TO = 'module_to'
  private static final String TENANT_MODULE_PARAMETERS = 'parameters'
  
  private static final String REGEX_SEMVER = /^.*?(((0|([1-9]\d*))\.){2}(0|([1-9]\d*))(-[A-Za-z]+(\.[A-Za-z\d-]+)*)?(\+[A-Za-z\d-]*)?).*?$/

  private static final String DISABLED_SUFFIX = '@disabled';
  
  FolioHibernateDatastore hibernateDatastore
  DataSource dataSource
  GrailsApplication grailsApplication
  
  @Autowired(required=true)
  FolioLockService folioLockService
  
  @Autowired(required=true)
  TenantManagerService tenantManagerService
  
  @Autowired(required=true)
  AppFederationService appFederationService
  
  
  private static String extractSemver ( final String text ) {
    return text?.replaceAll(REGEX_SEMVER, '$1')
  }

  private handleTenantParameters ( final String tenantId, final Map tenantData ) {

    log.trace("handleTenantParameters(${tenantId},${tenantData})")

    // Some defaults.
    final String event_prefix  = 'okapi:tenant_'
    final String from = extractSemver( tenantData[TENANT_MODULE_FROM] as String )
    final String to = extractSemver( tenantData[TENANT_MODULE_TO] as String )
    final boolean update = from as boolean
    final boolean existing_tenant = tenantData.existing_tenant
    
    try {
      final List<Map<String, ?>> params = (tenantData?.containsKey(TENANT_MODULE_PARAMETERS) ? tenantData.get(TENANT_MODULE_PARAMETERS) : null) as List<Map>       
      if (params) {

        for (final Map<String, ?> entry : params) {
          final String key = entry?.get('key')?.toString()?.trim()
          final boolean explicitSkip = (entry.get('value')?.toString()?.trim()?.toUpperCase() == 'FALSE')
          
          if (!explicitSkip && key?.toLowerCase()?.matches(/[a-z][a-z0-9_-]*/)) {
            final String event_name = "${event_prefix}${GrailsNameUtils.getScriptName(key).replaceAll('-', '_')}"
            
            log.trace "Raising event ${event_name} for tenant ${tenantId} with data ${entry.value}, ${existing_tenant}, ${update}, ${to}, ${from}"
            notify (event_name, tenantId, entry.value, existing_tenant, update, to, from)
          }
          else {
            log.trace("Skip: ${key}")
          }
        }
      }
    } catch (Exception e) {
      log.warn 'Error when extracting tenant parmeters.', e
    }
      
    // Raise tenant event here. We can add the metadata we have then and not duplicate effort.
    notify("${event_prefix}enabled", tenantId, existing_tenant, update, to, from)
  }

  @Transactional
  public void enableTenant( final String tenantId, final Map tenantData = null ) {
    
    folioLockService.federatedLockAndDo("tenant:${tenantId}", {
      
      log.trace("enableTenant(${tenantId},${tenantData})")
      tenantData.existing_tenant = false

      final String schema_name = OkapiTenantResolver.getTenantSchemaName(tenantId)      
      
      try {
        log.debug("See if we already have a datastore for ${schema_name}")
        if (hibernateDatastore.hasConnectionForSchema(schema_name)) {
          log.debug("Module already registered for tenant")    
          tenantData.existing_tenant = true
          
        } else {
          log.debug("New tenant create schema (${tenantId}/${schema_name})")
          createAccountSchema(schema_name)
        }
        
        log.debug("Run migrations against schema")
        updateTenantSchema(schema_name, tenantId)
        
        // IMPORTANT: We need to do this here. So that the AllTenantsResolver will
        // supply the tenant currently being added when the GormHelper registers the
        // domain objects. Otherwise the classes are never registered for the new
        // tenant.
        tenantManagerService.activate( tenantId )
        
        ensureDatasource(schema_name)
        
      } catch ( Exception e ) {
        log.error("Problem registering module for tenant/schema", e)
      }
  
      // Make this serial and the tenant parameter is now currently a noop.
      GormUtils.withNewTransaction {
        GrailsDomainRefdataHelpers.setDefaultsForTenant(schema_name)
        handleTenantParameters( tenantId, tenantData )
        log.debug("enableTenant exit cleanly")
      }
    })
  }
  
//  private void notifyOfTenantList() {
//    final List<String> ids = tenantManagerService.allActiveKnownTenantIds()
//    notify("okapi:tenant_list_updated", ids)
//  }

  private synchronized void createAccountSchema(String schema_name) {
    Sql sql = null
    try {
      sql = new Sql(dataSource)
      sql.withTransaction {
        log.debug("Execute -- create schema (if not exists) ${schema_name}")
        sql.execute("CREATE SCHEMA IF NOT EXISTS ${schema_name}" as String)
      }
      notify("okapi:tenant_created", schema_name)
      notify("okapi:tenant_schema_created", schema_name)
    } finally {
      sql?.close()
    }
  }

  public synchronized void purgeTenant(String tenantId) {
      
      log.debug("TenantAdminService::purgeTenant(${tenantId})")
    
    folioLockService.federatedLockAndDo("tenant:${tenantId}", {
      Sql sql = null
      String schema_name = OkapiTenantResolver.getTenantSchemaName (tenantId)
      
      // Disable the tenant internally before dropping the schema
      disableTenant (tenantId, false)
      try {
      
        sql = new Sql(dataSource)
        sql.withTransaction {
          
          final String sqlStr = "DROP SCHEMA IF EXISTS ${schema_name} CASCADE" as String
          
          log.debug("Execute -- ${sqlStr}")
          sql.execute(sqlStr)
        }
      } finally {
        sql?.close()
      }
      
      tenantManagerService.purge( tenantId )
      notify("okapi:tenant_purged", schema_name)
    })
  }

  public synchronized void disableTenant(String tenantId, boolean raiseEvent = true) {
    log.debug("TenantAdminService::disableTenant(${tenantId})")
    folioLockService.federatedLockAndDo("tenant:${tenantId}", {
      
      if (!schemaExistsForTenant( tenantId )) {
        log.debug ("No schema present for tenant NOOP")
        return
      }
      
      String schema_name = OkapiTenantResolver.getTenantSchemaName (tenantId)
      
      // Cleanup the datasource
      cleanupDatasource( schema_name )
      
      final String versionTag = appFederationService.getFamilyName()
      ExtendedGrailsLiquibase gl = getLiquibaseForSchema( schema_name )
      gl.ensureTag( "${versionTag}${DISABLED_SUFFIX}" )
      
      if (raiseEvent) {
        tenantManagerService.deactivate( tenantId )
        notify("okapi:tenant_disabled", tenantId)
      }
    })
  }
  
  protected List<String> allConsiderableSchemaNames() {
    final List<String> validlyNamedSchemas = []
    
    final ResultSet schemas = dataSource.getConnection().getMetaData().getSchemas()
    while(schemas.next()) {
      String schema_name = schemas.getString("TABLE_SCHEM")
      if ( schema_name.endsWith(OkapiTenantResolver.getSchemaSuffix()) ) {
        validlyNamedSchemas << schema_name
      }
    }
    
    validlyNamedSchemas
  }
  
  protected boolean schemaExistsForTenant(final String tenantId ) {
    log.debug("schemaExistsForTenant")
    allConsiderableSchemaNames().contains( OkapiTenantResolver.getTenantSchemaName( tenantId ) )
  }

  protected ExtendedGrailsLiquibase getLiquibaseForSchema( final String schema_name ) {
    final ExtendedGrailsLiquibase gl = new ExtendedGrailsLiquibase(grailsApplication.mainContext)
    gl.dataSource = dataSource
    gl.dropFirst = false
    gl.changeLog = 'module-tenant-changelog.groovy'
    gl.contexts = []
    gl.labels = []
    gl.defaultSchema = schema_name
    gl.databaseChangeLogTableName = 'tenant_changelog'
    gl.databaseChangeLogLockTableName = 'tenant_changelog_lock'
    
    gl
  }
  
  private boolean isTenantActive( final String tenantId ) {
    // Check the tenant manager service.
    tenantManagerService.isTenantActive( tenantId )
  }
  
  private synchronized void updateTenantSchema(String schema_name, String tenantId) {

    log.debug("updateTenantSchema(${schema_name},${tenantId})")
    // Now try create the tables for the schema
    try {
      ExtendedGrailsLiquibase gl = getLiquibaseForSchema( schema_name )
      gl.afterPropertiesSet() // this runs the update command
      
      final String versionTag = appFederationService.getFamilyName()
      
      if (versionTag) {
        log.debug "Tagging schema with ${versionTag}"
        gl.ensureTag( versionTag )
      }
      
    } catch (Exception e) {
      log.error("Error migrating database for $schema_name", e)
      throw e
    }
    finally {
      log.debug("Database migration completed")
    }

    try {
      // Method should only be concerned with forwarding the schema. Not adding the datasource.
//      hibernateDatastore.addTenantForSchema(schema_name)
      notify("okapi:schema_update", tenantId, schema_name)
    } catch (Exception e) {
      log.error("Exception adding tenant schema for ${schema_name}", e)
      throw e
    }
    finally {
      log.debug("added schema")
    }
  }
  
  private void ensureDatasource(final String schema_name ) {
    
    log.debug("ensureDatasource for ${schema_name}")
    if (!hibernateDatastore.hasConnectionForSchema(schema_name)) {
      hibernateDatastore.addTenantForSchema(schema_name)
      // Register the OKAPI listener too!
      RemoteOkapiLinkListener.listenForConnectionSourceName(schema_name)
      
      notify("okapi:tenant_datasource_added", schema_name)
    }
  }
  
  private void cleanupDatasource(final String schema_name ) {
    log.debug("cleanupDatasource for ${schema_name}")
    final boolean raiseEvent = hibernateDatastore.removeTenantForSchema(schema_name)
    
    // De-register the OKAPI listener too!
    RemoteOkapiLinkListener.stopListeningForConnectionSourceName(schema_name)
    
    if (raiseEvent) {      
      notify("okapi:tenant_datasource_removed", schema_name)
    }
  }
  
  /**
   * Checks to see if the tenantId is flagged as "active" for this version/family
   * If it is, we ensure we have a datasource configured.
   * 
   * @param tenantId
   * @return TRUE if the tenant is active and we have ensured a datasource. FALSE otherwise
   */
  @Transactional(propagation=REQUIRES_NEW)
  public boolean checkTenantAndEnsureDatasource( String tenantId ) {
    log.debug("checkTenantAndEnsureDatasource")
    if ( tenantId ) {
      final String lockName = "tenant:${tenantId}"
      
      // Block this thread until the tenant specific lock is gone.
      folioLockService.waitForNoFederatedLock(lockName)
      
      log.debug("Checking to see if ${tenantId} is already active")
      if ( isTenantActive(tenantId) ) {
        // Database suggests the tenant is active. This is now considered the
        // source of truth so we should just ensure the datasource here.
        final String schema_name = OkapiTenantResolver.getTenantSchemaName(tenantId)
        ensureDatasource( schema_name )
        
        return true
      }
    }
    
    false
  }
  
  /**
   * Activate a tenant that should be active, but has not yet been added to the list of sources.
   * 
   * @param tenantId The ID to check whether we should enable.
   * @return TRUE if the tenant was added and FALSE otherwise.
   */
//  public boolean performSchemaCheck( String tenantId ) {
//    if ( tenantId ) {
//      final String lockName = "tenant:${tenantId}"
//      
//      // Block this thread until the tenant specific lock is gone.
//      folioLockService.waitForNoFederatedLock(lockName)
//      
//      log.debug("Checking to see if ${tenantId} is already active")
//      if ( isTenantActive(tenantId) ) {
//        // Nothing to do - proceed
//        log.debug("performSchemaCheck(${tenantId}) -- true - no action needed")
//        return true
//      }
//      
//      // Otherwise check the schemata.
//      folioLockService.federatedLockAndDo(lockName, {
//        
//        // request is for a tenant not yet configured -- process
//        log.debug("${tenantId} is not known to be enabled for this version.")
//        String new_schema_name = OkapiTenantResolver.getTenantSchemaName(tenantId)
//        
//        if (schemaExistsForTenant( tenantId )) {
//          
//          final String version = appFederationService.familyName
//          
//          // The schema exists. Check that the schema is at the correct version.
//          // If we are calling this method then we assume the specific tenant is believed to be active for this version (effectively believing the routing) 
//          // So if we find the DB and changelog in sync we trust that to be enough. We should tag if it isn't already, for later seeding.
//          if (!getLiquibaseForSchema( new_schema_name ).logAndDatabaseMatchThenEnforceTag(version)) {
//            
//            log.error("performSchemaCheck(${tenantId}) -- Tenant database and log do not match. Suggests the tenant is not active for this version.")
//            return false
//          }
//          
//          // Update the record.
//          tenantManagerService.activate( tenantId )
//          
//          // Ensure we add the datasource ensure the datasource.
//          ensureDatasource( new_schema_name )
//  
//          // Let anyone interested know that we think we have located a new tenant we were not aware of at startup
//          log.debug("Added new schema for ${tenantId} - notify watchers")
//          notify("okapi:new_tenant_detected", tenantId)
//          notifyOfTenantList()
//          return true
//        }
//      })
//    }
//    
//    // Default is false
//    false
//  }
  
  
  
  private void seedTenantsTable() {
    log.debug("seedTenantsTable")
    // Get all the matching schemas.
    
    folioLockService.federatedLockAndDo("tenant:seeding", {
    
      // Get all the tenants known enabled/disabled
      final List<String> startList = tenantManagerService.allKnownTenantIds()
      final List<String> notSeen = startList.collect() // Copy the list
      
      // Seed from the schemata
      for (final String schema : allConsiderableSchemaNames()) {
        
        // Lock on this tenant name...
        final String tenantId = OkapiTenantResolver.schemaNameToTenantId( schema )
        folioLockService.federatedLockAndDo("tenant:${tenantId}", {
          final String version = appFederationService.familyName
          final ExtendedGrailsLiquibase gl = getLiquibaseForSchema( schema )
          if (gl.logAndDatabaseMatchAndIsTagged( version )) {
            
            log.debug("Adding enabled entry for tenantid: ${tenantId}")
            tenantManagerService.activate( tenantId )
            
          } else if (gl.logAndDatabaseMatchAndIsTagged( "${version}${DISABLED_SUFFIX}" )) {
            
            log.debug("Adding disabled entry for tenantid: ${tenantId}.")
            tenantManagerService.deactivate( tenantId )
          }
          
          // Else don't update the row....
        })
        // Flag we've seen a schema for the tenant id in the table
        notSeen.remove(tenantId)
      }
      
      for (final String unSeenTenant : notSeen) {
        // No schema seen for entry. We should disable the entry as we know it would fail.
        folioLockService.federatedLockAndDo("tenant:${unSeenTenant}", {
          tenantManagerService.deactivate( unSeenTenant ) // Disable any not listed
        })
      }
    })
  }
  
  /**
   * Returns the names of the active datasources that are configured for tenants.
   * 
   * @return The Collection of internal tenant IDs (Schema names)
   */
  public Collection<String> allConfiguredTenantSchemaNames() {
    hibernateDatastore.allConfiguredTenantConnectionSourceNames()
  }
  
  private int throttleCounter = 0
  private synchronized void updateInternalTenantDatasources() {
    
    // Throttle to every 3 ticks.
    if (throttleCounter % 3 != 0) {
      // Ignore for now.
      log.debug "Skipping this run. Only runs ever 3..."
      throttleCounter++
      return
    }
    
    // Reset to 1
    throttleCounter = 1;
    
    // Wait for 1 second otherwise skip this run
    if (!folioLockService.waitMaxForNoFederatedLock("tenant:seeding", 1000)) {
      log.info('updateInternalTenantDatasources() Skipping this time, as the tenant table is currently being seeded.' )
      return
    }
    
    // Grab the list of tenant datasource internally configured.
    final Collection<String> datastoreTenants = allConfiguredTenantSchemaNames()
    
    // ... and the list of known teants for this family from the table.
    final List<String> allKnownTenants = tenantManagerService.allKnownTenantIds()
    
    // Create a list of datasources that we don't see.
    final Set<String> notSeen = datastoreTenants.collect() as Set // Copy the list initially.
    
    // Go through all known tenants from the database table.
    for (final String tenantId : allKnownTenants) {
      
      // The schema name for this id.
      final String schema = OkapiTenantResolver.getTenantSchemaName(tenantId)
      
      // Make sure nothing locked the tenant state.
      if ( folioLockService.waitMaxForNoFederatedLock("tenant:${tenantId}", 3000) ) {
        
        // Get the schema name from the tenant ID
        
        // We recheck the status as something could have updated it.
        if (isTenantActive(tenantId)) {
          log.debug "tenant active ensure datasource"
          ensureDatasource(schema)
        } else {
          // Inactive, clean up.
          cleanupDatasource(schema)
        }
        
      } else {
        // Waited a max of 5 seconds
        log.warn("Waited for lock to be relinquished, skipping for now")
      }
      
      // Always remove the id schema to prevent decomissioning, even if the lock is
      // not obtained. This will be handled by a future run if it was removed.
      notSeen.remove(schema)
    }
    
    // Datasources that weren't in the list, active/inactive will be removed here.
    for (final String schema : notSeen) {
      cleanupDatasource(schema)
    }
  }
  
  @Transactional
  @Subscriber('federation:tick:drone')
  void tickDrone() {
    log.trace 'OkapiTenantAdminService::tickDrone()'
    updateInternalTenantDatasources()
  }
  
  @Transactional
  @Subscriber('federation:tick:leader')
  void tickLeader() {
    log.trace 'OkapiTenantAdminService::tickLeader'
    updateInternalTenantDatasources()
  }
  
  @Transactional
  @Subscriber('federation:registered:leader')
  void registeredAsLeader() {
    log.trace 'OkapiTenantAdminService::registeredAsLeader'
    seedTenantsTable()
  }
  
  @Transactional
  @Subscriber('federation:promoted')
  void promotedToLeader() {
    log.trace 'OkapiTenantAdminService::promotedToLeader'
    seedTenantsTable()
  }
}
