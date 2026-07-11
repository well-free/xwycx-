alter table payment_orders add column gateway_mode varchar(32) not null default 'gateway';
