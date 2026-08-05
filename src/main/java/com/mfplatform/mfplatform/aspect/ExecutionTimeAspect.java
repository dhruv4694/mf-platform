package com.mfplatform.mfplatform.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ExecutionTimeAspect measures and logs the execution time of service methods.
 *
 * AOP CONCEPTS DEMONSTRATED:
 *
 * @Around — the most powerful advice type. It completely wraps the target method:
 *   - You call joinPoint.proceed() to execute the original method
 *   - You can measure time before and after
 *   - You can modify the return value
 *   - You can suppress or rethrow exceptions
 *   - You can even skip calling proceed() entirely (not done here)
 *
 * Named @Pointcut — we define the pointcut expression as a reusable method
 * and reference it by name in @Around. This is better than embedding a
 * long expression string directly in @Around for readability and reuse.
 *
 * Pointcut expression anatomy:
 *   execution(                              → method execution join point
 *     *                                     → any return type
 *     com.mfplatform.mfplatform..*          → any class in this package or subpackage
 *     Service                               → class name contains "Service"
 *     .*(..))                               → any method, any arguments
 *
 * WHY WE TARGET SERVICE CLASSES SPECIFICALLY:
 *   - Controllers: HTTP overhead is measured by Spring's DispatcherServlet
 *     and belongs in access logs, not here
 *   - Repositories: Spring Data generates proxy classes — the method names
 *     are less meaningful and the real SQL timing is a DB concern
 *   - Services: this is the business logic layer. Slow services indicate
 *     either heavy computation, N+1 queries, or holding locks too long —
 *     all things worth alerting on
 *
 * SLOW THRESHOLD:
 * Currently set to 500ms. Anything slower is logged at WARN level.
 * Every call is logged at DEBUG (useful during development, silent in prod
 * since prod typically runs at INFO level).
 *
 * PRODUCTION CONSIDERATIONS:
 * In production you'd typically replace this with Micrometer metrics
 * (a timer per method, exported to Prometheus/Grafana). This aspect is
 * a simpler alternative that demonstrates the pattern clearly without
 * needing a metrics stack.
 */
@Aspect
@Component
public class ExecutionTimeAspect {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTimeAspect.class);

    /**
     * Threshold above which a method call is considered "slow" and logged at WARN.
     * Set conservatively — financial calculations should complete in well under 500ms.
     * A slow service method usually indicates an N+1 query or a missing index.
     */
    private static final long SLOW_THRESHOLD_MS = 500;

    /**
     * Named pointcut: any method on any class whose name ends in "Service"
     * anywhere in the application package tree.
     *
     * Examples of matched classes:
     *   InvestorService, DistributorService, PurchaseService, EodProcessingService,
     *   SipMandateService, PortfolioService, HoldingService, UserService...
     *
     * Examples NOT matched (intentionally):
     *   InvestorController, InvestorRepository, JwtAuthFilter, DataSeeder
     */
    @Pointcut("execution(* com.mfplatform.mfplatform..*Service.*(..))")
    public void serviceLayer() {}

    /**
     * @Around advice: wraps every service method to measure its execution time.
     *
     * ProceedingJoinPoint (extends JoinPoint) is required for @Around advice
     * because it provides the proceed() method to actually execute the target.
     * Regular JoinPoint (used in @Before/@After) does not have proceed().
     *
     * We always rethrow any exception — this aspect must be transparent.
     * It measures time, it doesn't change behavior.
     */
    @Around("serviceLayer()")
    public Object measureExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        String className  = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();

        long startMs = System.currentTimeMillis();

        try {
            // proceed() calls the actual service method
            Object result = joinPoint.proceed();

            long elapsedMs = System.currentTimeMillis() - startMs;

            if (elapsedMs >= SLOW_THRESHOLD_MS) {
                // WARN: slow call — needs investigation (N+1 query? missing index?)
                log.warn("SLOW SERVICE CALL | {}.{}() | {}ms (threshold: {}ms)",
                        className, methodName, elapsedMs, SLOW_THRESHOLD_MS);
            } else {
                // DEBUG: every call, for development profiling
                log.debug("SERVICE | {}.{}() | {}ms", className, methodName, elapsedMs);
            }

            return result;

        } catch (Throwable ex) {
            long elapsedMs = System.currentTimeMillis() - startMs;

            // Log failures with timing so we can correlate slow calls with timeouts
            log.error("SERVICE EXCEPTION | {}.{}() | {}ms | {}",
                    className, methodName, elapsedMs, ex.getMessage());

            // Always rethrow — this aspect must not swallow exceptions
            throw ex;
        }
    }
}
