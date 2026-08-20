ALTER TABLE spc.chat_session
    ADD COLUMN title_source varchar(24) NOT NULL DEFAULT 'FIRST_QUESTION';

ALTER TABLE spc.chat_session
    ADD CONSTRAINT chk_spc_chat_session_title_source
    CHECK (title_source IN ('FIRST_QUESTION', 'MODEL', 'USER'));
