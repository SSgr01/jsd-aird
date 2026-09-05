-- Same-document reparse is an explicit new-version operation.  The trigger
-- installed by V51 continues to reject the same content in another document.
ALTER TABLE kb.document_version
    DROP CONSTRAINT IF EXISTS document_version_document_id_sha256_key;
