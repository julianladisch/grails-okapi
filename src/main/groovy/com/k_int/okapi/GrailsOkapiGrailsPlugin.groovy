package com.k_int.okapi

import org.grails.events.bus.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.security.Http401AuthenticationEntryPoint
import org.springframework.boot.web.servlet.FilterRegistrationBean

import com.k_int.okapi.springsecurity.OkapiAuthAwareAccessDeniedHandler
import com.k_int.okapi.springsecurity.OkapiAuthenticationFilter
import com.k_int.okapi.springsecurity.OkapiAuthenticationProvider

import grails.plugin.springsecurity.SecurityFilterPosition
import grails.plugin.springsecurity.SpringSecurityUtils
import grails.plugins.*

class GrailsOkapiGrailsPlugin extends Plugin {
  
  @Value('${client.environment}')
  boolean okapiAuth = false

  // the version or versions of Grails the plugin is designed for
  def grailsVersion = "3.3.2 > *"
  
  def dependsOn = [
    "springSecurityCore": "3.2.0 > *"
  ]
  // resources that are excluded from plugin packaging
  def pluginExcludes = [
    "grails-app/views/**",
    "grails-app/controllers/**"
  ]

  // TODO Fill in these fields
  def title = "Grails Okapi" // Headline display name of the plugin
  def author = "Steve Osguthorpe"
  def authorEmail = "steve.osguthorpe@k-int.com"
  def description = '''\
    Adds helpers for integrating with the FOLIO OKAPI API gateway
  '''
  
  def profiles = ['web']
  def loadAfter = [
    'springSecurityCore'
  ]

  // URL to the plugin's documentation
  def documentation = "http://grails.org/plugin/grails-okapi"

  // Extra (optional) plugin metadata

  // License: one of 'APACHE', 'GPL2', 'GPL3'
  //    def license = "APACHE"

  // Details of company behind the plugin (if there is one)
  //    def organization = [ name: "My Company", url: "http://www.my-company.com/" ]

  // Any additional developers beyond the author specified above.
  //    def developers = [ [ name: "Joe Bloggs", email: "joe@bloggs.net" ]]

  // Location of the plugin's issue tracker.
  //    def issueManagement = [ system: "JIRA", url: "http://jira.grails.org/browse/GPMYPLUGIN" ]

  // Online location of the plugin's browseable source code.
  //    def scm = [ url: "http://svn.codehaus.org/grails-plugins/" ]

  Closure doWithSpring() { {->
      // If OKAPI aware app.
      if (okapiAuth && pluginManager.hasGrailsPlugin('springSecurityCore')) {
        // Lets load some OKAPI authentication beans for easy integration.
        
        // Change the authentication end point to throw a 401
        authenticationEntryPoint(Http401AuthenticationEntryPoint, "realm='${grailsApplication.config?.info?.app?.name ?: 'OKAPI'}'")
          
        // This filter registers itself in a particular order in the chain.
        okapiAuthenticationFilter(OkapiAuthenticationFilter){ bean ->
          authenticationManager = ref('authenticationManager')
        }
        
        // Because of the custom ordering above we need to stop this from being de/registered automatically.
        okapiAuthenticationFilterDeregistrationBean(FilterRegistrationBean) {
          filter = ref('okapiAuthenticationFilter')
          enabled = false
        }
        
        okapiAuthenticationProvider(OkapiAuthenticationProvider)
        
        // Replace the AccessDenied handler to not redirect if the authentication was done with OKAPI.
        okapiAuthAwareAccessDeniedHandler(OkapiAuthAwareAccessDeniedHandler)
        
    //    grailsEventBus(ExecutorEventBus, Executors.newFixedThreadPool(5))
      }

    }
  }

//  void doWithDynamicMethods() {
//    // TODO Implement registering dynamic methods to classes (optional)
//  }

  void doWithApplicationContext() {
    // Register this filter first.
    SpringSecurityUtils.clientRegisterFilter(
      'okapiAuthenticationFilter', SecurityFilterPosition.FIRST)
  }

//  void onChange(Map<String, Object> event) {
//    // TODO Implement code that is executed when any artefact that this plugin is
//    // watching is modified and reloaded. The event contains: event.source,
//    // event.application, event.manager, event.ctx, and event.plugin.
//  }
//
//  void onConfigChange(Map<String, Object> event) {
//    // TODO Implement code that is executed when the project configuration changes.
//    // The event is the same as for 'onChange'.
//  }
//
//  void onShutdown(Map<String, Object> event) {
//    // TODO Implement code that is executed when the application shuts down (optional)
//  }
}
