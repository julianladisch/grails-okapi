package com.k_int.okapi

import java.sql.ResultSet
import javax.sql.DataSource
import org.grails.datastore.mapping.core.exceptions.ConfigurationException
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.plugins.databasemigration.liquibase.GrailsLiquibase

import grails.converters.*
import grails.core.GrailsApplication
import grails.events.EventPublisher
import grails.rest.*
import groovy.sql.Sql

class OkapiTenantAdminService implements EventPublisher {

  HibernateDatastore hibernateDatastore
  def dataSource
  GrailsApplication grailsApplication

  public void createTenant(String tenantId) {

      String new_schema_name = OkapiTenantResolver.getTenantSchemaName(tenantId)
      try {
        log.debug("See if we already have a datastore for ${new_schema_name}")
        hibernateDatastore.getDatastoreForConnection(new_schema_name)
        log.debug("Module already registered for tenant")
      }
      catch ( ConfigurationException ce ) {
        log.debug("register module for tenant/schema (${tenantId}/${new_schema_name})")
        createAccountSchema(new_schema_name)
        updateAccountSchema(new_schema_name, tenantId)
        allTenantIds << tenantId

        // This is called in updateAccountSchema too - don't think we should call it twice
        // hibernateDatastore.addTenantForSchema(new_schema_name)
        notify("okapi:tenant_schema_created", new_schema_name)
      }
  }

  void createAccountSchema(String tenantId) {
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

  void dropTenant(String tenantId) {
    log.debug("TenantAdminService::dropTenant(${tenantId})")
    Sql sql = null
    String schema_name = OkapiTenantResolver.getTenantSchemaName (tenantId)
    try {
      sql = new Sql(dataSource as DataSource)
      sql.withTransaction {
          log.debug("Execute -- drop schema ${schema_name} cascade")
          sql.execute("drop schema ${schema_name} cascade" as String)
      }
      
      allTenantIds.remove(tenantId)
      notify("okapi:tenant_dropped", schema_name)
    } finally {
        sql?.close()
    }
  }
  
  private static Set<Serializable> allTenantIdentifiers = null
  private static Set<Serializable> allTenantSchemaIdentifiers = null
  
  Set<Serializable> getAllTenantIds () {
    log.trace ("TenantAdminService::getAllTenantIds")
    if (!allTenantIdentifiers) {
      
      // Initializing all tenants.
      log.debug ("Initializing tenant list from db schemas")
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
    }
    
    allTenantIdentifiers    
  }
  
  Set<Serializable> getAllTenantSchemaIds () {
    log.trace ("TenantAdminService::getAllTenantSchemaIds")
    
    if (!allTenantSchemaIdentifiers) {
      
      // Initializes both Sets.
      getAllTenantIds()
    }
    
    allTenantSchemaIdentifiers
  }
  

  void freshenAllTenantSchemas() {
    log.debug("freshenAllTenantSchemas()")
    
    for ( final Serializable tenantId : getAllTenantIds() ) {
      final schema_name = OkapiTenantResolver.getTenantSchemaName(tenantId)
      updateAccountSchema(schema_name,tenantId)
    }
    
    notify("okapi:all_schemas_refreshed")
  }

  void updateAccountSchema(String schema_name, String tenantId) {

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

}
