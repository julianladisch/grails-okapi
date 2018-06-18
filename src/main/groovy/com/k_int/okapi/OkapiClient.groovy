package com.k_int.okapi

import static groovyx.net.http.ContentTypes.JSON
import static groovyx.net.http.HttpBuilder.configure

import javax.annotation.PostConstruct

import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException
import org.grails.io.support.GrailsResourceUtils
import org.grails.io.support.PathMatchingResourcePatternResolver
import org.grails.io.support.Resource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value

import grails.core.GrailsApplication
import grails.gorm.multitenancy.Tenants
import grails.gorm.multitenancy.Tenants.CurrentTenant
import grails.http.client.*
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import groovyx.net.http.ChainedHttpConfig
import groovyx.net.http.HttpBuilder
import groovyx.net.http.HttpException
import groovyx.net.http.HttpVerb

@Slf4j
class OkapiClient {
  
  @Autowired
  GrailsApplication grailsApplication
  
  @Value('${okapi.service.host:localhost}')
  String okapiHost
  
  @Value('${okapi.service.port:9130}')
  int okapiPort
  
  @Value('${grails.server.host:localhost}')
  String backReferenceHost
  
  @Value('${grails.server.port:8080}')
  int backReferencePort 
  
  HttpBuilder client
  
  final Map<String,URI> descriptors = [:]
  
  boolean isRegistrationCapable() {
    descriptors.containsKey('module')
  }
  
  private void addHeaders (ChainedHttpConfig cfg) {
    
    try {
      Serializable tenantId = Tenants.currentId()
      
      cfg.request.headers = [
        "${OkapiHeaders.TENANT}" : CurrentTenant.get()
      ]
      
      log.debug "Adding header for tenant ${tenantId}"
    } catch (TenantNotFoundException e) {
      /* No tenant */
    } catch (UnsupportedOperationException e) {
      /* Datastore doesn't support multi-tenancy */
    }
  }
  
  @PostConstruct
  void init () {
    
    if (okapiHost && okapiPort) {
      
      final String root = "http://${okapiHost}:${okapiPort}"
      
      log.info "Creating OKAPI client for ${root}"
      client = configure {
        request.uri = root
        execution.interceptor(HttpVerb.values()) { ChainedHttpConfig cfg, fx ->
          
          // Add any headers we can derive to all types of requests.
          addHeaders(cfg)
          
          // Request JSON.
          cfg.chainedRequest.contentType = JSON[0]
                    
          // Apply the original action.
          fx.apply(cfg)
        }
      }
    }
    
    
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(grailsApplication.getClassLoader())
    Resource[] resources = resolver.getResources("classpath*:/okapi/*Descriptor.json")
    
    resources.each { Resource res ->
      log.info "Found Descriptor ${res.filename}"
      def matches = res.filename =~ /^(\w+)Descriptor\.json$/
      matches.each { groups ->
        log.info "Adding ${groups[1].toLowerCase()} to descriptors with URI ${res.URI}."
        descriptors."${groups[1].toLowerCase()}" = res.URI
      }
    }
    
    // Attempt self registration.
    selfRegister()
    selfDeploy()
  }
  
  void selfRegister () {
    
    URI modDescriptor = descriptors.module
    if (modDescriptor) {      
      
      // Post the descriptor to OKAPI
      String payload = GrailsResourceUtils.getFile(modDescriptor).text
      
      log.info "Registering module wityh OKAPI..."
      
      // Send the info.
      try {
        def response = client.post {
          request.contentType = JSON[0]
          request.uri.path = '/_/proxy/modules'
          request.body = payload
        }       
        
        log.info "Success: Got response ${response}"
      } catch (HttpException httpEx) {
        
        // Assume the response 400 means the module is
        if ((httpEx.fromServer?.statusCode ?: -1) == 400) {
          log.info "Treated error: \"${httpEx.body}\" as success."
           
        } else throw httpEx
        
      }
    }
  }
  
  void selfDeploy () {
    
    URI depDescriptor = descriptors.deployment
    if (depDescriptor) {
      
      // First we need to parse the deployment descriptor and replace with the correct values.
      def payload = new JsonSlurper().parseText ( GrailsResourceUtils.getFile(depDescriptor).text )
      
      if ( backReferenceHost && backReferencePort ) {
        payload.url = "http://${backReferenceHost}:${backReferencePort}/"
        payload.instId = backReferenceHost
      }
      
      log.info "Attempt to register deployment of module at ${payload.url}"
      
      // Send the info.
      def response = client.post {
        request.contentType = JSON[0]
        request.uri.path = '/_/discovery/modules'
        request.body = payload
      }
      
      log.info "Success: Got response ${response}"
    }
  }
}
