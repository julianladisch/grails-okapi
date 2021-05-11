package com.k_int.okapi

import groovy.transform.CompileStatic
import org.slf4j.MDC


@CompileStatic
public class OkapiMdcInterceptor {
  
  int order = HIGHEST_PRECEDENCE + 50

  private final static String PREFIX = 'x-okapi-'
  
  private static final String[] headersToVars = [OkapiHeaders.TENANT] as String[]
  

  public OkapiMdcInterceptor() {
    matchAll()
  }

  boolean before() {
    for (final String var : headersToVars) {
      
      final String val = request.getHeader(var)?.trim()
      if (val) {
        
        String key = var.toLowerCase()
        key = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key
        MDC.put("{$key}", "${val}")
      }
    }

    true
  }

  void afterView() {    
    MDC.clear()
  }
}
