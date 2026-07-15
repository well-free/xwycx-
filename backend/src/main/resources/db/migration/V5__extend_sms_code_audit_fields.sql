alter table sms_codes add column provider varchar(32) not null default 'local';
alter table sms_codes add column provider_request_id varchar(128);
alter table sms_codes add column status varchar(32) not null default 'SUCCESS';
