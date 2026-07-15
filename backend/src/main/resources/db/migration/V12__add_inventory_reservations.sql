alter table products add column reserved_stock bigint not null default 0;
alter table products add column sold_stock bigint not null default 0;

alter table inventory_logs add column reserved_change bigint not null default 0;
alter table inventory_logs add column sold_change bigint not null default 0;
alter table inventory_logs add column business_key varchar(160);

create unique index uk_inventory_log_business_key on inventory_logs(business_key);
create index idx_inventory_log_product_created on inventory_logs(product_id, created_at);
