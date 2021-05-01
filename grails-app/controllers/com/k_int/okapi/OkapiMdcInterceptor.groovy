package com.k_int.okapi

import javax.servlet.http.HttpServletRequest
import org.slf4j.MDC
import groovy.transform.CompileStatic


@CompileStatic
public class OkapiMdcInterceptor {
  
  int order = HIGHEST_PRECEDENCE + 50

  private final static String PREFIX = "x-okapi-"
  private static final String[] headersToVars = [
    OkapiHeaders.TENANT,
  ] as String[]

  public OkapiMdcInterceptor() {
    log.debug "OkapiMdcInterceptor::Init"
    matchAll()
  }

  boolean before() {
    for (final String var : headersToVars) {
      
      log.debug "Looking for ${var}"
      String val = request.getHeader(var)?.trim()
      if (val) {
        
        String key = var.toLowerCase()
        
        key = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key
        
        log.debug "Adding log val ${key}"
        MDC.put(key, val)
      }
    }

    true
  }

  void afterView() {
    MDC.clear()
  }
}
