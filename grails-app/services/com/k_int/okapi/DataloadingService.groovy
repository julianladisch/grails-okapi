package com.k_int.okapi;

import java.util.concurrent.ConcurrentHashMap

import javax.annotation.PostConstruct

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.runtime.DefaultGroovyMethodsSupport
import org.grails.io.support.PathMatchingResourcePatternResolver
import org.grails.io.support.Resource

import com.k_int.web.toolkit.refdata.GrailsDomainRefdataHelpers

import grails.core.GrailsApplication
import grails.events.EventPublisher
import grails.events.annotation.Subscriber
import grails.gorm.multitenancy.Tenants
import grails.util.GrailsNameUtils
import grails.web.databinding.DataBinder
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
public class DataloadingService implements EventPublisher, DataBinder {
  
  GrailsApplication grailsApplication
  
  private static final String TENANT_FILE_PATH = '/sample_data'
  private static final String TENANT_FILE_SUFFIX = 'Data'
  private static final String DEFAULT_FILE = '_'
  
  private final Map availableNames = new ConcurrentHashMap<String, String>()
  
  private PathMatchingResourcePatternResolver resolver
  
  @PostConstruct
  private void init() {
    resolver = new PathMatchingResourcePatternResolver(grailsApplication.getClassLoader())
    log.info "Dataloading services started."
    Resource[] resources = resolver.getResources("classpath*:${DataloadingService.TENANT_FILE_PATH}/*${DataloadingService.TENANT_FILE_SUFFIX}.groovy")
    resources.each { Resource res ->
      log.info "Found Tenant file ${res.filename}"
      def matches = res.filename =~ "^(\\w+)${DataloadingService.TENANT_FILE_SUFFIX}\\.groovy\$"
      matches.each { List<String> groups ->
        log.info "Adding ${groups[1].toLowerCase()} to available names."
        availableNames[groups[1].toLowerCase()] = res
      }
    }
  }
  
  private Resource resolveTenantFile(final String tenantId, final String type) {
    
    // The order to look for matches.
    final List<String> template_lookup = [
      "${tenantId.toLowerCase()}-${type.toLowerCase()}".toString(),
      tenantId.toLowerCase(),
      DEFAULT_FILE
    ]
    
    Resource file = null
    for (int i=0; !file && i<template_lookup.size(); i++) {
      final String className = GrailsNameUtils.getNameFromScript(template_lookup[i])
      
      log.info "Looking for ${template_lookup[i]} as ${className}"
      
      file = availableNames.containsKey(className) ? availableNames[className] : null
    }
    
    file
  }
  
  private void executeTenantDataFile (final Resource res, final String tenantId, final Map vars = []) {
    BufferedReader br
    try {
      // Delegating script.
      CompilerConfiguration cc = new CompilerConfiguration()
      cc.setScriptBaseClass(DelegatingScript.class.getName())
      
      GroovyShell shell = new GroovyShell(cc)
      
      // Reader. Needed as once the app is in a war the scripts will not be on the filesystem as such.
      br = new BufferedReader(new InputStreamReader(res.getInputStream()))
      
      
      final String schemaName = OkapiTenantResolver.getTenantSchemaName(tenantId)
      DelegatingScript script = (DelegatingScript)shell.parse(br)
      script.setBinding(new Binding(vars))
      Tenants.withId(schemaName) {
        script.setDelegate(delegate)
        script.run()
      }
      
      // Run the script with this class as the delegate.
    } catch (Exception e ) {
      // Just log the error.
      log.error ("Error executing tenant init script.", e)
    } finally {
      // Close the stream and log waring if anything goes wrong.
      DefaultGroovyMethodsSupport.closeWithWarning(br)
    }

  }
  
  @Subscriber('okapi:tenant_load_reference')
  public void onTenantLoadReference(final String tenantId, final String value, final boolean existing_tenant, final boolean upgrading, final String toVersion, final String fromVersion) {
    final String schemaName = OkapiTenantResolver.getTenantSchemaName(tenantId)
    
    log.debug("RefdataService::onTenantLoadReference(${tenantId}, ${value}, ${existing_tenant}, ${upgrading}, ${toVersion}, ${fromVersion})")
    GrailsDomainRefdataHelpers.setDefaultsForTenant(schemaName)
    
    notify('okapi:dataload:reference', tenantId, value, existing_tenant, upgrading, toVersion, fromVersion)
  }
  
  @Subscriber('okapi:tenant_load_sample')
  public void onTenantLoadSample(final String tenantId, final String value, final boolean existing_tenant, final boolean upgrading, final String toVersion, final String fromVersion) {
    Resource res = resolveTenantFile(tenantId, value)
    
    if (res.exists()) {
      log.info "Resource ${res.filename} exists"
      executeTenantDataFile(res, tenantId, ['existing_tenant': existing_tenant, 'upgrade': upgrading, 'toVersion': toVersion, 'fromVersion': fromVersion])
    }
    notify('okapi:dataload:sample', tenantId, value, existing_tenant, upgrading, toVersion, fromVersion)
  }
}
