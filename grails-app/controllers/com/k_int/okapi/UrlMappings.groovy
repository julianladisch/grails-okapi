package com.k_int.okapi

class UrlMappings {
  static mappings = {
    "/_/$action" (controller: 'okapi')
    
    "/proxytest/$id?" {
      controller = 'resourceProxy'
      namespace = 'okapi'
      targetPath = '/licenses'
      withParameters = true // Allow params to be proxied.
    }
  }
}
