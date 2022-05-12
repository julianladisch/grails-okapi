package com.k_int.okapi

class UrlMappings {
  static mappings = {
    group '/_', {
      
      // Tenant mappings.
      '/tenant' (controller: 'tenant', method: 'POST')
      '/tenant' (controller: 'tenant', method: 'DELETE')
      '/tenant/disable' (controller: 'tenant', action: 'disable', method: 'POST')
    }
  }
}
