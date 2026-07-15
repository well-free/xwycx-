create table wechat_identities (
  id bigint primary key,
  user_id bigint not null,
  appid varchar(64) not null,
  openid varchar(128) not null,
  unionid varchar(128),
  created_at timestamp not null,
  updated_at timestamp not null,
  unique (appid, openid)
);

create table cart_items (
  id bigint primary key,
  user_id bigint not null,
  product_id bigint not null,
  quantity bigint not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  unique (user_id, product_id)
);

alter table shipping_addresses add column province varchar(64) not null default '';
alter table shipping_addresses add column city varchar(64) not null default '';
alter table shipping_addresses add column district varchar(64) not null default '';
alter table customer_orders add column shipping_snapshot varchar(2000);
alter table payment_orders add column prepay_id varchar(128);
alter table payment_orders add column payment_parameters varchar(2000);
