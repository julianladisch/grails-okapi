package com.k_int.okapi.springsecurity

import javax.annotation.PostConstruct
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.access.AccessDeniedHandler

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
class OkapiAuthAwareAccessDeniedHandler implements AccessDeniedHandler {
  
  @Autowired
  private ConfigurableBeanFactory beanFactory
  
  @Autowired
  AccessDeniedHandler previousAccessDeniedHandler
  
  @PostConstruct
  private void init () {
    
    // Check if we have already registered a bean as the previous version.
    BeanDefinitionRegistry reg = (BeanDefinitionRegistry) beanFactory
      
    if (!reg.containsBeanDefinition('previousAccessDeniedHandler')) {      
      
      // Get the existing handler bean def.
      BeanDefinition previous = reg.getBeanDefinition('accessDeniedHandler')
      
      // Register the original under another name so we can refer to it here.
      reg.registerBeanDefinition('previousAccessDeniedHandler', previous)
      
      // Remove the default definition name so we can replace it.
      reg.removeBeanDefinition('accessDeniedHandler')
      
      // Replace the default def name here with the one for this bean.
      reg.registerBeanDefinition('accessDeniedHandler', reg.getBeanDefinition('okapiAuthAwareAccessDeniedHandler'))
      
      // Manually update the previous here.
      previousAccessDeniedHandler = (AccessDeniedHandler) beanFactory.getBean('previousAccessDeniedHandler')
      
      log.info "Replacing the default accessDenied Handler with an OKAPI request aware version."
    }
  }
  
  @Override
  void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) throws IOException, ServletException {
    if (authentication && OkapiAuthenticationToken.class.isAssignableFrom(authentication.class)) {
      log.trace 'Sending 403 for OKAPI request without attempting forward.'
      response.sendError HttpServletResponse.SC_FORBIDDEN, e.message
      return
    }
    
    // Otherwise we just delegate to the previous handler.
    previousAccessDeniedHandler?.handle(request, response, e) // ELSE NOOP.
  }
  
  protected Authentication getAuthentication() {
    SecurityContextHolder.context?.authentication
  }
}
