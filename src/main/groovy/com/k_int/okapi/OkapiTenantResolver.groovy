package com.k_int.okapi

import javax.servlet.http.HttpServletRequest

import org.grails.datastore.mapping.multitenancy.AllTenantsResolver
import org.grails.datastore.mapping.multitenancy.TenantResolver
import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletWebRequest
import services.k_int.core.TenantManagerService
import grails.util.Holders
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * A tenant resolver that resolves the tenant from the request HTTP Header
 */
@CompileStatic
@Slf4j
class OkapiTenantResolver implements TenantResolver, AllTenantsResolver {
  
  public static final String schemaNameToTenantId ( String schemaName ) {
    schemaName.substring(0, schemaName.length() - getSchemaSuffix().length())
  }
  
  public static final String getTenantSchemaName ( String tenantId ) {
    "${tenantId}${getSchemaSuffix()}"
  }
  
  public static final boolean isValidTenantSchemaName ( String schemaName ) {
    schemaName.endsWith(getSchemaSuffix())
  }
  
  public static final String getSchemaSuffix () {
    return ('_'+getSchemaAppName())
  }
  
  public static String APP_SCHEMA_NAME = null
  
  /**
   * Determine the app name to use when appending a suffix to the schemas for tenants generated. Although this tries to use the value from application.metadata,
   * this value isn't available when running the Application.groovy file as a java app directly. 
   */
  public static final String getSchemaAppName () {
    if (!APP_SCHEMA_NAME) {
      try {
        String appName = Holders.grailsApplication.config.getProperty("okapi.schema.appName", Holders.grailsApplication.metadata.applicationName) 
        
        APP_SCHEMA_NAME =  "${appName.replaceAll(/\s/,'').replaceAll(/-/,'_').toLowerCase()}".toString()
      } catch (NullPointerException e) {
        throw new RuntimeException("Could not determine an appname to use in the suffix for schema generation. Please add the paramter okapi.schema.appName to the application config.", e)
      }
    }
    
    APP_SCHEMA_NAME
  }
  
  
  /**
   * Nicely return a tenant ID without throwing exception when the current request isn't
   * bound to a tenant.
   * @return The current tenant or null
   * @throws TenantNotFoundException if this is not a web request
   */
  static Serializable resolveTenantIdentifierOptionally() throws TenantNotFoundException {
    final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes()
    if(requestAttributes instanceof ServletWebRequest) {
      final HttpServletRequest httpServletRequest = ((ServletWebRequest) requestAttributes).getRequest()
      final Serializable tenantId = httpServletRequest.getHeader(OkapiHeaders.TENANT.toLowerCase())?.toLowerCase()

      return tenantId ? getTenantSchemaName(tenantId) : null
    }

    throw new TenantNotFoundException("Tenant could not be resolved outside a web request")
  }
  
  @Override
  Serializable resolveTenantIdentifier() {
    final Serializable currentTenant = resolveTenantIdentifierOptionally()
    if (currentTenant) {
      return currentTenant
    }
    throw new TenantNotFoundException("Tenant could not be resolved from HTTP Header: ${OkapiHeaders.TENANT}")
  }

  private static flaggedAsReady = false
  public static void flagReady() {
    flaggedAsReady = true
  }
  
  
  private TenantManagerService _tms
  private TenantManagerService getTenantManagerService() {
    if (_tms != null) return _tms
    
    _tms = Holders.applicationContext?.getBean(TenantManagerService)
    _tms
  }
  
  /**
   * Grails internal id uses the schema name.
   * @return List of schema names for the tenants.
   */
  @Override
  public Iterable<Serializable> resolveTenantIds () {
    
    // Just return an empty set if we haven't been flagged as ready.
    // This allows us to defer the tenant initialization until the app is ready and able
    // to read from the necessary datasource.
    if (!flaggedAsReady) return Collections.EMPTY_SET
    
    tenantManagerService?.allActiveKnownTenantIds()?.collect { getTenantSchemaName( it ) } as Iterable ?: Collections.EMPTY_SET
  }
}
