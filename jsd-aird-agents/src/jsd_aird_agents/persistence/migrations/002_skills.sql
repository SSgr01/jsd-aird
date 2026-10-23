CREATE TABLE agent_runtime.skill_versions (
    name text NOT NULL, sha256 text NOT NULL, metadata jsonb NOT NULL, instructions text NOT NULL,
    manifest jsonb NOT NULL, source text NOT NULL, enabled boolean NOT NULL DEFAULT false,
    registered_at timestamptz NOT NULL DEFAULT now(), enabled_at timestamptz,
    PRIMARY KEY(name,sha256)
);
CREATE TABLE agent_runtime.skill_catalog (
    name text PRIMARY KEY, current_sha256 text NOT NULL, available boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(name,current_sha256) REFERENCES agent_runtime.skill_versions(name,sha256)
);
CREATE TABLE agent_runtime.skill_selections (
    run_id uuid PRIMARY KEY REFERENCES agent_runtime.runs(id), selected jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE agent_runtime.skill_loads (
    run_id uuid NOT NULL REFERENCES agent_runtime.runs(id), name text NOT NULL, sha256 text NOT NULL,
    path text NOT NULL, content bytea NOT NULL, media_type text NOT NULL,
    loaded_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(run_id,name,sha256,path),
    FOREIGN KEY(name,sha256) REFERENCES agent_runtime.skill_versions(name,sha256)
);
CREATE TABLE agent_runtime.skill_audit (
    id bigserial PRIMARY KEY, run_id uuid REFERENCES agent_runtime.runs(id), actor_id uuid,
    kind text NOT NULL, name text, sha256 text, path text, detail jsonb NOT NULL DEFAULT '{}',
    operator text NOT NULL DEFAULT current_user, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX skill_run_audit ON agent_runtime.skill_audit(run_id,id);
