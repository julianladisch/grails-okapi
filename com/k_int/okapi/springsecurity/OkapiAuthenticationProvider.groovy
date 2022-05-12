package com.k_int.okapi.springsecurity

import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.util.Assert

class OkapiAuthenticationProvider implements AuthenticationProvider {

  @Override
  public boolean supports(Class<?> authentication) {
    OkapiAuthenticationToken.class.isAssignableFrom(authentication)
  }

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {

    // Return null if we don't support this token.
    if (!supports(authentication.class)) return null
    
    OkapiAuthenticationToken authToken = (OkapiAuthenticationToken) authentication
      
    // Ideally we could with some form of check here.
    authToken.setAuthenticated(true)
    
    authToken
  }
}
