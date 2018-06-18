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
  String okapiPort
  
  @Value('${grails.server.host:localhost}')
  String backReferenceHost
  
  @Value('${grails.server.port:8080}')
  String backReferencePort
  
  HttpBuilder client
  
  final Map<String,Resource> descriptors = [:]
  
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
        log.info "Adding ${groups[1].toLowerCase()} to descriptors."
        descriptors."${groups[1].toLowerCase()}" = res
      }
    }
    
    // Attempt self registration.
    selfRegister()
    selfDeploy()
  }
  
  /**
   * Because of a limitation of not being able to uppercase text within Kubernetes to generate the
   * necessary value. We may end up with a mixed case env variable that therefore gets missed. Try and clean here.
   * 
   */
  String cleanVals(String val) {
    
    val.replaceAll (/\$\(([^\)]+)\)/) { def fullMatch, def mixedName ->
      def key = "${mixedName}".replaceAll( /\W/, '_').toUpperCase()
      String newVal = System.getenv().getOrDefault(key, key)
      
      log.info "Rewriting ${fullMatch} as ${newVal}"
      
      newVal
    }
  }
  
  void selfRegister () {
    
    Resource modDescriptor = descriptors.module
    if (modDescriptor) {      
      
      // Post the descriptor to OKAPI
      String payload = modDescriptor.inputStream.text
      
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
    
    Resource depDescriptor = descriptors.deployment
    if (depDescriptor) {
      
      // First we need to parse the deployment descriptor and replace with the correct values.
      def payload = new JsonSlurper().parseText ( depDescriptor.inputStream.text )
      
      if ( backReferenceHost && backReferencePort ) {
        backReferenceHost = cleanVals(backReferenceHost)
        backReferencePort = cleanVals(backReferencePort)
        payload.url = "http://${backReferenceHost}:${backReferencePort}/"
        payload.instId = backReferenceHost
      }
      
      final String discoUrl = '/_/discovery/modules'
      
      log.info "Attempt to de-register first."
      def response = client.delete {
        request.uri.path = "${discoUrl}/${payload.srvcId}"
      }
      
      log.info "Attempt to register deployment of module at ${payload.url}"
      
      // Send the info.
      response = client.post {
        request.contentType = JSON[0]
        request.uri.path = discoUrl
        request.body = payload
      }
      
      log.info "Success: Got response ${response}"
    }
  }
}
