package com.mfplatform.mfplatform.common;

import com.mfplatform.mfplatform.distributor.DistributorNotActiveException;
import com.mfplatform.mfplatform.distributor.DistributorNotFoundException;
import com.mfplatform.mfplatform.folio.FolioNotFoundException;
import com.mfplatform.mfplatform.investor.InvestorNotFoundException;
import com.mfplatform.mfplatform.sip.SipMandateNotFoundException;
import com.mfplatform.mfplatform.transaction.InvalidTransactionStateException;
import com.mfplatform.mfplatform.transaction.TransactionNotFoundException;
import com.mfplatform.mfplatform.transaction.validation.TransactionValidationException;
import com.mfplatform.mfplatform.scheme.SchemeNotFoundException;
import com.mfplatform.mfplatform.transaction.InsufficientUnitsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({
            InvestorNotFoundException.class,
            DistributorNotFoundException.class,
            SchemeNotFoundException.class,
            FolioNotFoundException.class,
            TransactionNotFoundException.class,
            SipMandateNotFoundException.class
    })
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage(), Instant.now()));
    }

    // Catches unique-constraint violations (duplicate email/PAN/ARN/username)
    // and returns a clean 409 instead of letting a raw 500 leak the DB
    // constraint name to the client.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(
                        "A record with one of the provided unique fields (email, PAN, ARN code, or username) already exists.",
                        Instant.now()));
    }

    @ExceptionHandler(DistributorNotActiveException.class)
    public ResponseEntity<ErrorResponse> handleDistributorNotActive(
            DistributorNotActiveException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(ex.getMessage(), Instant.now()));
    }

    // Transaction validation failure — business rule not met (KYC, min amount etc)
    @ExceptionHandler(TransactionValidationException.class)
    public ResponseEntity<ErrorResponse> handleTransactionValidation(TransactionValidationException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(ex.getMessage(), Instant.now()));
    }

    // Insufficient units for redemption
    @ExceptionHandler(InsufficientUnitsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientUnits(InsufficientUnitsException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(ex.getMessage(), Instant.now()));
    }

    // Invalid state transition — programming error, not user error
    @ExceptionHandler(InvalidTransactionStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStateTransition(InvalidTransactionStateException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("You do not have permission to perform this action", Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(message, Instant.now()));
    }

    record ErrorResponse(String message, Instant timestamp) {}
}
