ALTER TABLE data.data_category
    ADD COLUMN description varchar(240);

ALTER TABLE kb.document_category
    ADD COLUMN description varchar(240);

ALTER TABLE tpl.template_category
    ADD COLUMN description varchar(240);
