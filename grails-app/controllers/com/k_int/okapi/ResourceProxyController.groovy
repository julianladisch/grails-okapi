package com.k_int.okapi

import static groovyx.net.http.ContentTypes.JSON

import javax.servlet.http.HttpServletResponse

import grails.io.IOUtils
import groovyx.net.http.ChainedHttpConfig
import groovyx.net.http.FromServer
import groovyx.net.http.HttpException

class ResourceProxyController {
  
  private static final Set<String> ignoredParams = ['controller', 'targetPath', 'action', 'extraParams', 'id']
  
  // Adding namespace so we can have multiple controllers named ResourcePorxy controller but send traffic to the correct one.  
  static namespace = 'okapi'
  static responseFormats = ['json']

  OkapiClient okapiClient
  
  private static final HashMap<String, List> paramsToMap (def params) {
    final HashMap<String, List> paramMap = [:]
    
    params.keySet().each{ String k ->
      if (!ignoredParams.contains(k)) {
        paramMap.put(k, params.list(k))
      }
    }
    
    paramMap
  }
  
  private static final Map<String, List> mergeParams (final Map<String, ?> lhs, final Map<String, ?> rhs = [:]) {
    rhs.each { final String key, final def rhsValue ->
      
      final List<String> rhsListValue = []
      
      // Ensure collection
      if (!(rhsValue instanceof Collection)) {
        rhsListValue << rhsValue
      } else {
        rhsListValue.addAll(rhsValue)
      }
      
      final def lhsValue = lhs[key]
      if (!lhsValue) {
        // Just add the value.
        lhs[key] = rhsListValue
        
      } else if (!(lhsValue instanceof Collection)) {
        // Create a collection and add to it.
        lhs[key] = [lhsValue] + rhsListValue
      } else {
        // Collection
        lhs[key] = lhsValue + rhsListValue
      }
    }
    
    lhs
  }

  def index(final String targetPath, final String id, final Map<String, ?> defaultParams, final Boolean extraParams) {
    String uri = "${targetPath}".replaceAll(/^\s*\/?(.*?)\/?\s*$/, '/$1')
    
    final Map<String, ?> proxyParams = extraParams ? paramsToMap( params ) : [:]
    if (id) {
      final String strId = "${id}".replaceAll(/^\s*\/?(.*?)\/?\s*$/, '$1')
      uri += "/${strId}"
    } else {
      // Only merge the defaults in when accessing the root path.
      mergeParams (proxyParams, defaultParams)
    }
    
    log.debug ("Proxying to URI: ${uri}")
    
    log.debug ("Proxying params: ${proxyParams}")
    
    final HttpServletResponse clientResponse = getResponse()
    
    try {
      try {
        okapiClient.get( uri, proxyParams) {
          delegate.response.parser(JSON[0]) { ChainedHttpConfig cfg, FromServer fs ->
            IOUtils.copy(fs.inputStream, clientResponse.outputStream)
          }
        }
      } catch (HttpException httpEx) {
      log.error ("Error when attempting to proxy to OKAPI at ${uri}", httpEx)
        render (status: httpEx.fromServer.statusCode, text: httpEx.fromServer.message)
      } catch (Exception otherEx) {
      log.error ("Error when attempting to proxy to OKAPI at ${uri}", otherEx)
        render (status: 500, text: otherEx.message)
      }
    } catch (Exception e) {
      log.error ("Error when attempting to proxy to OKAPI at ${uri}", e)
    }
  }
}
