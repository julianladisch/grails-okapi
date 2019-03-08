package com.k_int.okapi

import javax.servlet.http.HttpServletResponse

import grails.io.IOUtils
import groovyx.net.http.ChainedHttpConfig
import groovyx.net.http.FromServer
import groovyx.net.http.HttpException

class ResourceProxyController {  
  
  private static final Set<String> ignoredParams = ['controller', 'action', 'targetPath', 'withParameters', 'defaultParams', 'id']
  
  // Adding namespace so we can have multiple controllers named ResourcePorxy controller but send traffic to the correct one.  
  static namespace = 'okapi'
  static responseFormats = ['json']

  OkapiClient okapiClient
  
  private final HashMap<String, List> paramsToMap (Map params) {
    final HashMap<String, List> paramMap = [:]
    
    for (String k : params.keySet()) {
      if (!ignoredParams.contains(k) && !k.endsWith('Id')) {
        def val = params[k]
        if (val instanceof Closure) {
          val.setDelegate(this)
          val = val()
        } else {
          val = params.list(k)
          val = val.collect {
            if (it instanceof Closure) {
              it.setDelegate(this)
              return it()
            }
            
            it
          }
        }
        
        paramMap.put(k, val)
      }
    }
    
    paramMap
  }
  
  private final def evaluateParamIfClosure ( def c ) {
    if (c instanceof Closure) {
      c.setDelegate(this)
      c.setResolveStrategy(Closure.DELEGATE_FIRST)
      return c()
    }
    
    c
  }
  
  private final Map<String, List> mergeParams (final Map<String, ?> lhs, final Map<String, ?> rhs = [:]) {
    rhs.each { final String key, def rhsValue ->
      
      rhsValue = evaluateParamIfClosure (rhsValue)
      
      final List<String> rhsListValue = []
      
      // Ensure collection
      if (!(rhsValue instanceof Collection)) {
        rhsListValue << rhsValue
      } else {
        rhsListValue.addAll( rhsValue.collect { evaluateParamIfClosure(it) } )
      }
      
      def lhsValue = evaluateParamIfClosure (lhs[key])
      if (!lhsValue) {
        // Just add the value.
        lhs[key] = rhsListValue
        
      } else {
        
        if (!(lhsValue instanceof Collection)) {
          // Create a collection and add to it.
          lhs[key] = [evaluateParamIfClosure( lhsValue )] + rhsListValue
        } else {
          // Collection
          lhs[key] = lhsValue.collect { evaluateParamIfClosure(it) } + rhsListValue
        }
      }
    }
    
    lhs
  }

  def index(final String targetPath, final String id, final Boolean withParameters) {
    
    // Maps cannot be in the action definition above. The compiler complains.
    // This map is set in the special url mappings.
    final Map<String, ?> defaultParams = params['defaultParams']
    
    String uri = "${targetPath}".replaceAll(/^\s*\/?(.*?)\/?\s*$/, '/$1')
    
    // Start with current parameters if flagged.
    final Map<String, ?> proxyParams = (withParameters == true) ? paramsToMap( params ) : [:]
    
    if (id) {
      uri += id.replaceAll(/^\s*\/?(.*?)\/?\s*$/, '/$1')
    } else {
      // Only merge the defaults in when accessing the root path.
      mergeParams (proxyParams, defaultParams)
    }
    
    log.debug ("Proxying to URI: ${uri} with parameters ${proxyParams}")
    
    final HttpServletResponse clientResponse = getResponse()
    
    try {
      okapiClient.get( uri, proxyParams ) {
        delegate.response.parser('*/*') { ChainedHttpConfig cfg, FromServer fs ->
          clientResponse.setStatus(fs.statusCode)
          IOUtils.copy(fs.inputStream, clientResponse.outputStream)
        }
      }
    } catch (HttpException httpEx) {
      log.error ("Error when attempting to proxy to OKAPI at ${uri}", httpEx)
//      render (status: httpEx.fromServer.statusCode, text: httpEx.fromServer.message)
    } catch (Exception otherEx) {
      log.error ("Error when attempting to proxy to OKAPI at ${uri}", otherEx)
//      render (status: 500, text: otherEx.message)
    }
  }
}
