package com.k_int.okapi

import java.sql.ResultSet

import javax.sql.DataSource

import org.grails.datastore.mapping.core.exceptions.ConfigurationException
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.plugins.databasemigration.liquibase.GrailsLiquibase

import com.k_int.okapi.remote_resources.RemoteOkapiLinkListener

import grails.converters.*
import grails.core.GrailsApplication
import grails.events.EventPublisher
import grails.rest.*
import grails.util.GrailsNameUtils
import groovy.sql.Sql
import groovy.util.logging.Slf4j

@Slf4j
class OkapiTenantAdminService implements EventPublisher {
  
  private static final String TENANT_MODULE_FROM = 'module_from'
  private static final String TENANT_MODULE_TO = 'module_to'
  private static final String TENANT_MODULE_PARAMETERS = 'parameters'

  private static Set<Serializable> allTenantIdentifiers = null
  private static Set<Serializable> allTenantSchemaIdentifiers = null
  

  HibernateDatastore hibernateDatastore
  def dataSource
  GrailsApplication grailsApplication

  private handleTenantParameters ( final String tenantId, final Map tenantData ) {
    
    try {
      final List<Map> params = tenantData?.containsKey(TENANT_MODULE_PARAMETERS) ? tenantData.get(TENANT_MODULE_PARAMETERS) : null
      if (params) {
        final String event_prefix  = 'okapi:tenant_' 
        
        final String from = tenantData[TENANT_MODULE_FROM]
        final String to = tenantData[TENANT_MODULE_TO]
        final boolean update = from as boolean
        final boolean existing_tenant = tenantData.existing_tenant
        
        params.each { Map<String,String> entry ->
          final String key = entry?.key?.trim()
          if (key?.toLowerCase()?.matches(/[a-z][a-z0-9_-]*/)) {
            final String event_name = "${event_prefix}${GrailsNameUtils.getScriptName(key).replaceAll('-', '_')}"
            
            log.trace "Raising event ${event_name} for tenant ${tenantId} with data ${entry.value}, ${existing_tenant}, ${update}, ${to}, ${from}"
            notify (event_name, tenantId, entry.value, existing_tenant, update, to, from)
          }
        }
      }
    } catch (Exception e) {
      log.warn 'Error when extracting tenant parmeters.', e
    }
  }
    
  public void enableTenant( final String tenantId, final Map tenantData = null ) {
      tenantData.existing_tenant = false
      String new_schema_name = OkapiTenantResolver.getTenantSchemaName(tenantId)
      try {
        log.debug("See if we already have a datastore for ${new_schema_name}")
        hibernateDatastore.getDatastoreForConnection(new_schema_name)
        log.debug("Module already registered for tenant")
        tenantData.existing_tenant = true
      }
      catch ( ConfigurationException ce ) {
        log.debug("register module for tenant/schema (${tenantId}/${new_schema_name})")
        createAccountSchema(new_schema_name)
        updateAccountSchema(new_schema_name, tenantId)

        // Get hold of the cached list of tenant IDs and then add this new tenant to the list
        getAllTenantIds() << tenantId

        notify("okapi:tenant_schema_created", new_schema_name)
        notify("okapi:tenant_list_updated", getAllTenantIds())
        
        // Having trouble catching the event in the global listener. Call directly for now.
        RemoteOkapiLinkListener.listenForConnectionSourceName(new_schema_name)
      }
      
      handleTenantParameters( tenantId, tenantData )
      notify("okapi:tenant_enabled", tenantId)
  }

  synchronized void createAccountSchema(String tenantId) {
    Sql sql = null
    try {
        sql = new Sql(dataSource as DataSource)
        sql.withTransaction {
          log.debug("Execute -- create schema ${tenantId}");
          sql.execute("create schema ${tenantId}" as String)
        }
        notify("okapi:tenant_created", tenantId)
    } finally {
        sql?.close()
    }
  }

  synchronized void purgeTenant(String tenantId) {
    log.debug("TenantAdminService::purgeTenant(${tenantId})")
    Sql sql = null
    String schema_name = OkapiTenantResolver.getTenantSchemaName (tenantId)
    try {
      sql = new Sql(dataSource as DataSource)
      sql.withTransaction {
          log.debug("Execute -- drop schema ${schema_name} cascade")
          sql.execute("drop schema ${schema_name} cascade" as String)
      }
      
      getAllTenantIds().remove(tenantId)
      notify("okapi:tenant_purged", schema_name)
    } finally {
        sql?.close()
    }
  }
  
  synchronized void disableTenant(String tenantId) {
    log.debug("TenantAdminService::disableTenant(${tenantId})")
    /* NOOP Just raise an event */
    notify("okapi:tenant_disabled", tenantId)
  }
  
  public Set<Serializable> getAllTenantIds () {
    log.trace ("TenantAdminService::getAllTenantIds (${})")
    if (allTenantIdentifiers == null) {
      
      // Initializing all tenants.
      log.debug ("allTenantIdentifiers is NULL - so establish the list and pull default values from the database");
      allTenantIdentifiers = []
      allTenantSchemaIdentifiers = []
      
      ResultSet schemas = dataSource.getConnection().getMetaData().getSchemas()
      while(schemas.next()) {
        String schema_name = schemas.getString("TABLE_SCHEM")
        if ( schema_name.endsWith(OkapiTenantResolver.getSchemaSuffix()) ) {
          final String tenantId = OkapiTenantResolver.schemaNameToTenantId(schema_name)
          log.debug ("Adding tenant ${tenantId} and schema ${schema_name}")
          allTenantSchemaIdentifiers << schema_name
          allTenantIdentifiers << tenantId
        } else {
          log.debug("${schema_name} does not end with schema suffix ${OkapiTenantResolver.getSchemaSuffix()}, skipping");
        }
      }

      // This is the first pass through - so let anyone interested know what the current list of tenants is.
      // The methods for enableTenant (Newly enable the module for a tenant) and performSchemaCheck (Detecting 
      // if a tenant has been added by a clustered instance of this module) can also fire off this event.
      notify("okapi:tenant_list_updated", allTenantIdentifiers)
    }
    
    allTenantIdentifiers    
  }
  
  Set<Serializable> getAllTenantSchemaIds () {
    log.trace ("TenantAdminService::getAllTenantSchemaIds")
    
    if (!allTenantSchemaIdentifiers) {
      // Initializes both Sets.
      getAllTenantIds()
    }
    
    // We should rebuild if we are now out of date with the list of ids.
    if (allTenantSchemaIdentifiers.size() < allTenantIdentifiers.size()) {
      allTenantSchemaIdentifiers = getAllTenantIds().collect { Serializable id -> OkapiTenantResolver.getTenantSchemaName(id) }
    }
    
    allTenantSchemaIdentifiers
  }

  synchronized void freshenAllTenantSchemas() {
    log.debug("freshenAllTenantSchemas()")
    
    for ( final Serializable tenantId : getAllTenantIds() ) {
      final schema_name = OkapiTenantResolver.getTenantSchemaName(tenantId)
      updateAccountSchema(schema_name,tenantId)
    }
    
    notify("okapi:all_schemas_refreshed")
  }

  synchronized void updateAccountSchema(String schema_name, String tenantId) {

    log.debug("updateAccountSchema(${schema_name},${tenantId})")
    // Now try create the tables for the schema
    try {
      GrailsLiquibase gl = new GrailsLiquibase(grailsApplication.mainContext)
      gl.dataSource = dataSource
      gl.dropFirst = false
      gl.changeLog = 'module-tenant-changelog.groovy'
      gl.contexts = []
      gl.labels = []
      gl.defaultSchema = schema_name
      gl.databaseChangeLogTableName = 'tenant_changelog'
      gl.databaseChangeLogLockTableName = 'tenant_changelog_lock'
      gl.afterPropertiesSet() // this runs the update command
    } catch (Exception e) {
      log.error("Exception trying to create new account schema tables for $schema_name", e)
      throw e
    }
    finally {
      log.debug("Database migration completed")
    }

    try {
      log.debug("adding tenant for ${schema_name}")
      hibernateDatastore.addTenantForSchema(schema_name)
      
      notify("okapi:schema_update", tenantId, schema_name)
    } catch (Exception e) {
      log.error("Exception adding tenant schema for ${schema_name}", e)
      throw e
    }
    finally {
      log.debug("added schema")
    }
  }

  /**
   * This method checks to see if a tenant mentioned in the request is already configured in the datastore.
   * If not, register it and continue.
   */
  public void performSchemaCheck(String tenantId) {
    if ( tenantId ) {
      log.debug("Checking to see if ${tenantId} is already present in getAllTenantIds()  : ${getAllTenantIds().contains(tenantId)}");
      if ( getAllTenantIds().contains(tenantId) ) {
        // Nothing to do - proceed
        log.debug("performSchemaCheck(${tenantId}) -- true - no action needed");
      }
      else {
        // request is for a tenant not yet configured -- process
        log.debug("${tenantId} is not registered in the list of all Tenant IDs, configure and add now");
        String new_schema_name = OkapiTenantResolver.getTenantSchemaName(tenantId)
        // Just register the new tenant/schema name
        hibernateDatastore.addTenantForSchema(new_schema_name)
        // Alternatively, we could register the schema name AND update the schema
        // updateAccountSchema(new_schema_name, tenantId);
        getAllTenantIds() << tenantId

        // Let anyone interested know that we think we gave located a new tenant we were not aware of at startup
        log.debug("Added new schema for ${tenantId} - notify watchers");
        notify("okapi:new_tenant_detected", tenantId)
        notify("okapi:tenant_list_updated", getAllTenantIds())
      }
    }
  }
}
