package com.k_int.okapi

import javax.servlet.http.HttpServletRequest

import org.grails.datastore.mapping.multitenancy.TenantResolver
import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletWebRequest

import com.k_int.okapi.OkapiHeaders

import grails.util.Holders
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j

/**
 * A tenant resolver that resolves the tenant from the request HTTP Header
 */
@CompileStatic
@Slf4j
class OkapiTenantResolver implements TenantResolver {
  
  public static final String schemaNameToTenantId ( String schemaName ) {
    schemaName.substring(0, schemaName.length() - getSchemaSuffix().length())
  }
  
  public static final String getTenantSchemaName ( String tenantId ) {
    "${tenantId}${getSchemaSuffix()}"
  }
  
  public static final String getSchemaSuffix () {
    return ('_'+getSchemaAppName())
  }
  
  /**
   * Determine the app name to use when appending a suffix to the schemas for tenants generated. Although this tries to use the value from application.metadata,
   * this value isn't available when running the Application.groovy file as a java app directly. 
   */
  @CompileStatic(TypeCheckingMode.SKIP)
  public static final String getSchemaAppName () {
    
    try {
      String appName = Holders.grailsApplication.config?.okapi?.schema?.appName ?: Holders.grailsApplication.metadata.applicationName
      "${appName.replaceAll(/\s/,'').replaceAll(/-/,'_').toLowerCase()}"
    } catch (NullPointerException e) {
      throw new RuntimeException("Could not determine an appname to use in the suffix for schema generation. Please add the paramter okapi.schema.appName to the application config.", e)
    }
  }
  
  @Override
  Serializable resolveTenantIdentifier() {

    RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes()
    if(requestAttributes instanceof ServletWebRequest) {
      HttpServletRequest httpServletRequest = ((ServletWebRequest) requestAttributes).getRequest()
      String tenantId = httpServletRequest.getHeader(OkapiHeaders.TENANT.toLowerCase())?.toLowerCase()

      if ( tenantId ) {
        return getTenantSchemaName(tenantId)
      }
      throw new TenantNotFoundException("Tenant could not be resolved from HTTP Header: ${OkapiHeaders.TENANT}")
    }

    throw new TenantNotFoundException("Tenant could not be resolved outside a web request")
  }
}
