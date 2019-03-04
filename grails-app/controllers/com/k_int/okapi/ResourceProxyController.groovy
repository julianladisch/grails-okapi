package com.k_int.okapi

import grails.io.IOUtils
import groovy.json.JsonBuilder
import groovyx.net.http.FromServer
import groovyx.net.http.HttpException
import javax.servlet.ServletOutputStream
import javax.servlet.http.HttpServletResponse

class ResourceProxyController {
  
  // Adding namespace so we can have multiple controllers named ResourcePorxy controller but send traffic to the correct one.  
  static namespace = 'okapi'
  static responseFormats = ['json']

  OkapiClient okapiClient
  
  private static final HashMap<String, List<String>> paramsToMap (def params) {
    HashMap<String, List<String>> paramMap = [:]
    
    params.keySet().each{ String k ->
      paramMap.put(k, params.list(k))
    }
  }

  def index(String targetPath, Serializable id, Boolean withParams) {
    String uri = "${targetPath}".replaceAll(/^\s*\/?(.*?)\/?\s*$/, '/$1')
    
    if (id) {
      String strId = "${id}".replaceAll(/^\s*\/?(.*?)\/?\s*$/, '$1')
      uri += "/${strId}"
    }
    
    final HttpServletResponse clientResponse = response
    okapiClient.get( uri, (withParams == true ? paramsToMap( params ) : null )) {
      response.success { FromServer fs ->
        IOUtils.copy(fs.inputStream, clientResponse.outputStream)
      }
    }
  }
  
  def handleHttpException ( HttpException httpEx ) {
    // Just pipe the server response back to the user.
    render (status: httpEx.fromServer.statusCode, text: httpEx.fromServer.message)
    respond ([:], status: httpEx.fromServer.statusCode)
  }
}
