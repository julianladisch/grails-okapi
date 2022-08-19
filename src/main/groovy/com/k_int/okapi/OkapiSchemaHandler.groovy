package com.k_int.okapi

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import javax.sql.DataSource

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

import org.grails.datastore.gorm.jdbc.MultiTenantConnection
import org.grails.datastore.gorm.jdbc.schema.SchemaHandler
import org.springframework.jdbc.datasource.ConnectionProxy
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy

/**
 * Resolves the schema names
 *
 * @See https://github.com/grails/gorm-hibernate5/blob/master/grails-datastore-gorm-hibernate5/src/main/groovy/org/grails/orm/hibernate/HibernateDatastore.java
 */
@CompileStatic
@Slf4j
class OkapiSchemaHandler implements SchemaHandler {

    final String useSchemaStatement
    final String createSchemaStatement
    final String defaultSchemaName
    final String useDefatulSchemaStatement

    OkapiSchemaHandler() {
        // We use the search_path variant as folio installs some extensions in the public schema and 
        // we need to be able to access them.
        // useSchemaStatement = "SET SCHEMA '%s'"
      this(
        "SET search_path TO '%s', 'public'",
        "CREATE SCHEMA '%s'",
        'public')
    }

    OkapiSchemaHandler(String useSchemaStatement, String createSchemaStatement, String defaultSchemaName) {
        this.useSchemaStatement = useSchemaStatement
        this.createSchemaStatement = createSchemaStatement
        this.defaultSchemaName = defaultSchemaName
        this.useDefatulSchemaStatement = "SET SCHEMA '${defaultSchemaName}'"
    }

    @Override
    void useSchema(Connection connection, String name) {

      log.debug("useSchema(conn, ${name})")
      
      String useStatement = defaultSchemaName.equalsIgnoreCase(name) ? useDefatulSchemaStatement : String.format(useSchemaStatement, name)
        
      try {
        // Calling if ( checkSchemaIsValid(connection,name) ) here seems unnecessary and a substantial per-request overhead.
        // Removing for now
        // The assumption seems to be that this will throw an exception if the schema does not exist, but pg silently continues...
        
        // Use box type so we can have the unset state.
        boolean isClosed = connection.isClosed()
        
        // In certain situations the spring proxy returns false for closed when we expect
        // it to instead show as true. In this case we delegate to the wrapped proxy.
        if (!isClosed && MultiTenantConnection.isAssignableFrom(connection.class)) {
          MultiTenantConnection mtc = connection as MultiTenantConnection
          Connection targetCon = mtc.target
          if (ConnectionProxy.isAssignableFrom(targetCon.getClass())) {
            
            ConnectionProxy conP = (ConnectionProxy) targetCon
            targetCon = conP.getTargetConnection()
            isClosed = targetCon.isClosed()
          }
        }
        
        if (!isClosed) {
          connection
            .createStatement()
            .execute(useStatement)
        }
      }
      catch ( Exception e ) {
        log.error("problem trying to use schema - \"${useStatement}\"",e)
        // Rethrow
        throw e
      }
    }

    @Override
    void useDefaultSchema(Connection connection) {
        // log.debug("useDefaultSchema");
        useSchema(connection, defaultSchemaName)
    }

    @Override
    void createSchema(Connection connection, String name) {
        String schemaCreateStatement = String.format(createSchemaStatement, name)
        connection
                .createStatement()
                .execute(schemaCreateStatement)
    }
    
    @Override
    Collection<String> resolveSchemaNames(DataSource dataSource) {
      
      // If this is called by HibernateDatastore.java then the next step will be for the
      // addTenantForSchemaInternal method to be called for this db
      log.debug("OkapiSchemaHandler::resolveSchemaNames called")
      Collection<String> schemaNames = []
      Connection connection = dataSource.getConnection()
      ResultSet schemas
      
      
      // Because Grails attempts to fetch schema names after shutdown of the pool for cleanup,
      // trying to access the pool here is going to cause us problems.
      // For now catch the exception and return an empty set as the destruction should happen with the JVM.
      try {
        
        // Iterate through all schemas, ignore any that don't end OkapiTenantResolver.getSchemaSuffix(), add those that do to the result.
        // This may be the place to run migrations, or it may be better to do that in bootstrap.
        schemas = connection.getMetaData().getSchemas()
      } catch (SQLException sqlEx) {
        
        // Assume that the dataSource has already been closed and this method has been called after
        // Suppress the exception, and bail early. There is no method to check if closed so the exception
        // will have to do.
        return Collections.EMPTY_SET
      } finally {
        try {
            connection.createStatement().execute('set schema \'public\'')
            connection?.close()
        } catch (Throwable e) {
            log.debug("Error closing SQL connection: $e.message", e)
        }
      }
      
      try {
        while(schemas.next()) {
          String schema_name = schemas.getString("TABLE_SCHEM")
          if ( schema_name.endsWith(OkapiTenantResolver.getSchemaSuffix()) ) {
            // log.debug('resolveSchemaNames adding schema for '+schema_name);
            schemaNames.add(schema_name)
          }
          else {
            // log.debug('resolveSchemaNames skipping '+schema_name);
          }
        }
      } finally {
          try {
              connection.createStatement().execute('set schema \'public\'')
              connection?.close()
          } catch (Throwable e) {
              log.debug("Error closing SQL connection: $e.message", e)
          }
      }
      log.debug("OkapiSchemaHandler::resolveSchemaNames called - returning ${schemaNames}")
      return schemaNames
    }
}

