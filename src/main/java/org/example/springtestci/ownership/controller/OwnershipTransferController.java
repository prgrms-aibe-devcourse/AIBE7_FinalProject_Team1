package org.example.springtestci.ownership.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.common.security.CurrentUserIdResolver;
import org.example.springtestci.ownership.dto.AcceptTransferRequest;
import org.example.springtestci.ownership.dto.AcceptTransferResponse;
import org.example.springtestci.ownership.dto.CreateTransferRequest;
import org.example.springtestci.ownership.dto.CreateTransferResponse;
import org.example.springtestci.ownership.dto.OwnerTransferResponse;
import org.example.springtestci.ownership.dto.VerifyTransferResponse;
import org.example.springtestci.ownership.service.OwnershipTransferService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/ownership/transfers")
public class OwnershipTransferController {

  private final OwnershipTransferService service;
  private final CurrentUserIdResolver currentUserIdResolver;

  public OwnershipTransferController(
      OwnershipTransferService service, CurrentUserIdResolver currentUserIdResolver) {
    this.service = service;
    this.currentUserIdResolver = currentUserIdResolver;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<CreateTransferResponse>> createTransfer(
      Authentication authentication, @Valid @RequestBody CreateTransferRequest request) {
    long actorId = currentUserIdResolver.resolveAuthentication(authentication);
    CreateTransferResponse response = service.createTransfer(actorId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(response));
  }

  @GetMapping("/verify")
  public ResponseEntity<ApiResponse<VerifyTransferResponse>> verifyTransfer(
      Authentication authentication,
      @RequestParam("token") @NotBlank @Size(max = 256) String token) {
    long actorId = currentUserIdResolver.resolveAuthentication(authentication);
    VerifyTransferResponse response = service.verifyTransfer(actorId, token);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(response));
  }

  @PostMapping("/accept")
  public ResponseEntity<ApiResponse<AcceptTransferResponse>> acceptTransfer(
      Authentication authentication, @Valid @RequestBody AcceptTransferRequest request) {
    long actorId = currentUserIdResolver.resolveAuthentication(authentication);
    AcceptTransferResponse response = service.acceptTransfer(actorId, request);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(response));
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<ApiResponse<Void>> cancelTransfer(
      Authentication authentication, @PathVariable("id") @Positive Long transferId) {
    long actorId = currentUserIdResolver.resolveAuthentication(authentication);
    service.cancelTransfer(actorId, transferId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @GetMapping("/product/{productId}")
  public ResponseEntity<ApiResponse<OwnerTransferResponse>> ownerStatus(
      Authentication authentication, @PathVariable @Positive long productId) {
    long actorId = currentUserIdResolver.resolveAuthentication(authentication);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(service.ownerStatus(actorId, productId)));
  }
}
