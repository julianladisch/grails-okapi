package services.k_int.core

import grails.core.GrailsApplication
import groovy.transform.CompileStatic

import org.grails.plugins.databasemigration.liquibase.GrailsLiquibase

import com.k_int.okapi.OkapiTenantResolver

@CompileStatic
public class SystemDataService {
  
  public static final String DATASOURCE_SYSTEM = 'system'
  public static String getSystemDatasourceSchemaName() {
    "${OkapiTenantResolver.getSchemaAppName()}__${DATASOURCE_SYSTEM}"
  }
  
//  final GrailsApplication grailsApplication
//  
//  public SystemDataService(GrailsApplication grailsApplication) {
//    this.grailsApplication = grailsApplication
//  } 
//  
  
//  synchronized void runSystemDataMigrations() {
//
//    log.debug("updateAccountSchema(${schema_name},${tenantId})")
//    // Now try create the tables for the schema
//    try {
//      GrailsLiquibase gl = new GrailsLiquibase(grailsApplication.mainContext)
//      gl.dataSource = dataSource
//      gl.dropFirst = false
//      gl.changeLog = 'module-tenant-changelog.groovy'
//      gl.contexts = []
//      gl.labels = []
//      gl.defaultSchema = schema_name
//      gl.databaseChangeLogTableName = 'tenant_changelog'
//      gl.databaseChangeLogLockTableName = 'tenant_changelog_lock'
//      gl.afterPropertiesSet() // this runs the update command
//    } catch (Exception e) {
//      log.error("Exception trying to create new account schema tables for $schema_name", e)
//      throw e
//    }
//    finally {
//      log.debug("Database migration completed")
//    }
//
//    try {
//      log.debug("adding tenant for ${schema_name}")
//      hibernateDatastore.addTenantForSchema(schema_name)
//      notify("okapi:schema_update", tenantId, schema_name)
//    } catch (Exception e) {
//      log.error("Exception adding tenant schema for ${schema_name}", e)
//      throw e
//    }
//    finally {
//      log.debug("added schema")
//    }
//  }
}
