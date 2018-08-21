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
  
  @Value('${selfRegister:try}')  // try|yes|on|no|off
  String selfRegister
  
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
    switch ( selfRegister ) {
      case 'no':
      case 'off':
        log.info("No self registration");
        break;
      case 'yes':
      case 'on':
        // If this fails, the app will bomb out with an exception
        doSelfRegister();
        break;
      case 'try':
        try { 
          doSelfRegister();
        }
        catch ( Exception e ) {
          log.error("**Self registration failed**, but selfRegister set to try. Continuing",e);
        }
        break;
      default:
        break;
    }
  }

  private void doSelfRegister() {
    
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
      def payload = new JsonSlurper().parseText ( modDescriptor.inputStream.text )
      
      log.info "Registering module with OKAPI... request path is /_/proxy/modules/${payload.id}"
      
      // Send the info.
      def response
      try {
        response = client.put {
          request.contentType = JSON[0]
          request.uri.path = "/_/proxy/modules/${payload.id}"
          request.body = payload
        }
        
        log.info "Success: Got response ${response}"
      } catch (HttpException err) {
        // Error on put for update. Try posting for new mod.
        log.info "Error updating. Must be newly registering. err:${err} sc:${err.getStatusCode()}"
        
        response = client.post {
          request.contentType = JSON[0]
          request.uri.path = '/_/proxy/modules'
          request.body = payload
        }
        
        log.info "Success: Got response ${response}"
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
        
        // Using backReferenceHost default of localhost seems to cause okapi to choke with
        // BadRequest: 983996/discovery RES 400 3614us okapi Duplicate instance localhost
        // Current okapi du jour seems to be to supply a UUID for instId
        // payload.instId = backReferenceHost
        payload.instId = java.util.UUID.randomUUID().toString()
      }
      
      final String discoUrl = '/_/discovery/modules'
      
      def response
      try {
        final String delURI = "${discoUrl}/${payload.srvcId}/${payload.instId}"
        log.info "Attempt to de-register first using: ${delURI}"
        response = client.delete {
          request.uri.path = "${delURI}"
        }
      } catch (HttpException httpEx) {
        
        // Assume the response 404 means the module is
        if ((httpEx.fromServer?.statusCode ?: -1) == 404) {
          log.info "Treated error: \"${httpEx.body}\" as success."
        } else throw httpEx
        
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
