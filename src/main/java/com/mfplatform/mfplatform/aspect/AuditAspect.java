package com.mfplatform.mfplatform.aspect;

import com.mfplatform.mfplatform.security.ActorContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;

/**
 * AuditAspect intercepts methods annotated with @Auditable and writes a
 * structured audit log entry for every invocation.
 *
 * LOG FORMAT:
 * Each entry is a single-line JSON object, one per line in the audit log file.
 * Single-line JSON is the standard for log-aggregation tools (Splunk, ELK,
 * Datadog) — each line is a parseable event. Example:
 *
 * {"timestamp":"2026-07-14T09:12:34.567Z","operation":"PURCHASE",
 *  "actor":"priya.sharma","role":"INVESTOR","investorId":7,
 *  "result":"SUCCESS","durationMs":312,"details":""}
 *
 * {"timestamp":"2026-07-14T09:12:55.123Z","operation":"REDEMPTION",
 *  "actor":"priya.sharma","role":"INVESTOR","investorId":7,
 *  "result":"FAILURE","durationMs":28,"details":"Insufficient units: requested 500.0000 but only 103.6765 held"}
 *
 * WHY @Around (not @AfterReturning + @AfterThrowing):
 * We need timing (start before, stop after), access to both success and failure
 * cases, and a single code path for the log write. @Around gives all three.
 * We could use @AfterReturning + @AfterThrowing as separate methods but that
 * duplicates the log-building logic.
 *
 * LOG FILE ROTATION:
 * Configured in logback-spring.xml (see src/main/resources/).
 * The "AUDIT" logger name routes to a dedicated RollingFileAppender:
 *   - File: logs/audit.log (current)
 *   - Rolled: logs/audit.2026-07-13.log (yesterday), etc.
 *   - Rotation: daily
 *   - Retention: 90 days (3 months of audit history)
 *   - Max size: 10MB per file before forced rotation (safety net)
 *
 * WHY A DEDICATED LOG FILE (not the main application log):
 *   1. Audit entries must be readable without wading through debug noise
 *   2. Audit retention (90 days) differs from app log retention (typically 7-14 days)
 *   3. Compliance/security teams can monitor just audit.log independently
 *   4. In production, audit.log can be streamed to a SIEM (security monitoring tool)
 *      separately from the application log pipeline
 */
@Aspect
@Component
public class AuditAspect {

    /**
     * Dedicated audit logger.
     * "AUDIT" is the logger name — logback-spring.xml routes this specific
     * name to the rolling file appender rather than the console/application log.
     */
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    /**
     * @Around all methods annotated with @Auditable.
     * Captures operation name from the annotation, caller identity from
     * SecurityContext, timing, and outcome (success/failure + message).
     */
    @Around("@annotation(Auditable)")
    public Object auditOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        // Read the @Auditable annotation to get the operation name
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Auditable annotation = method.getAnnotation(Auditable.class);
        String operation = annotation.operation();

        // Read caller identity from Spring SecurityContext
        // Already set by JwtAuthFilter before this aspect fires
        String actor  = "anonymous";
        String role   = "NONE";
        String entityId = "";

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ActorContext actorCtx) {
            actor = auth.getName(); // the username (from JWT sub claim)
            role  = actorCtx.role().name();
            // Include the relevant entity ID for traceability
            entityId = actorCtx.investorId()    != null ? "investorId=" + actorCtx.investorId()
                     : actorCtx.distributorId() != null ? "distributorId=" + actorCtx.distributorId()
                     : "";
        }

        long startMs = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();

            long elapsed = System.currentTimeMillis() - startMs;

            // SUCCESS entry
            audit.info(buildLogEntry(
                operation, actor, role, entityId,
                "SUCCESS", elapsed, ""
            ));

            return result;

        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - startMs;

            // FAILURE entry — includes the exception message for diagnosis
            // We intentionally keep it brief (no stack trace) — audit logs
            // are for "what happened", not debugging. The app log has the stack trace.
            audit.warn(buildLogEntry(
                operation, actor, role, entityId,
                "FAILURE", elapsed, sanitize(ex.getMessage())
            ));

            // Always rethrow — the aspect must never swallow exceptions
            throw ex;
        }
    }

    /**
     * Builds a single-line JSON audit log entry.
     *
     * We build JSON manually rather than using a library (Jackson) to keep
     * the aspect self-contained and avoid any risk of the JSON serializer
     * itself throwing an exception during audit logging.
     *
     * The format is intentionally simple — flat structure, no nesting —
     * because log-aggregation tools parse flat JSON most efficiently.
     */
    private String buildLogEntry(
            String operation, String actor, String role,
            String entityId, String result, long durationMs, String details) {

        return String.format(
            "{\"timestamp\":\"%s\",\"operation\":\"%s\",\"actor\":\"%s\"," +
            "\"role\":\"%s\",\"%s\",\"result\":\"%s\"," +
            "\"durationMs\":%d,\"details\":\"%s\"}",
            Instant.now().toString(),
            operation,
            actor,
            role,
            entityId.isEmpty() ? "\"entityId\":\"\"" : "\"" + entityId.split("=")[0] + "\":" + entityId.split("=")[1],
            result,
            durationMs,
            details
        );
    }

    /**
     * Sanitizes a string for safe embedding in a JSON value:
     * escapes double quotes and removes newlines to keep entries single-line.
     */
    private String sanitize(String s) {
        if (s == null) return "";
        return s.replace("\"", "'").replace("\n", " ").replace("\r", "");
    }
}
