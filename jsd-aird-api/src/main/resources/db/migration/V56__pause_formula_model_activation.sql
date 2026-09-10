ALTER TABLE ai.formula_model_activation
    DROP CONSTRAINT formula_model_activation_status_check;

ALTER TABLE ai.formula_model_activation
    ADD CONSTRAINT formula_model_activation_status_check
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ROLLED_BACK', 'REPLACED'));

COMMENT ON COLUMN ai.formula_model_activation.status IS
    'ACTIVE serves traffic; PAUSED awaits review after artifact failure or quality drift; ROLLED_BACK and REPLACED are terminal history states';
