ALTER TABLE ai.research_activity_context
    ADD COLUMN research_basis_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK(jsonb_typeof(research_basis_jsonb)='array');
