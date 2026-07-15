create table store_settings (
  id bigint primary key,
  store_name varchar(128) not null,
  logo_url varchar(512) not null,
  customer_service_phone varchar(32) not null,
  shipping_address varchar(500) not null,
  refund_address varchar(500) not null,
  business_status varchar(32) not null,
  created_at timestamp not null,
  updated_at timestamp not null
);

insert into store_settings
  (id, store_name, logo_url, customer_service_phone, shipping_address, refund_address,
   business_status, created_at, updated_at)
values
  (1, 'xwycx disposable supplies store', '', '', '', '', 'OPEN', current_timestamp, current_timestamp);
