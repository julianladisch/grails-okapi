package com.k_int.okapi

import java.util.concurrent.ConcurrentHashMap

import javax.annotation.PostConstruct

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.runtime.DefaultGroovyMethodsSupport
import org.grails.io.support.PathMatchingResourcePatternResolver
import org.grails.io.support.Resource

import com.k_int.web.toolkit.utils.GormUtils

import grails.core.GrailsApplication
import grails.events.EventPublisher
import grails.events.annotation.Subscriber
import grails.gorm.multitenancy.Tenants
import grails.util.Environment
import grails.web.databinding.DataBinder
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
public class DataloadingService implements EventPublisher, DataBinder {
  
  GrailsApplication grailsApplication
  
  private static final String TENANT_FILE_PATH = '/sample_data'
  private static final String TENANT_FILE_SUFFIX = 'data'
  private static final String DEFAULT_FILE = '_'
  
  private final Map<String, Resource> availableNames = new ConcurrentHashMap<String, Resource>()
  
  private PathMatchingResourcePatternResolver resolver
  
  @PostConstruct
  private void init() {
    resolver = new PathMatchingResourcePatternResolver(grailsApplication.getClassLoader())
    log.info "Dataloading services started."
    Resource[] resources = resolver.getResources("classpath*:${DataloadingService.TENANT_FILE_PATH}/${DEFAULT_FILE}${DataloadingService.TENANT_FILE_SUFFIX}.groovy") +  
      resolver.getResources("classpath*:${DataloadingService.TENANT_FILE_PATH}/*-${DataloadingService.TENANT_FILE_SUFFIX}.groovy")
    
    resources.each { Resource res ->
      log.info "Found Tenant file ${res.filename}"
      
      // Special handling of default filename.
      if (res.filename == '_data.groovy') {
        log.info "Adding ${DEFAULT_FILE} to available names."
        availableNames[DEFAULT_FILE] = res
      } else {
      
        def matches = res.filename =~ "^([A-Za-z0-9-]+)-${DataloadingService.TENANT_FILE_SUFFIX}\\.groovy\$"
        
        if (matches) {
          matches.each { List<String> groups ->
            log.info "Adding ${groups[1].toLowerCase()} to available names."
            availableNames[groups[1].toLowerCase()] = res
          }
        }
      }
    }
  }
  
  private Resource resolveTenantFile(final String tenantId, final String type) {
    // The order to look for matches.
    final List<String> template_lookup = [
      "${tenantId.toLowerCase()}-${Environment.getCurrent().name}-${type.toLowerCase()}".toString(),
      "${tenantId.toLowerCase()}-${Environment.getCurrent().name}".toString(),
      tenantId.toLowerCase()
    ]
    
    Resource file = null
    for (int i=0; !file && i<template_lookup.size(); i++) {
      final String name = template_lookup[i].toLowerCase().replaceAll(/[^A-Za-z0-9-]/, '-').replaceAll(/-{2,}/, '-')
      
      log.info "Looking for file for ${name}"
      file = availableNames.containsKey(name) ? availableNames[name] : null
    }
    
    if (!file && availableNames.containsKey(DEFAULT_FILE)) {
      file = availableNames[DEFAULT_FILE]
    }
    
    file
  }
  
  private void executeTenantDataFile (final Resource res, final String tenantId, final Map vars = [:]) {
    log.debug "executeTenantDataFile (${res}, ${tenantId}, ${vars})"
    BufferedReader br
    try {
      
      // Delegating script.
      final CompilerConfiguration cc = new CompilerConfiguration()
      cc.setScriptBaseClass(DelegatingScript.class.getName())
      
      // Create a shell with the classloader, binding and compiler config.
      final GroovyShell shell = new GroovyShell(grailsApplication.getClassLoader() ,new Binding(vars), cc)
      
      // Reader. Needed as once the app is in a war the scripts will not be on the filesystem as such.
      br = new BufferedReader(new InputStreamReader(res.getInputStream()))
      final DelegatingScript script = (DelegatingScript)shell.parse(br)      
      
      final String schemaName = OkapiTenantResolver.getTenantSchemaName(tenantId)
      Tenants.withId(schemaName) {
        
        // Ensure the delegate of the script is set to the same as the wrapping closure.
        script.setDelegate(delegate)
        GormUtils.withTransaction {
          script.run()
        }
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
//    final String schemaName = OkapiTenantResolver.getTenantSchemaName(tenantId)
//    
//    log.debug("RefdataService::onTenantLoadReference(${tenantId}, ${value}, ${existing_tenant}, ${upgrading}, ${toVersion}, ${fromVersion})")
//    if (!existing_tenant) GrailsDomainRefdataHelpers.setDefaultsForTenant(schemaName)
    log.debug "okapi:tenant_load_reference currently a noop. Just raise our post action event okapi:dataload:reference"
    notify('okapi:dataload:reference', tenantId, value, existing_tenant, upgrading, toVersion, fromVersion)
  }
  
  @Subscriber('okapi:tenant_load_sample')
  public void onTenantLoadSample(final String tenantId, final String value, final boolean existing_tenant, final boolean upgrading, final String toVersion, final String fromVersion) {
    Resource res = resolveTenantFile(tenantId, value)
    
    if (res?.exists()) {
      log.info "Resource ${res.filename} exists"
      executeTenantDataFile(res, tenantId, ['existing_tenant': existing_tenant, 'upgrade': upgrading, 'toVersion': toVersion, 'fromVersion': fromVersion])
    }
    notify('okapi:dataload:sample', tenantId, value, existing_tenant, upgrading, toVersion, fromVersion)
  }
}
