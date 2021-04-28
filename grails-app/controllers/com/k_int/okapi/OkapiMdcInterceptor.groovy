package com.k_int.okapi

import javax.servlet.http.HttpServletRequest
import com.k_int.web.toolkit.mdc.TrackingMdcWrapper
import groovy.transform.CompileStatic


@CompileStatic
public class OkapiMdcInterceptor {
  
  private static final TrackingMdcWrapper MDC = new TrackingMdcWrapper()

  int order = HIGHEST_PRECEDENCE + 50

  private final static String PREFIX = "x-okapi-"
  private static final String[] headersToVars = [
    OkapiHeaders.TENANT,
  ] as String[]

  public OkapiMdcInterceptor() {
    log.info "OkapiMdcInterceptor::Init"
    matchAll()
  }

  boolean before() {
    for (final String var : headersToVars) {
      
      log.info "Looking for ${var}"
      String val = request.getHeader(var)?.trim()
      if (val) {
        
        String key = var.toLowerCase()
        
        key = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key
        
        log.info "Adding log val ${key}"
        MDC.put(key, val)
      }
    }

    true
  }

  void afterView() {
    MDC.clear()
  }
}
