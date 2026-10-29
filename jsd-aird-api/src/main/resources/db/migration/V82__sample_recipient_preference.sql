CREATE TABLE mfg.sample_recipient_preference (
    customer_id uuid PRIMARY KEY REFERENCES mdm.business_partner(id) ON DELETE CASCADE,
    recipient_name varchar(100) NOT NULL,
    recipient_phone varchar(64) NOT NULL,
    sample_address varchar(500) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
