package com.k_int.okapi.springsecurity
import org.springframework.security.core.AuthenticationException

class OkapiAuthenticationException extends AuthenticationException {
  OkapiAuthenticationException(String msg, Throwable t) {
    super(msg, t);
  }
  OkapiAuthenticationException(String msg) {
    super(msg);
  }
}