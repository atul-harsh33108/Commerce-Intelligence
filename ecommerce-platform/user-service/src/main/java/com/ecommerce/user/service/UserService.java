package com.ecommerce.user.service;

import com.ecommerce.user.dto.AuthDtos.LoginRequest;
import com.ecommerce.user.dto.AuthDtos.LoginResponse;
import com.ecommerce.user.dto.AuthDtos.RegisterRequest;
import com.ecommerce.user.dto.AuthDtos.UserResponse;

public interface UserService {

    UserResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    UserResponse byUsername(String username);

    UserResponse byId(Long id);
}
