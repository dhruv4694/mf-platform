-- V5 created BATCH_JOB_EXEC_PARAMS with Spring Batch 5's column shape
-- (PARAMETER_NAME/PARAMETER_TYPE/PARAMETER_VALUE/IDENTIFYING), but Spring
-- Batch 5 (bundled with Spring Boot 3.3) actually reads/writes the table
-- BATCH_JOB_EXECUTION_PARAMS (full "EXECUTION", not the abbreviated 4.x-era
-- name) — every real job launch failed with "relation ... does not exist"
-- until now, since no SIP batch run had ever completed against a real
-- database before (the cron never fired mid-session, and the manual trigger
-- didn't exist until this change).
ALTER TABLE BATCH_JOB_EXEC_PARAMS RENAME TO BATCH_JOB_EXECUTION_PARAMS;
